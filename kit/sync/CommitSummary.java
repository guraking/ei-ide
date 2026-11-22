package kinggura.sync;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 커밋 창 「문구 넣기」(CommitSummaryAction)가 커밋 문구 칸에 넣는 글을 만든다. IntelliJ 클래스 없이 글만 다룬다.
 * 바뀐 파일을 메뉴(화면) 이름별 한 줄로 묶는다. 예: 「킥오프: 일정 조회 수정, 담당자 저장 추가, 화면 수정」.
 * <p>
 * 이름 찾는 순서: 코드에 적힌 이름(주석 첫 줄 · Swagger 설명 · 화면 제목 · 라우터 · 메뉴 제목) → 이름표(영어 낱말 = 한국어) →
 * 같은 이름의 다른 파일이 찾은 이름(KickoffMapper 는 @Tag("킥오프") 가 붙은 KickoffController 의 이름을 따른다) → 영어 이름 그대로.
 * Java · Kotlin 은 메서드, MyBatis 매퍼는 쿼리 id 단위로 무엇이 더해지고 · 바뀌고 · 빠졌는지 적는다. 화면 파일(vue · jsx · tsx · jsp · html)은
 * 「화면 수정」, 그 밖의 파일은 파일 단위로 적는다. 읽기만 하고 실패해도 예외를 내지 않는다(모르는 모양은 파일 단위로 적는다).
 */
final class CommitSummary {
  enum Kind { ADDED, MODIFIED, DELETED }

  /** 바뀐 파일 하나. path 는 '/' 로 나눈 경로, before · after 는 고치기 전 · 뒤 내용(없거나 못 읽었으면 null). */
  record FileChange(String path, Kind kind, String before, String after) {}

  /** 한 줄에 적는 기능 수. 넘치면 「외 N건」. */
  static final int MAX_ITEMS = 5;
  private static final String WORD_ADDED = "추가";
  private static final String WORD_MODIFIED = "수정";
  private static final String WORD_DELETED = "삭제";
  private static final String WORD_SCREEN = "화면";
  private static final String MORE = "외 %d건";
  private static final String INDEX = "index";

  // 이름에서 떼는 계층 낱말. 같은 메뉴의 Controller · Service · Mapper 를 한 줄로 묶으려고 뗀다. 긴 것을 먼저 본다.
  private static final List<String> ROLE_SUFFIXES = List.of(
    "RestController", "Controller", "ServiceImpl", "Service", "Impl", "Mapper", "Repository", "Dao", "DAO",
    "Page", "View", "Screen", "Main");
  private static final Set<String> ROLE_WORDS = Set.of(
    "restcontroller", "controller", "serviceimpl", "service", "impl", "mapper", "repository", "dao", "page", "view", "screen",
    "컨트롤러", "서비스", "매퍼", "구현", "구현체", "화면", "페이지");
  private static final Set<String> SCREEN_EXTS = Set.of("vue", "jsx", "tsx", "jsp", "html", "htm");
  private static final Set<String> SCRIPT_EXTS = Set.of("js", "ts", "mjs", "cjs");
  private static final Set<String> KEYWORDS = Set.of(
    "if", "for", "while", "switch", "catch", "synchronized", "return", "new", "try", "else", "do", "super", "this");

