package ei.db.mybatis;

import ei.db.mybatis.MapperDoc.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 매퍼 문장 · sql 조각의 SQL 에서 표 이름과 「어느 표의 칼럼인지 확실한」 칼럼 이름을 뽑는다(IntelliJ 클래스 없음, MapperSqlScanCheck).
 * 목표는 오탐 0 이라 애매하면 내지 않는다.
 * <ul>
 * <li>표: FROM · JOIN · UPDATE · INSERT INTO · MERGE INTO · USING · DELETE 뒤의 「이름」 · 「스키마.이름」 만.
 *     서브쿼리 · WITH 이름 · 함수(TABLE( ) 등) · DB 링크(@) · DUAL · 세 마디 이름 · ${} 가 붙은 이름은 내지 않는다.</li>
 * <li>칼럼: 「별칭.칼럼」 은 그 별칭이 같은 문장(바깥 문장 포함)에서 표 하나로 정해질 때만. 함수 부르기 · 세 마디 이름 · 의사 칼럼은 뺀다.</li>
 * <li>한정자 없는 칼럼: INSERT 의 칼럼 목록과 UPDATE SET 의 왼쪽(대상 표의 칼럼), 그리고 바깥 문장의 표가 정확히 하나일 때
 *     SELECT 목록의 「칼럼」 · 「칼럼 별칭」 항목과 WHERE 조건의 「AND 칼럼 비교」 왼쪽만. ${} · include 가 있는 문장과 sql 조각은 뒤의 둘을 보지 않는다.</li>
 * <li>동적 태그는 안의 글을 차례로 이어 본다(where · set · trim prefix · foreach open/close 는 그 글로). 태그 경계 바로 뒤가 별칭 자리면 그 문장은 건너뛴다.</li>
 * <li>따옴표 · 괄호가 안 맞거나 PL/SQL 블록 · 여러 문장 · q'' 문자열이면 그 문장은 건너뛴다.</li>
 * </ul>
 * 위치는 문서 글자 오프셋이다(엔티티 · CDATA 는 길이를 바꾸지 않고 그 자리에서 가린다).
 */
final class MapperSqlScan {
  private MapperSqlScan() {}

  /** 문장에 쓴 표. schema · name 은 쓴 그대로(따옴표 포함), schema 는 없으면 null. 위치는 name 의 것. */
  record Table(String schema, String name, int start, int end) {}

  /** table 의 칼럼으로 쓴 이름(쓴 그대로)과 그 위치. */
  record Column(Table table, String name, int start, int end) {}

  record Result(List<Table> tables, List<Column> columns) {}

  /** 문서의 모든 문장과 sql 조각. doc 은 text 를 읽은 것이어야 한다. */
  static Result scan(CharSequence text, MapperDoc doc) {
    Result out = new Result(new ArrayList<>(), new ArrayList<>());
    for (Node s : doc.statements) scanNode(text, s, false, out);
    for (Node f : doc.fragments.values()) scanNode(text, f, true, out);
    return out;
  }

  private static void scanNode(CharSequence text, Node node, boolean fragment, Result out) {
    if (node.end > text.length() || node.start < 0 || node.end <= node.start) return;
    char[] buf = new char[node.end - node.start];
    Arrays.fill(buf, ' ');
    try {
      flatten(text, node, buf, node.start);
      scanSql(new String(buf), node.start, fragment, out);
    } catch (Skip | StackOverflowError skip) {
      // 확실하지 않은 문장 · 서브쿼리가 수천 겹인 문장: 아무것도 내지 않는다.
    }
  }

  /** SQL 글 하나를 훑어 out 에 더한다. base 는 sql 첫 글자의 문서 오프셋. 건너뛸 문장이면 아무것도 더하지 않는다. */
  static void scanSql(String sql, int base, boolean fragment, Result out) {
    Result mine = new Result(new ArrayList<>(), new ArrayList<>());
    try {
      new Parser(sql, base, fragment, mine).run();
    } catch (Skip skip) {
      return;
    }
    out.tables().addAll(mine.tables());
    out.columns().addAll(mine.columns());
  }

  /** Oracle 사전 뷰 · PostgreSQL 카탈로그처럼 어느 스키마에서나 보이는 이름(표 없음으로 짚지 않는다). */
  static boolean systemName(String bare) {
    String u = bare.toUpperCase(Locale.ROOT);
    if (SYSTEM_NAMES.contains(u)) return true;
    for (String p : SYSTEM_PREFIXES) {
      if (u.startsWith(p)) return true;
    }
    return false;
  }

  /** 따옴표를 뗀 이름. */
  static String bare(String written) { return written.replace("\"", ""); }

  // ---------------------------------------------------------------- 글 펴기

