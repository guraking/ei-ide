package ei.db.mybatis;

import ei.db.gen.Names;
import ei.db.mybatis.MapperDoc.Node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「DB 바뀐 것 코드에 맞추기」의 매퍼 XML 쪽 순수 논리(IntelliJ 클래스 없음, ColumnSyncCheck).
 * 새 칼럼은 같은 목록(SELECT 목록 · INSERT 칼럼과 VALUES · UPDATE SET · resultMap)의 마지막 항목 줄을 복제해 옛 칼럼 · 옛 필드 이름만 바꿔 넣는다.
 * 쉼표 위치 · 별칭 접두어 · 들여쓰기 · 한 줄짜리 &lt;if&gt; 덩이는 그 줄 그대로다. 없어진 칼럼은 한 줄로 떨어지는 항목만 지우기를 낸다.
 * 복제 · 지우기를 확실히 할 수 없는 곳은 고치지 않고 손으로 할 일(edits 없음, at 으로 이동만)로 낸다.
 * 위치는 모두 문서 글자 오프셋이다.
 */
final class ColumnSync {
  private ColumnSync() {}

  enum Kind { ADD, REMOVE, ADD_BY_HAND, FIX_BY_HAND }

  /** 고친 곳의 자리: SELECT 목록 · INSERT 칼럼과 값 · UPDATE SET · resultMap · 그 밖(WHERE · 조인 등). */
  enum Part { SELECT, INSERT, UPDATE, RESULT_MAP, OTHER }

  /** [start, end) 를 text 로. */
  record Edit(int start, int end, String text) {}

  /** 고칠 것 하나. owner 는 문장 · resultMap id. edits 가 비면 손으로 할 일. order 는 같은 자리에 넣을 때 차례(DB 칼럼 순서). */
  record Change(Kind kind, Part part, String owner, String column, List<Edit> edits, boolean checked, int at, int order) {}

  /** 넣을 칼럼: DB 이름, DTO 필드 이름, 주석(없으면 빈 글), jdbcType(모르면 null: 본보기 줄에 jdbcType 이 있으면 복제하지 않는다). */
  record NewColumn(String name, String field, String comment, String jdbcType) {}

  /** 「거의 다 가진 목록」: 새 칼럼 말고 빠진 칼럼이 이 수 이하이거나 */
  private static final int NEAR_FULL_MIN_GAP = 2;
  /** 테이블 칼럼 수를 이 값으로 나눈 수 이하면, 새 칼럼이 아닌 빠진 칼럼도 (체크 끈 채) 넣기로 낸다. 일부만 고른 조회에 칼럼을 늘어놓지 않으려는 것. */
  private static final int NEAR_FULL_DIVISOR = 5;
  /** SELECT 결과를 합치는 낱말. 깊이 0 에 있으면 목록이 여럿이라 고치지 않는다. */
  private static final Set<String> SET_OPERATORS = Set.of("UNION", "INTERSECT", "MINUS", "EXCEPT");
  private static final String INCLUDE_TAG = "include";
  private static final Pattern REFID = Pattern.compile("\\brefid\\s*=\\s*([\"'])(.*?)\\1");

  /**
   * 문장 하나에서 고칠 것. table 은 따옴표 없는 테이블 이름, dbColumns 는 DB 칼럼(대문자), candidates 는 넣을 수 있는 칼럼(DB 순서),
   * fresh 는 그 가운데 「새 칼럼」(대문자: 이 테이블을 쓰는 어떤 문장 · resultMap 도 쓰지 않는 칼럼), dropped 는 지울 칼럼(대문자).
   * 목록(SELECT 목록 · INSERT 칼럼 · UPDATE SET)마다 그 목록이 갖지 않은 칼럼으로 판단한다:
   * 새 칼럼은 늘 내고(기본 체크는 INSERT 이거나 목록이 새 칼럼 말고는 다 가질 때), 새 칼럼이 아닌 빠진 칼럼은 목록이 거의 다 가질 때만 체크 끈 채 낸다.
   * 목록 모양을 읽을 수 없는 이 테이블 대상 INSERT · UPDATE · 합친 SELECT · 다른 파일 조각을 include 한 SELECT 목록은 새 칼럼마다 손으로 넣기를 내고,
   * 같은 파일 조각을 include 한 SELECT 목록에는 넣지 않는다(그 조각을 forFragment 가 따로 본다).
   * doc 은 include 가 같은 파일 조각인지 보는 데, scan 은 이 문서의 MapperSqlScan 결과. 문장을 확실히 읽을 수 없으면 빈 목록.
   */
  static List<Change> forStatement(CharSequence text, MapperDoc doc, Node st, MapperSqlScan.Result scan, String table, Set<String> dbColumns,
                                   List<NewColumn> candidates, Set<String> fresh, Set<String> dropped) {
    List<Change> out = new ArrayList<>();
    List<Tok> toks = tokenize(text, st.start, Math.min(st.end, text.length()));
    if (toks == null) return out;
    String id = st.attr("id") == null ? "" : st.attr("id");
    Set<Integer> certain = new HashSet<>();
    Set<String> used = new HashSet<>();
    for (MapperSqlScan.Column c : scan.columns()) {
      if (c.start() >= st.start && c.end() <= st.end && MapperSqlScan.bare(c.table().name()).equalsIgnoreCase(table)) {
        certain.add(c.start());
        used.add(MapperSqlScan.bare(c.name()).toUpperCase(Locale.ROOT));
      }
    }
    Clause clause = clause(toks, table, doc);
    Set<Integer> handled = new HashSet<>();
    if (clause != null) {
      listChanges(text, clause, certain, used, dbColumns, candidates, fresh, dropped, id, handled, out);
    } else if (unreadableTarget(toks, table)) {
      for (int k = 0; k < candidates.size(); k++) {
        NewColumn nc = candidates.get(k);
        String up = nc.name().toUpperCase(Locale.ROOT);
        if (fresh.contains(up) && !used.contains(up)) out.add(new Change(Kind.ADD_BY_HAND, Part.OTHER, id, nc.name(), List.of(), false, st.start, k));
      }
    }
    for (String d : dropped) {
      for (MapperSqlScan.Column c : scan.columns()) {
        if (certain.contains(c.start()) && !handled.contains(c.start()) && MapperSqlScan.bare(c.name()).equalsIgnoreCase(d)) {
          out.add(new Change(Kind.FIX_BY_HAND, Part.OTHER, id, d, List.of(), false, c.start(), 0));
          break;
        }
      }
    }
    return out;
  }

