package ei.db.ui;

import ei.db.DbKind;

import java.util.ArrayList;
import java.util.List;

/**
 * 읽어 온 칼럼 · 제약 · 인덱스 · 주석으로 CREATE 문을 조립한다(PostgreSQL 카탈로그, Oracle 의 메타데이터 대체 길). IntelliJ 클래스를 쓰지 않는다(DdlBuildCheck 로 점검).
 * 칼럼 · 제약은 한 줄에 하나, 쉼표는 줄 앞. 문장마다 ; 로 끝내고 CREATE · 인덱스 · 주석 묶음 사이는 빈 줄.
 * 이름 표기는 CommentSql.name(필요할 때만 따옴표). 형 · 기본값 · 제약 정의 · 인덱스 문 · 뷰 본문처럼 DB 가 준 글은 그대로 쓴다.
 */
final class DdlBuild {
  private DdlBuild() {}

  /**
   * 칼럼 한 줄. type 은 DB 표기 그대로(길이 포함). defaultExpr 은 DEFAULT 뒤 식(없으면 null).
   * generated 는 「GENERATED … AS IDENTITY」 · 「GENERATED ALWAYS AS (식) STORED」 절 전체(없으면 null).
   */
  record Column(String name, String type, String defaultExpr, String generated, boolean notNull, String comment) {}

  /** 표 제약. name 이 비면 CONSTRAINT 이름 없이 definition 만 쓴다. definition 예: 「PRIMARY KEY (id)」. */
  record Constraint(String name, String definition) {}

  /**
   * 표 · 뷰 하나. 뷰 · 구체화 뷰는 query(AS 뒤 본문)를 쓰고 columns 는 주석에만 쓴다. tail 은 닫는 괄호 뒤 절(PARTITION BY …, 없으면 null).
   * indexes 는 CREATE INDEX 문 전체(끝의 ; 는 있어도 없어도 된다).
   */
  record Table(CommentSql.ObjectKind kind, String schema, String name, List<Column> columns, List<Constraint> constraints,
               String tail, String query, List<String> indexes, String comment) {}

  private static final String FIRST = "       ";
  private static final String NEXT = "     , ";
  private static final String STATEMENT_END = ";";
  private static final String NAME_SEPARATOR = ", ";

  /** CREATE 문 + 인덱스 + 주석. 끝은 줄바꿈 하나. */
  static String create(DbKind db, Table t) {
    String name = CommentSql.name(db, t.schema(), t.name());
    StringBuilder sb = new StringBuilder();
    switch (t.kind()) {
      case VIEW -> sb.append("CREATE OR REPLACE VIEW ").append(name).append(" AS\n").append(statement(t.query())).append('\n');
      case MATERIALIZED_VIEW -> sb.append("CREATE MATERIALIZED VIEW ").append(name).append(" AS\n").append(statement(t.query())).append('\n');
      default -> table(sb, db, t, name);
    }
    return rest(sb, db, t);
  }

  /**
   * 분할(자식) 표: 「CREATE TABLE 자식 PARTITION OF 부모 / FOR VALUES …」 + 인덱스 + 주석. 칼럼 · 제약은 부모에게서 받으므로 쓰지 않는다
   * (t.columns 는 주석에만 쓴다). bound 는 DB 가 준 경계 글(FOR VALUES … · DEFAULT), t.tail 은 다시 나눌 때의 PARTITION BY 절.
   */
  static String createPartition(DbKind db, Table t, String parentSchema, String parent, String bound) {
    StringBuilder sb = new StringBuilder("CREATE TABLE ").append(CommentSql.name(db, t.schema(), t.name()))
        .append(" PARTITION OF ").append(CommentSql.name(db, parentSchema, parent)).append('\n').append(bound.strip());
    if (!blank(t.tail())) sb.append('\n').append(t.tail().strip());
    sb.append(STATEMENT_END).append('\n');
    return rest(sb, db, t);
  }

