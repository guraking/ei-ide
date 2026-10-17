package ei.db.gen;

import ei.db.Meta;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 표 하나의 MyBatis 매퍼 뼈대(XML 문장 · 자바 인터페이스 · DTO). 화면과 떨어진 순수 논리라 자체 점검에서 바로 부른다.
 * 목록 검색은 칼럼마다 &lt;if&gt; 하나: 문자 칼럼은 LIKE, 날짜 칼럼은 필드From · 필드To 범위, 나머지는 =.
 */
public final class MapperGen {

  public enum Kind { RESULT_MAP, LIST, ONE, INSERT, UPDATE, DELETE }

  /**
   * oldJoin 은 단일 표 문장이라 지금 글에는 영향이 없다(조인이 생기면 콤마 + WHERE 로 쓴다는 약속만 보존).
   * mysql 이면 LIKE 이어 붙이기를 CONCAT 으로.
   */
  public record Options(boolean oldJoin, boolean cdata, boolean where11, boolean sqlComments, boolean mysql) {}

  /** id 는 XML 문장 id 또는 자바 메서드 이름, text 는 덧붙일 글 그대로. */
  public record Block(String id, String text) {}

  private static final String RANGE_FROM = "From";
  private static final String RANGE_TO = "To";

  private final String table;
  private final String alias;
  private final String entity;
  private final List<Meta.Column> cols;
  private final String namespace;
  private final String dtoType;
  private final Options o;

  /** dtoType 은 DTO 의 FQN(패키지가 없으면 단순 이름). */
  public MapperGen(String table, List<Meta.Column> cols, String namespace, String dtoType, Options o) {
    this.table = table;
    this.alias = Names.alias(table);
    this.entity = Names.pascal(table);
    this.cols = cols;
    this.namespace = namespace;
    this.dtoType = dtoType;
    this.o = o;
  }

  public boolean hasPk() {
    for (Meta.Column c : cols) if (c.pk()) return true;
    return false;
  }

  public String id(Kind k) {
    return switch (k) {
      case LIST -> "select" + entity + "List";
      case ONE -> "select" + entity;
      case INSERT -> "insert" + entity;
      case UPDATE -> "update" + entity;
      case DELETE -> "delete" + entity;
      case RESULT_MAP -> Names.lowerFirst(entity) + "Map";
    };
  }

  /** PK 가 없으면 PK 로 찾는 문장(ONE · UPDATE · DELETE)은 빠진다. */
  public List<Block> xmlBlocks(Set<Kind> kinds) {
    List<Block> out = new ArrayList<>();
    for (Kind k : Kind.values()) {
      if (!kinds.contains(k) || (needsPk(k) && !hasPk())) continue;
      String text = switch (k) {
        case RESULT_MAP -> DtoGen.resultMap(id(k), dtoType, fields(), pkColumns());
        case LIST -> selectList(kinds.contains(Kind.RESULT_MAP));
        case ONE -> selectOne(kinds.contains(Kind.RESULT_MAP));
        case INSERT -> insert();
        case UPDATE -> update();
        case DELETE -> delete();
      };
      out.add(new Block(id(k), text));
    }
    return out;
  }

  public static boolean needsPk(Kind k) {
    return k == Kind.ONE || k == Kind.UPDATE || k == Kind.DELETE;
  }

  /** 새 파일 전체(DOCTYPE 포함). */
  public String xmlFile(Set<Kind> kinds) {
    StringBuilder b = new StringBuilder(Xml.HEADER);
    b.append("<mapper namespace=\"").append(Xml.attr(namespace)).append("\">\n");
    b.append(joinBlocks(xmlBlocks(kinds)));
    return b.append("</mapper>\n").toString();
  }

  public static String joinBlocks(List<Block> blocks) {
    StringBuilder b = new StringBuilder();
    for (Block bl : blocks) b.append('\n').append(bl.text());
    return b.toString();
  }

  /** 인터페이스 메서드 한 줄씩. resultMap 은 메서드가 없다. */
  public List<Block> methods(Set<Kind> kinds) {
    String dto = Names.simple(dtoType);
    List<Block> out = new ArrayList<>();
    for (Kind k : Kind.values()) {
      if (!kinds.contains(k) || k == Kind.RESULT_MAP || (needsPk(k) && !hasPk())) continue;
      String sig = switch (k) {
        case LIST -> "List<" + dto + "> " + id(k) + "(Map<String, Object> param);";
        case ONE -> dto + " " + id(k) + "(" + dto + " param);";
        default -> "int " + id(k) + "(" + dto + " param);";
      };
      out.add(new Block(id(k), "    " + sig + "\n"));
    }
    return out;
  }