  private static final Pattern HANGUL = Pattern.compile("[가-힣]");
  private static final Pattern TYPE_DECL = Pattern.compile("\\b(class|interface|enum|record|object)\\s+(\\w+)");
  private static final Pattern IMPORT_LINE = Pattern.compile("(?m)^[ \\t]*(import|package)\\b[^\\n]*\\n?");
  private static final Pattern THROWS = Pattern.compile("\\s*throws\\s+[\\w.\\s,<>?]+$");
  private static final Pattern KOTLIN_FUN = Pattern.compile("\\G(?:<[^>]*>\\s*)?[\\w.<>?]*?(\\w+)\\s*\\(");
  private static final Pattern OPERATION = Pattern.compile("@Operation\\s*\\([^)]*?\\bsummary\\s*=\\s*\"([^\"]*)\"", Pattern.DOTALL);
  private static final Pattern API_OPERATION = Pattern.compile("@ApiOperation\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"", Pattern.DOTALL);
  private static final Pattern TAG = Pattern.compile("@Tag\\s*\\([^)]*?\\bname\\s*=\\s*\"([^\"]*)\"", Pattern.DOTALL);
  private static final Pattern API = Pattern.compile("@Api\\s*\\([^)]*?\\btags\\s*=\\s*\\{?\\s*\"([^\"]*)\"", Pattern.DOTALL);
  private static final Pattern DOC_COMMENT = Pattern.compile("/\\*\\*(.*?)\\*/", Pattern.DOTALL);
  private static final Pattern LINE_COMMENT = Pattern.compile("(?m)^[ \\t]*//+(.*)$");
  private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
  private static final Pattern LINK = Pattern.compile("\\{@\\w+\\s+([^}]*)}");
  private static final Pattern MAPPER = Pattern.compile("<mapper\\b[^>]*\\bnamespace\\s*=\\s*\"([^\"]*)\"");
  private static final Pattern STATEMENT = Pattern.compile("<(select|insert|update|delete|sql)\\b[^>]*?\\bid\\s*=\\s*\"([^\"]+)\"[^>]*>");
  private static final Pattern XML_COMMENT = Pattern.compile("<!--(.*?)-->", Pattern.DOTALL);
  private static final Pattern JSP_COMMENT = Pattern.compile("<%--(.*?)--%>", Pattern.DOTALL);
  private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*(.*?)\\*/", Pattern.DOTALL);
  private static final Pattern TITLE_TAG = Pattern.compile("<title>([^<]*)</title>", Pattern.CASE_INSENSITIVE);
  private static final Pattern ROUTE_TITLE = Pattern.compile("\\b(?:title|label|name|menuName|menuNm)\\s*:\\s*(['\"`])((?:(?!\\1).)*[가-힣](?:(?!\\1).)*)\\1");
  private static final Pattern WORD_SPLIT = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[_\\-.\\s]+");

  private CommitSummary() {}

  /**
   * 한 파일이 무엇을 고쳤는지. label 은 코드에 적힌 이름(없으면 null), base 는 영어 이름, items 는 「일정 조회 수정」 같은 기능 줄.
   * code 는 Java · Kotlin · 매퍼(클래스 이름이 메뉴를 가리킨다). 같은 base 의 다른 파일에 이름을 빌려주는 것은 code 뿐이다(List.vue 끼리 섞이지 않게).
   */
  private record Part(String label, String base, String fallback, List<String> items, Kind kind, boolean screen, boolean code) {}

  /** 코드 파일의 맨 위 형식(클래스 · 매퍼) 이름 · 이름표와 그 안의 메서드 · 쿼리. */
  record Unit(String name, String label, Map<String, Member> members) {
    static final Unit EMPTY = new Unit(null, null, Map.of());
  }

  record Member(String name, String label, String text) {}

  /**
   * changes 를 메뉴별 줄로 만든다. names 는 이름표(소문자 영어 낱말 → 한국어), routes 는 라우터 · 메뉴 파일 내용들.
   * 바뀐 것이 없으면 빈 글.
   */
  static String summarize(List<FileChange> changes, Map<String, String> names, List<String> routes) {
    List<FileChange> sorted = new ArrayList<>(changes);
    sorted.sort((a, b) -> a.path().compareTo(b.path()));
    List<Part> parts = new ArrayList<>();
    Map<String, String> aliases = new HashMap<>();
    for (FileChange change : sorted) {
      Part part = part(change, names, routes);
      parts.add(part);
      if (part.code() && part.label() != null) aliases.putIfAbsent(part.base().toLowerCase(Locale.ROOT), part.label());
    }
    Map<String, Group> groups = new LinkedHashMap<>();
    for (Part part : parts) {
      String label = part.label();
      if (label == null) label = mapped(part.base(), names);
      if (label == null) label = aliases.get(part.base().toLowerCase(Locale.ROOT));
      boolean named = label != null;
      if (label == null) label = part.fallback();
      Group group = groups.computeIfAbsent(label, key -> new Group());
      group.items.addAll(part.items());
      if (part.screen() && named) group.items.add(WORD_SCREEN + " " + verb(EnumSet.of(part.kind())));
      group.kinds.add(part.kind());
    }
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, Group> entry : groups.entrySet()) {
      if (!out.isEmpty()) out.append('\n');
      out.append(entry.getKey()).append(entry.getValue().text());
    }
    return out.toString();
  }

  private static final class Group {
    final Set<String> items = new LinkedHashSet<>();
    final Set<Kind> kinds = EnumSet.noneOf(Kind.class);

    String text() {
      if (items.isEmpty()) return " " + verb(kinds);
      List<String> list = new ArrayList<>(items);
      String shown = String.join(", ", list.subList(0, Math.min(MAX_ITEMS, list.size())));
      return ": " + shown + (list.size() > MAX_ITEMS ? " " + String.format(MORE, list.size() - MAX_ITEMS) : "");
    }
  }

