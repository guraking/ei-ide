package kinggura.sync;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「기록」 창이 읽는 커밋 목록과 가지 선 배치. IntelliJ 클래스 없이 git log · svn log --xml 출력만 다룬다.
 * 파일 경로는 고른 프로젝트 폴더 기준 상대 경로다(Git 은 --relative, SVN 은 폴더의 저장소 경로를 떼어 냄).
 */
final class History {
  /**
   * id 는 Git 해시 또는 SVN 리비전 번호. parents 는 목록 안에서 이어 그릴 부모(Git 은 폴더 기준으로 다시 쓴 부모, SVN 은 바로 앞 리비전).
   * refs 는 이 커밋을 가리키는 브랜치 · 태그 이름(Git 만). message 는 전체 메시지, files 는 이 프로젝트 폴더 안에서 바뀐 파일만.
   * email 은 작성자 메일(Git 만, SVN 은 빈 글).
   */
  record Entry(String id, List<String> parents, String author, String email, Instant time, List<String> refs, String message,
               List<FileChange> files) {
    String subject() { return firstLine(message); }
    boolean merge() { return parents.size() > 1; }
  }

  /**
   * status 는 한 글자(M · A · D · R 등). oldPath 는 커밋 직전 경로(이름 바꿈 · 복사는 옛 경로, 나머지는 path 와 같음).
   * oldBlob · newBlob 은 커밋 직전 · 커밋 뒤 파일 내용의 Git 객체 해시(40자, 없는 파일은 0 만). SVN 은 빈 글.
   */
  record FileChange(String status, String path, String oldPath, String oldBlob, String newBlob) {
    FileChange(String status, String path) { this(status, path, path); }
    FileChange(String status, String path, String oldPath) { this(status, path, oldPath, "", ""); }
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
      result.add(new Link(LinkKind.MERGED_FROM, parent != null ? parent : new Entry(id, List.of(), "", "", Instant.EPOCH, List.of(), "", List.of())));
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

  // git log --format 의 구분자. 커밋 메시지에 나오지 않는 제어 문자를 쓴다. BODY_END 뒤에 --raw 줄이 온다.
  static final char RECORD_SEP = '\u001e';
  static final char FIELD_SEP = '\u001f';
  static final char BODY_END = '\u001d';
  private static final String GIT_FORMAT = "--format=%x1e%H%x1f%P%x1f%an%x1f%at%x1f%D%x1f%B%x1f%ae%x1d";
  private static final int GIT_FIELDS = 7;
  // 합치기 커밋을 부모마다 따로 비교한 출력(sideArgs)의 머리: 해시만.
  private static final String SIDE_FORMAT = "--format=%x1e%H%x1d";
  // --raw 한 줄 「:옛모드 새모드 옛해시 새해시 상태\t경로[\t새경로]」의 앞 칸 수.
  private static final int RAW_FIELDS = 5;
  private static final String HEAD_ARROW = "HEAD -> ";
  private static final String HEAD = "HEAD";
  // 얕은 clone 의 끝 커밋에 git 이 붙이는 표시. 브랜치 · 태그가 아니다.
  private static final String GRAFTED = "grafted";
  // .git 파일(작업 트리 · 하위 모듈)의 머리와 .git/HEAD 의 브랜치 머리, 떨어진 HEAD 를 보일 해시 길이.
  private static final String GITDIR = "gitdir:";
  private static final String HEAD_REF = "ref: refs/heads/";
  private static final int SHORT_ID = 7;

  private History() {}