  /**
   * &lt;sql&gt; 조각 하나. 칼럼 이름만 쉼표로 늘어놓은 조각(Base_Column_List 따위, 절반 이상이 이 테이블 칼럼)은 SELECT 목록처럼 넣기 · 지우기를 내고,
   * 그 밖의 조각은 문장과 같이 본다.
   */
  static List<Change> forFragment(CharSequence text, MapperDoc doc, Node frag, MapperSqlScan.Result scan, String table, Set<String> dbColumns,
                                  List<NewColumn> candidates, Set<String> fresh, Set<String> dropped) {
    List<Tok> toks = tokenize(text, frag.start, Math.min(frag.end, text.length()));
    List<Item> items = toks == null ? null : items(toks, 0);
    if (items != null && !items.isEmpty()) {
      int known = 0;
      Set<Integer> certain = new HashSet<>();
      for (Item it : items) {
        selectShape(it);
        if (it.column != null && (dbColumns.contains(it.column) || dropped.contains(it.column))) {
          known++;
          certain.add(it.columnStart);
        }
      }
      if (known * 2 >= items.size() && onlyItems(toks)) {
        List<Change> out = new ArrayList<>();
        String id = frag.attr("id") == null ? "" : frag.attr("id");
        listChanges(text, new Clause(Part.SELECT, items, null, toks, frag.start, false, false), certain, Set.of(), dbColumns, candidates, fresh, dropped, id,
            new HashSet<>(), out);
        return out;
      }
    }
    return forStatement(text, doc, frag, scan, table, dbColumns, candidates, fresh, dropped);
  }

  /** toks 가 태그 · 주석 · 낱말 · 쉼표 · 점 붙은 낱말뿐(SQL 낱말 · 괄호 없음)이면 true. */
  private static boolean onlyItems(List<Tok> toks) {
    for (Tok t : toks) {
      if (t.type == Type.TAG || t.type == Type.COMMENT || t.type == Type.COMMA) continue;
      if (t.type != Type.WORD || isKeyword(t.text)) return false;
    }
    return true;
  }

  /**
   * 한 목록의 넣기 · 지우기(forStatement 설명대로). used 는 문장이 쓰는 이 테이블 칼럼(대문자):
   * UPDATE 는 WHERE 의 키 칼럼을 SET 에 두지 않으므로 문장 어디서든 쓰는 칼럼을 가진 것으로 본다.
   */
  private static void listChanges(CharSequence text, Clause clause, Set<Integer> certain, Set<String> used, Set<String> dbColumns,
                                  List<NewColumn> candidates, Set<String> fresh, Set<String> dropped, String id, Set<Integer> handled,
                                  List<Change> out) {
    Set<String> covered = new HashSet<>(clause.part() == Part.UPDATE ? used : Set.of());
    for (Item it : clause.cols()) {
      if (it.column != null && (clause.part() != Part.SELECT || certain.contains(it.columnStart))) covered.add(it.column);
    }
    int gap = 0;
    for (String col : dbColumns) if (!covered.contains(col) && !fresh.contains(col)) gap++;
    boolean nearlyFull = gap <= Math.max(NEAR_FULL_MIN_GAP, dbColumns.size() / NEAR_FULL_DIVISOR);
    boolean full = clause.part() == Part.INSERT || gap == 0;
    for (int k = 0; k < candidates.size(); k++) {
      NewColumn nc = candidates.get(k);
      String up = nc.name().toUpperCase(Locale.ROOT);
      if (covered.contains(up)) continue;
      boolean isFresh = fresh.contains(up);
      if (!isFresh && !nearlyFull) continue;
      // 같은 파일 조각을 include 한 목록은 그 조각이 따로 판단된다(forFragment). 여기서도 넣으면 두 번 들어간다.
      if (clause.local()) continue;
      if (clause.foreign()) {
        if (isFresh) out.add(byHand(clause, nc, id, k));
        continue;
      }
      Change c = add(text, clause, certain, dbColumns, nc, id, isFresh && full, k, isFresh);
      if (c != null) out.add(c);
    }
    for (String d : dropped) removes(text, clause, certain, d, id, handled, out);
  }

  /** 이 테이블에 넣는 INSERT · 이 테이블을 고치는 UPDATE · 결과를 합치는 SELECT 이면 true(목록을 읽지 못했을 때 손으로 넣기를 낼 곳). */
  private static boolean unreadableTarget(List<Tok> toks, String table) {
    int first = nextSql(toks, 0);
    if (first < 0 || toks.get(first).type != Type.WORD) return false;
    String kw = toks.get(first).text.toUpperCase(Locale.ROOT);
    if ("SELECT".equals(kw)) return setOperator(toks, first + 1);
    int name = nextSql(toks, first + 1);
    if ("INSERT".equals(kw)) {
      if (name < 0 || !"INTO".equalsIgnoreCase(toks.get(name).text)) return false;
      name = nextSql(toks, name + 1);
    } else if (!"UPDATE".equals(kw)) {
      return false;
    }
    return name >= 0 && sameTable(toks.get(name), table);
  }

  private static boolean setOperator(List<Tok> toks, int from) {
    for (int i = from; i < toks.size(); i++) {
      Tok t = toks.get(i);
      if (t.type == Type.WORD && t.depth == 0 && SET_OPERATORS.contains(t.text.toUpperCase(Locale.ROOT))) return true;
    }
    return false;
  }