  private static String verb(Set<Kind> kinds) {
    if (kinds.equals(EnumSet.of(Kind.ADDED))) return WORD_ADDED;
    if (kinds.equals(EnumSet.of(Kind.DELETED))) return WORD_DELETED;
    return WORD_MODIFIED;
  }

  private static Part part(FileChange change, Map<String, String> names, List<String> routes) {
    String path = change.path().replace('\\', '/');
    String fileName = path.substring(path.lastIndexOf('/') + 1);
    int dot = fileName.lastIndexOf('.');
    String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
    String ext = dot > 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    String before = normalize(change.before());
    String after = normalize(change.after());
    String text = after != null ? after : before;
    Kind kind = change.kind();
    List<String> folders = folders(path);

    if (text != null && (ext.equals("java") || ext.equals("kt"))) {
      boolean kotlin = ext.equals("kt");
      return codePart(parseCode(before, kotlin), parseCode(after, kotlin), stem, folders, kind, names);
    }
    if (text != null && ext.equals("xml") && MAPPER.matcher(text).find()) {
      return codePart(parseMapper(before), parseMapper(after), stem, folders, kind, names);
    }
    boolean screen = SCREEN_EXTS.contains(ext);
    String base = stripRole(stem);
    String label = null;
    if (text != null && (screen || SCRIPT_EXTS.contains(ext))) {
      label = topComment(text);
      if (label == null && (ext.equals("jsp") || ext.startsWith("htm"))) label = titleTag(text);
      if (label == null) label = routeTitle(routes, routeKeys(stem, folders));
      if (label != null) label = stripRoleWords(label);
    }
    if (label == null && mapped(base, names) == null) label = folderName(folders, names);
    return new Part(label, base, fileName, List.of(), kind, screen, false);
  }

  private static Part codePart(Unit before, Unit after, String stem, List<String> folders, Kind kind, Map<String, String> names) {
    Unit ref = after.name() != null ? after : before;
    String base = stripRole(ref.name() != null ? ref.name() : stem);
    String label = ref.label() != null ? stripRoleWords(ref.label()) : null;
    if (label == null && mapped(base, names) == null) label = folderName(folders, names);
    // 이름이 같은 기능이 빠지고 더해지면(인자를 바꾼 메서드) 「수정」 하나로 적는다.
    Map<String, String> verbs = new LinkedHashMap<>();
    for (Map.Entry<String, Member> entry : after.members().entrySet()) {
      Member old = before.members().get(entry.getKey());
      Member member = entry.getValue();
      if (old == null) putVerb(verbs, memberLabel(member, names), WORD_ADDED);
      else if (!old.text().equals(member.text())) putVerb(verbs, memberLabel(member, names), WORD_MODIFIED);
    }
    for (Map.Entry<String, Member> entry : before.members().entrySet()) {
      if (!after.members().containsKey(entry.getKey())) putVerb(verbs, memberLabel(entry.getValue(), names), WORD_DELETED);
    }
    List<String> items = new ArrayList<>();
    for (Map.Entry<String, String> entry : verbs.entrySet()) items.add(entry.getKey() + " " + entry.getValue());
    return new Part(label, base, base, items, kind, false, true);
  }

  private static void putVerb(Map<String, String> verbs, String label, String verb) {
    String old = verbs.putIfAbsent(label, verb);
    if (old != null && !old.equals(verb)) verbs.put(label, WORD_MODIFIED);
  }

  private static String memberLabel(Member member, Map<String, String> names) {
    if (member.label() != null) return member.label();
    String whole = names.get(member.name().toLowerCase(Locale.ROOT));
    return whole != null ? whole : member.name();
  }

  /** 이름표로 바꾼 이름. 이름 전체가 있으면 그것, 아니면 낱말마다 바꾸고 바꾼 낱말이 하나도 없으면 null. */
  static String mapped(String name, Map<String, String> names) {
    if (name == null || name.isEmpty() || names.isEmpty()) return null;
    String whole = names.get(name.toLowerCase(Locale.ROOT));
    if (whole != null) return whole;
    boolean any = false;
    List<String> words = new ArrayList<>();
    for (String word : WORD_SPLIT.split(name)) {
      if (word.isEmpty()) continue;
      String korean = names.get(word.toLowerCase(Locale.ROOT));
      any |= korean != null;
      words.add(korean != null ? korean : word);
    }
    return any ? String.join(" ", words) : null;
  }