  /** include 자리 표시(무엇이 들어올지 모르는 조각). */
  private static final char OPAQUE_MARK = '\u0001';
  /** 동적 태그 경계 표시. */
  private static final char BOUNDARY_MARK = '\u0002';
  /** 엔티티(&lt; 등)를 가릴 때 두는 글자. 길이를 지키려고 연산자 하나로 본다. */
  private static final char ENTITY_MARK = '=';
  private static final String CDATA_OPEN = "<![CDATA[";
  private static final String CDATA_CLOSE = "]]>";
  /** 본문을 보지 않는 태그(다른 문장 · 값 정의). */
  private static final Set<String> IGNORED_TAGS = Set.of("selectKey", "bind", "property");
  private static final List<String> SYSTEM_PREFIXES = List.of("USER_", "ALL_", "DBA_", "V$", "GV$", "V_$", "PG_", "NLS_", "SESSION_");

  /** Oracle 이 모든 스키마에 공개 동의어로 주는 사전 뷰 · 표. */
  private static final Set<String> SYSTEM_NAMES = Set.of("TAB", "TABS", "COLS", "CAT", "DICT", "DICTIONARY", "IND", "OBJ", "SEQ", "SYN",
      "GLOBAL_NAME", "PLAN_TABLE", "RECYCLEBIN");

  private static final class Skip extends RuntimeException {
    Skip() { super(null, null, false, false); }
  }

  private static final Skip SKIP = new Skip();

  private static void flatten(CharSequence text, Node n, char[] buf, int base) {
    for (Node c : n.children) {
      if (c.name == null) {
        copyText(text, c, buf, base);
      } else if ("include".equals(c.name)) {
        buf[c.start - base] = OPAQUE_MARK;
      } else if (!IGNORED_TAGS.contains(c.name)) {
        int openEnd = openTagEnd(text, c.start, c.end);
        String prefix = switch (c.name) {
          case "where" -> "WHERE";
          case "set" -> "SET";
          case "trim" -> c.attr("prefix");
          case "foreach" -> c.attr("open");
          default -> null;
        };
        String suffix = "trim".equals(c.name) ? c.attr("suffix") : "foreach".equals(c.name) ? c.attr("close") : null;
        int closeStart = c.end - c.name.length() - 3;
        boolean closed = closeStart >= openEnd && text.charAt(closeStart) == '<' && text.charAt(closeStart + 1) == '/';
        if (prefix != null && !prefix.isBlank()) put(prefix, buf, c.start - base, openEnd - base);
        else buf[c.start - base] = BOUNDARY_MARK;
        flatten(text, c, buf, base);
        if (suffix != null && !suffix.isBlank()) {
          if (!closed) throw SKIP;
          put(suffix, buf, closeStart - base, c.end - base);
        } else if (closed) {
          buf[closeStart - base] = BOUNDARY_MARK;
        }
      }
    }
  }

  /** s 를 buf[from, to) 안에 앞뒤 빈칸을 하나씩 두고 넣는다(앞뒤 낱말과 붙지 않게). 자리가 모자라면 그 문장을 건너뛴다. */
  private static void put(String s, char[] buf, int from, int to) {
    if (from + s.length() + 2 > to) throw SKIP;
    for (int k = 0; k < s.length(); k++) buf[from + 1 + k] = Character.isWhitespace(s.charAt(k)) ? ' ' : s.charAt(k);
  }

  private static void copyText(CharSequence text, Node c, char[] buf, int base) {
    boolean cdata = startsWith(text, c.start, CDATA_OPEN);
    int from = cdata ? c.start + CDATA_OPEN.length() : c.start;
    int to = cdata && c.end - CDATA_CLOSE.length() >= from && startsWith(text, c.end - CDATA_CLOSE.length(), CDATA_CLOSE)
        ? c.end - CDATA_CLOSE.length() : c.end;
    for (int i = from; i < to; i++) {
      char ch = text.charAt(i);
      if (!cdata && ch == '&') {
        int semi = i + 1;
        while (semi < to && semi - i <= MAX_ENTITY && text.charAt(semi) != ';') semi++;
        if (semi < to && text.charAt(semi) == ';') {
          buf[i - base] = ENTITY_MARK;
          i = semi;
          continue;
        }
      }
      buf[i - base] = ch;
    }
  }

  /** 엔티티 이름으로 볼 가장 긴 길이(&#x10FFFF; 까지). */
  private static final int MAX_ENTITY = 10;

  private static boolean startsWith(CharSequence text, int at, String s) {
    if (at < 0 || at + s.length() > text.length()) return false;
    for (int k = 0; k < s.length(); k++) {
      if (text.charAt(at + k) != s.charAt(k)) return false;
    }
    return true;
  }

  /** 여는 태그의 「&gt;」 다음 위치. 속성 값 안의 &gt; 는 넘긴다. */
  private static int openTagEnd(CharSequence text, int from, int limit) {
    char quote = 0;
    for (int i = from; i < limit; i++) {
      char ch = text.charAt(i);
      if (quote != 0) {
        if (ch == quote) quote = 0;
      } else if (ch == '"' || ch == '\'') {
        quote = ch;
      } else if (ch == '>') {
        return i + 1;
      }
    }
    return limit;
  }

