package ei.db.mybatis;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「주석 만들기」의 글 만들기. 메서드 · 클래스 이름을 낱말로 쪼개 사전으로 한국어 설명을 짓고,
 * 매퍼 SQL 에서 표 · 조건 칼럼 · 매개변수 ↔ 칼럼 짝을 읽어 설명 줄과 @param 설명에 쓴다. IntelliJ 클래스를 쓰지 않는다.
 */
public final class DocComment {
  private DocComment() {}

  private static final String VERB_PREFIX = "verb.";
  private static final String WORD_PREFIX = "word.";
  private static final String TYPE_PREFIX = "type.";
  /** 「~ 여부를 확인한다」로 쓰는 동사. */
  private static final String VERB_CHECK = "확인";
  /** 「~ 건수를 조회한다」로 쓰는 동사. */
  private static final String VERB_COUNT = "건수 조회";
  /** 정수를 돌려주면 「처리 건수」로 적는 동사. */
  private static final Set<String> VERB_WRITE = Set.of("등록", "수정", "삭제", "저장", "변경");
  private static final String NO_VERB = "처리";
  private static final String FALLBACK_RESULT = "결과";
  private static final String THROWS_TEXT = "처리 중 오류가 나면";

  private static final Set<String> NUMBER_TYPES = Set.of("int", "long", "short", "Integer", "Long", "Short");
  private static final Set<String> BOOLEAN_TYPES = Set.of("boolean", "Boolean");
  private static final Set<String> VOID_TYPES = Set.of("void", "Void");
  private static final Set<String> LIST_TYPES = Set.of("List", "ArrayList", "LinkedList", "Collection", "Set", "HashSet", "Iterable", "Page", "Stream");
  /** 매개변수 형식이 이것이면 형식 대신 매개변수 이름으로 설명한다. */
  private static final Set<String> PLAIN_TYPES = Set.of(
      "String", "int", "Integer", "long", "Long", "short", "Short", "double", "Double", "float", "Float",
      "boolean", "Boolean", "char", "Character", "byte", "Byte", "BigDecimal", "BigInteger", "Object",
      "Date", "LocalDate", "LocalDateTime", "LocalTime", "Timestamp", "Map", "HashMap", "LinkedHashMap");
  /** 컨트롤러가 String 을 돌려주면 화면 이름이다. */
  private static final String VIEW_NAME = "화면 이름";

  // ---------------------------------------------------------------- 사전

  /** verbs: 첫 낱말 → 동사. words: 낱말 → 한국어(빈 값이면 뺀다). types: 형식 단순 이름 → 설명. 키는 소문자(types 는 그대로). */
  public record Dict(Map<String, String> verbs, Map<String, String> words, Map<String, String> types) {}

  /** 기본 사전(properties) 위에 사용자 사전 글(같은 꼴, 한 줄에 하나)을 덮는다. 잘못된 줄은 건너뛴다. */
  public static Dict dict(Reader defaults, String userText) throws IOException {
    Properties p = new Properties();
    p.load(defaults);
    if (userText != null && !userText.isBlank()) p.load(new StringReader(userText));
    Map<String, String> verbs = new LinkedHashMap<>();
    Map<String, String> words = new LinkedHashMap<>();
    Map<String, String> types = new LinkedHashMap<>();
    for (String key : p.stringPropertyNames()) {
      String value = p.getProperty(key).trim();
      if (key.startsWith(VERB_PREFIX)) verbs.put(key.substring(VERB_PREFIX.length()).toLowerCase(Locale.ROOT), value);
      else if (key.startsWith(WORD_PREFIX)) words.put(key.substring(WORD_PREFIX.length()).toLowerCase(Locale.ROOT), value);
      else if (key.startsWith(TYPE_PREFIX)) types.put(key.substring(TYPE_PREFIX.length()), value);
    }
    return new Dict(verbs, words, types);
  }

