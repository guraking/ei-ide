package kinggura.sync;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 「기록」 창이 읽는 커밋 목록. IntelliJ 클래스 없이 git log · svn log --xml 출력만 다룬다.
 * 파일 경로는 고른 프로젝트 폴더 기준 상대 경로다(Git 은 --relative, SVN 은 폴더의 저장소 경로를 떼어 냄).
 */
final class History {
  /** id 는 Git 해시 또는 SVN 리비전 번호. files 는 이 프로젝트 폴더 안에서 바뀐 파일만. */
  record Entry(String id, String author, Instant time, String message, List<FileChange> files) {}

  /** status 는 한 글자(M · A · D · R 등). */
  record FileChange(String status, String path) {
    @Override public String toString() { return status + "  " + path; }
  }

  // git log --format 의 구분자. 커밋 메시지에 나오지 않는 제어 문자를 쓴다.
  static final char RECORD_SEP = '\u001e';
  static final char FIELD_SEP = '\u001f';
  static final String GIT_FORMAT = "--format=%x1e%H%x1f%an%x1f%at%x1f%s";
  private static final int GIT_FIELDS = 4;

  private History() {}

  /**
   * 작업 폴더(프로젝트 폴더) 안을 바꾼 최근 커밋 limit 개. 합치기(Merge) 커밋은 뺀다.
   * --relative 로 경로를 작업 폴더 기준으로 받고, 「-- .」 로 그 폴더 밖만 바꾼 커밋을 거른다.
   */
  static String[] gitLogArgs(int limit) {
    return new String[]{"log", "--no-merges", "-n", String.valueOf(limit), "--relative", "--name-status", GIT_FORMAT, "--", "."};
  }

  /**
   * git log --name-status GIT_FORMAT 출력. 한 커밋은 RECORD_SEP 로 시작하는 머리줄 뒤에 "상태\t경로" 줄이 온다.
   * 이름 바꿈 · 복사(R100\t옛\t새)는 새 경로를 쓴다. 필드가 모자란 머리줄은 IOException.
   */
  static List<Entry> parseGitLog(String output) throws IOException {
    List<Entry> result = new ArrayList<>();
    for (String chunk : output.split(String.valueOf(RECORD_SEP))) {
      if (chunk.isBlank()) continue;
      String[] lines = chunk.split("\n");
      String[] head = lines[0].split(String.valueOf(FIELD_SEP), -1);
      if (head.length < GIT_FIELDS) throw new IOException("git log 결과를 읽지 못했습니다: " + lines[0]);
      List<FileChange> files = new ArrayList<>();
      for (int i = 1; i < lines.length; i++) {
        if (lines[i].isBlank()) continue;
        String[] cols = lines[i].split("\t");
        files.add(new FileChange(cols[0].substring(0, 1), cols[cols.length - 1]));
      }
      Instant time;
      try {
        time = Instant.ofEpochSecond(Long.parseLong(head[2]));
      }
      catch (NumberFormatException e) {
        throw new IOException("git log 시간 값을 읽지 못했습니다: " + head[2]);
      }
      result.add(new Entry(head[0], head[1], time, head[3], files));
    }
    return result;
  }

  /**
   * svn log --xml -v 출력. prefix 는 프로젝트 폴더의 저장소 경로(예: /trunk/app). 그 아래 경로만 남기고 앞부분을 뗀다.
   * 작성자 · 날짜가 없는 리비전(권한 없음)은 빈 작성자 · Instant.EPOCH 로 둔다.
   */
  static List<Entry> parseSvnLog(String xml, String prefix) throws IOException {
    String base = prefix.endsWith("/") ? prefix : prefix + "/";
    List<Entry> result = new ArrayList<>();
    NodeList entries = parse(xml, "svn log").getElementsByTagName("logentry");
    for (int i = 0; i < entries.getLength(); i++) {
      Element entry = (Element)entries.item(i);
      List<FileChange> files = new ArrayList<>();
      NodeList paths = entry.getElementsByTagName("path");
      for (int j = 0; j < paths.getLength(); j++) {
        Element p = (Element)paths.item(j);
        String path = p.getTextContent();
        if (path.startsWith(base)) files.add(new FileChange(p.getAttribute("action"), path.substring(base.length())));
      }
      String date = text(entry, "date");
      Instant time;
      try {
        time = date.isEmpty() ? Instant.EPOCH : Instant.parse(date);
      }
      catch (java.time.format.DateTimeParseException e) {
        throw new IOException("svn log 날짜를 읽지 못했습니다: " + date);
      }
      result.add(new Entry(entry.getAttribute("revision"), text(entry, "author"), time, firstLine(text(entry, "msg")), files));
    }
    return result;
  }

  /** svn info --xml 의 저장소 루트 URL 과 이 폴더의 저장소 경로(^/ 를 / 로). 둘 중 하나라도 없으면 IOException. */
  static String[] parseSvnInfo(String xml) throws IOException {
    Element doc = parse(xml, "svn info");
    String root = text(doc, "root");
    String relative = text(doc, "relative-url");
    if (root.isEmpty() || !relative.startsWith("^")) throw new IOException("svn info 결과에 저장소 경로가 없습니다.");
    return new String[]{root, relative.substring(1)};
  }

  private static Element parse(String xml, String what) throws IOException {
    try {
      DocumentBuilder builder = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder();
      builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());  // 기본 처리기는 표준 오류에 따로 찍는다. 이 처리기는 예외만 던진다.
      return builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }
    catch (Exception e) {
      throw new IOException(what + " 결과를 읽지 못했습니다: " + e.getMessage());
    }
  }

  private static String text(Element parent, String tag) {
    NodeList list = parent.getElementsByTagName(tag);
    return list.getLength() == 0 ? "" : list.item(0).getTextContent();
  }

  /** 목록 한 줄에 보일 메시지. Git 의 %s 와 맞춰 첫 줄만. */
  static String firstLine(String message) {
    String trimmed = message.strip();
    int nl = trimmed.indexOf('\n');
    return (nl < 0 ? trimmed : trimmed.substring(0, nl)).strip();
  }
}