  /** 가까운 폴더부터 이름표에 통째로 있는 폴더 이름. */
  private static String folderName(List<String> folders, Map<String, String> names) {
    for (String folder : folders) {
      String korean = names.get(folder.toLowerCase(Locale.ROOT));
      if (korean != null) return korean;
    }
    return null;
  }

  private static List<String> folders(String path) {
    String[] parts = path.split("/");
    List<String> folders = new ArrayList<>();
    for (int i = parts.length - 2; i >= 0; i--) {
      if (!parts[i].isEmpty()) folders.add(parts[i]);
    }
    return folders;
  }

  /** KickoffServiceImpl → Kickoff. 다 떼면 빈 이름이 되는 경우(Service)는 그대로 둔다. */
  static String stripRole(String name) {
    String result = name;
    boolean again = true;
    while (again) {
      again = false;
      for (String suffix : ROLE_SUFFIXES) {
        if (result.length() > suffix.length() && result.endsWith(suffix)) {
          result = result.substring(0, result.length() - suffix.length());
          again = true;
          break;
        }
      }
    }
    return result;
  }

  /** 「킥오프 관리 Controller」 → 「킥오프 관리」. 낱말이 모두 계층 낱말이면 그대로 둔다. */
  static String stripRoleWords(String label) {
    String[] words = label.trim().split("\\s+");
    int end = words.length;
    while (end > 1 && ROLE_WORDS.contains(words[end - 1].toLowerCase(Locale.ROOT))) end--;
    return String.join(" ", java.util.Arrays.copyOf(words, end));
  }

  private static String normalize(String text) {
    return text == null ? null : text.replace("\r\n", "\n").replace('\r', '\n');
  }

  // ---- 이름표 저장 모양: 줄마다 「영어 낱말\t한국어」 ----

  private static final Pattern NAME_WORD = Pattern.compile("[A-Za-z0-9_-]+");

  /** 저장 글 → 소문자 낱말 → 한국어. 모양이 틀린 줄은 건너뛴다. */
  static Map<String, String> parseNames(String stored) {
    Map<String, String> names = new HashMap<>();
    for (Map.Entry<String, String> entry : parseNamesKeepCase(stored).entrySet()) {
      names.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
    }
    return names;
  }

  /** 저장 글 → 적은 그대로의 낱말 → 한국어(설정 표용, 적은 순서). */
  static Map<String, String> parseNamesKeepCase(String stored) {
    Map<String, String> names = new LinkedHashMap<>();
    if (stored == null) return names;
    for (String line : stored.split("\n")) {
      int tab = line.indexOf('\t');
      if (tab <= 0) continue;
      String word = line.substring(0, tab).trim();
      String korean = line.substring(tab + 1).trim();
      if (NAME_WORD.matcher(word).matches() && !korean.isEmpty()) names.putIfAbsent(word, korean);
    }
    return names;
  }

  static String formatNames(List<String[]> rows) {
    StringBuilder out = new StringBuilder();
    for (String[] row : rows) {
      if (!out.isEmpty()) out.append('\n');
      out.append(row[0]).append('\t').append(row[1]);
    }
    return out.toString();
  }

  /** 표 한 줄의 문제(SyncMessages 키) 또는 null. rows 는 앞서 받은 줄. */
  static String nameProblem(List<String[]> rows, String word, String korean) {
    if (word.isEmpty() || korean.isEmpty()) return "commitnames.error.half";
    if (!NAME_WORD.matcher(word).matches()) return "commitnames.error.word";
    if (korean.indexOf('\t') >= 0 || korean.indexOf('\n') >= 0) return "commitnames.error.korean";
    for (String[] row : rows) {
      if (row[0].equalsIgnoreCase(word)) return "commitnames.error.same";
    }
    return null;
  }

  // ---- Java · Kotlin ----

  /** 맨 위 첫 형식(클래스 · 인터페이스 · enum · record · object)과 그 몸 바로 안의 메서드 · 안쪽 형식. text 가 null 이면 빈 것. */
  static Unit parseCode(String text, boolean kotlin) {
    if (text == null) return Unit.EMPTY;
    String masked = mask(text);
    int[] depth = depths(masked);
    Matcher decl = TYPE_DECL.matcher(masked);
    while (decl.find()) {
      if (depth[decl.start()] != 0) continue;
      int headerStart = 0;
      Matcher imports = IMPORT_LINE.matcher(masked).region(0, decl.start());
      while (imports.find()) headerStart = imports.end();
      String label = label(text.substring(headerStart, decl.start()), TAG, API);
      int open = bodyOpen(masked, decl.end(), kotlin);
      Map<String, Member> members = new LinkedHashMap<>();
      if (open >= 0) {
        int close = match(masked, open);
        if (kotlin) kotlinMembers(text, masked, open + 1, close, members);
        else javaMembers(text, masked, open + 1, close, members);
      }
      return new Unit(decl.group(2), label, members);
    }
    return Unit.EMPTY;
  }

