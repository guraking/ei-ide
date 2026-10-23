package ei.db.gen;

import ei.db.DbKind;
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

  public enum Kind { RESULT_MAP, LIST, ONE, INSERT, UPDATE, DELETE, MERGE }

  /**
   * oldJoin 은 단일 표 문장이라 지금 글에는 영향이 없다(조인이 생기면 콤마 + WHERE 로 쓴다는 약속만 보존).
   * kind 는 이름 표기(Names.sql)와 LIKE 이어 붙이기(MySQL 은 CONCAT)를 정한다.
   */
  public record Options(boolean oldJoin, boolean cdata, boolean where11, boolean sqlComments, DbKind kind) {}

  /** id 는 XML 문장 id 또는 자바 메서드 이름, text 는 덧붙일 글 그대로. */
  public record Block(String id, String text) {}

  private static final String RANGE_FROM = "From";
  private static final String RANGE_TO = "To";
  /** 앞 쉼표 목록의 줄 머리(XML 안 4칸 + 7칸). */
  private static final String LEAD_FIRST = "           ";
  private static final String LEAD_NEXT = "         , ";
  /** MERGE 의 UPDATE SET · INSERT 목록은 WHEN 절 아래로 6칸 더 들인다. */
  private static final String MERGE_INDENT = "      ";

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
      case MERGE -> "merge" + entity;
      case RESULT_MAP -> Names.lowerFirst(entity) + "Map";
    };
  }

  /** PK 가 아닌 칼럼이 있어야 UPDATE 의 SET 이 빈 채로 남지 않는다. */
  public boolean hasNonPk() {
    for (Meta.Column c : cols) if (!c.pk()) return true;
    return false;
  }

  /** 만들 수 있는 문장인지: PK 로 찾는 문장(ONE · UPDATE · DELETE · MERGE)은 PK 가, UPDATE · MERGE 는 PK 아닌 칼럼도 있어야 한다. */
  public boolean possible(Kind k) {
    return (!needsPk(k) || hasPk()) && (k != Kind.UPDATE && k != Kind.MERGE || hasNonPk());
  }

  /** 만들 수 없는 문장(possible 이 거짓)은 빠진다. */
  public List<Block> xmlBlocks(Set<Kind> kinds) {
    List<Block> out = new ArrayList<>();
    for (Kind k : Kind.values()) {
      if (!kinds.contains(k) || !possible(k)) continue;
      String text = switch (k) {
        case RESULT_MAP -> DtoGen.resultMap(id(k), dtoType, fields(), pkColumns());
        case LIST -> selectList(kinds.contains(Kind.RESULT_MAP));
        case ONE -> selectOne(kinds.contains(Kind.RESULT_MAP));
        case INSERT -> insert();
        case UPDATE -> update();
        case DELETE -> delete();
        case MERGE -> merge();
      };
      out.add(new Block(id(k), text));
    }
    return out;
  }

  public static boolean needsPk(Kind k) {
    return k == Kind.ONE || k == Kind.UPDATE || k == Kind.DELETE || k == Kind.MERGE;
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
      if (!kinds.contains(k) || k == Kind.RESULT_MAP || !possible(k)) continue;
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
      case MERGE -> "등록 · 수정 (PK 로 있으면 수정, 없으면 등록)";
      default -> "삭제";
    };
    return "  <!-- " + Xml.comment(table + " " + label) + " -->\n  <" + tag + " id=\"" + id(k) + "\"" + extra + ">\n";
  }

  private String resultAttr(boolean useMap) {
    return useMap ? " resultMap=\"" + id(Kind.RESULT_MAP) + "\"" : " resultType=\"" + dtoType + "\"";
  }

  /** SELECT 는 키워드만 한 줄, 칼럼은 다음 줄부터 앞 쉼표(첫 칼럼 앞 7칸, 다음부터 「     , 」, XML 안 4칸 위에). */
  private String selectColumns() {
    int width = 0;
    for (Meta.Column c : cols) width = Math.max(width, name(c.name()).length());
    StringBuilder b = new StringBuilder("    SELECT\n");
    boolean first = true;
    for (Meta.Column c : cols) {
      b.append(first ? LEAD_FIRST : LEAD_NEXT).append(alias).append('.');
      String name = name(c.name());
      if (o.sqlComments() && !c.comment().isBlank()) {
        b.append(pad(name, width)).append(' ').append(sqlComment(c.comment()));
      } else {
        b.append(name);
      }
      b.append('\n');
      first = false;
    }
    b.append("      FROM ").append(name(table)).append(' ').append(alias).append('\n');
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
      String col = alias + "." + name(c.name());
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
    b.append("    INSERT INTO ").append(name(table)).append(" (\n");
    boolean first = true;
    for (Meta.Column c : cols) {
      b.append(first ? LEAD_FIRST : LEAD_NEXT).append(name(c.name())).append('\n');
      first = false;
    }
    b.append("    ) VALUES (\n");
    first = true;
    for (Meta.Column c : cols) {
      b.append(first ? LEAD_FIRST : LEAD_NEXT).append("#{").append(Names.camel(c.name())).append("}\n");
      first = false;
    }
    return b.append("    )\n  </insert>\n").toString();
  }

  private String update() {
    StringBuilder b = new StringBuilder(head(Kind.UPDATE, "update", " parameterType=\"" + dtoType + "\""));
    b.append("    UPDATE ").append(name(table)).append('\n');
    b.append("    <set>\n");
    for (Meta.Column c : cols) {
      if (c.pk()) continue;
      String f = Names.camel(c.name());
      b.append("      <if test=\"").append(f).append(" != null\">").append(name(c.name()))
       .append(" = #{").append(f).append("},</if>\n");
    }
    b.append("    </set>\n");
    pkWhere(b, "");
    return b.append("  </update>\n").toString();
  }

  private String delete() {
    StringBuilder b = new StringBuilder(head(Kind.DELETE, "delete", " parameterType=\"" + dtoType + "\""));
    b.append("    DELETE FROM ").append(name(table)).append('\n');
    pkWhere(b, "");
    return b.append("  </delete>\n").toString();
  }

  /**
   * PK 가 있으면 수정, 없으면 등록. Oracle 은 MERGE INTO ... USING DUAL, MySQL 은 ON DUPLICATE KEY UPDATE,
   * PostgreSQL 은 ON CONFLICT (PK) DO UPDATE. PK 칼럼은 고치지 않는다(Oracle 은 ON 에 쓴 칼럼을 UPDATE 할 수 없다).
   */
  private String merge() {
    StringBuilder b = new StringBuilder(head(Kind.MERGE, "update", " parameterType=\"" + dtoType + "\""));
    if (o.kind() != DbKind.ORACLE) {
      b.append("    INSERT INTO ").append(name(table)).append(" (\n");
      columnList(b, "", false);
      b.append("    ) VALUES (\n");
      columnList(b, "", true);
      b.append("    )\n");
      if (o.kind() == DbKind.MYSQL) {
        b.append("    ON DUPLICATE KEY UPDATE\n");
      } else {
        b.append("    ON CONFLICT (").append(String.join(", ", pkNames())).append(") DO UPDATE SET\n");
      }
      boolean first = true;
      for (Meta.Column c : cols) {
        if (c.pk()) continue;
        String n = name(c.name());
        b.append(first ? LEAD_FIRST : LEAD_NEXT).append(n).append(" = ")
         .append(o.kind() == DbKind.MYSQL ? "VALUES(" + n + ")" : "EXCLUDED." + n).append('\n');
        first = false;
      }
      return b.append("  </update>\n").toString();
    }
    b.append("    MERGE INTO ").append(name(table)).append(' ').append(alias).append('\n');
    b.append("    USING DUAL\n");
    List<String> on = new ArrayList<>();
    for (Meta.Column c : cols) {
      if (c.pk()) on.add(alias + "." + name(c.name()) + " = #{" + Names.camel(c.name()) + "}");
    }
    b.append("       ON (").append(String.join(" AND ", on)).append(")\n");
    b.append("     WHEN MATCHED THEN\n");
    b.append("          UPDATE SET\n");
    boolean first = true;
    for (Meta.Column c : cols) {
      if (c.pk()) continue;
      b.append(MERGE_INDENT).append(first ? LEAD_FIRST : LEAD_NEXT).append(alias).append('.').append(name(c.name()))
       .append(" = #{").append(Names.camel(c.name())).append("}\n");
      first = false;
    }
    b.append("     WHEN NOT MATCHED THEN\n");
    b.append("          INSERT (\n");
    columnList(b, MERGE_INDENT, false);
    b.append("          ) VALUES (\n");
    columnList(b, MERGE_INDENT, true);
    b.append("          )\n");
    return b.append("  </update>\n").toString();
  }

  /** 모든 칼럼을 앞 쉼표 목록으로. values 면 #{필드}, 아니면 칼럼 이름. */
  private void columnList(StringBuilder b, String indent, boolean values) {
    boolean first = true;
    for (Meta.Column c : cols) {
      b.append(indent).append(first ? LEAD_FIRST : LEAD_NEXT);
      b.append(values ? "#{" + Names.camel(c.name()) + "}" : name(c.name())).append('\n');
      first = false;
    }
  }

  private List<String> pkNames() {
    List<String> out = new ArrayList<>();
    for (Meta.Column c : cols) if (c.pk()) out.add(name(c.name()));
    return out;
  }

  /** PK 조건. where11 이면 WHERE 1 = 1 다음 AND 줄로, 아니면 첫 조건을 WHERE 에 붙인다. */
  private void pkWhere(StringBuilder b, String prefix) {
    boolean first = !o.where11();
    if (o.where11()) b.append("     WHERE 1 = 1\n");
    for (Meta.Column c : cols) {
      if (!c.pk()) continue;
      b.append(first ? "     WHERE " : "       AND ").append(prefix).append(name(c.name()))
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
    return o.kind() == DbKind.MYSQL ? "CONCAT('%', #{" + f + "}, '%')" : "'%' || #{" + f + "} || '%'";
  }

  private static String sqlComment(String s) {
    return "/* " + Xml.text(s.replace("*/", "* /").replace('\n', ' ').strip()) + " */";
  }

  private String name(String raw) {
    return Names.sql(o.kind(), raw);
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
