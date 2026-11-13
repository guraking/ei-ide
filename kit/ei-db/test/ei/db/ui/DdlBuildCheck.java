package ei.db.ui;

import ei.db.DbKind;

import java.util.List;

/** CREATE 문 조립 자체 점검: java -cp out:test-out ei.db.ui.DdlBuildCheck */
public class DdlBuildCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  public static void main(String[] args) {
    // PostgreSQL 표: 쉼표는 줄 앞, identity · 기본값 · 생성 칼럼 · NOT NULL, 제약, 분할, 인덱스, 주석(빈 주석은 뺀다).
    DdlBuild.Table orders = new DdlBuild.Table(CommentSql.ObjectKind.TABLE, "public", "orders", List.of(
        new DdlBuild.Column("id", "bigint", null, "GENERATED ALWAYS AS IDENTITY", true, "번호"),
        new DdlBuild.Column("user", "character varying(100)", "'x'::character varying", null, true, "주문자 '이름'"),
        new DdlBuild.Column("seq", "integer", "nextval('orders_seq_seq'::regclass)", null, true, ""),
        new DdlBuild.Column("total", "numeric(12,2)", "(price * qty)", "GENERATED ALWAYS AS ((price * qty)) STORED", false, null),
        new DdlBuild.Column("Memo", "text", null, null, false, null)),
        List.of(new DdlBuild.Constraint("orders_pkey", "PRIMARY KEY (id)"),
                new DdlBuild.Constraint("orders_user_fk", "FOREIGN KEY (\"user\") REFERENCES users(id)"),
                new DdlBuild.Constraint("", "CHECK ((seq > 0))")),
        "PARTITION BY RANGE (id)", null,
        List.of("CREATE INDEX orders_seq_idx ON public.orders USING btree (seq)", "CREATE INDEX i2 ON public.orders USING btree (id);"),
        "주문");
    eq(DdlBuild.create(DbKind.POSTGRES, orders), """
        CREATE TABLE PUBLIC.ORDERS (
               ID bigint GENERATED ALWAYS AS IDENTITY NOT NULL
             , "user" character varying(100) DEFAULT 'x'::character varying NOT NULL
             , SEQ integer DEFAULT nextval('orders_seq_seq'::regclass) NOT NULL
             , TOTAL numeric(12,2) GENERATED ALWAYS AS ((price * qty)) STORED
             , "Memo" text
             , CONSTRAINT ORDERS_PKEY PRIMARY KEY (id)
             , CONSTRAINT ORDERS_USER_FK FOREIGN KEY ("user") REFERENCES users(id)
             , CHECK ((seq > 0))
        )
        PARTITION BY RANGE (id);

        CREATE INDEX orders_seq_idx ON public.orders USING btree (seq);
        CREATE INDEX i2 ON public.orders USING btree (id);

        COMMENT ON TABLE PUBLIC.ORDERS IS '주문';
        COMMENT ON COLUMN PUBLIC.ORDERS.ID IS '번호';
        COMMENT ON COLUMN PUBLIC.ORDERS."user" IS '주문자 ''이름''';
        """);

    // 뷰: DB 가 준 본문 그대로, 끝의 ; 는 하나로. 칼럼은 주석에만 쓴다. PostgreSQL 은 COMMENT ON VIEW.
    DdlBuild.Table view = new DdlBuild.Table(CommentSql.ObjectKind.VIEW, "public", "v_orders",
        List.of(new DdlBuild.Column("id", "bigint", null, null, false, "번호")), List.of(), null,
        " SELECT o.id\n   FROM orders o;\n", List.of(), null);
    eq(DdlBuild.create(DbKind.POSTGRES, view), """
        CREATE OR REPLACE VIEW PUBLIC.V_ORDERS AS
        SELECT o.id
           FROM orders o;

        COMMENT ON COLUMN PUBLIC.V_ORDERS.ID IS '번호';
        """);
    // 구체화 뷰: 인덱스도 잇는다. Oracle 은 뷰 주석도 TABLE 낱말(구체화 뷰만 따로).
    DdlBuild.Table mview = new DdlBuild.Table(CommentSql.ObjectKind.MATERIALIZED_VIEW, "SCOTT", "MV_EMP", List.of(), List.of(), null,
        "SELECT * FROM EMP", List.of("CREATE INDEX SCOTT.MV_EMP_IX ON SCOTT.MV_EMP (DEPTNO)"), "사원 요약");
    eq(DdlBuild.create(DbKind.ORACLE, mview), """
        CREATE MATERIALIZED VIEW SCOTT.MV_EMP AS
        SELECT * FROM EMP;

        CREATE INDEX SCOTT.MV_EMP_IX ON SCOTT.MV_EMP (DEPTNO);

        COMMENT ON MATERIALIZED VIEW SCOTT.MV_EMP IS '사원 요약';
        """);