  public List<String> interfaceImports(Set<Kind> kinds) {
    Set<String> out = new TreeSet<>();
    if (kinds.contains(Kind.LIST)) {
      out.add("java.util.List");
      out.add("java.util.Map");
    }
    String dtoPkg = Names.packageOf(dtoType);
    if (!dtoPkg.isEmpty() && !dtoPkg.equals(Names.packageOf(namespace))) out.add(dtoType);
    return new ArrayList<>(out);
  }

  public String interfaceFile(Set<Kind> kinds) {
    StringBuilder b = new StringBuilder();
    String pkg = Names.packageOf(namespace);
    if (!pkg.isEmpty()) b.append("package ").append(pkg).append(";\n\n");
    List<String> imports = interfaceImports(kinds);
    for (String imp : imports) b.append("import ").append(imp).append(";\n");
    if (!imports.isEmpty()) b.append('\n');
    b.append("public interface ").append(Names.simple(namespace)).append(" {\n");
    for (Block m : methods(kinds)) b.append(m.text());
    return b.append("}\n").toString();
  }

  public String dtoFile() {
    return DtoGen.dto(Names.packageOf(dtoType), Names.simple(dtoType), fields(), true, true);
  }

  public List<DtoGen.Field> fields() {
    List<DtoGen.Field> out = new ArrayList<>();
    for (Meta.Column c : cols) {
      out.add(new DtoGen.Field(c.name(), Names.camel(c.name()), TypeMap.forColumn(c.type(), c.scale()), c.comment()));
    }
    return out;
  }

  private Set<String> pkColumns() {
    Set<String> out = new HashSet<>();
    for (Meta.Column c : cols) if (c.pk()) out.add(c.name());
    return out;
  }

  // ---- 문장 ----

  private String head(Kind k, String tag, String extra) {
    String label = switch (k) {
      case LIST -> "목록 (조건 검색)";
      case ONE -> "한 건 (PK)";
      case INSERT -> "등록";
      case UPDATE -> "수정";
      default -> "삭제";
    };
    return "  <!-- " + Xml.comment(table + " " + label) + " -->\n  <" + tag + " id=\"" + id(k) + "\"" + extra + ">\n";
  }

  private String resultAttr(boolean useMap) {
    return useMap ? " resultMap=\"" + id(Kind.RESULT_MAP) + "\"" : " resultType=\"" + dtoType + "\"";
  }

  private String selectColumns() {
    int width = 0;
    for (Meta.Column c : cols) width = Math.max(width, Names.sql(c.name()).length());
    StringBuilder b = new StringBuilder();
    boolean first = true;
    for (Meta.Column c : cols) {
      b.append(first ? "    SELECT " : "         , ").append(alias).append('.');
      String name = Names.sql(c.name());
      if (o.sqlComments() && !c.comment().isBlank()) {
        b.append(pad(name, width)).append(' ').append(sqlComment(c.comment()));
      } else {
        b.append(name);
      }
      b.append('\n');
      first = false;
    }
    b.append("      FROM ").append(Names.sql(table)).append(' ').append(alias).append('\n');
    return b.toString();
  }

  private String selectList(boolean useMap) {
    StringBuilder b = new StringBuilder(head(Kind.LIST, "select", " parameterType=\"map\"" + resultAttr(useMap)));
    b.append(selectColumns());
    String indent = "    ";
    if (o.where11()) {
      b.append("     WHERE 1 = 1\n");
    } else {
      b.append("    <where>\n");
      indent = "      ";
    }
    for (Meta.Column c : cols) {
      String col = alias + "." + Names.sql(c.name());
      String f = Names.camel(c.name());
      if (isDate(c.type())) {
        ifBlock(b, indent, f + RANGE_FROM + " != null", "AND " + col + " " + cmp(">=") + " #{" + f + RANGE_FROM + "}");
        ifBlock(b, indent, f + RANGE_TO + " != null", "AND " + col + " " + cmp("<=") + " #{" + f + RANGE_TO + "}");
      } else if (isChar(c.type())) {
        ifBlock(b, indent, f + " != null and " + f + " != ''", "AND " + col + " LIKE " + like(f));
      } else {
        ifBlock(b, indent, f + " != null", "AND " + col + " = #{" + f + "}");
      }
    }
    if (!o.where11()) b.append("    </where>\n");
    return b.append("  </select>\n").toString();
  }