  /**
   * resultMap 하나에서 고칠 것. 바로 아래 id · result 요소만 본다(association · collection 안은 보지 않는다).
   * 테이블 칼럼(dbColumns)을 MIN_TABLE_COLUMNS 개 넘게 쓰거나 칼럼이 모두 테이블 칼럼인 resultMap 만 이 테이블 것으로 본다
   * (FK · 등록일처럼 이름이 겹치는 칼럼 하나로 다른 테이블 resultMap 에 넣지 않게).
   */
  static List<Change> forResultMap(CharSequence text, Node rm, Set<String> dbColumns, List<NewColumn> adds, Set<String> dropped) {
    List<Change> out = new ArrayList<>();
    String id = rm.attr("id") == null ? "" : rm.attr("id");
    Set<String> have = new HashSet<>();
    Node anchor = null;
    int tableColumns = 0;
    for (Node c : rm.children) {
      if (c.name == null || !RESULT_TAGS.contains(c.name) || c.attr(COLUMN_ATTR) == null) continue;
      String col = c.attr(COLUMN_ATTR).trim().toUpperCase(Locale.ROOT);
      have.add(col);
      if (dbColumns.contains(col)) {
        tableColumns++;
        if (RESULT_TAG.equals(c.name) && lineOnly(text, c.start, c.end)) anchor = c;
      }
    }
    if (tableColumns >= MIN_TABLE_COLUMNS || (tableColumns > 0 && tableColumns == have.size())) {
      for (int k = 0; k < adds.size(); k++) {
        NewColumn nc = adds.get(k);
        if (have.contains(nc.name().toUpperCase(Locale.ROOT))) continue;
        String clone = anchor == null ? null : cloneResult(text, anchor, nc);
        if (clone == null) {
          out.add(new Change(Kind.ADD_BY_HAND, Part.RESULT_MAP, id, nc.name(), List.of(), false, rm.start, k));
        } else {
          int le = lineEnd(text, anchor.end);
          out.add(new Change(Kind.ADD, Part.RESULT_MAP, id, nc.name(), List.of(new Edit(le, le, "\n" + clone)), true, anchor.start, k));
        }
      }
    }
    for (Node c : rm.children) {
      if (c.name == null || !RESULT_TAGS.contains(c.name) || c.attr(COLUMN_ATTR) == null) continue;
      String col = c.attr(COLUMN_ATTR).trim();
      if (!dropped.contains(col.toUpperCase(Locale.ROOT))) continue;
      if (lineOnly(text, c.start, c.end)) {
        out.add(new Change(Kind.REMOVE, Part.RESULT_MAP, id, col, List.of(removeLine(text, c.start, c.end)), false, c.start, 0));
      } else {
        out.add(new Change(Kind.FIX_BY_HAND, Part.RESULT_MAP, id, col, List.of(), false, c.start, 0));
      }
    }
    return out;
  }

  /** resultMap 을 이 테이블 것으로 보는 겹치는 칼럼 수. */
  private static final int MIN_TABLE_COLUMNS = 2;

  /** edits 를 반영한 글. 같은 자리에 넣는 것은 주어진 차례대로 들어간다. 겹치면 IllegalArgumentException. */
  static String apply(CharSequence text, List<Edit> edits) {
    List<Edit> sorted = new ArrayList<>(edits);
    sorted.sort(Comparator.comparingInt(Edit::start));
    StringBuilder b = new StringBuilder(text.length());
    int at = 0;
    for (Edit e : sorted) {
      if (e.start() < at) throw new IllegalArgumentException("겹치는 고칠 곳이 있습니다: " + e.start());
      b.append(text, at, e.start()).append(e.text());
      at = e.end();
    }
    return b.append(text, at, text.length()).toString();
  }

  /** changes 의 edits 를 모두 모은다(같은 자리에 넣는 것은 order 차례). apply 에 넘긴다. */
  static List<Edit> edits(List<Change> changes) {
    List<Change> sorted = new ArrayList<>(changes);
    sorted.sort(Comparator.comparingInt(Change::order));
    List<Edit> out = new ArrayList<>();
    for (Change c : sorted) out.addAll(c.edits());
    return out;
  }

  /** SQL 주석에 넣을 글: 닫는 표시와 XML 특수 글자를 가린다. */
  static String commentText(String comment) {
    return comment.replace("*/", "* /").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\r", " ").replace("\n", " ").trim();
  }

  // ---------------------------------------------------------------- 넣기

  /** 넣기 하나. 복제할 줄을 못 찾으면 byHandOk 일 때 손으로 넣기, 아니면 null. */
  private static Change add(CharSequence text, Clause clause, Set<Integer> certain, Set<String> dbColumns, NewColumn nc, String id,
                            boolean full, int order, boolean byHandOk) {
    if (clause.part() == Part.INSERT) {
      if (clause.vals() == null || clause.vals().size() != clause.cols().size()) return byHandOk ? byHand(clause, nc, id, order) : null;
      for (int i = clause.cols().size() - 1; i >= 0; i--) {
        Item c = clause.cols().get(i);
        Item v = clause.vals().get(i);
        if (c.column == null || !dbColumns.contains(c.column) || !c.simple() || v.ph == null || !v.simple()) continue;
        if (!exclusive(text, clause.all(), c) || !exclusive(text, clause.all(), v)) continue;
        boolean after = canAfter(c) && canAfter(v);
        String cc = clone(text, c, nc, after, null);
        String vc = clone(text, v, nc, after, v.field);
        if (cc == null || vc == null) continue;
        return new Change(Kind.ADD, Part.INSERT, id, nc.name(), List.of(insert(text, c, cc, after), insert(text, v, vc, after)), full,
            c.start, order);
      }
      return byHandOk ? byHand(clause, nc, id, order) : null;
    }
    boolean any = false;
    for (int i = clause.cols().size() - 1; i >= 0; i--) {
      Item c = clause.cols().get(i);
      if (c.column == null || !dbColumns.contains(c.column)) continue;
      if (clause.part() == Part.SELECT && !certain.contains(c.columnStart)) continue;
      any = true;
      if (!c.simple() || !exclusive(text, clause.all(), c)) continue;
      boolean after = canAfter(c);
      String cc = clone(text, c, nc, after, c.field);
      if (cc == null) continue;
      return new Change(Kind.ADD, clause.part(), id, nc.name(), List.of(insert(text, c, cc, after)), full, c.start, order);
    }
    return any && byHandOk ? byHand(clause, nc, id, order) : null;
  }

  private static Change byHand(Clause clause, NewColumn nc, String id, int order) {
    return new Change(Kind.ADD_BY_HAND, clause.part(), id, nc.name(), List.of(), false, clause.at(), order);
  }

  /** 줄 앞이나 뒤에 쉼표가 있으면 줄 뒤에 넣어도 목록이 맞다. 쉼표 없는 줄은 앞에 넣는다(복제한 줄에 쉼표를 붙여서). */
  private static boolean canAfter(Item it) {
    return it.leadingComma || it.trailingComma;
  }