  /**
   * start(커밋 해시 또는 HEAD)에서 닿는 커밋 가운데 작업 폴더(프로젝트 폴더) 안을 바꾼 것을 최근 것부터 skip 개 건너뛰고 limit 개. 날짜 순이되 자식이 부모보다 먼저 온다.
   * 다음 묶음은 첫 묶음 때의 HEAD 해시를 start 로 넘겨, 그사이 커밋 · pull · 브랜치 전환이 있어도 같은 기록에서 이어 센다.
   * --parents 로 폴더 밖 커밋을 건너뛴 부모를 받아 가지 선이 끊기지 않게 하고, --full-history 로 합치기 한쪽 가지의 커밋도 남긴다.
   * 합치기 커밋의 바뀐 파일은 첫 부모와 비교한 것(--diff-merges=first-parent), --relative 로 경로를 작업 폴더 기준으로 받는다.
   * --raw --no-abbrev 로 파일 내용 해시를 함께 받아(clashes) 따로 git 을 부르지 않고 내 소스를 건드린 커밋을 찾는다.
   */
  static String[] gitLogArgs(String start, int limit, int skip) {
    return new String[]{"log", start, "--date-order", "--parents", "--full-history", "--diff-merges=first-parent", "-n", String.valueOf(limit),
                        "--skip", String.valueOf(skip), "--relative", "--raw", "--no-abbrev", GIT_FORMAT, "--", "."};
  }

  /**
   * 합치기 커밋 ids 를 부모마다 따로 비교한 파일(--diff-merges=separate). 부모와 차이가 없으면 그 부모 몫은 나오지 않는다.
   * 경로 거르기(-- .)를 주면 한쪽 부모와 같은 합치기 커밋이 빠질 수 있어 --relative(작업 폴더 밖 변경 뺌)만 쓴다.
   */
  static String[] sideArgs(List<String> ids) {
    List<String> args = new ArrayList<>(List.of("log", "--no-walk=unsorted", "--diff-merges=separate", "--relative", "--raw", "--no-abbrev", SIDE_FORMAT));
    args.addAll(ids);
    return args.toArray(String[]::new);
  }

  /** sideArgs 출력: 해시마다 부모별 바뀐 파일 목록(나온 순서대로). 머리가 깨졌으면 IOException. */
  static Map<String, List<List<FileChange>>> parseSides(String output) throws IOException {
    Map<String, List<List<FileChange>>> result = new LinkedHashMap<>();
    for (String chunk : output.split(String.valueOf(RECORD_SEP))) {
      if (chunk.isBlank()) continue;
      int end = chunk.indexOf(BODY_END);
      if (end < 0) throw new IOException("git log 결과를 읽지 못했습니다: " + firstLine(chunk));
      result.computeIfAbsent(chunk.substring(0, end).trim(), k -> new ArrayList<>()).add(parseRaw(chunk.substring(end + 1)));
    }
    return result;
  }

  /**
   * --raw 줄들. 이름 바꿈 · 복사(R100\t옛\t새)는 새 경로를 path, 옛 경로를 oldPath 로 둔다. 「:」로 시작하지 않는 줄(빈 줄)은 건너뛰고,
   * 앞 칸이 모자라면 IOException.
   */
  private static List<FileChange> parseRaw(String text) throws IOException {
    List<FileChange> files = new ArrayList<>();
    for (String line : text.split("\n")) {
      if (!line.startsWith(":")) continue;
      String[] cols = line.split("\t");
      String[] meta = cols[0].substring(1).split(" ");
      if (meta.length < RAW_FIELDS || cols.length < 2) throw new IOException("git log 파일 줄을 읽지 못했습니다: " + line);
      String path = cols[cols.length - 1];
      files.add(new FileChange(meta[4].substring(0, 1), path, cols.length > 2 ? cols[1] : path, meta[2], meta[3]));
    }
    return files;
  }