  // ---------------------------------------------------------------- 낱말

  private enum Type { IDENT, NUMBER, STRING, SYMBOL, BIND, OPAQUE }

  private static final class Tok {
    final Type type;
    final int start;
    final int end;
    /** 쓴 그대로. */
    final String raw;
    /** 이름은 따옴표를 떼고 대문자로, 기호는 그대로. */
    final String up;
    final boolean quoted;
    /** 바로 앞에 동적 태그 경계가 있다. */
    boolean boundary;
    /** ${} · include 와 빈칸 없이 붙은 이름. */
    boolean tainted;

    Tok(Type type, int start, int end, String raw) {
      this.type = type;
      this.start = start;
      this.end = end;
      this.raw = raw;
      this.quoted = type == Type.IDENT && raw.startsWith("\"");
      this.up = type == Type.IDENT ? bare(raw).toUpperCase(Locale.ROOT) : raw;
    }

    boolean sym(String s) { return type == Type.SYMBOL && up.equals(s); }

    boolean word(String s) { return type == Type.IDENT && !quoted && up.equals(s); }

    boolean in(Set<String> words) { return type == Type.IDENT && !quoted && words.contains(up); }
  }

  private static final Set<String> STATEMENT_HEADS = Set.of("SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE");
  private static final Set<String> JOIN_WORDS = Set.of("JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "OUTER", "STRAIGHT_JOIN", "APPLY");
  /** 표 목록을 끝내는 다음 절 머리. */
  private static final Set<String> CLAUSE = Set.of("WHERE", "GROUP", "ORDER", "HAVING", "UNION", "MINUS", "INTERSECT", "EXCEPT", "CONNECT", "START",
      "FOR", "LIMIT", "OFFSET", "FETCH", "WINDOW", "SET", "RETURNING", "WHEN", "SELECT", "VALUES", "MODEL", "QUALIFY", "DEFAULT", "OVERRIDING", "LOG");
  /** 표 · 별칭 자리에 와도 이름이 아닌 낱말. */
  private static final Set<String> NOT_NAME = union(CLAUSE, JOIN_WORDS, Set.of("ON", "USING", "AS", "OF", "PARTITION", "SUBPARTITION", "SAMPLE",
      "TABLESAMPLE", "PIVOT", "UNPIVOT", "VERSIONS", "MATCH_RECOGNIZE", "NOWAIT", "SKIP", "WITH", "FROM", "INTO", "UPDATE", "INSERT", "DELETE", "MERGE",
      "AND", "OR", "NOT", "ONLY", "LATERAL", "THEN", "ELSE", "END", "WAIT", "BY", "IS", "IN", "LIKE", "BETWEEN", "NULL", "EXISTS", "CASE"));
  private static final Set<String> WHERE_END = Set.of("GROUP", "ORDER", "HAVING", "CONNECT", "START", "UNION", "MINUS", "INTERSECT", "EXCEPT",
      "FOR", "LIMIT", "OFFSET", "FETCH", "RETURNING", "WINDOW", "MODEL", "QUALIFY");
  private static final Set<String> SET_END = Set.of("WHERE", "FROM", "RETURNING", "DELETE", "LOG");
  private static final Set<String> SELECT_QUANTIFIERS = Set.of("DISTINCT", "ALL", "UNIQUE");
  private static final Set<String> COMPARE_WORDS = Set.of("LIKE", "IN", "IS", "BETWEEN", "NOT");
  private static final Set<String> COMPARE_SYMBOLS = Set.of("=", "<", ">", "!", "^");
  private static final Set<String> PREDICATE_HEADS = Set.of("WHERE", "AND", "OR");
  /** 표의 칼럼이 아닌데 칼럼 자리에 올 수 있는 낱말: 의사 칼럼 · 인자 없는 함수 · 값 · 예약어. */
  private static final Set<String> NOT_COLUMN = union(NOT_NAME, Set.of("ROWNUM", "ROWID", "LEVEL", "SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE",
      "CURRENT_TIMESTAMP", "CURRENT_TIME", "LOCALTIMESTAMP", "LOCALTIME", "USER", "UID", "TRUE", "FALSE", "UNKNOWN", "CONNECT_BY_ISLEAF",
      "CONNECT_BY_ISCYCLE", "CONNECT_BY_ROOT", "PRIOR", "ORA_ROWSCN", "SESSIONTIMEZONE", "DBTIMEZONE", "CURRENT_USER", "SESSION_USER",
      "CURRENT_SCHEMA", "CURRENT_CATALOG", "CURRENT_ROLE", "DISTINCT", "ALL", "UNIQUE", "ANY", "SOME", "INTERVAL", "DATE", "TIMESTAMP", "TIME",
      "CAST", "NEW", "OLD", "EXCLUDED", "CTID", "XMIN", "XMAX", "CMIN", "CMAX", "TABLEOID", "OID", "NEXTVAL", "CURRVAL", "OBJECT_ID", "OBJECT_VALUE",
      "COLUMN_VALUE", "ROW", "ARRAY", "BINARY", "SQLCODE", "SQLERRM", "NOCYCLE", "ASC", "DESC", "NULLS", "ESCAPE", "OVER", "ROWS", "RANGE", "TOP", "SYSDATETIME"),
      Set.of());
  /** MODEL 절(칼럼 이름 MODEL 이 아닌)의 MODEL 바로 뒤에 오는 낱말. */
  private static final Set<String> MODEL_NEXT = Set.of("DIMENSION", "PARTITION", "RETURN", "UNIQUE", "IGNORE", "KEEP", "REFERENCE", "MAIN");
  private static final String VERSIONS_PREFIX = "VERSIONS_";
  private static final String DUAL = "DUAL";

  @SafeVarargs
  private static Set<String> union(Set<String>... sets) {
    Set<String> out = new HashSet<>();
    for (Set<String> s : sets) out.addAll(s);
    return Set.copyOf(out);
  }

  private static List<Tok> tokens(String s) {
    List<Tok> out = new ArrayList<>();
    int n = s.length();
    int i = 0;
    boolean boundary = false;
    while (i < n) {
      char c = s.charAt(i);
      if (Character.isWhitespace(c)) { i++; continue; }
      if (c == BOUNDARY_MARK) { boundary = true; i++; continue; }
      int start = i;
      Type type;
      if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
        int eol = s.indexOf('\n', i);
        i = eol < 0 ? n : eol;
        continue;
      } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
        int close = s.indexOf("*/", i + 2);
        if (close < 0) throw SKIP;
        i = close + 2;
        continue;
      } else if (c == '\'') {
        i++;
        while (true) {
          int q = s.indexOf('\'', i);
          if (q < 0) throw SKIP;
          if (q > 0 && s.charAt(q - 1) == '\\') throw SKIP; // 역슬래시 이스케이프(E'..') 는 끝을 확신할 수 없다.
          i = q + 1;
          if (i < n && s.charAt(i) == '\'') i++; else break;
        }
        type = Type.STRING;
      } else if (c == '"') {
        int q = s.indexOf('"', i + 1);
        if (q < 0) throw SKIP;
        i = q + 1;
        // 따옴표 이름 안의 ${} 는 무엇이 될지 모른다.
        type = s.substring(start, q).contains("${") ? Type.OPAQUE : Type.IDENT;
      } else if ((c == '#' || c == '$') && i + 1 < n && s.charAt(i + 1) == '{') {
        int close = s.indexOf('}', i);
        if (close < 0) throw SKIP;
        i = close + 1;
        type = c == '#' ? Type.BIND : Type.OPAQUE;
      } else if (c == OPAQUE_MARK) {
        i++;
        type = Type.OPAQUE;
      } else if (Character.isLetter(c) || c == '_') {
        while (i < n && isIdentPart(s, i)) i++;
        type = Type.IDENT;
      } else if (Character.isDigit(c)) {
        while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) i++;
        type = Type.NUMBER;
      } else if (c == ':' && i + 1 < n && s.charAt(i + 1) == ':') {
        i += 2;
        type = Type.SYMBOL;
      } else {
        i++;
        type = Type.SYMBOL;
      }
      Tok t = new Tok(type, start, i, s.substring(start, i));
      t.boundary = boundary;
      boundary = false;
      Tok prev = out.isEmpty() ? null : out.get(out.size() - 1);
      if (prev != null && prev.end == start) {
        if (prev.type == Type.OPAQUE && type == Type.IDENT) t.tainted = true;
        if (type == Type.OPAQUE && prev.type == Type.IDENT) prev.tainted = true;
        // Oracle q'[..]' · n'..' · PostgreSQL e'..' : 문자열 끝을 확신할 수 없다.
        if (type == Type.STRING && prev.type == Type.IDENT) throw SKIP;
        // PostgreSQL $$..$$ 문자열.
        if (prev.sym("$") && t.sym("$")) throw SKIP;
      }
      out.add(t);
    }
    return out;
  }

  private static boolean isIdentPart(String s, int i) {
    char c = s.charAt(i);
    if (c == '#' || c == '$') return i + 1 >= s.length() || s.charAt(i + 1) != '{';
    return Character.isLetterOrDigit(c) || c == '_';
  }

  // ---------------------------------------------------------------- 문장 읽기

  /** 괄호 한 겹의 문장(서브쿼리)마다 하나. 별칭은 대문자 → 표(모르는 원본이면 null). */
  private static final class Scope {
    final Scope parent;
    final Map<String, Table> aliases = new HashMap<>();
    final Set<String> ambiguous = new HashSet<>();
    int sources;
    /** 표 하나로 정해지지 않는 원본(서브쿼리 · 함수 · 치환 등)이 있다. */
    boolean unknown;
    /** INSERT · UPDATE · MERGE 의 대상 표. 모르면 null. */
    Table target;
    /** 마지막으로 읽은 표(sources 가 1 일 때만 뜻이 있다). */
    Table last;

    Scope(Scope parent) { this.parent = parent; }

    void register(String key, Table table) {
      if (!aliases.containsKey(key)) {
        aliases.put(key, table);
        return;
      }
      Table old = aliases.get(key);
      boolean same = old == null ? table == null
          : table != null && old.name().equalsIgnoreCase(table.name()) && String.valueOf(old.schema()).equalsIgnoreCase(String.valueOf(table.schema()));
      if (!same) ambiguous.add(key);
    }

    /**
     * 이 문장 · 바깥 문장에서 key 가 가리키는 표. 없거나 둘 이상이거나 모르는 원본이면 null.
     * 모르는 원본이 있는 문장에서는 바깥으로 나가 찾지 않는다(그 원본이 같은 이름을 가졌을 수 있다).
     */
    Table resolve(String key) {
      for (Scope s = this; s != null; s = s.parent) {
        if (s.ambiguous.contains(key)) return null;
        if (s.aliases.containsKey(key)) return s.aliases.get(key);
        if (s.unknown) return null;
      }
      return null;
    }
  }

  private static final class Parser {
    private final int base;
    private final boolean fragment;
    private final Result out;
    private final Tok[] toks;
    /** 여는 괄호 자리 → 닫는 괄호 자리. */
    private final int[] match;
    /** 표 · 별칭으로 읽은 낱말(칼럼 자리로 다시 보지 않는다). */
    private final boolean[] consumed;
    private final Set<String> cte = new HashSet<>();
    private boolean opaque;

    Parser(String sql, int base, boolean fragment, Result out) {
      this.base = base;
      this.fragment = fragment;
      this.out = out;
      this.toks = tokens(sql).toArray(new Tok[0]);
      this.match = new int[toks.length];
      this.consumed = new boolean[toks.length];
    }

    void run() {
      int n = toks.length;
      if (n == 0) return;
      // 맨 앞의 include · ${}(쪽 나누기 머리 등)는 넘기고 첫 낱말로 문장 종류를 가린다.
      int head = 0;
      while (head < n && toks[head].type == Type.OPAQUE) head++;
      if (!fragment && (head == n || !toks[head].in(STATEMENT_HEADS))) throw SKIP;
      int[] stack = new int[n];
      int depth = 0;
      for (int i = 0; i < n; i++) {
        Tok t = toks[i];
        if (t.type == Type.OPAQUE) opaque = true;
        if (t.sym("(")) stack[depth++] = i;
        else if (t.sym(")")) {
          if (depth == 0) throw SKIP;
          match[stack[--depth]] = i;
        } else if (t.sym(";") && i < n - 1) {
          throw SKIP;
        } else if (t.sym("{") || t.sym("}")) {
          throw SKIP;
        }
        if (t.type == Type.IDENT && (isCteHead(i))) cte.add(t.up);
      }
      if (depth != 0) throw SKIP;
      Scope top = new Scope(null);
      scope(0, n, top);
      unqualified(0, n, top);
    }

    /** 「이름 AS (」 · 「이름 (칼럼들) AS (」 · 「이름 AS [NOT] MATERIALIZED (」 의 이름 자리. 넉넉히 잡는다(잡히면 검사가 줄 뿐이다). */
    private boolean isCteHead(int i) {
      int k = i + 1;
      if (sym(k, "(")) {
        // 닫는 괄호는 아직 모를 수 있어 앞으로 찾는다. 칼럼 목록은 짧다.
        int close = k + 1;
        while (close < toks.length && !toks[close].sym(")") && !toks[close].sym("(")) close++;
        if (!sym(close, ")")) return false;
        k = close + 1;
      }
      if (!word(k, "AS")) return false;
      k++;
      if (word(k, "NOT")) k++;
      if (word(k, "MATERIALIZED")) k++;
      return sym(k, "(");
    }

    private boolean sym(int i, String s) { return i >= 0 && i < toks.length && toks[i].sym(s); }

    private boolean word(int i, String s) { return i >= 0 && i < toks.length && toks[i].word(s); }

    private boolean ident(int i) { return i >= 0 && i < toks.length && toks[i].type == Type.IDENT; }

    /** 괄호 안이 문장(서브쿼리, PostgreSQL WITH 의 INSERT · UPDATE · DELETE 포함)이면 true. */
    private boolean subquery(int open) { return open + 1 < toks.length && toks[open + 1].in(STATEMENT_HEADS); }

    private void scope(int from, int to, Scope sc) {
      structure(from, to, sc);
      refs(from, to, sc);
    }

    /** 깊이 0 의 절만 보고 표 · 별칭 · 대상 칼럼을 읽는다. */
    private void structure(int from, int to, Scope sc) {
      for (int i = from; i < to; i++) {
        Tok t = toks[i];
        if (t.sym("(")) { i = match[i]; continue; }
        // 「별칭.SET」 같은 칼럼 이름은 절 머리가 아니다. FROM 은 어디서나 예약어라 쓰다 만 「a. FROM」 으로 본다.
        if (t.type != Type.IDENT || t.quoted || sym(i - 1, ".") && !t.word("FROM")) continue;
        switch (t.up) {
          case "FROM" -> {
            // IS DISTINCT FROM 값 은 절이 아니다.
            if (!word(i - 1, "DISTINCT")) i = sources(i + 1, to, sc, false, false) - 1;
          }
          case "UPDATE" -> {
            // FOR UPDATE [OF ..] · FOR NO KEY UPDATE · ON DUPLICATE KEY UPDATE 칼럼 = · MERGE/ON CONFLICT 의 UPDATE SET 은 표가 따르지 않는다.
            if (!word(i - 1, "FOR") && !word(i - 1, "KEY") && !word(i + 1, "SET")) i = sources(i + 1, to, sc, true, false) - 1;
          }
          case "INTO" -> {
            boolean insert = word(i - 1, "INSERT");
            if (insert || word(i - 1, "MERGE")) {
              i = sources(i + 1, to, sc, true, insert);
              if (insert && sym(i, "(")) {
                columnList(i, sc);
                i = match[i];
              } else {
                i--;
              }
            }
          }
          case "DELETE" -> {
            if (ident(i + 1) && i + 1 < to && !word(i + 1, "FROM") && !word(i + 1, "WHERE") && !deleteAliases(i + 1, to)) i = sources(i + 1, to, sc, true, false) - 1;
          }
          case "INSERT" -> {
            // MERGE 의 WHEN NOT MATCHED THEN INSERT (칼럼들).
            if (sym(i + 1, "(") && i + 1 < to) {
              columnList(i + 1, sc);
              i = match[i + 1];
            }
          }
          case "SET" -> setItems(i, to, sc);
          default -> { }
        }
      }
    }

    /** 「DELETE 별칭[, 별칭] FROM 표 …」(MySQL 여러 표 지우기)면 true. DELETE 뒤의 이름은 표가 아니라 FROM 에서 정한 별칭이다. */
    private boolean deleteAliases(int i, int to) {
      for (int k = i; k < to; k++) {
        Tok t = toks[k];
        if (t.word("FROM")) return true;
        if (t.in(CLAUSE) || t.type != Type.IDENT && !t.sym(",") && !t.sym(".") && !t.sym("*")) return false;
      }
      return false;
    }

    /**
     * 표 목록(쉼표 · JOIN · USING 으로 이어진 것)을 읽고 끝난 자리를 돌려준다. target 이면 첫 표가 대상 표,
     * insert 면 첫 표 뒤의 괄호는 칼럼 목록이라 거기서 멈춘다.
     */
    private int sources(int i, int to, Scope sc, boolean target, boolean insert) {
      boolean first = true;
      while (i < to) {
        while (i < to && (toks[i].word("ONLY") || toks[i].word("LATERAL"))) i++;
        if (i >= to) break;
        Tok t = toks[i];
        Table table = null;
        String key = null;
        boolean source = true;
        if (t.sym("(")) {
          i = match[i] + 1;
        } else if (t.type == Type.IDENT && (t.quoted || !NOT_NAME.contains(t.up))) {
          int head = i;
          int parts = 1;
          boolean taint = t.tainted;
          consumed[i] = true;
          while (sym(i + 1, ".") && i + 2 < to && ident(i + 2)) {
            i += 2;
            parts++;
            consumed[i] = true;
            taint |= toks[i].tainted;
          }
          Tok last = toks[i];
          i++;
          boolean plain = !taint && parts <= 2;
          if (sym(i, "@")) {
            plain = false;
            // 링크 이름은 「이름[.이름…]」 까지다. 그 뒤 낱말은 별칭 · 다음 절이다.
            i++;
            if (i < to && ident(i)) i++;
            while (sym(i, ".") && i + 1 < to && ident(i + 1)) i += 2;
          }
          if (sym(i, ".")) plain = false;
          if (i < to && sym(i, "(") && !(insert && first)) {
            plain = false;
            i = match[i] + 1;
          }
          if (parts == 1 && !last.quoted && (cte.contains(last.up) || DUAL.equals(last.up))) plain = false;
          if (i < to && toks[i].type == Type.OPAQUE && toks[i].start == last.end) plain = false;
          if (plain) table = new Table(parts == 2 ? toks[head].raw : null, last.raw, base + last.start, base + last.end);
          key = last.up;
        } else {
          // 치환 · 바인드 · 다음 절 머리: 표가 아니다. 아래 고리가 다음 구분자까지 넘긴다.
          source = false;
          first = false;
          if (!t.in(CLAUSE) && !t.sym(";")) sc.unknown = true;
        }
        if (source) {
          int a = i < to && toks[i].word("AS") ? i + 1 : i;
          if (a < to && ident(a) && (toks[a].quoted || !NOT_NAME.contains(toks[a].up))) {
            // 태그 경계 · 치환 바로 뒤의 낱말은 별칭인지 알 수 없다.
            if (toks[a].boundary || toks[a].tainted) throw SKIP;
            consumed[a] = true;
            key = toks[a].up;
            i = a + 1;
            // PostgreSQL 「별칭(칼럼, …)」 은 칼럼 이름을 바꾼다.
            if (i < to && sym(i, "(") && !(insert && first)) table = null;
          }
          if (table != null) out.tables().add(table);
          else sc.unknown = true;
          if (key != null) sc.register(key, table);
          sc.sources++;
          sc.last = table;
          if (target && first) sc.target = table;
          boolean stop = insert && first && i < to && sym(i, "(");
          first = false;
          if (stop) return i;
        }
        boolean next = false;
        while (i < to && !next) {
          Tok x = toks[i];
          if (x.sym("(")) {
            sc.unknown = true;
            if (key != null) sc.ambiguous.add(key);
            i = match[i] + 1;
          } else if (x.sym(",")) {
            i++;
            next = true;
          } else if (x.sym(";") || x.in(CLAUSE)) {
            return i;
          } else if (x.in(JOIN_WORDS) && !sym(i + 1, "(")) {
            while (i < to && toks[i].in(JOIN_WORDS)) i++;
            next = true;
          } else if (x.word("USING")) {
            i++;
            next = true;
          } else if (x.word("ON")) {
            i++;
            // 조건 안의 「별칭.LIMIT」 같은 칼럼 이름과 CASE WHEN 은 다음 절 머리가 아니다(거기서 끊으면 뒤의 JOIN 표를 놓친다).
            int cases = 0;
            while (i < to && !toks[i].sym(",") && !toks[i].sym(";")) {
              Tok y = toks[i];
              boolean name = sym(i - 1, ".");
              if (!name && y.word("CASE")) cases++;
              else if (!name && y.word("END") && cases > 0) cases--;
              else if (!name && cases == 0 && (y.in(CLAUSE) || y.in(JOIN_WORDS) && !sym(i + 1, "("))) break;
              i = y.sym("(") ? match[i] + 1 : i + 1;
            }
          } else {
            // PARTITION · SAMPLE · PIVOT · AS OF · 뜻밖의 낱말: 이 문장의 원본을 다 안다고 할 수 없고, 방금 읽은 이름도 믿지 않는다.
            sc.unknown = true;
            if (key != null) sc.ambiguous.add(key);
            i++;
          }
        }
        if (!next) break;
      }
      return i;
    }

    /** 「(칼럼, 칼럼, …)」 이 이름과 쉼표뿐일 때 대상 표의 칼럼으로 낸다. 다른 것이 섞였으면 아무것도 내지 않는다. */
    private void columnList(int open, Scope sc) {
      if (sc.target == null || sc.parent != null || subquery(open)) return;
      List<Column> found = new ArrayList<>();
      boolean wantName = true;
      for (int i = open + 1; i < match[open]; i++) {
        Tok t = toks[i];
        if (t.sym(",")) {
          wantName = true;
        } else if (wantName && t.type == Type.IDENT && !t.tainted && (t.quoted || !NOT_COLUMN.contains(t.up))) {
          found.add(new Column(sc.target, t.raw, base + t.start, base + t.end));
          wantName = false;
        } else {
          return;
        }
      }
      out.columns().addAll(found);
    }

    /**
     * SET 뒤 「칼럼 = 값」 들의 왼쪽을 대상 표의 칼럼으로 낸다. 「UPDATE SET」(MERGE · ON CONFLICT)은 늘 대상 표의 것이고,
     * 그 밖에는 그때까지 읽은 표가 대상 하나뿐일 때만(여러 표를 고치는 UPDATE 는 어느 표 칼럼인지 모른다).
     */
    private void setItems(int set, int to, Scope sc) {
      if (sc.target == null || sc.parent != null) return;
      if (!word(set - 1, "UPDATE") && (sc.sources != 1 || sc.unknown)) return;
      int cases = 0;
      boolean itemStart = true;
      for (int i = set + 1; i < to; i++) {
        Tok t = toks[i];
        if (t.sym("(")) { i = match[i]; itemStart = false; continue; }
        if (t.word("CASE")) cases++;
        else if (t.word("END") && cases > 0) cases--;
        else if (cases == 0 && (t.in(SET_END) || t.word("WHEN") || t.word("SET") || t.sym(";"))) return;
        if (t.sym(",") && cases == 0) { itemStart = true; continue; }
        if (itemStart && t.type == Type.IDENT && sym(i + 1, "=") && !t.tainted && (t.quoted || !NOT_COLUMN.contains(t.up))) {
          out.columns().add(new Column(sc.target, t.raw, base + t.start, base + t.end));
        }
        itemStart = false;
      }
    }

    /** 「별칭.칼럼」 을 찾고 서브쿼리는 새 문장으로 읽는다. */
    private void refs(int from, int to, Scope sc) {
      for (int i = from; i < to; i++) {
        Tok t = toks[i];
        if (t.sym("(") && subquery(i)) {
          scope(i + 1, match[i], new Scope(sc));
          i = match[i];
          continue;
        }
        if (t.type != Type.IDENT || !sym(i + 1, ".")) continue;
        int head = i;
        int parts = 1;
        while (sym(i + 1, ".") && i + 2 < to && ident(i + 2)) {
          i += 2;
          parts++;
        }
        Tok col = toks[i];
        if (consumed[head] || parts != 2 || t.tainted || col.tainted) continue;
        if (sym(head - 1, ".") || sym(head - 1, "::") || sym(head - 1, "@")) continue;
        if (head > 0 && toks[head - 1].type == Type.OPAQUE && toks[head - 1].end == t.start) continue;
        // 「a.b.」(뒤가 * 등) · 함수 부르기(PKG.FUNC( ) · DB 링크. 「칼럼(+)」 는 구식 아우터 조인 표시라 칼럼이다.
        if (sym(i + 1, ".") || sym(i + 1, "@")) continue;
        if (sym(i + 1, "(") && !(sym(i + 2, "+") && sym(i + 3, ")"))) continue;
        if (i + 1 < toks.length && toks[i + 1].type == Type.OPAQUE && toks[i + 1].start == col.end) continue;
        if (!col.quoted && (NOT_COLUMN.contains(col.up) || col.up.startsWith(VERSIONS_PREFIX))) continue;
        Table table = sc.resolve(t.up);
        if (table != null) out.columns().add(new Column(table, col.raw, base + col.start, base + col.end));
      }
    }

    /** 바깥 문장의 표가 정확히 하나일 때 SELECT 목록의 단순 항목과 WHERE 조건 왼쪽의 한정자 없는 이름. */
    private void unqualified(int from, int to, Scope sc) {
      if (fragment || opaque || sc.sources != 1 || sc.unknown || sc.last == null || !sc.ambiguous.isEmpty()) return;
      Table table = sc.last;
      int select = -1;
      int fromAt = -1;
      int where = -1;
      int whereEnd = to;
      for (int i = from; i < to; i++) {
        Tok t = toks[i];
        if (t.sym("(")) { i = match[i]; continue; }
        // MODEL 절이 있으면 SELECT 목록의 이름이 MEASURES 가 지은 이름일 수 있다.
        if (t.word("MODEL") && i + 1 < to && toks[i + 1].in(MODEL_NEXT)) return;
        if (select < 0 && t.word("SELECT")) select = i;
        else if (select >= 0 && fromAt < 0 && t.word("FROM")) fromAt = i;
        else if (where < 0 && t.word("WHERE")) where = i;
        else if (where >= 0 && t.in(WHERE_END)) { whereEnd = i; break; }
      }
      if (select >= 0 && fromAt > select) {
        int item = select + 1;
        while (item < fromAt && toks[item].in(SELECT_QUANTIFIERS)) item++;
        boolean first = true;
        for (int i = item; i <= fromAt; i++) {
          if (toks[i].sym("(")) { i = match[i]; continue; }
          if (i < fromAt && !toks[i].sym(",")) continue;
          selectItem(item, i, table, first);
          first = false;
          item = i + 1;
        }
      }
      if (where < 0) return;
      for (int i = where + 1; i < whereEnd; i++) {
        Tok t = toks[i];
        if (t.sym("(") && subquery(i)) { i = match[i]; continue; }
        if (t.type != Type.IDENT || t.tainted || !t.quoted && NOT_COLUMN.contains(t.up)) continue;
        if (!toks[i - 1].in(PREDICATE_HEADS) || i + 1 >= whereEnd) continue;
        Tok next = toks[i + 1];
        if (next.in(COMPARE_WORDS) || next.type == Type.SYMBOL && COMPARE_SYMBOLS.contains(next.up)) {
          out.columns().add(new Column(table, t.raw, base + t.start, base + t.end));
        }
      }
    }

    /**
     * 항목 [a, b) 이 「칼럼」 · 「칼럼 별칭」 · 「칼럼 AS 별칭」 꼴이면 그 칼럼을 낸다.
     * 첫 항목의 「낱말 낱말」 은 앞 낱말이 SELECT 꾸밈말(SQL_CALC_FOUND_ROWS 등)일 수 있어 보지 않는다.
     */
    private void selectItem(int a, int b, Table table, boolean first) {
      int n = b - a;
      if (n < 1 || n > 3) return;
      Tok t = toks[a];
      if (t.type != Type.IDENT || t.tainted || !t.quoted && NOT_COLUMN.contains(t.up)) return;
      boolean alias = n == 1
          || n == 2 && !first && toks[a + 1].type == Type.IDENT && (toks[a + 1].quoted || !NOT_COLUMN.contains(toks[a + 1].up))
          || n == 3 && toks[a + 1].word("AS") && (toks[a + 2].type == Type.IDENT || toks[a + 2].type == Type.STRING);
      if (alias) out.columns().add(new Column(table, t.raw, base + t.start, base + t.end));
    }
  }
}