  private static Edit insert(CharSequence text, Item it, String clone, boolean after) {
    int ls = lineStart(text, it.start);
    int le = lineEnd(text, it.end);
    return after ? new Edit(le, le, "\n" + clone) : new Edit(ls, ls, clone + "\n");
  }

  /**
   * it 의 줄을 nc 로 바꾼 글. 칼럼 이름은 대소문자를 따르고 #{} 의 마지막 마디 · if test 의 옛 필드는 새 필드로, SQL 주석은 새 칼럼 주석으로 바꾼다.
   * 앞에 넣을 때(after 거짓) 쉼표가 없는 줄이면 항목 뒤에 쉼표를 붙인다. oldField 는 그 줄의 #{} 필드(없으면 칼럼의 카멜 이름).
   * 확실히 바꿀 수 없으면(모르는 #{} 선택 · test 에 다른 이름 · 모르는 별칭) null.
   */
  private static String clone(CharSequence text, Item it, NewColumn nc, boolean after, String oldField) {
    int ls = lineStart(text, it.start);
    int le = lineEnd(text, it.end);
    String old = oldField != null ? oldField : it.column == null ? null : Names.camel(it.column);
    List<Edit> edits = new ArrayList<>();
    for (Tok t : it.lineToks) {
      if (t.type == Type.WORD && t.start == it.wordStart && it.column != null) {
        String seg = t.text.substring(it.columnStart - t.start);
        edits.add(new Edit(it.columnStart - ls, t.end - ls, ColumnRename.caseLike(seg.replace("\"", ""), nc.name())));
      } else if (t == it.alias) {
        String a = t.text;
        String repl;
        if (old != null && a.equalsIgnoreCase(old)) repl = nc.field();
        else if (it.column != null && a.equalsIgnoreCase(it.column)) repl = ColumnRename.caseLike(a, nc.name());
        else return null;
        edits.add(new Edit(t.start - ls, t.end - ls, repl));
      } else if (t.type == Type.PH) {
        String ph = placeholder(t.text, nc);
        if (ph == null) return null;
        edits.add(new Edit(t.start - ls, t.end - ls, ph));
      } else if (t.type == Type.TAG && "if".equals(t.tag)) {
        String tag = test(t.text, old, nc.field());
        if (tag == null) return null;
        edits.add(new Edit(t.start - ls, t.end - ls, tag));
      } else if (t.type == Type.COMMENT) {
        String c = nc.comment() == null ? "" : commentText(nc.comment());
        if (c.isEmpty()) {
          int from = t.start;
          while (from > ls && (text.charAt(from - 1) == ' ' || text.charAt(from - 1) == '\t')) from--;
          edits.add(new Edit(from - ls, t.end - ls, ""));
        } else {
          edits.add(new Edit(t.start - ls, t.end - ls, t.text.startsWith(XML_COMMENT_OPEN) ? xmlComment(nc.comment())
              : t.text.startsWith("--") ? "-- " + c : "/* " + c + " */"));
        }
      }
    }
    if (!after && !it.leadingComma && !it.trailingComma) edits.add(new Edit(it.end - ls, it.end - ls, ","));
    return apply(text.subSequence(ls, le), edits);
  }

  private static final Pattern JDBC_TYPE = Pattern.compile("(?i)jdbcType");
  private static final String XML_COMMENT_OPEN = "<!--";
  private static final String XML_COMMENT_CLOSE = "-->";
  private static final String CDATA_OPEN = "<![CDATA[";
  private static final String CDATA_CLOSE = "]]>";

  /** XML 주석 &lt;!-- 글 --&gt;. 주석 안에 둘 수 없는 「--」 는 벌린다. */
  private static String xmlComment(String comment) {
    String c = comment.replace("\r", " ").replace("\n", " ").trim();
    while (c.contains("--")) c = c.replace("--", "- -");
    return XML_COMMENT_OPEN + " " + c + " " + XML_COMMENT_CLOSE;
  }

  /** #{경로, 선택} 의 경로 마지막 마디를 새 필드로. jdbcType 은 새 칼럼 것으로, 새 칼럼 jdbcType 을 모르거나 다른 선택이 있거나 ${} 면 null. */
  private static String placeholder(String raw, NewColumn nc) {
    if (!raw.startsWith("#{") || !raw.endsWith("}")) return null;
    String body = raw.substring(2, raw.length() - 1);
    String[] parts = body.split(",");
    String path = parts[0].trim();
    int dot = path.lastIndexOf('.');
    StringBuilder b = new StringBuilder("#{").append(dot < 0 ? "" : path.substring(0, dot + 1)).append(nc.field());
    for (int i = 1; i < parts.length; i++) {
      String[] kv = parts[i].split("=", 2);
      if (kv.length != 2 || !JDBC_TYPE.matcher(kv[0].trim()).matches() || nc.jdbcType() == null) return null;
      b.append(", ").append(kv[0].trim()).append('=').append(nc.jdbcType());
    }
    return b.append('}').toString();
  }

  private static final Pattern TEST_ATTR = Pattern.compile("\\btest\\s*=\\s*([\"'])(.*?)\\1", Pattern.DOTALL);
  private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  /** if test 에 있어도 되는 낱말(OGNL 연산자 · 값). */
  private static final Set<String> OGNL_WORDS = Set.of("null", "and", "or", "not", "true", "false", "eq", "neq", "lt", "gt", "lte", "gte", "empty");

  /** if 여는 태그의 test 에서 옛 필드를 새 필드로. 다른 필드 이름이 있으면 null. test 가 없으면 그대로. */
  private static String test(String tag, String oldField, String newField) {
    Matcher m = TEST_ATTR.matcher(tag);
    if (!m.find()) return tag;
    String expr = m.group(2);
    StringBuilder b = new StringBuilder();
    int at = 0;
    boolean quoted = false;
    for (int i = 0; i < expr.length(); i++) {
      char c = expr.charAt(i);
      if (c == '\'' || (c == '"' && m.group(1).equals("'"))) {
        quoted = !quoted;
        continue;
      }
      if (quoted || !(Character.isLetter(c) || c == '_') || (i > 0 && Character.isLetterOrDigit(expr.charAt(i - 1)))) continue;
      Matcher w = WORD.matcher(expr).region(i, expr.length());
      if (!w.lookingAt()) continue;
      String word = w.group();
      boolean member = i > 0 && expr.charAt(i - 1) == '.';
      if (word.equals(oldField)) {
        b.append(expr, at, i).append(newField);
        at = w.end();
      } else if (!member && !OGNL_WORDS.contains(word)) {
        return null;
      }
      i = w.end() - 1;
    }
    b.append(expr.substring(at));
    return tag.substring(0, m.start(2)) + b + tag.substring(m.end(2));
  }