  /**
   * 형식 선언 뒤 몸을 여는 '{'. 괄호 안(생성자 인자 · 어노테이션)은 건너뛴다. 몸이 없으면 -1.
   * Kotlin 은 ';' 없이 줄로 끝나므로(data class X(val a: Int)), 괄호 밖 줄바꿈 다음 글이 '{' · ':' · ',' 가 아니면 몸이 없다.
   */
  private static int bodyOpen(String masked, int from, boolean kotlin) {
    int paren = 0;
    for (int i = from; i < masked.length(); i++) {
      char c = masked.charAt(i);
      if (c == '(') paren++;
      else if (c == ')') paren--;
      else if (paren == 0 && c == '{') return i;
      else if (paren == 0 && c == ';') return -1;
      else if (kotlin && paren == 0 && c == '\n') {
        int next = skipSpace(masked, i, masked.length());
        if (next >= masked.length() || "{:,".indexOf(masked.charAt(next)) < 0) return -1;
        i = next - 1;
      }
    }
    return -1;
  }

  private static void javaMembers(String text, String masked, int from, int to, Map<String, Member> members) {
    int start = from;
    int paren = 0;
    for (int i = from; i < to; i++) {
      char c = masked.charAt(i);
      if (c == '(') paren++;
      else if (c == ')') paren--;
      else if (paren == 0 && c == ';') {
        javaMember(text, masked, start, i, i + 1, members);
        start = i + 1;
      }
      else if (paren == 0 && c == '{') {
        int end = Math.min(match(masked, i), to - 1);
        javaMember(text, masked, start, i, end + 1, members);
        i = end;
        start = end + 1;
      }
    }
  }

  /** start..body 가 머리(어노테이션 · 이름 · 인자), body..end 가 몸. 메서드 · 안쪽 형식만 넣고 필드 · 초기화 블록은 뺀다. */
  private static void javaMember(String text, String masked, int start, int body, int end, Map<String, Member> members) {
    String header = masked.substring(start, body).trim();
    String name;
    Matcher type = TYPE_DECL.matcher(header);
    if (!hasTopLevelAssign(header) && type.find()) {
      name = type.group(2);
    }
    else {
      String sig = THROWS.matcher(header).replaceFirst("");
      if (!sig.endsWith(")")) return;
      int open = openParen(sig, sig.length() - 1);
      if (open <= 0 || hasTopLevelAssign(sig.substring(0, open))) return;
      int nameEnd = open;
      while (nameEnd > 0 && Character.isWhitespace(sig.charAt(nameEnd - 1))) nameEnd--;
      int nameStart = nameEnd;
      while (nameStart > 0 && Character.isJavaIdentifierPart(sig.charAt(nameStart - 1))) nameStart--;
      name = sig.substring(nameStart, nameEnd);
      if (name.isEmpty() || KEYWORDS.contains(name) || name.startsWith("@")) return;
      if (nameStart > 0 && sig.charAt(nameStart - 1) == '@') return;
      name = name + "(" + collapse(sig.substring(open + 1, sig.length() - 1)) + ")";
    }
    putMember(text, masked, start, body, end, name, members);
  }

  private static void kotlinMembers(String text, String masked, int from, int to, Map<String, Member> members) {
    int start = from;
    int paren = 0;
    boolean lineInParen = false;
    for (int i = from; i < to; i++) {
      char c = masked.charAt(i);
      if (c == '(') paren++;
      else if (c == ')') paren--;
      else if (c == '\n') {
        // 어노테이션 · 빈 줄 · 주석 줄이 아닌 코드 줄(프로퍼티 등)이 끝나면 다음 메서드 머리는 그 뒤부터다.
        int lineStart = masked.lastIndexOf('\n', i - 1) + 1;
        String line = masked.substring(Math.max(lineStart, start), i).trim();
        if (paren == 0 && !lineInParen && !line.isEmpty() && line.charAt(0) != '@' && !line.startsWith("fun")) start = i + 1;
        lineInParen = paren > 0;
      }
      else if (paren == 0 && c == '{') {
        int end = Math.min(match(masked, i), to - 1);
        i = end;
        start = end + 1;
      }
      else if (paren == 0 && c == 'f' && masked.startsWith("fun", i) && wordAt(masked, i, 3)) {
        Matcher fun = KOTLIN_FUN.matcher(masked).region(skipSpace(masked, i + 3, to), to);
        if (!fun.lookingAt()) continue;
        String name = fun.group(1);
        int open = fun.end() - 1;
        int close = Math.min(match(masked, open, '(', ')'), to - 1);
        String params = collapse(masked.substring(open + 1, close));
        int end = kotlinEnd(masked, close + 1, to);
        putMember(text, masked, start, i, end, name + "(" + params + ")", members);
        i = end - 1;
        start = end;
      }
    }
  }

