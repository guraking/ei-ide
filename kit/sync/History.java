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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「기록」 창이 읽는 커밋 목록과 가지 선 배치. IntelliJ 클래스 없이 git log · svn log --xml 출력만 다룬다.
 * 파일 경로는 고른 프로젝트 폴더 기준 상대 경로다(Git 은 --relative, SVN 은 폴더의 저장소 경로를 떼어 냄).
 */
final class History {
  /**
   * id 는 Git 해시 또는 SVN 리비전 번호. parents 는 목록 안에서 이어 그릴 부모(Git 은 폴더 기준으로 다시 쓴 부모, SVN 은 바로 앞 리비전).
   * refs 는 이 커밋을 가리키는 브랜치 · 태그 이름(Git 만). message 는 전체 메시지, files 는 이 프로젝트 폴더 안에서 바뀐 파일만.
   */
  record Entry(String id, List<String> parents, String author, Instant time, List<String> refs, String message, List<FileChange> files) {
    String subject() { return firstLine(message); }
    boolean merge() { return parents.size() > 1; }
  }

  /** status 는 한 글자(M · A · D · R 등). */
  record FileChange(String status, String path) {
    @Override public String toString() { return status + "  " + path; }
  }

  /** 가지 선 한 토막. from · to 는 칸 번호, lane 은 색을 고를 칸 번호. */
  record Seg(int from, int to, int lane) {}

  /** 한 줄의 그림: 점(커밋)이 놓일 칸, 줄 위쪽 반(위 줄 → 점)과 아래쪽 반(점 → 아래 줄) 토막. */
  record Row(int col, List<Seg> top, List<Seg> bottom) {}

  /** 커밋 하나가 다른 커밋과 이어진 방식. 그림에서는 점으로 꺾여 들어오거나 점에서 꺾여 나가는 선이다. */
  enum LinkKind {
    /** 이 커밋(합치기)이 다른 가지의 커밋을 합쳐 들임: 점에서 아래로 꺾여 나가는 선. */
    MERGED_FROM,
    /** 다른 칸의 위쪽 커밋이 이 커밋을 첫 부모로 시작함(여기서 가지가 갈라짐): 위에서 꺾여 들어오는 선. */
    BRANCHED_TO,
    /** 위쪽 합치기 커밋이 이 커밋을 합쳐 감: 위에서 꺾여 들어오는 선. */
    MERGED_INTO
  }

  record Link(LinkKind kind, Entry other) {}

  /**
   * index 줄 커밋이 목록 안 다른 커밋과 꺾인 선으로 이어진 것. 위에서 곧게 이어지는 자식(같은 칸 · 첫 부모)은 넣지 않는다.
   * 목록 밖 부모(합쳐 들인 커밋이 아직 안 읽힘)는 other 를 null 로 두지 않고 id 만 아는 빈 Entry 로 만든다.
   */
  static List<Link> links(List<Entry> entries, List<Row> rows, int index) {
    Entry entry = entries.get(index);
    int col = rows.get(index).col();
    List<Link> result = new ArrayList<>();
    for (int k = 0; k < index; k++) {
      int at = entries.get(k).parents().indexOf(entry.id());
      if (at > 0) result.add(new Link(LinkKind.MERGED_INTO, entries.get(k)));
      else if (at == 0 && rows.get(k).col() != col) result.add(new Link(LinkKind.BRANCHED_TO, entries.get(k)));
    }
    for (int p = 1; p < entry.parents().size(); p++) {
      String id = entry.parents().get(p);
      Entry parent = null;
      for (int k = index + 1; k < entries.size() && parent == null; k++) if (entries.get(k).id().equals(id)) parent = entries.get(k);
      result.add(new Link(LinkKind.MERGED_FROM, parent != null ? parent : new Entry(id, List.of(), "", Instant.EPOCH, List.of(), "", List.of())));
    }
    return result;
  }