  /** resultMap 의 result 한 줄을 nc 로. column · property 만 바꾸고 jdbcType 은 새 칼럼 것으로(모르면 null). 다른 형식 속성이 있으면 null. */
  private static String cloneResult(CharSequence text, Node el, NewColumn nc) {
    for (String a : el.attrs.keySet()) {
      if (!COLUMN_ATTR.equals(a) && !PROPERTY_ATTR.equals(a) && !JDBC_TYPE.matcher(a).matches()) return null;
    }
    int ls = lineStart(text, el.start);
    int le = lineEnd(text, el.end);
    List<Edit> edits = new ArrayList<>();
    int[] col = ColumnRename.attrRange(text, el, COLUMN_ATTR);
    int[] prop = ColumnRename.attrRange(text, el, PROPERTY_ATTR);
    if (col == null || prop == null) return null;
    edits.add(new Edit(col[0] - ls, col[1] - ls, ColumnRename.caseLike(text.subSequence(col[0], col[1]).toString().trim(), nc.name())));
    edits.add(new Edit(prop[0] - ls, prop[1] - ls, nc.field()));
    for (String a : el.attrs.keySet()) {
      if (!JDBC_TYPE.matcher(a).matches()) continue;
      int[] r = ColumnRename.attrRange(text, el, a);
      if (r == null || nc.jdbcType() == null) return null;
      edits.add(new Edit(r[0] - ls, r[1] - ls, nc.jdbcType()));
    }
    return apply(text.subSequence(ls, le), edits);
  }

  // ---------------------------------------------------------------- 지우기

  private static void removes(CharSequence text, Clause clause, Set<Integer> certain, String dropped, String id, Set<Integer> handled,
                              List<Change> out) {
    for (int i = 0; i < clause.cols().size(); i++) {
      Item c = clause.cols().get(i);
      if (c.column == null || !c.column.equals(dropped)) continue;
      if (clause.part() == Part.SELECT && !certain.contains(c.columnStart)) continue;
      handled.add(c.columnStart);
      if (clause.part() == Part.INSERT) {
        Item v = clause.vals() == null || clause.vals().size() != clause.cols().size() ? null : clause.vals().get(i);
        if (v != null && removable(text, clause.all(), c) && removable(text, clause.all(), v)) {
          out.add(new Change(Kind.REMOVE, Part.INSERT, id, dropped, List.of(removeLine(text, c.start, c.end), removeLine(text, v.start, v.end)),
              false, c.start, 0));
          continue;
        }
      } else if (removable(text, clause.all(), c)) {
        out.add(new Change(Kind.REMOVE, clause.part(), id, dropped, List.of(removeLine(text, c.start, c.end)), false, c.start, 0));
        continue;
      }
      out.add(new Change(Kind.FIX_BY_HAND, clause.part(), id, dropped, List.of(), false, c.start, 0));
    }
  }

  private static boolean removable(CharSequence text, List<Tok> clauseToks, Item it) {
    return exclusive(text, clauseToks, it) && (it.leadingComma || it.trailingComma);
  }

  private static Edit removeLine(CharSequence text, int start, int end) {
    int ls = lineStart(text, start);
    int le = lineEnd(text, end);
    return le < text.length() ? new Edit(ls, le + 1, "") : new Edit(Math.max(0, ls - 1), le, "");
  }

  // ---------------------------------------------------------------- 줄

  private static int lineStart(CharSequence text, int at) {
    int i = at;
    while (i > 0 && text.charAt(i - 1) != '\n') i--;
    return i;
  }

  private static int lineEnd(CharSequence text, int at) {
    int i = at;
    while (i < text.length() && text.charAt(i) != '\n') i++;
    return i;
  }

  /** [start, end) 의 줄에 그 요소 말고는 빈칸뿐이면 true. */
  private static boolean lineOnly(CharSequence text, int start, int end) {
    int ls = lineStart(text, start);
    int le = lineEnd(text, end);
    for (int i = ls; i < start; i++) if (!Character.isWhitespace(text.charAt(i))) return false;
    for (int i = end; i < le; i++) if (!Character.isWhitespace(text.charAt(i))) return false;
    return true;
  }

  /**
   * it 의 줄(들)에 그 항목의 낱말 · 항목 앞뒤 쉼표 · 주석 · if 태그 말고는 없으면 true. 줄 위의 토큰은 it.lineToks 에 담는다.
   * 다른 항목 · 절 낱말(SELECT · FROM · 괄호 등) · include 같은 다른 태그가 같은 줄에 있으면 false.
   */
  private static boolean exclusive(CharSequence text, List<Tok> clauseToks, Item it) {
    int ls = lineStart(text, it.start);
    int le = lineEnd(text, it.end);
    List<Tok> line = new ArrayList<>();
    for (Tok t : clauseToks) {
      if (t.end <= ls || t.start >= le) continue;
      if (t.start < ls || t.end > le) return false;
      line.add(t);
      if (t.type == Type.COMMENT) continue;
      if (t.type == Type.TAG) {
        if (!"if".equals(t.tag) && !"/if".equals(t.tag)) return false;
        continue;
      }
      if (it.toks.contains(t) || t == it.lead || t == it.trail) continue;
      return false;
    }
    it.lineToks = line;
    return true;
  }

  // ---------------------------------------------------------------- 절 나누기

  private static final Set<String> RESULT_TAGS = Set.of("id", "result");
  private static final String RESULT_TAG = "result";
  private static final String COLUMN_ATTR = "column";
  private static final String PROPERTY_ATTR = "property";
  /** 본문을 SQL 로 보지 않는 태그(다른 문장 · 값 정의). */
  private static final Set<String> SKIPPED_TAGS = Set.of("selectKey", "bind");