  /** Kotlin 메서드 끝: '{' 몸이면 짝 '}' 뒤, '=' 식이면 괄호가 닫힌 줄 끝, 몸이 없으면 그 줄 끝. */
  private static int kotlinEnd(String masked, int from, int to) {
    int depth = 0;
    boolean expression = false;
    for (int i = from; i < to; i++) {
      char c = masked.charAt(i);
      if (c == '(' || c == '[') depth++;
      else if (c == ')' || c == ']') depth--;
      else if (depth == 0 && c == '{') return Math.min(match(masked, i), to - 1) + 1;
      else if (depth == 0 && c == '=' && !expression) expression = true;
      else if (depth == 0 && (c == '\n' || c == ';')) {
        if (!expression) return i;
        String rest = masked.substring(from, i).trim();
        if (!rest.endsWith("=")) return i;
      }
    }
    return to;
  }

  /** key 는 겹치기를 가르는 이름 + 인자, 줄에 적는 이름은 메서드 이름만. */
  private static void putMember(String text, String masked, int start, int decl, int end, String key, Map<String, Member> members) {
    // 머리 첫 줄이 앞 줄 끝 주석(int a; // 개수)뿐이면 그 줄은 이 메서드 것이 아니다.
    int labelStart = start;
    int newline = masked.indexOf('\n', start);
    if (newline >= 0 && newline < decl && masked.substring(start, newline).isBlank()) labelStart = newline + 1;
    String label = label(text.substring(labelStart, decl), OPERATION, API_OPERATION);
    int paren = key.indexOf('(');
    String name = paren < 0 ? key : key.substring(0, paren);
    members.putIfAbsent(key, new Member(name, label, collapse(text.substring(labelStart, end))));
  }

  // ---- MyBatis 매퍼 ----

  static Unit parseMapper(String text) {
    if (text == null) return Unit.EMPTY;
    Matcher mapper = MAPPER.matcher(text);
    if (!mapper.find()) return Unit.EMPTY;
    String namespace = mapper.group(1);
    String name = namespace.substring(namespace.lastIndexOf('.') + 1);
    String label = lastComment(XML_COMMENT, text.substring(0, mapper.start()));
    Map<String, Member> members = new LinkedHashMap<>();
    int previous = mapper.end();
    Matcher statement = STATEMENT.matcher(text);
    while (statement.find(previous)) {
      String close = "</" + statement.group(1) + ">";
      int end = text.indexOf(close, statement.end());
      end = end < 0 ? statement.end() : end + close.length();
      String id = statement.group(2);
      String itemLabel = lastComment(XML_COMMENT, text.substring(previous, statement.start()));
      members.putIfAbsent(id, new Member(id, itemLabel, collapse(text.substring(statement.start(), end))));
      previous = end;
    }
    return new Unit(name, label, members);
  }

  // ---- 화면 · 스크립트 ----

  /** 파일 맨 위(import · 지시문 · 빈 줄 다음)의 첫 주석 첫 줄. 그 앞에 다른 코드가 있으면 null. */
  static String topComment(String text) {
    int i = 0;
    while (i < text.length()) {
      int lineEnd = text.indexOf('\n', i);
      if (lineEnd < 0) lineEnd = text.length();
      String line = text.substring(i, lineEnd).trim();
      String rest = text.substring(i).stripLeading();
      if (line.startsWith("<!--")) return korean(firstLine(leading(XML_COMMENT, rest)));
      if (line.startsWith("<%--")) return korean(firstLine(leading(JSP_COMMENT, rest)));
      if (line.startsWith("/*")) return korean(firstLine(leading(BLOCK_COMMENT, rest)));
      if (line.startsWith("//")) return korean(firstLine(line.replaceFirst("^//+", "")));
      boolean skip = line.isEmpty() || line.startsWith("import ") || line.startsWith("'use ") || line.startsWith("\"use ")
                     || line.startsWith("<script") || line.startsWith("<%@") || line.toLowerCase(Locale.ROOT).startsWith("<!doctype");
      if (!skip) return null;
      i = lineEnd + 1;
    }
    return null;
  }