    // Oracle 메타데이터 대체 길: 제약 · 인덱스 글도 조립한다. DEFAULT 는 NOT NULL 앞.
    DdlBuild.Table emp = new DdlBuild.Table(CommentSql.ObjectKind.TABLE, "SCOTT", "EMP", List.of(
        new DdlBuild.Column("EMPNO", "NUMBER(4)", null, null, true, "사번"),
        new DdlBuild.Column("HIREDATE", "DATE", "SYSDATE", null, true, ""),
        new DdlBuild.Column("DEPTNO", "NUMBER(2)", null, null, false, "")),
        List.of(new DdlBuild.Constraint("PK_EMP", DdlBuild.primaryKey(DbKind.ORACLE, List.of("EMPNO"))),
                new DdlBuild.Constraint("FK_DEPTNO", DdlBuild.foreignKey(DbKind.ORACLE, List.of("DEPTNO"), "SCOTT", "DEPT", List.of("DEPTNO")))),
        null, null,
        List.of(DdlBuild.index(DbKind.ORACLE, "SCOTT", "EMP_DEPT_IX", false, "EMP", List.of("DEPTNO", "HIREDATE")),
                DdlBuild.index(DbKind.ORACLE, "SCOTT", "emp_uq", true, "EMP", List.of("EMPNO"))),
        "");
    eq(DdlBuild.create(DbKind.ORACLE, emp), """
        CREATE TABLE SCOTT.EMP (
               EMPNO NUMBER(4) NOT NULL
             , HIREDATE DATE DEFAULT SYSDATE NOT NULL
             , DEPTNO NUMBER(2)
             , CONSTRAINT PK_EMP PRIMARY KEY (EMPNO)
             , CONSTRAINT FK_DEPTNO FOREIGN KEY (DEPTNO) REFERENCES SCOTT.DEPT (DEPTNO)
        );

        CREATE INDEX SCOTT.EMP_DEPT_IX ON SCOTT.EMP (DEPTNO, HIREDATE);
        CREATE UNIQUE INDEX SCOTT."emp_uq" ON SCOTT.EMP (EMPNO);

        COMMENT ON COLUMN SCOTT.EMP.EMPNO IS '사번';
        """);

    // 외부 표 낱말, 칼럼 · 제약 · 인덱스 · 주석이 없으면 CREATE 문만.
    eq(DdlBuild.create(DbKind.POSTGRES, new DdlBuild.Table(CommentSql.ObjectKind.FOREIGN_TABLE, "public", "ext",
           List.of(new DdlBuild.Column("a", "text", null, null, false, null)), List.of(), null, null, List.of(), null)),
       "CREATE FOREIGN TABLE PUBLIC.EXT (\n       A text\n);\n");

    // 분할(자식) 표: 칼럼 목록 없이 PARTITION OF, 자식만의 인덱스 · 주석은 잇는다. 다시 나눈 분할은 PARTITION BY 도.
    DdlBuild.Table child = new DdlBuild.Table(CommentSql.ObjectKind.TABLE, "public", "orders_2024",
        List.of(new DdlBuild.Column("id", "bigint", null, null, true, "번호")), List.of(new DdlBuild.Constraint("orders_2024_pkey", "PRIMARY KEY (id)")),
        "PARTITION BY HASH (id)", null, List.of("CREATE INDEX only_here ON public.orders_2024 USING btree (id)"), "2024 년");
    eq(DdlBuild.createPartition(DbKind.POSTGRES, child, "public", "orders", "FOR VALUES FROM (1) TO (100)"), """
        CREATE TABLE PUBLIC.ORDERS_2024 PARTITION OF PUBLIC.ORDERS
        FOR VALUES FROM (1) TO (100)
        PARTITION BY HASH (id);

        CREATE INDEX only_here ON public.orders_2024 USING btree (id);

        COMMENT ON TABLE PUBLIC.ORDERS_2024 IS '2024 년';
        COMMENT ON COLUMN PUBLIC.ORDERS_2024.ID IS '번호';
        """);

    // PK 와 칼럼 구성이 같은 유일 인덱스(이름 · 순서가 달라도)는 PK 제약이 만든다. 유일이 아니거나 칼럼이 다르면 아니다.
    eq(DdlBuild.backsPrimaryKey(true, List.of("B", "A"), List.of("A", "B")), true);
    eq(DdlBuild.backsPrimaryKey(false, List.of("A", "B"), List.of("A", "B")), false);
    eq(DdlBuild.backsPrimaryKey(true, List.of("A"), List.of("A", "B")), false);
    eq(DdlBuild.backsPrimaryKey(true, List.of("A", "C"), List.of("A", "B")), false);
    eq(DdlBuild.backsPrimaryKey(true, List.of(), List.of()), false);

    // 문장 끝: 공백 · ; 여러 개를 ; 하나로, null 은 빈 문장.
    eq(DdlBuild.statement("  CREATE INDEX a ON t (x) ;;\n"), "CREATE INDEX a ON t (x);");
    eq(DdlBuild.statement(null), ";");

    System.out.println("DdlBuildCheck 통과");
  }
}