  /** 목록 항목 하나. column 은 대문자 칼럼(칼럼 항목이 아니면 null), field 는 #{} 의 마지막 마디(없으면 null). */
  private static final class Item {
    final List<Tok> toks = new ArrayList<>();
    int start;
    int end;
    Tok lead;
    Tok trail;
    boolean leadingComma;
    boolean trailingComma;
    String column;
    int columnStart = -1;
    int wordStart = -1;
    Tok alias;
    String field;
    Tok ph;
    boolean simpleShape;
    List<Tok> lineToks = List.of();

    boolean simple() { return simpleShape; }
  }

  /**
   * 한 절의 목록. INSERT 면 cols 가 칼럼, vals 가 VALUES 값. all 은 문장의 모든 토큰(같은 줄에 다른 것이 있는지 볼 때).
   * local 은 목록 안에서 같은 파일 조각을, foreign 은 다른 파일(찾지 못한) 조각을 include 하는지.
   */
  private record Clause(Part part, List<Item> cols, List<Item> vals, List<Tok> all, int at, boolean foreign, boolean local) {}

  /** 문장의 첫 낱말로 SELECT · INSERT · UPDATE 목록을 나눈다. 대상 테이블이 table 이 아니거나 결과를 합치는 SELECT 이거나 모양을 모르면 null. */
  private static Clause clause(List<Tok> toks, String table, MapperDoc doc) {
    int first = nextSql(toks, 0);
    if (first < 0 || toks.get(first).type != Type.WORD) return null;
    String kw = toks.get(first).text.toUpperCase(Locale.ROOT);
    switch (kw) {
      case "SELECT" -> {
        int from = findWord(toks, first + 1, "FROM", 0);
        if (from < 0 || setOperator(toks, first + 1)) return null;
        List<Tok> region = toks.subList(first + 1, from);
        List<Item> items = items(region, 0);
        if (items == null) return null;
        for (Item it : items) selectShape(it);
        boolean foreign = false;
        boolean local = false;
        for (Tok t : region) {
          if (t.type != Type.TAG || !INCLUDE_TAG.equals(t.tag)) continue;
          Matcher m = REFID.matcher(t.text);
          if (m.find() && doc.fragment(m.group(2)) != null) {
            local = true;
          } else {
            foreign = true;
          }
        }
        return new Clause(Part.SELECT, items, null, toks, toks.get(first).start, foreign, local);
      }
      case "INSERT" -> {
        int into = nextSql(toks, first + 1);
        int name = into < 0 ? -1 : nextSql(toks, into + 1);
        if (name < 0 || !"INTO".equalsIgnoreCase(toks.get(into).text) || !sameTable(toks.get(name), table)) return null;
        int open = nextSql(toks, name + 1);
        if (open < 0 || toks.get(open).type != Type.OPEN) return null;
        int close = closeOf(toks, open);
        int values = close < 0 ? -1 : nextSql(toks, close + 1);
        if (values < 0 || !"VALUES".equalsIgnoreCase(toks.get(values).text)) return null;
        int open2 = nextSql(toks, values + 1);
        if (open2 < 0 || toks.get(open2).type != Type.OPEN) return null;
        int close2 = closeOf(toks, open2);
        if (close2 < 0) return null;
        List<Tok> colToks = toks.subList(open + 1, close);
        List<Tok> valToks = toks.subList(open2 + 1, close2);
        List<Item> cols = items(colToks, toks.get(open).depth + 1);
        List<Item> vals = items(valToks, toks.get(open2).depth + 1);
        if (cols == null || vals == null) return null;
        for (Item it : cols) wordShape(it);
        for (Item it : vals) valueShape(it);
        return new Clause(Part.INSERT, cols, vals, toks, toks.get(first).start, false, false);
      }
      case "UPDATE" -> {
        int name = nextSql(toks, first + 1);
        if (name < 0 || !sameTable(toks.get(name), table)) return null;
        int set = -1;
        for (int i = name + 1; i < toks.size() && set < 0; i++) {
          Tok t = toks.get(i);
          if ((t.type == Type.WORD && t.depth == 0 && "SET".equalsIgnoreCase(t.text)) || (t.type == Type.TAG && "set".equals(t.tag))) set = i;
        }
        if (set < 0) return null;
        int end = toks.size();
        for (int i = set + 1; i < toks.size(); i++) {
          Tok t = toks.get(i);
          if ((t.type == Type.WORD && t.depth == 0 && "WHERE".equalsIgnoreCase(t.text))
              || (t.type == Type.TAG && ("where".equals(t.tag) || "/set".equals(t.tag) || "/update".equals(t.tag)))) {
            end = i;
            break;
          }
        }
        List<Tok> region = toks.subList(set + 1, end);
        List<Item> items = items(region, 0);
        if (items == null) return null;
        for (Item it : items) setShape(it);
        return new Clause(Part.UPDATE, items, null, toks, toks.get(first).start, false, false);
      }
      default -> {
        return null;
      }
    }
  }

  private static boolean sameTable(Tok t, String table) {
    if (t.type != Type.WORD) return false;
    String name = t.text.substring(t.text.lastIndexOf('.') + 1).replace("\"", "");
    return name.equalsIgnoreCase(table);
  }

  /** from 부터 첫 SQL 토큰(태그 · 주석이 아닌 것) 번호. 없으면 -1. */
  private static int nextSql(List<Tok> toks, int from) {
    for (int i = from; i < toks.size(); i++) {
      Type t = toks.get(i).type;
      if (t != Type.TAG && t != Type.COMMENT) return i;
    }
    return -1;
  }

  private static int findWord(List<Tok> toks, int from, String word, int depth) {
    for (int i = from; i < toks.size(); i++) {
      Tok t = toks.get(i);
      if (t.type == Type.WORD && t.depth == depth && word.equalsIgnoreCase(t.text)) return i;
    }
    return -1;
  }

  private static int closeOf(List<Tok> toks, int open) {
    int depth = toks.get(open).depth;
    for (int i = open + 1; i < toks.size(); i++) {
      Tok t = toks.get(i);
      if (t.type == Type.CLOSE && t.depth == depth) return i;
    }
    return -1;
  }