  /**
   * git log GIT_FORMAT 출력. 한 커밋은 RECORD_SEP 로 시작해 BODY_END 까지가 머리, 그 뒤가 --raw 줄이다(parseRaw).
   * 필드가 모자라거나 시간이 숫자가 아니면 IOException.
   */
  static List<Entry> parseGitLog(String output) throws IOException {
    List<Entry> result = new ArrayList<>();
    for (String chunk : output.split(String.valueOf(RECORD_SEP))) {
      if (chunk.isBlank()) continue;
      int end = chunk.indexOf(BODY_END);
      String[] head = (end < 0 ? chunk : chunk.substring(0, end)).split(String.valueOf(FIELD_SEP), -1);
      if (end < 0 || head.length < GIT_FIELDS) throw new IOException("git log 결과를 읽지 못했습니다: " + firstLine(chunk));
      List<FileChange> files = parseRaw(chunk.substring(end + 1));
      Instant time;
      try {
        time = Instant.ofEpochSecond(Long.parseLong(head[3]));
      }
      catch (NumberFormatException e) {
        throw new IOException("git log 시간 값을 읽지 못했습니다: " + head[3]);
      }
      List<String> parents = head[1].isBlank() ? List.of() : List.of(head[1].trim().split(" "));
      result.add(new Entry(head[0], parents, head[2], head[6].trim(), time, parseRefs(head[4]), head[5].strip(), files));
    }
    return result;
  }

  /** %D 값("HEAD -> develop, origin/develop, tag: v1")을 이름 목록으로. 홀로 있는 HEAD 와 얕은 clone 표시(grafted)는 뺀다. */
  static List<String> parseRefs(String decoration) {
    List<String> refs = new ArrayList<>();
    for (String ref : decoration.split(", ")) {
      String name = ref.startsWith(HEAD_ARROW) ? ref.substring(HEAD_ARROW.length()) : ref.trim();
      if (!name.isEmpty() && !name.equals(HEAD) && !name.equals(GRAFTED)) refs.add(name);
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
      result.add(new Entry(entry.getAttribute("revision"), parents, text(entry, "author"), "", time, List.of(), text(entry, "msg").strip(), files));
    }
    return result;
  }