  private String selectOne(boolean useMap) {
    StringBuilder b = new StringBuilder(head(Kind.ONE, "select", " parameterType=\"" + dtoType + "\"" + resultAttr(useMap)));
    b.append(selectColumns());
    pkWhere(b, alias + ".");
    return b.append("  </select>\n").toString();
  }

  private String insert() {
    StringBuilder b = new StringBuilder(head(Kind.INSERT, "insert", " parameterType=\"" + dtoType + "\""));
    b.append("    INSERT INTO ").append(Names.sql(table)).append(" (\n");
    boolean first = true;
    for (Meta.Column c : cols) {
      b.append(first ? "           " : "         , ").append(Names.sql(c.name())).append('\n');
      first = false;
    }
    b.append("    ) VALUES (\n");
    first = true;
    for (Meta.Column c : cols) {
      b.append(first ? "           " : "         , ").append("#{").append(Names.camel(c.name())).append("}\n");
      first = false;
    }
    return b.append("    )\n  </insert>\n").toString();
  }

  private String update() {
    StringBuilder b = new StringBuilder(head(Kind.UPDATE, "update", " parameterType=\"" + dtoType + "\""));
    b.append("    UPDATE ").append(Names.sql(table)).append('\n');
    b.append("    <set>\n");
    for (Meta.Column c : cols) {
      if (c.pk()) continue;
      String f = Names.camel(c.name());
      b.append("      <if test=\"").append(f).append(" != null\">").append(Names.sql(c.name()))
       .append(" = #{").append(f).append("},</if>\n");
    }
    b.append("    </set>\n");
    pkWhere(b, "");
    return b.append("  </update>\n").toString();
  }

  private String delete() {
    StringBuilder b = new StringBuilder(head(Kind.DELETE, "delete", " parameterType=\"" + dtoType + "\""));
    b.append("    DELETE FROM ").append(Names.sql(table)).append('\n');
    pkWhere(b, "");
    return b.append("  </delete>\n").toString();
  }

  /** PK 조건. where11 이면 WHERE 1 = 1 다음 AND 줄로, 아니면 첫 조건을 WHERE 에 붙인다. */
  private void pkWhere(StringBuilder b, String prefix) {
    boolean first = !o.where11();
    if (o.where11()) b.append("     WHERE 1 = 1\n");
    for (Meta.Column c : cols) {
      if (!c.pk()) continue;
      b.append(first ? "     WHERE " : "       AND ").append(prefix).append(Names.sql(c.name()))
       .append(" = #{").append(Names.camel(c.name())).append("}\n");
      first = false;
    }
  }

  private static void ifBlock(StringBuilder b, String indent, String test, String body) {
    b.append(indent).append("<if test=\"").append(Xml.attr(test)).append("\">\n");
    b.append(indent).append("   ").append(body).append('\n');
    b.append(indent).append("</if>\n");
  }

  private String cmp(String op) {
    return o.cdata() ? "<![CDATA[ " + op + " ]]>" : Xml.text(op);
  }

  private String like(String f) {
    return o.mysql() ? "CONCAT('%', #{" + f + "}, '%')" : "'%' || #{" + f + "} || '%'";
  }

  private static String sqlComment(String s) {
    return "/* " + Xml.text(s.replace("*/", "* /").replace('\n', ' ').strip()) + " */";
  }

  private static String pad(String s, int width) {
    return s.length() >= width ? s : s + " ".repeat(width - s.length());
  }

  static boolean isChar(String type) {
    String t = type.toUpperCase(Locale.ROOT);
    return t.contains("CHAR") || t.contains("TEXT") || t.contains("CLOB");
  }

  static boolean isDate(String type) {
    String t = type.toUpperCase(Locale.ROOT);
    return t.contains("DATE") || t.contains("TIMESTAMP");
  }

  public static Set<Kind> allKinds() {
    return EnumSet.allOf(Kind.class);
  }
}