  /** depth 의 쉼표로 나눈 항목. 끝에 남은 빈 항목(마지막 쉼표 뒤)은 앞 항목의 뒤 쉼표로 본다. 가운데 빈 항목이 있으면 null. */
  private static List<Item> items(List<Tok> region, int depth) {
    List<Item> out = new ArrayList<>();
    Item cur = new Item();
    Tok prevComma = null;
    for (Tok t : region) {
      if (t.type == Type.TAG || t.type == Type.COMMENT) continue;
      if (t.type == Type.COMMA && t.depth == depth) {
        if (cur.toks.isEmpty()) return null;
        cur.lead = prevComma;
        cur.trail = t;
        out.add(cur);
        prevComma = t;
        cur = new Item();
        continue;
      }
      cur.toks.add(t);
    }
    if (!cur.toks.isEmpty()) {
      cur.lead = prevComma;
      out.add(cur);
    } else if (!out.isEmpty() && prevComma != null) {
      // 마지막 쉼표 뒤가 비었다(<set> · <trim suffixOverrides> 의 꼬리 쉼표): 그 쉼표는 마지막 항목의 것.
      out.get(out.size() - 1).trail = prevComma;
    }
    for (int i = 0; i < out.size(); i++) {
      Item it = out.get(i);
      it.start = it.toks.get(0).start;
      it.end = it.toks.get(it.toks.size() - 1).end;
      // 쉼표는 같은 줄에 있는 항목의 것이다.
      it.leadingComma = it.lead != null && !hasNewline(it.lead.end, it.start, region);
      it.trailingComma = it.trail != null && !hasNewline(it.end, it.trail.start, region);
      if (it.leadingComma && i > 0 && out.get(i - 1).trailingComma && out.get(i - 1).trail == it.lead) it.leadingComma = false;
    }
    return out;
  }

  private static boolean hasNewline(int from, int to, List<Tok> region) {
    CharSequence text = region.isEmpty() ? "" : region.get(0).source;
    for (int i = from; i < to && i < text.length(); i++) if (text.charAt(i) == '\n') return true;
    return false;
  }

  /** SELECT 항목: 「칼럼」 · 「별칭.칼럼」 뒤에 「AS 별칭」 · 「별칭」 이 올 수 있다. */
  private static void selectShape(Item it) {
    List<Tok> t = it.toks;
    if (t.isEmpty() || t.get(0).type != Type.WORD || isKeyword(t.get(0).text)) return;
    setColumn(it, t.get(0));
    if (t.size() == 1) {
      it.simpleShape = true;
    } else if (t.size() == 2 && t.get(1).type == Type.WORD && !isKeyword(t.get(1).text)) {
      it.alias = t.get(1);
      it.simpleShape = true;
    } else if (t.size() == 3 && "AS".equalsIgnoreCase(t.get(1).text) && t.get(2).type == Type.WORD) {
      it.alias = t.get(2);
      it.simpleShape = true;
    }
    if (it.alias != null && it.alias.text.contains(".")) it.simpleShape = false;
  }

  /** INSERT 칼럼 항목: 낱말 하나. */
  private static void wordShape(Item it) {
    if (it.toks.size() == 1 && it.toks.get(0).type == Type.WORD && !isKeyword(it.toks.get(0).text)) {
      setColumn(it, it.toks.get(0));
      it.simpleShape = true;
    }
  }

  /** VALUES 값 항목: #{} 하나. */
  private static void valueShape(Item it) {
    if (it.toks.size() == 1 && it.toks.get(0).type == Type.PH) {
      it.ph = it.toks.get(0);
      it.field = fieldOf(it.ph.text);
      it.simpleShape = it.field != null;
    }
  }

  /** UPDATE SET 항목: 「칼럼 = #{필드}」. 값이 #{} 하나가 아니면 칼럼만 알고 복제하지 않는다. */
  private static void setShape(Item it) {
    List<Tok> t = it.toks;
    if (t.isEmpty() || t.get(0).type != Type.WORD) return;
    setColumn(it, t.get(0));
    if (t.size() == 3 && t.get(1).type == Type.OTHER && "=".equals(t.get(1).text) && t.get(2).type == Type.PH) {
      it.ph = t.get(2);
      it.field = fieldOf(it.ph.text);
      it.simpleShape = it.field != null;
    }
  }

  private static void setColumn(Item it, Tok word) {
    int dot = word.text.lastIndexOf('.');
    it.wordStart = word.start;
    it.columnStart = word.start + dot + 1;
    it.column = word.text.substring(dot + 1).replace("\"", "").toUpperCase(Locale.ROOT);
  }

  /** #{a.b, jdbcType=X} 의 마지막 마디 b. ${} 이거나 비었으면 null. */
  private static String fieldOf(String ph) {
    if (!ph.startsWith("#{") || !ph.endsWith("}")) return null;
    String path = ph.substring(2, ph.length() - 1).split(",")[0].trim();
    String last = path.substring(path.lastIndexOf('.') + 1);
    return WORD.matcher(last).matches() ? last : null;
  }

  private static final Set<String> KEYWORDS = Set.of("SELECT", "FROM", "WHERE", "DISTINCT", "AS", "CASE", "WHEN", "THEN", "ELSE", "END",
      "AND", "OR", "NOT", "NULL", "SET", "VALUES", "INTO", "ON", "JOIN");

  private static boolean isKeyword(String word) {
    return KEYWORDS.contains(word.toUpperCase(Locale.ROOT));
  }

  // ---------------------------------------------------------------- 토큰

  enum Type { WORD, COMMA, OPEN, CLOSE, PH, TAG, COMMENT, OTHER }

  /** tag 는 TAG 의 이름(닫는 태그는 「/이름」). depth 는 괄호 깊이(OPEN 은 열기 전, CLOSE 는 닫은 뒤). source 는 문서 글. */
  private record Tok(Type type, int start, int end, String text, int depth, String tag, CharSequence source) {}