  /**
   * index 줄에서 lane 칸을 곧게 지나가는 선이 닿는 아래쪽 커밋의 줄 번호. 그 칸에 지나가는 선이 없거나
   * 목록 안에서 닿지 않으면(목록 밖 커밋을 기다림) -1.
   */
  static int passingTarget(List<Row> rows, int index, int lane) {
    if (!passes(rows.get(index), lane)) return -1;
    for (int k = index + 1; k < rows.size(); k++) {
      for (Seg s : rows.get(k).top()) if (s.from() == lane && s.to() == rows.get(k).col()) return k;
    }
    return -1;
  }

  private static boolean passes(Row row, int lane) {
    for (Seg s : row.bottom()) if (s.from() == lane && s.to() == lane && lane != row.col()) return true;
    return false;
  }

  // git log --format 의 구분자. 커밋 메시지에 나오지 않는 제어 문자를 쓴다. BODY_END 뒤에 --name-status 줄이 온다.
  static final char RECORD_SEP = '\u001e';
  static final char FIELD_SEP = '\u001f';
  static final char BODY_END = '\u001d';
  private static final String GIT_FORMAT = "--format=%x1e%H%x1f%P%x1f%an%x1f%at%x1f%D%x1f%B%x1d";
  private static final int GIT_FIELDS = 6;
  private static final String HEAD_ARROW = "HEAD -> ";
  private static final String HEAD = "HEAD";

  private History() {}

  /**
   * 작업 폴더(프로젝트 폴더) 안을 바꾼 커밋을 최근 것부터 skip 개 건너뛰고 limit 개. 날짜 순이되 자식이 부모보다 먼저 온다.
   * --parents 로 폴더 밖 커밋을 건너뛴 부모를 받아 가지 선이 끊기지 않게 하고, --relative 로 경로를 작업 폴더 기준으로 받는다.
   */
  static String[] gitLogArgs(int limit, int skip) {
    return new String[]{"log", "--date-order", "--parents", "-n", String.valueOf(limit), "--skip", String.valueOf(skip), "--relative",
                        "--name-status", GIT_FORMAT, "--", "."};
  }

  /**
   * git log GIT_FORMAT 출력. 한 커밋은 RECORD_SEP 로 시작해 BODY_END 까지가 머리, 그 뒤가 "상태\t경로" 줄이다.
   * 이름 바꿈 · 복사(R100\t옛\t새)는 새 경로를 쓴다. 필드가 모자라거나 시간이 숫자가 아니면 IOException.
   */
  static List<Entry> parseGitLog(String output) throws IOException {
    List<Entry> result = new ArrayList<>();
    for (String chunk : output.split(String.valueOf(RECORD_SEP))) {
      if (chunk.isBlank()) continue;
      int end = chunk.indexOf(BODY_END);
      String[] head = (end < 0 ? chunk : chunk.substring(0, end)).split(String.valueOf(FIELD_SEP), -1);
      if (end < 0 || head.length < GIT_FIELDS) throw new IOException("git log 결과를 읽지 못했습니다: " + firstLine(chunk));
      List<FileChange> files = new ArrayList<>();
      for (String line : chunk.substring(end + 1).split("\n")) {
        if (line.isBlank()) continue;
        String[] cols = line.split("\t");
        files.add(new FileChange(cols[0].substring(0, 1), cols[cols.length - 1]));
      }
      Instant time;
      try {
        time = Instant.ofEpochSecond(Long.parseLong(head[3]));
      }
      catch (NumberFormatException e) {
        throw new IOException("git log 시간 값을 읽지 못했습니다: " + head[3]);
      }
      List<String> parents = head[1].isBlank() ? List.of() : List.of(head[1].trim().split(" "));
      result.add(new Entry(head[0], parents, head[2], time, parseRefs(head[4]), head[5].strip(), files));
    }
    return result;
  }

  /** %D 값("HEAD -> develop, origin/develop, tag: v1")을 이름 목록으로. 홀로 있는 HEAD 는 뺀다. */
  static List<String> parseRefs(String decoration) {
    List<String> refs = new ArrayList<>();
    for (String ref : decoration.split(", ")) {
      String name = ref.startsWith(HEAD_ARROW) ? ref.substring(HEAD_ARROW.length()) : ref.trim();
      if (!name.isEmpty() && !name.equals(HEAD)) refs.add(name);
    }
    return refs;
  }