  /** CREATE 문 뒤에 인덱스 · 주석 묶음을 잇는다. */
  private static String rest(StringBuilder sb, DbKind db, Table t) {
    if (!t.indexes().isEmpty()) {
      sb.append('\n');
      for (String index : t.indexes()) sb.append(statement(index)).append('\n');
    }
    List<CommentSql.Change> comments = new ArrayList<>();
    if (!blank(t.comment())) comments.add(new CommentSql.Change(null, t.comment()));
    for (Column col : t.columns()) {
      if (!blank(col.comment())) comments.add(new CommentSql.Change(col.name(), col.comment()));
    }
    if (!comments.isEmpty()) {
      sb.append('\n').append(CommentSql.script(CommentSql.statements(db, t.kind(), t.schema(), t.name(), comments)));
    }
    return sb.toString();
  }

  private static void table(StringBuilder sb, DbKind db, Table t, String name) {
    sb.append("CREATE ").append(t.kind().word).append(' ').append(name).append(" (\n");
    List<String> lines = new ArrayList<>();
    for (Column col : t.columns()) lines.add(column(db, col));
    for (Constraint con : t.constraints()) {
      lines.add(blank(con.name()) ? con.definition() : "CONSTRAINT " + CommentSql.name(db, con.name()) + " " + con.definition());
    }
    for (int i = 0; i < lines.size(); i++) sb.append(i == 0 ? FIRST : NEXT).append(lines.get(i)).append('\n');
    sb.append(')');
    if (!blank(t.tail())) sb.append('\n').append(t.tail().strip());
    sb.append(STATEMENT_END).append('\n');
  }

  /** 이름 형 [DEFAULT 식 | GENERATED 절] [NOT NULL]. Oracle 은 DEFAULT 가 NOT NULL 앞에 와야 한다. */
  private static String column(DbKind db, Column col) {
    StringBuilder sb = new StringBuilder(CommentSql.name(db, col.name())).append(' ').append(col.type());
    if (!blank(col.generated())) sb.append(' ').append(col.generated().strip());
    else if (!blank(col.defaultExpr())) sb.append(" DEFAULT ").append(col.defaultExpr().strip());
    if (col.notNull()) sb.append(" NOT NULL");
    return sb.toString();
  }

  /** 「PRIMARY KEY (A, B)」. */
  static String primaryKey(DbKind db, List<String> columns) {
    return "PRIMARY KEY (" + names(db, columns) + ")";
  }

  /** 「FOREIGN KEY (A) REFERENCES 스키마.표 (B)」. */
  static String foreignKey(DbKind db, List<String> columns, String refSchema, String refTable, List<String> refColumns) {
    return "FOREIGN KEY (" + names(db, columns) + ") REFERENCES " + CommentSql.name(db, refSchema, refTable) + " (" + names(db, refColumns) + ")";
  }

  /** 「CREATE [UNIQUE] INDEX 스키마.이름 ON 스키마.표 (A, B)」(끝의 ; 없음). */
  static String index(DbKind db, String schema, String name, boolean unique, String table, List<String> columns) {
    return "CREATE " + (unique ? "UNIQUE " : "") + "INDEX " + CommentSql.name(db, schema, name) + " ON " + CommentSql.name(db, schema, table)
           + " (" + names(db, columns) + ")";
  }

  /** 유일 인덱스의 칼럼 구성이 PK 와 같으면(순서 무관) 참: PK 제약이 그 인덱스를 만들므로 따로 내지 않는다. PK 가 없으면 거짓. */
  static boolean backsPrimaryKey(boolean unique, List<String> indexColumns, List<String> pkColumns) {
    return unique && !pkColumns.isEmpty() && indexColumns.size() == pkColumns.size() && indexColumns.containsAll(pkColumns);
  }

  private static String names(DbKind db, List<String> columns) {
    List<String> out = new ArrayList<>(columns.size());
    for (String col : columns) out.add(CommentSql.name(db, col));
    return String.join(NAME_SEPARATOR, out);
  }

  /** DB 가 준 문장의 앞뒤 공백과 끝의 ; 를 떼고 ; 하나로 끝낸다. null 은 빈 문장. */
  static String statement(String sql) {
    String s = sql == null ? "" : sql.strip();
    while (s.endsWith(STATEMENT_END)) s = s.substring(0, s.length() - 1).stripTrailing();
    return s + STATEMENT_END;
  }

  private static boolean blank(String s) { return s == null || s.isBlank(); }
}