  /** [from, to) 를 토큰으로. 따옴표 · #{} · 태그 · 주석이 닫히지 않으면 null(그 문장은 건너뛴다). */
  static List<Tok> tokenize(CharSequence text, int from, int to) {
    List<Tok> out = new ArrayList<>();
    int depth = 0;
    int i = from;
    while (i < to) {
      char c = text.charAt(i);
      if (startsWith(text, i, XML_COMMENT_OPEN)) {
        int e = indexOf(text, XML_COMMENT_CLOSE, i + XML_COMMENT_OPEN.length(), to);
        if (e < 0) return null;
        int end = e + XML_COMMENT_CLOSE.length();
        out.add(new Tok(Type.COMMENT, i, end, text.subSequence(i, end).toString(), depth, null, text));
        i = end;
      } else if (startsWith(text, i, CDATA_OPEN) || startsWith(text, i, CDATA_CLOSE)) {
        // CDATA 표시는 태그로 둔다: 표시가 있는 줄은 항목만 있는 줄이 아니라 복제 · 지우기를 하지 않는다.
        String mark = startsWith(text, i, CDATA_OPEN) ? CDATA_OPEN : CDATA_CLOSE;
        out.add(new Tok(Type.TAG, i, i + mark.length(), mark, depth, mark, text));
        i += mark.length();
      } else if (c == '<' && i + 1 < to && (Character.isLetter(text.charAt(i + 1)) || text.charAt(i + 1) == '/')) {
        int e = tagEnd(text, i, to);
        if (e < 0) return null;
        boolean close = text.charAt(i + 1) == '/';
        int n = i + (close ? 2 : 1);
        int ne = n;
        while (ne < e && (Character.isLetterOrDigit(text.charAt(ne)) || text.charAt(ne) == '_' || text.charAt(ne) == '-')) ne++;
        String name = text.subSequence(n, ne).toString();
        boolean selfClosing = text.charAt(e - 1) == '/';
        out.add(new Tok(Type.TAG, i, e + 1, text.subSequence(i, e + 1).toString(), depth, close ? "/" + name : name, text));
        i = e + 1;
        if (!close && !selfClosing && SKIPPED_TAGS.contains(name)) {
          int end = indexOf(text, "</" + name, i, to);
          if (end < 0) return null;
          i = end;
        }
      } else if (startsWith(text, i, "/*")) {
        int e = indexOf(text, "*/", i + 2, to);
        if (e < 0) return null;
        out.add(new Tok(Type.COMMENT, i, e + 2, text.subSequence(i, e + 2).toString(), depth, null, text));
        i = e + 2;
      } else if (startsWith(text, i, "--")) {
        int e = i;
        while (e < to && text.charAt(e) != '\n' && !startsWith(text, e, CDATA_CLOSE) && text.charAt(e) != '<') e++;
        out.add(new Tok(Type.COMMENT, i, e, text.subSequence(i, e).toString(), depth, null, text));
        i = e;
      } else if (c == '\'') {
        int e = i + 1;
        while (e < to) {
          if (text.charAt(e) == '\'') {
            if (e + 1 < to && text.charAt(e + 1) == '\'') e += 2;
            else break;
          } else {
            e++;
          }
        }
        if (e >= to) return null;
        out.add(new Tok(Type.OTHER, i, e + 1, text.subSequence(i, e + 1).toString(), depth, null, text));
        i = e + 1;
      } else if ((c == '#' || c == '$') && i + 1 < to && text.charAt(i + 1) == '{') {
        int e = indexOf(text, "}", i + 2, to);
        if (e < 0) return null;
        out.add(new Tok(Type.PH, i, e + 1, text.subSequence(i, e + 1).toString(), depth, null, text));
        i = e + 1;
      } else if (c == '&') {
        int e = i + 1;
        while (e < to && e - i <= MAX_ENTITY && (Character.isLetterOrDigit(text.charAt(e)) || text.charAt(e) == '#')) e++;
        e = e < to && text.charAt(e) == ';' ? e + 1 : i + 1;
        out.add(new Tok(Type.OTHER, i, e, text.subSequence(i, e).toString(), depth, null, text));
        i = e;
      } else if (Character.isLetter(c) || c == '_' || c == '"') {
        int e = ident(text, i, to);
        while (e + 1 < to && text.charAt(e) == '.' && (Character.isLetter(text.charAt(e + 1)) || text.charAt(e + 1) == '_' || text.charAt(e + 1) == '"')) {
          e = ident(text, e + 1, to);
        }
        if (e <= i) return null;
        out.add(new Tok(Type.WORD, i, e, text.subSequence(i, e).toString(), depth, null, text));
        i = e;
      } else if (c == '(') {
        out.add(new Tok(Type.OPEN, i, i + 1, "(", depth, null, text));
        depth++;
        i++;
      } else if (c == ')') {
        depth--;
        out.add(new Tok(Type.CLOSE, i, i + 1, ")", depth, null, text));
        i++;
      } else if (c == ',') {
        out.add(new Tok(Type.COMMA, i, i + 1, ",", depth, null, text));
        i++;
      } else if (Character.isWhitespace(c)) {
        i++;
      } else {
        out.add(new Tok(Type.OTHER, i, i + 1, String.valueOf(c), depth, null, text));
        i++;
      }
    }
    return out;
  }

  private static final int MAX_ENTITY = 10;

  /** 식별자 하나의 끝. 「"이름"」 은 닫는 따옴표 다음. 닫히지 않으면 i. */
  private static int ident(CharSequence text, int i, int to) {
    if (text.charAt(i) == '"') {
      int e = indexOf(text, "\"", i + 1, to);
      return e < 0 ? i : e + 1;
    }
    int e = i;
    while (e < to && (Character.isLetterOrDigit(text.charAt(e)) || text.charAt(e) == '_' || text.charAt(e) == '$' || text.charAt(e) == '#')) {
      if ((text.charAt(e) == '#' || text.charAt(e) == '$') && e + 1 < to && text.charAt(e + 1) == '{') break;
      e++;
    }
    return e;
  }

  /** 여는 태그 끝 '>' 의 위치(속성 따옴표 안의 '>' 는 건너뜀). 없으면 -1. */
  private static int tagEnd(CharSequence text, int i, int to) {
    char quote = 0;
    for (int k = i + 1; k < to; k++) {
      char c = text.charAt(k);
      if (quote != 0) {
        if (c == quote) quote = 0;
      } else if (c == '"' || c == '\'') {
        quote = c;
      } else if (c == '>') {
        return k;
      } else if (c == '<') {
        return -1;
      }
    }
    return -1;
  }

  private static boolean startsWith(CharSequence text, int at, String s) {
    if (at < 0 || at + s.length() > text.length()) return false;
    for (int k = 0; k < s.length(); k++) if (text.charAt(at + k) != s.charAt(k)) return false;
    return true;
  }

  private static int indexOf(CharSequence text, String s, int from, int to) {
    for (int k = from; k + s.length() <= to; k++) if (startsWith(text, k, s)) return k;
    return -1;
  }
}