  /**
   * svn info --xml 의 저장소 루트 URL 과 이 폴더의 저장소 경로(^/ 를 / 로). 둘 중 하나라도 없으면 IOException.
   * relative-url 은 %ED%95%9C · %20 처럼 인코딩되어 오고 svn log 의 경로는 풀려 오므로 UTF-8 로 풀어 돌려준다.
   * svn 은 「+」를 인코딩하지 않으므로 URLDecoder 가 공백으로 바꾸지 않게 먼저 %2B 로 둔다.
   */
  static String[] parseSvnInfo(String xml) throws IOException {
    Element doc = parse(xml, "svn info");
    String root = text(doc, "root");
    String relative = text(doc, "relative-url");
    if (root.isEmpty() || !relative.startsWith("^")) throw new IOException("svn info 결과에 저장소 경로가 없습니다.");
    try {
      return new String[]{root, URLDecoder.decode(relative.substring(1).replace("+", "%2B"), StandardCharsets.UTF_8)};
    }
    catch (IllegalArgumentException e) {
      throw new IOException("svn info 저장소 경로를 풀지 못했습니다: " + relative);
    }
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

  /**
   * 이 저장소에서 「나」: git config user.name · user.email, 그리고 사용자가 「나로 보기」로 더한 다른 계정(aliases, 소문자 메일 또는 이름).
   * 모두 비면 아무 커밋도 내 것이 아니다.
   */
  record Me(String name, String email, Set<String> aliases) {
    static final Me NONE = new Me("", "");

    Me(String name, String email) { this(name, email, Set.of()); }

    Me withAliases(Set<String> more) { return new Me(name, email, Set.copyOf(more)); }

    boolean known() { return !name.isEmpty() || !email.isEmpty() || !aliases.isEmpty(); }

    /** git config 의 나: 메일이 같거나(대소문자 무시) 이름이 같다. */
    boolean configured(Entry entry) {
      return !email.isEmpty() && email.equalsIgnoreCase(entry.email()) || !name.isEmpty() && name.equals(entry.author());
    }

    /** 내 커밋: git config 의 나이거나 더한 계정(aliasKey)이다. */
    boolean is(Entry entry) {
      return configured(entry) || aliases.contains(aliasKey(entry));
    }

    /** 「나로 보기」에 저장할 값: 메일(소문자), 메일이 없으면 이름(소문자). */
    static String aliasKey(Entry entry) {
      return (entry.email().isEmpty() ? entry.author() : entry.email()).toLowerCase(Locale.ROOT);
    }
  }

  /** git config --get-regexp ^user\\.(name|email)$ 출력(「user.name 값」 줄). 없는 값은 빈 글. */
  static Me parseMe(String output) {
    String name = "";
    String email = "";
    for (String line : output.split("\n")) {
      int space = line.indexOf(' ');
      if (space < 0) continue;
      String key = line.substring(0, space).toLowerCase(Locale.ROOT);
      String value = line.substring(space + 1).trim();
      if (key.equals("user.name")) name = value;
      else if (key.equals("user.email")) email = value;
    }
    return new Me(name, email);
  }

  /** 건드린 방식. 되돌림: 결과가 그 판을 만든 커밋 직전 내용과 같음. 덮어씀: 합치며 그 판을 버리고 다른 부모 판을 택함. 이어 고침: 그 판 위에서 고침. */
  enum ClashKind { REVERTED, OVERWRITTEN, EDITED }

  /**
   * 한 커밋이 다른 쪽(나 ↔ 다른 사람) 판을 바꾼 것. change 는 이 커밋의 파일 변경, owner · ownerChange 는 바뀌기 직전 내용을 만든 커밋과 그 파일 변경.
   * others 는 내 커밋이 남의 소스를 건드림(false 면 다른 사람 커밋이 내 소스를 건드림).
   */
  record Clash(FileChange change, Entry owner, FileChange ownerChange, ClashKind kind, boolean others) {}

  private record Version(Entry entry, FileChange change) {}

  /**
   * 커밋마다 다른 쪽 소스를 건드린 파일(커밋 id → 목록, 없으면 빠짐). entries 는 최근 것부터(git log 순서). 다른 사람끼리 · 내 커밋끼리는 보지 않는다.
   * 오래된 커밋부터 보며 나 · 다른 사람 커밋이 만든 「경로 + 내용 해시」를 따로 기억하고, 파일 변경이 다른 쪽이 만든 내용에서 출발한 것(직전 내용이 다른 쪽 판)을 본다.
   * 내용 해시로 잇기 때문에 가지가 갈라져 있어도, rebase 로 다시 올린 커밋이어도 맞는다.
   * 결과가 그 판을 만든 커밋 직전 내용과 같으면 되돌림. 아니면 보통 커밋은 이어 고침,
   * 합치기 커밋은 sides(부모마다 따로 비교한 파일)로 본다: 한 부모와만 다른 파일은 다른 부모 판을 그대로 가져온 것이므로 직전 내용이 다른 쪽 판이면
   * 합치며 그 수정을 버린 것이다(덮어씀). 두 부모 모두와 다른 파일은 양쪽을 합친 결과라 뺀다. sides 에 없는 합치기 커밋은 files 로 본다.
   * 합치며 결과가 다른 쪽의 또 다른 판인 것(그쪽 새 수정이 들어옴)과 모드만 바뀐 변경(내용 같음)은 뺀다.
   */
  static Map<String, List<Clash>> clashes(List<Entry> entries, Me me, Map<String, List<List<FileChange>>> sides) {
    Map<String, Version> mine = new HashMap<>();
    Map<String, Version> theirs = new HashMap<>();
    Map<String, List<Clash>> result = new HashMap<>();
    if (!me.known()) return result;
    for (int i = entries.size() - 1; i >= 0; i--) {
      Entry entry = entries.get(i);
      boolean byMe = me.is(entry);
      Map<String, Version> owners = byMe ? theirs : mine;
      List<Clash> found = new ArrayList<>();
      Set<String> paths = new HashSet<>();
      for (FileChange f : candidates(entry, sides.get(entry.id()))) {
        if (f.oldBlob().isEmpty() || f.oldBlob().equals(f.newBlob())) continue;
        Version owner = owners.get(f.oldPath() + '\0' + f.oldBlob());
        if (owner == null) continue;
        ClashKind kind;
        if (f.newBlob().equals(owner.change().oldBlob())) kind = ClashKind.REVERTED;
        else if (!entry.merge()) kind = ClashKind.EDITED;
        else if (owners.containsKey(f.path() + '\0' + f.newBlob())) continue;
        else kind = ClashKind.OVERWRITTEN;
        if (paths.add(f.path())) found.add(new Clash(f, owner.entry(), owner.change(), kind, byMe));
      }
      if (!found.isEmpty()) result.put(entry.id(), found);
      Map<String, Version> own = byMe ? mine : theirs;
      for (FileChange f : entry.files()) {
        if (!f.newBlob().isEmpty()) own.put(f.path() + '\0' + f.newBlob(), new Version(entry, f));
      }
    }
    return result;
  }

  /** 커밋 한 줄에 보일 대표: 되돌림 > 덮어씀 > 이어 고침 순으로 가장 센 것. clashes 는 비어 있지 않다. */
  static ClashKind strongest(List<Clash> clashes) {
    ClashKind best = ClashKind.EDITED;
    for (Clash c : clashes) if (c.kind().ordinal() < best.ordinal()) best = c.kind();
    return best;
  }

  /** 볼 파일 변경: 보통 커밋은 files, 합치기 커밋은 부모별 비교 가운데 다른 부모 비교에는 없는 경로만(clashes 설명). */
  private static List<FileChange> candidates(Entry entry, List<List<FileChange>> perParent) {
    if (!entry.merge() || perParent == null) return entry.files();
    List<FileChange> result = new ArrayList<>();
    for (List<FileChange> side : perParent) {
      for (FileChange f : side) {
        boolean elsewhere = false;
        for (List<FileChange> other : perParent) {
          if (other == side) continue;
          for (FileChange o : other) elsewhere |= o.path().equals(f.path());
        }
        if (!elsewhere) result.add(f);
      }
    }
    return result;
  }

  /**
   * dir 이 든 Git 작업본의 현재 브랜치 이름. git 을 부르지 않고 .git/HEAD 를 읽는다(.git 이 파일이면 「gitdir: 경로」를 따라감).
   * 떨어진 HEAD 는 짧은 해시, Git 작업본이 아니거나 읽지 못하면 빈 글.
   */
  static String gitHead(File dir) {
    for (File d = dir.getAbsoluteFile(); d != null; d = d.getParentFile()) {
      File git = new File(d, ".git");
      if (!git.exists()) continue;
      try {
        if (git.isFile()) {
          String link = Files.readString(git.toPath(), StandardCharsets.UTF_8).trim();
          if (!link.startsWith(GITDIR)) return "";
          File target = new File(link.substring(GITDIR.length()).trim());
          git = target.isAbsolute() ? target : new File(d, target.getPath());
        }
        String head = Files.readString(new File(git, HEAD).toPath(), StandardCharsets.UTF_8).trim();
        if (head.startsWith(HEAD_REF)) return head.substring(HEAD_REF.length());
        return head.length() > SHORT_ID ? head.substring(0, SHORT_ID) : head;
      }
      catch (IOException e) {
        return "";
      }
    }
    return "";
  }

  /** 목록 한 줄에 보일 메시지: 첫 줄만. */
  static String firstLine(String message) {
    String trimmed = message.strip();
    int nl = trimmed.indexOf('\n');
    return (nl < 0 ? trimmed : trimmed.substring(0, nl)).strip();
  }
}