  private static String titleTag(String text) {
    Matcher title = TITLE_TAG.matcher(text);
    if (!title.find()) return null;
    String value = title.group(1).trim();
    return value.isEmpty() || value.contains("$") || value.contains("{") ? null : value;
  }

  /**
   * 라우터에서 찾을 이름. 폴더와 함께 쓴 경로(kickoff/List)를 먼저 찾아 List.vue 같은 흔한 이름이 다른 메뉴에 붙지 않게 한다.
   * index.vue 는 폴더 이름만으로도 불린다(import('@/views/kickoff')).
   */
  private static List<String> routeKeys(String stem, List<String> folders) {
    if (folders.isEmpty()) return List.of(stem);
    String folder = folders.get(0);
    return List.of(folder + "/" + stem, stem.equalsIgnoreCase(INDEX) ? folder : stem);
  }

  /** 라우터 · 메뉴 파일에서 keys 를 차례로 찾아, 그 이름이 들어 있는 가장 안쪽 { } 의 한국어 title · label · name. */
  static String routeTitle(List<String> routes, List<String> keys) {
    for (String key : keys) {
      String title = routeTitle(routes, key);
      if (title != null) return title;
    }
    return null;
  }

  private static String routeTitle(List<String> routes, String key) {
    Pattern word = Pattern.compile("(?<![\\w$])" + Pattern.quote(key) + "(?![\\w$])");
    for (String route : routes) {
      String masked = maskComments(route);
      Matcher found = word.matcher(masked);
      while (found.find()) {
        int open = enclosingOpen(masked, found.start());
        if (open < 0) continue;
        int close = match(masked, open);
        Matcher title = ROUTE_TITLE.matcher(route.substring(open, Math.min(close + 1, route.length())));
        if (title.find()) return title.group(2).trim();
      }
    }
    return null;
  }

  private static int enclosingOpen(String masked, int at) {
    int depth = 0;
    for (int i = at - 1; i >= 0; i--) {
      char c = masked.charAt(i);
      if (c == '}') depth++;
      else if (c == '{') {
        if (depth == 0) return i;
        depth--;
      }
    }
    return -1;
  }

  // ---- 이름표 찾기 공통 ----

  /** 머리 글에서 이름: 어노테이션 설명(마지막 것) → 한국어가 든 문서 주석(마지막 것) 첫 줄 → 한국어가 든 줄 주석 첫 줄. */
  private static String label(String header, Pattern... annotations) {
    for (Pattern annotation : annotations) {
      String value = lastGroup(annotation, header);
      if (value != null && !value.isBlank()) return value.trim();
    }
    String doc = korean(firstLine(lastGroup(DOC_COMMENT, header)));
    if (doc != null) return doc;
    Matcher line = LINE_COMMENT.matcher(header);
    while (line.find()) {
      String value = korean(firstLine(line.group(1)));
      if (value != null) return value;
    }
    return null;
  }

  private static String lastComment(Pattern comment, String text) {
    return korean(firstLine(lastGroup(comment, text)));
  }

  /** 주석에서 온 이름은 한국어가 들어 있을 때만 쓴다(「Created by …」 · 저작권 머리 · 주석 처리한 코드를 거른다). */
  private static String korean(String value) {
    return value != null && HANGUL.matcher(value).find() ? value : null;
  }

  private static String lastGroup(Pattern pattern, String text) {
    Matcher m = pattern.matcher(text);
    String last = null;
    while (m.find()) last = m.group(1);
    return last;
  }

  private static String leading(Pattern pattern, String text) {
    Matcher m = pattern.matcher(text);
    return m.lookingAt() ? m.group(1) : null;
  }

  /** 주석 본문의 첫 글 줄. 줄머리 '*' · HTML 태그를 떼고 '@' 로 시작하는 줄(@param 등)은 건너뛴다. */
  static String firstLine(String comment) {
    if (comment == null) return null;
    for (String raw : comment.split("\n")) {
      String line = raw.trim().replaceFirst("^\\*+", "").trim();
      line = LINK.matcher(line).replaceAll("$1");
      line = HTML_TAG.matcher(line).replaceAll("").trim();
      if (line.isEmpty()) continue;
      if (line.startsWith("@")) return null;
      while (line.endsWith(".") || line.endsWith("*")) line = line.substring(0, line.length() - 1).trim();
      return line.isEmpty() ? null : line;
    }
    return null;
  }

  // ---- 글 다루기 ----