  /**
   * svn log --xml -v 출력. prefix 는 프로젝트 폴더의 저장소 경로(예: /trunk/app). 그 아래 경로만 남기고 앞부분을 뗀다.
   * 부모는 목록의 다음 리비전(SVN 은 한 줄 기록). 작성자 · 날짜가 없는 리비전(권한 없음)은 빈 작성자 · Instant.EPOCH 로 둔다.
   */
  static List<Entry> parseSvnLog(String xml, String prefix) throws IOException {
    String base = prefix.endsWith("/") ? prefix : prefix + "/";
    NodeList entries = parse(xml, "svn log").getElementsByTagName("logentry");
    List<Entry> result = new ArrayList<>();
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
      List<String> parents = i + 1 < entries.getLength() ? List.of(((Element)entries.item(i + 1)).getAttribute("revision")) : List.of();
      result.add(new Entry(entry.getAttribute("revision"), parents, text(entry, "author"), time, List.of(), text(entry, "msg").strip(), files));
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

  /**
   * 가지 선 배치. 위에서부터 칸마다 「다음에 올 커밋」을 들고 내려가며, 커밋은 자기를 기다리던 칸에 점을 찍는다.
   * 여러 칸이 같은 커밋을 기다렸으면(가지가 갈라진 곳) 그 칸들이 점으로 모인다. 첫 부모는 점의 칸을 이어받고,
   * 나머지 부모(합치기)는 이미 기다리는 칸이 있으면 그 칸으로, 없으면 빈 칸(없으면 오른쪽 새 칸)으로 선을 낸다.
   * 목록 밖 부모를 기다리는 칸은 맨 아래까지 이어진다.
   */
  static List<Row> layout(List<Entry> entries) {
    List<String> lanes = new ArrayList<>();
    List<Row> rows = new ArrayList<>();
    for (Entry e : entries) {
      int col = lanes.indexOf(e.id());
      boolean fromAbove = col >= 0;
      if (!fromAbove) col = freeSlot(lanes);

      List<Seg> top = new ArrayList<>();
      for (int j = 0; j < lanes.size(); j++) {
        String waiting = lanes.get(j);
        if (waiting == null) continue;
        boolean mine = waiting.equals(e.id());
        top.add(new Seg(j, mine ? col : j, j));
        if (mine) lanes.set(j, null);
      }

      Set<Integer> passing = new HashSet<>();
      for (int j = 0; j < lanes.size(); j++) if (lanes.get(j) != null) passing.add(j);
      Set<Integer> fromNode = new HashSet<>();
      for (int p = 0; p < e.parents().size(); p++) {
        String parent = e.parents().get(p);
        int j = p == 0 ? col : lanes.indexOf(parent);
        if (j < 0) j = freeSlot(lanes);
        if (j >= lanes.size()) lanes.add(null);
        lanes.set(j, parent);
        fromNode.add(j);
      }

      List<Seg> bottom = new ArrayList<>();
      for (int j = 0; j < lanes.size(); j++) {
        if (passing.contains(j)) bottom.add(new Seg(j, j, j));
        if (fromNode.contains(j)) bottom.add(new Seg(col, j, j));
      }
      while (!lanes.isEmpty() && lanes.get(lanes.size() - 1) == null) lanes.remove(lanes.size() - 1);
      rows.add(new Row(col, top, bottom));
    }
    return rows;
  }

  /** 첫 빈 칸 번호. 없으면 lanes.size()(호출한 쪽이 칸을 늘린다). */
  private static int freeSlot(List<String> lanes) {
    int free = lanes.indexOf(null);
    return free < 0 ? lanes.size() : free;
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

  /** 목록 한 줄에 보일 메시지: 첫 줄만. */
  static String firstLine(String message) {
    String trimmed = message.strip();
    int nl = trimmed.indexOf('\n');
    return (nl < 0 ? trimmed : trimmed.substring(0, nl)).strip();
  }
}