  /** 카멜 · 파스칼 · 스네이크 이름을 소문자 낱말로. 연속 대문자(URLPath)는 마지막 대문자 앞에서 끊는다. 숫자는 앞 낱말에 붙인다. */
  public static List<String> words(String name) {
    List<String> out = new ArrayList<>();
    if (name == null) return out;
    StringBuilder cur = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c == '_' || c == '$' || c == '-' || c == ' ') {
        flush(cur, out);
        continue;
      }
      boolean upper = Character.isUpperCase(c);
      boolean prevLower = i > 0 && (Character.isLowerCase(name.charAt(i - 1)) || Character.isDigit(name.charAt(i - 1)));
      boolean nextLower = i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1));
      boolean prevUpper = i > 0 && Character.isUpperCase(name.charAt(i - 1));
      if (upper && (prevLower || (prevUpper && nextLower))) flush(cur, out);
      cur.append(c);
    }
    flush(cur, out);
    return out;
  }

  private static void flush(StringBuilder cur, List<String> out) {
    if (cur.length() > 0) out.add(cur.toString().toLowerCase(Locale.ROOT));
    cur.setLength(0);
  }

  /** 낱말들을 사전으로 바꿔 빈칸으로 잇는다. 사전에 빈 값이면 빼고, 없으면 영어 낱말 그대로. */
  static String noun(List<String> words, Dict d) {
    StringBuilder b = new StringBuilder();
    for (String w : words) {
      String k = d.words().containsKey(w) ? d.words().get(w) : w;
      if (k.isEmpty()) continue;
      if (b.length() > 0) b.append(' ');
      b.append(k);
    }
    return b.toString();
  }

  /** 목적격 조사. 마지막 글자가 한글이면 받침으로 을 · 를, 아니면 「을(를)」. */
  static String objectParticle(String noun) {
    if (noun.isEmpty()) return "";
    char c = noun.charAt(noun.length() - 1);
    if (c < '가' || c > '힣') return "을(를)";
    return (c - '가') % 28 == 0 ? "를" : "을";
  }

  /** 메서드 이름 → 한 줄 설명(마침표 포함). */
  static String sentence(String methodName, Dict d) {
    List<String> w = words(methodName);
    String verb = w.isEmpty() ? null : d.verbs().get(w.get(0));
    String n = noun(verb == null ? w : w.subList(1, w.size()), d);
    if (VERB_CHECK.equals(verb)) return n.isEmpty() ? VERB_CHECK + "한다." : n + " 여부를 " + VERB_CHECK + "한다.";
    if (VERB_COUNT.equals(verb)) return (n.isEmpty() ? "" : n + " ") + "건수를 조회한다.";
    String v = verb == null ? NO_VERB : verb;
    return n.isEmpty() ? v + "한다." : n + objectParticle(n) + " " + v + "한다.";
  }

  /** 메서드 이름에서 동사를 뺀 명사. */
  private static String subject(String methodName, Dict d) {
    List<String> w = words(methodName);
    boolean verb = !w.isEmpty() && d.verbs().containsKey(w.get(0));
    return noun(verb ? w.subList(1, w.size()) : w, d);
  }

  private static String verbOf(String methodName, Dict d) {
    List<String> w = words(methodName);
    return w.isEmpty() ? null : d.verbs().get(w.get(0));
  }

  /** 제네릭을 뗀 단순 형식 이름(List&lt;UserVo&gt; → List, String[] → String[]). */
  static String rawType(String type) {
    int lt = type.indexOf('<');
    String raw = lt < 0 ? type : type.substring(0, lt) + (type.endsWith("[]") ? "[]" : "");
    return raw.substring(raw.lastIndexOf('.') + 1).trim();
  }

  /** 제네릭 첫 인자(List&lt;UserVo&gt; → UserVo). 없으면 null. */
  static String elementType(String type) {
    int lt = type.indexOf('<');
    int gt = type.lastIndexOf('>');
    if (lt < 0 || gt < lt) return null;
    String inner = type.substring(lt + 1, gt);
    int comma = inner.indexOf(',');
    return (comma < 0 ? inner : inner.substring(0, comma)).trim();
  }

  // ---------------------------------------------------------------- SQL

  /** 매퍼 문장 하나. label 은 「UserMapper.selectUserList」 꼴, text 는 동적 태그를 뺀 SQL 글. */
  public record Sql(String label, String text) {}

  /** 표(대문자, 나온 차례) · 조건 칼럼(대문자) · 매개변수 이름 → 칼럼. */
  public record SqlFacts(List<String> tables, List<String> conditions, Map<String, String> paramColumns) {}

  private static final String NAME = "([\\w.$#\"]+)";
  private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
  private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
  private static final Pattern SINGLE_TABLE = Pattern.compile("(?i)\\b(?:INTO|UPDATE|JOIN|DELETE(?:\\s+FROM)?)\\s+" + NAME);
  private static final Pattern FROM = Pattern.compile("(?i)\\bFROM\\s+(.*?)(?=\\bWHERE\\b|\\bGROUP\\b|\\bORDER\\b|\\bHAVING\\b|\\bUNION\\b|\\bCONNECT\\b|\\bSTART\\b"
      + "|\\bINNER\\b|\\bLEFT\\b|\\bRIGHT\\b|\\bFULL\\b|\\bCROSS\\b|\\bJOIN\\b|\\bON\\b|\\bLIMIT\\b|\\bFETCH\\b|\\bFOR\\b|\\)|;|$)", Pattern.DOTALL);
  /** FROM 바로 앞 낱말이 이것이면 EXTRACT(YEAR FROM …) · TRIM(LEADING … FROM …) 의 FROM 이다. */
  private static final Pattern NOT_TABLE_FROM = Pattern.compile("(?i)(?:\\b(?:YEAR|MONTH|DAY|HOUR|MINUTE|SECOND|LEADING|TRAILING|BOTH)|')\\s*$");
  private static final Pattern WHERE = Pattern.compile("(?i)\\bWHERE\\b");
  private static final Pattern CONDITION = Pattern.compile(
      "(?i)(?:\\b\\w+\\.)?\\b([A-Za-z_]\\w*)\\s*(?:=|<>|!=|>=|<=|<|>|\\bNOT\\s+LIKE\\b|\\bLIKE\\b|\\bNOT\\s+IN\\b|\\bIN\\b|\\bBETWEEN\\b)\\s*\\(?\\s*"
          + "(?:'%'\\s*\\|\\|\\s*|CONCAT\\s*\\(\\s*'%'\\s*,\\s*)?[#$]\\{\\s*([\\w.]+)");
  private static final Pattern INSERT_VALUES = Pattern.compile("(?is)\\bINSERT\\s+INTO\\s+[\\w.$#\"]+\\s*\\((.*?)\\)\\s*VALUES\\s*\\((.*)\\)");
  private static final Pattern PARAM = Pattern.compile("[#$]\\{\\s*([\\w.]+)");
  /** 표 이름 자리에 와도 표가 아닌 낱말. */
  private static final Set<String> NOT_TABLES = Set.of("SET", "SELECT", "DUAL", "OF", "NOWAIT", "WAIT", "SKIP", "LATERAL", "ONLY", "FROM", "TABLE");
  /** 조건 칼럼으로 적지 않을 낱말(행 번호 · SQL 낱말). */
  private static final Set<String> NOT_COLUMNS = Set.of("ROWNUM", "RN", "RNUM", "LEVEL", "AND", "OR", "NOT", "WHERE", "ON", "THEN", "WHEN", "ELSE");

  /** 매퍼 SQL 글에서 표 · 조건 · 매개변수 ↔ 칼럼을 읽는다. 동적 SQL(${})로 만든 표 이름은 뺀다. */
  public static SqlFacts facts(String sql) {
    String s = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(sql).replaceAll(" ")).replaceAll(" ");
    Set<String> tables = new LinkedHashSet<>();
    Matcher f = FROM.matcher(s);
    while (f.find()) {
      if (NOT_TABLE_FROM.matcher(s.substring(Math.max(0, f.start() - 20), f.start())).find()) continue;
      for (String part : f.group(1).split(",")) {
        String t = part.trim().split("\\s+")[0];
        addTable(tables, t);
      }
    }
    Matcher one = SINGLE_TABLE.matcher(s);
    while (one.find()) addTable(tables, one.group(1));

    Map<String, String> paramColumns = new LinkedHashMap<>();
    Set<String> conditions = new LinkedHashSet<>();
    Matcher where = WHERE.matcher(s);
    int whereAt = where.find() ? where.start() : -1;
    Matcher c = CONDITION.matcher(s);
    while (c.find()) {
      String col = c.group(1).toUpperCase(Locale.ROOT);
      if (NOT_COLUMNS.contains(col)) continue;
      paramColumns.putIfAbsent(lastSegment(c.group(2)), col);
      if (whereAt >= 0 && c.start() > whereAt) conditions.add(col);
    }
    Matcher ins = INSERT_VALUES.matcher(s);
    if (ins.find()) {
      List<String> cols = splitTop(ins.group(1));
      List<String> vals = splitTop(ins.group(2));
      for (int i = 0; i < cols.size() && i < vals.size(); i++) {
        Matcher p = PARAM.matcher(vals.get(i));
        if (p.find()) paramColumns.putIfAbsent(lastSegment(p.group(1)), cols.get(i).trim().toUpperCase(Locale.ROOT));
      }
    }
    return new SqlFacts(new ArrayList<>(tables), new ArrayList<>(conditions), paramColumns);
  }

  private static void addTable(Set<String> tables, String raw) {
    if (raw.isEmpty() || raw.startsWith("(") || raw.contains("{")) return;
    String n = raw.replace("\"", "");
    n = n.substring(n.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
    if (n.isEmpty() || NOT_TABLES.contains(n) || !Character.isLetter(n.charAt(0))) return;
    tables.add(n);
  }

  private static String lastSegment(String path) {
    return path.substring(path.lastIndexOf('.') + 1);
  }

  /** 괄호 깊이 0 의 쉼표로 나눈다. */
  private static List<String> splitTop(String s) {
    List<String> out = new ArrayList<>();
    int depth = 0;
    int from = 0;
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch == '(') depth++;
      else if (ch == ')') depth--;
      else if (ch == ',' && depth == 0) {
        out.add(s.substring(from, i));
        from = i + 1;
      }
    }
    out.add(s.substring(from));
    return out;
  }

  // ---------------------------------------------------------------- 주석 글

  public record Param(String name, String type) {}

  /**
   * 메서드 하나의 재료. returnType 이 null 이면 생성자. url 은 「GET /user/list」 꼴(없으면 null).
   * sqls 는 이 메서드가 매퍼 문장이거나 따라가 닿은 매퍼 문장들.
   */
  public record Method(String name, List<Param> params, String returnType, List<String> throwsTypes, String url, List<Sql> sqls) {}

  /** 주석에 넣을 공통 꼬리. author · date 가 비면 그 줄을 뺀다. */
  public record Tail(String author, String date) {}

  /**
   * 메서드 문서 주석 전체(「/**」 ~ 「*&#47;」). tableComments · columnComments 는 대문자 이름 → DB 주석(없으면 빈 맵).
   */
  public static String forMethod(Method m, Dict d, Map<String, String> tableComments, Map<String, String> columnComments, Tail tail) {
    List<String> lines = new ArrayList<>();
    lines.add(sentence(m.name(), d));
    List<String> info = new ArrayList<>();
    if (m.url() != null) info.add("주소: " + m.url());
    Map<String, String> paramColumns = new LinkedHashMap<>();
    if (!m.sqls().isEmpty()) {
      Set<String> tables = new LinkedHashSet<>();
      Set<String> conditions = new LinkedHashSet<>();
      List<String> labels = new ArrayList<>();
      for (Sql sql : m.sqls()) {
        SqlFacts f = facts(sql.text());
        tables.addAll(f.tables());
        conditions.addAll(f.conditions());
        for (Map.Entry<String, String> e : f.paramColumns().entrySet()) paramColumns.putIfAbsent(e.getKey(), e.getValue());
        labels.add(sql.label());
      }
      if (!tables.isEmpty()) info.add("표: " + withComments(tables, tableComments, true));
      if (!conditions.isEmpty()) info.add("조건: " + withComments(conditions, columnComments, false));
      info.add("SQL: " + String.join(", ", labels));
    }
    if (!info.isEmpty()) {
      lines.add("");
      lines.addAll(info);
    }
    List<String> tags = new ArrayList<>();
    for (Param p : m.params()) tags.add("@param " + p.name() + " " + paramText(p, paramColumns, columnComments, d));
    String ret = m.returnType() == null ? null : returnText(m, d);
    if (ret != null) tags.add("@return " + ret);
    for (String t : m.throwsTypes()) tags.add("@throws " + rawType(t) + " " + THROWS_TEXT);
    addTail(tags, tail);
    return render(lines, tags);
  }

  /** 클래스 문서 주석: 이름 설명 한 줄과 작성자 · 날짜. */
  public static String forClass(String className, Dict d, Tail tail) {
    String n = noun(words(className), d);
    List<String> tags = new ArrayList<>();
    addTail(tags, tail);
    return render(List.of((n.isEmpty() ? className : n) + "."), tags);
  }

  private static void addTail(List<String> tags, Tail tail) {
    if (tail.author() != null && !tail.author().isBlank()) tags.add("@author " + tail.author().trim());
    if (tail.date() != null && !tail.date().isBlank()) tags.add("@since " + tail.date().trim());
  }

  /** 「TB_USER(사용자)」 · 「사용자 아이디(USER_ID)」 꼴. 주석이 없으면 이름만. */
  private static String withComments(Set<String> names, Map<String, String> comments, boolean nameFirst) {
    List<String> out = new ArrayList<>();
    for (String n : names) {
      String c = comments.get(n);
      if (c == null || c.isBlank()) out.add(n);
      else out.add(nameFirst ? n + "(" + c.trim() + ")" : c.trim() + "(" + n + ")");
    }
    return String.join(", ", out);
  }

  /** 칼럼 주석 → 이름이 같은 칼럼 주석 → 형식 사전 → VO 형식 이름 → 매개변수 이름 차례로 고른다. */
  static String paramText(Param p, Map<String, String> paramColumns, Map<String, String> columnComments, Dict d) {
    String col = paramColumns.get(p.name());
    if (col != null && columnComments.containsKey(col)) return columnComments.get(col);
    String snake = String.join("_", words(p.name())).toUpperCase(Locale.ROOT);
    if (columnComments.containsKey(snake)) return columnComments.get(snake);
    String raw = rawType(p.type());
    if (d.types().containsKey(raw)) return d.types().get(raw);
    if (!PLAIN_TYPES.contains(raw) && !LIST_TYPES.contains(raw) && !raw.endsWith("[]")) {
      String t = noun(words(raw), d);
      if (!t.isEmpty()) return t;
    }
    String n = noun(words(p.name()), d);
    return n.isEmpty() ? p.name() : n;
  }

  /** @return 설명. void 면 null. */
  static String returnText(Method m, Dict d) {
    String type = m.returnType();
    String raw = rawType(type);
    if (VOID_TYPES.contains(raw)) return null;
    if (d.types().containsKey(raw)) return d.types().get(raw);
    String verb = verbOf(m.name(), d);
    String subject = subject(m.name(), d);
    if (BOOLEAN_TYPES.contains(raw)) return (subject.isEmpty() ? "" : subject + " ") + "여부";
    if (NUMBER_TYPES.contains(raw)) {
      if (VERB_COUNT.equals(verb)) return (subject.isEmpty() ? "" : subject + " ") + "건수";
      if (verb != null && VERB_WRITE.contains(verb)) return "처리 건수";
    }
    if (LIST_TYPES.contains(raw) || raw.endsWith("[]")) {
      if (subject.endsWith("목록")) return subject;
      String el = elementType(type);
      String base = subject.isEmpty() && el != null ? noun(words(rawType(el)), d) : subject;
      return (base.isEmpty() ? "" : base + " ") + "목록";
    }
    if ("String".equals(raw) && m.url() != null) return VIEW_NAME;
    if (!PLAIN_TYPES.contains(raw)) {
      String t = noun(words(raw), d);
      if (!t.isEmpty() && !t.equalsIgnoreCase(raw)) return t;
    }
    return subject.isEmpty() ? FALLBACK_RESULT : subject;
  }

  /** 줄 · 태그를 문서 주석으로. 글 안의 「*&#47;」는 주석을 닫지 않게 바꾼다. */
  private static String render(List<String> lines, List<String> tags) {
    StringBuilder b = new StringBuilder("/**\n");
    for (String l : lines) b.append(l.isEmpty() ? " *\n" : " * " + safe(l) + "\n");
    if (!tags.isEmpty()) {
      b.append(" *\n");
      for (String t : tags) b.append(" * ").append(safe(t)).append('\n');
    }
    return b.append(" */").toString();
  }

  private static String safe(String s) {
    return s.replace("*/", "*&#47;");
  }

  // ---------------------------------------------------------------- Git 사용자

  /**
   * dir 이 든 Git 저장소의 user.name. 저장소 설정(.git/config, 작업 트리 · 서브모듈은 .git 파일이 가리키는 곳)에 없으면
   * 사용자 설정(home/.gitconfig, home/.config/git/config). 어디에도 없거나 읽지 못하면 null. include 는 따라가지 않는다.
   */
  public static String gitUserName(Path dir, Path home) {
    for (Path p = dir; p != null; p = p.getParent()) {
      Path git = p.resolve(".git");
      if (Files.isDirectory(git)) {
        String n = configName(git.resolve("config"));
        if (n != null) return n;
        break;
      }
      if (Files.isRegularFile(git)) {
        Path gitDir = pointedGitDir(p, git);
        if (gitDir != null) {
          String n = configName(gitDir.resolve("config"));
          if (n == null) n = configName(commonDir(gitDir).resolve("config"));
          if (n != null) return n;
        }
        break;
      }
    }
    if (home == null) return null;
    String n = configName(home.resolve(".gitconfig"));
    return n != null ? n : configName(home.resolve(".config").resolve("git").resolve("config"));
  }

  /** 「gitdir: 경로」 파일이 가리키는 폴더. 못 읽으면 null. */
  private static Path pointedGitDir(Path workTree, Path gitFile) {
    try {
      for (String line : Files.readAllLines(gitFile, StandardCharsets.UTF_8)) {
        if (line.startsWith("gitdir:")) return workTree.resolve(line.substring("gitdir:".length()).trim()).normalize();
      }
    } catch (IOException ignored) {
      // 읽지 못하는 .git 파일은 저장소가 없는 것으로 본다.
    }
    return null;
  }

  /** 작업 트리의 gitdir 이면 commondir 이 가리키는 본 저장소 폴더, 아니면 그대로. */
  private static Path commonDir(Path gitDir) {
    Path f = gitDir.resolve("commondir");
    try {
      if (Files.isRegularFile(f)) return gitDir.resolve(Files.readString(f, StandardCharsets.UTF_8).trim()).normalize();
    } catch (IOException ignored) {
      // commondir 을 못 읽으면 gitdir 자체 설정만 본다.
    }
    return gitDir;
  }

  /** git 설정 파일의 [user] name. 파일이 없거나 값이 없으면 null. */
  static String configName(Path file) {
    if (!Files.isRegularFile(file)) return null;
    try {
      return parseUserName(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      return null;
    }
  }

  /** [user] 절의 name 값. 따옴표는 벗기고, 따옴표 밖 「;」 · 「#」 뒤는 주석으로 본다. 여러 번 나오면 마지막 값(git 과 같다). */
  static String parseUserName(String config) {
    boolean inUser = false;
    String found = null;
    for (String raw : config.split("\\R")) {
      String line = raw.trim();
      if (line.startsWith("[")) {
        int close = line.indexOf(']');
        inUser = close > 0 && line.substring(1, close).trim().equalsIgnoreCase("user");
        continue;
      }
      if (!inUser) continue;
      int eq = line.indexOf('=');
      if (eq < 0 || !line.substring(0, eq).trim().equalsIgnoreCase("name")) continue;
      String v = stripComment(line.substring(eq + 1).trim());
      if (!v.isEmpty()) found = v;
    }
    return found;
  }

  private static String stripComment(String v) {
    StringBuilder b = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      if (c == '"') {
        quoted = !quoted;
        continue;
      }
      if (!quoted && (c == ';' || c == '#')) break;
      if (c == '\\' && i + 1 < v.length()) c = v.charAt(++i);
      b.append(c);
    }
    return b.toString().trim();
  }
}