  /** 주석 · 문자열 · 문자 글을 같은 길이의 빈칸으로 바꾼다(줄바꿈은 둔다). 괄호 세기용. */
  static String mask(String text) {
    StringBuilder out = new StringBuilder(text);
    int n = text.length();
    int i = 0;
    while (i < n) {
      char c = text.charAt(i);
      int end;
      if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') end = lineEnd(text, i);
      else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') end = closeAt(text, "*/", i + 2);
      else if (text.startsWith("\"\"\"", i)) end = closeAt(text, "\"\"\"", i + 3);
      else if (c == '"' || c == '\'') end = quoteEnd(text, i, c);
      else {
        i++;
        continue;
      }
      blank(out, i, end);
      i = end;
    }
    return out.toString();
  }

  /** 주석만 빈칸으로(JS 라우터 파일용. 문자열 안의 화면 이름은 남긴다). */
  private static String maskComments(String text) {
    StringBuilder out = new StringBuilder(text);
    int n = text.length();
    int i = 0;
    while (i < n) {
      char c = text.charAt(i);
      if (c == '"' || c == '\'' || c == '`') {
        i = quoteEnd(text, i, c);
        continue;
      }
      int end;
      if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') end = lineEnd(text, i);
      else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') end = closeAt(text, "*/", i + 2);
      else {
        i++;
        continue;
      }
      blank(out, i, end);
      i = end;
    }
    return out.toString();
  }

  private static void blank(StringBuilder out, int from, int to) {
    for (int k = from; k < to; k++) {
      if (out.charAt(k) != '\n') out.setCharAt(k, ' ');
    }
  }

  private static int lineEnd(String text, int from) {
    int end = text.indexOf('\n', from);
    return end < 0 ? text.length() : end;
  }

  private static int closeAt(String text, String close, int from) {
    int end = text.indexOf(close, from);
    return end < 0 ? text.length() : end + close.length();
  }

  /** 따옴표 글의 끝(닫는 따옴표 뒤). 줄이 끝나도 닫힌 것으로 본다(' 가 글자가 아닌 곳에서 쓰인 경우). */
  private static int quoteEnd(String text, int from, char quote) {
    for (int k = from + 1; k < text.length(); k++) {
      char c = text.charAt(k);
      if (c == '\\') k++;
      else if (c == quote) return k + 1;
      else if (c == '\n' && quote != '`') return k;
    }
    return text.length();
  }

  private static int[] depths(String masked) {
    int[] depth = new int[masked.length() + 1];
    int d = 0;
    for (int i = 0; i < masked.length(); i++) {
      char c = masked.charAt(i);
      if (c == '}') d = Math.max(0, d - 1);
      depth[i] = d;
      if (c == '{') d++;
    }
    depth[masked.length()] = d;
    return depth;
  }

  /** open 의 '{' 짝 '}' 위치. 없으면 글 끝 앞. */
  private static int match(String masked, int open) {
    return match(masked, open, '{', '}');
  }

  private static int match(String masked, int open, char left, char right) {
    int depth = 0;
    for (int i = open; i < masked.length(); i++) {
      char c = masked.charAt(i);
      if (c == left) depth++;
      else if (c == right && --depth == 0) return i;
    }
    return masked.length() - 1;
  }

  private static int openParen(String text, int close) {
    int depth = 0;
    for (int i = close; i >= 0; i--) {
      char c = text.charAt(i);
      if (c == ')') depth++;
      else if (c == '(' && --depth == 0) return i;
    }
    return -1;
  }

  /** 괄호 · 꺾쇠 밖의 '=' (필드 초기값). '==' · '->' 는 해당 없음. */
  private static boolean hasTopLevelAssign(String text) {
    int depth = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '(' || c == '<' || c == '[') depth++;
      else if (c == ')' || c == '>' || c == ']') depth = Math.max(0, depth - 1);
      else if (c == '=' && depth == 0) {
        boolean pair = (i + 1 < text.length() && text.charAt(i + 1) == '=') || (i > 0 && "=!<>".indexOf(text.charAt(i - 1)) >= 0);
        if (!pair) return true;
      }
    }
    return false;
  }

  private static boolean wordAt(String text, int at, int length) {
    boolean before = at == 0 || !Character.isJavaIdentifierPart(text.charAt(at - 1));
    boolean after = at + length >= text.length() || !Character.isJavaIdentifierPart(text.charAt(at + length));
    return before && after;
  }

  private static int skipSpace(String text, int from, int to) {
    int i = from;
    while (i < to && Character.isWhitespace(text.charAt(i))) i++;
    return i;
  }

  private static String collapse(String text) {
    return text.trim().replaceAll("\\s+", " ");
  }
}
