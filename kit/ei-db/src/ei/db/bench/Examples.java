package ei.db.bench;

import ei.db.DbKind;

import java.util.List;

/**
 * 실무 쿼리 예제 모음. 인터넷 없이 쓰도록 플러그인 안에 둔다. 표 이름(ORDERS · CUSTOMER 등)은 보기이고 편집기에 넣은 뒤 바꿔 쓴다.
 * Oracle 과 PostgreSQL 글을 따로 두고, MySQL 은 PostgreSQL 글을 보인다(LIMIT · COALESCE 등이 같다).
 */
public final class Examples {
  private Examples() {}

  public record Example(String group, String title, String description, String oracle, String postgres) {
    public String sql(DbKind kind) { return kind == DbKind.ORACLE ? oracle : postgres; }

    @Override public String toString() { return title; }
  }

  public static final List<Example> ALL = List.of(
      new Example("기본 조회", "겹치지 않는 값만 (DISTINCT)", "상태 칼럼에 어떤 값들이 있는지 한 번씩만 봅니다.",
          """
          SELECT DISTINCT
                 o.STATUS
            FROM ORDERS o
           ORDER BY
                 o.STATUS""",
          """
          SELECT DISTINCT
                 o.STATUS
            FROM ORDERS o
           ORDER BY
                 o.STATUS"""),
      new Example("기본 조회", "글자가 들어간 것 찾기 (대소문자 무시)", "이름에 친 글자가 들어간 고객. 앞에 % 가 붙으면 색인을 못 탑니다.",
          """
          SELECT
                 c.*
            FROM CUSTOMER c
           WHERE UPPER(c.CUST_NAME) LIKE '%' || UPPER(:keyword) || '%'""",
          """
          SELECT
                 c.*
            FROM CUSTOMER c
           WHERE c.CUST_NAME ILIKE '%' || :keyword || '%'"""),
      new Example("기본 조회", "여러 값 중 하나 (IN)", "상태가 접수 · 진행 중 하나인 주문.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.STATUS IN ('NEW', 'WORK')""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.STATUS IN ('NEW', 'WORK')"""),
      new Example("기본 조회", "숫자 범위 (BETWEEN)", "금액이 1만 이상 10만 이하. BETWEEN 은 양 끝을 포함합니다.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.AMOUNT BETWEEN 10000 AND 100000""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.AMOUNT BETWEEN 10000 AND 100000"""),
      new Example("조인", "두 표 잇기 (짝 있는 것만)", "주문마다 고객 이름을 붙입니다. 고객이 없는 주문은 빠집니다.",
          """
          SELECT
                 o.ORDER_ID
               , o.ORDER_DT
               , c.CUST_NAME
            FROM ORDERS o, CUSTOMER c
           WHERE o.CUST_ID = c.CUST_ID""",
          """
          SELECT
                 o.ORDER_ID
               , o.ORDER_DT
               , c.CUST_NAME
            FROM ORDERS o
            JOIN CUSTOMER c
              ON c.CUST_ID = o.CUST_ID"""),
      new Example("조인", "짝이 없어도 다 보기 (아우터)", "고객이 없는 주문도 나오고 고객 이름은 비어 있습니다. Oracle 옛 방식은 비어도 되는 쪽에 (+).",
          """
          SELECT
                 o.ORDER_ID
               , c.CUST_NAME
            FROM ORDERS o, CUSTOMER c
           WHERE o.CUST_ID = c.CUST_ID(+)""",
          """
          SELECT
                 o.ORDER_ID
               , c.CUST_NAME
            FROM ORDERS o
            LEFT JOIN CUSTOMER c
              ON c.CUST_ID = o.CUST_ID"""),
      new Example("조인", "세 표 잇기", "주문 · 고객 · 주문 상세를 함께 봅니다. 상세가 여러 줄이면 주문도 여러 줄이 됩니다.",
          """
          SELECT
                 o.ORDER_ID
               , c.CUST_NAME
               , i.PART_NO
               , i.QTY
            FROM ORDERS o, CUSTOMER c, ORDER_ITEM i
           WHERE o.CUST_ID = c.CUST_ID
             AND i.ORDER_ID = o.ORDER_ID""",
          """
          SELECT
                 o.ORDER_ID
               , c.CUST_NAME
               , i.PART_NO
               , i.QTY
            FROM ORDERS o
            JOIN CUSTOMER c
              ON c.CUST_ID = o.CUST_ID
            JOIN ORDER_ITEM i
              ON i.ORDER_ID = o.ORDER_ID"""),
      new Example("조인", "같은 표끼리 잇기 (자기 조인)", "메뉴마다 부모 메뉴 이름을 붙입니다. 같은 표를 다른 별칭으로 두 번 씁니다.",
          """
          SELECT
                 m.MENU_ID
               , m.MENU_NAME
               , p.MENU_NAME AS PARENT_NAME
            FROM MENU m, MENU p
           WHERE m.PARENT_ID = p.MENU_ID(+)""",
          """
          SELECT
                 m.MENU_ID
               , m.MENU_NAME
               , p.MENU_NAME AS PARENT_NAME
            FROM MENU m
            LEFT JOIN MENU p
              ON p.MENU_ID = m.PARENT_ID"""),
      new Example("조인", "짝이 없는 것만 (아우터 + IS NULL)", "주문이 한 건도 없는 고객. NOT EXISTS 와 같은 결과입니다.",
          """
          SELECT
                 c.*
            FROM CUSTOMER c, ORDERS o
           WHERE c.CUST_ID = o.CUST_ID(+)
             AND o.ORDER_ID IS NULL""",
          """
          SELECT
                 c.*
            FROM CUSTOMER c
            LEFT JOIN ORDERS o
              ON o.CUST_ID = c.CUST_ID
           WHERE o.ORDER_ID IS NULL"""),
      new Example("조인", "묶은 뒤 잇기 (건수 불어남 막기)", "상세를 먼저 주문마다 합친 뒤 주문에 붙입니다. 그냥 이으면 주문 금액이 상세 줄 수만큼 여러 번 더해집니다.",
          """
          SELECT
                 o.ORDER_ID
               , o.AMOUNT
               , s.ITEM_CNT
               , s.ITEM_AMT
            FROM ORDERS o
               , (
                  SELECT
                         i.ORDER_ID
                       , COUNT(*) AS ITEM_CNT
                       , SUM(i.QTY * i.PRICE) AS ITEM_AMT
                    FROM ORDER_ITEM i
                   GROUP BY
                         i.ORDER_ID
                 ) s
           WHERE o.ORDER_ID = s.ORDER_ID(+)""",
          """
          SELECT
                 o.ORDER_ID
               , o.AMOUNT
               , s.ITEM_CNT
               , s.ITEM_AMT
            FROM ORDERS o
            LEFT JOIN (
                  SELECT
                         i.ORDER_ID
                       , COUNT(*) AS ITEM_CNT
                       , SUM(i.QTY * i.PRICE) AS ITEM_AMT
                    FROM ORDER_ITEM i
                   GROUP BY
                         i.ORDER_ID
                 ) s
              ON s.ORDER_ID = o.ORDER_ID"""),
      new Example("서브쿼리", "칸 하나를 다른 표에서 (스칼라 서브쿼리)", "줄마다 고객 이름을 한 칸으로 가져옵니다. 결과가 두 줄 이상이면 오류가 납니다.",
          """
          SELECT
                 o.ORDER_ID
               , (SELECT c.CUST_NAME FROM CUSTOMER c WHERE c.CUST_ID = o.CUST_ID) AS CUST_NAME
            FROM ORDERS o""",
          """
          SELECT
                 o.ORDER_ID
               , (SELECT c.CUST_NAME FROM CUSTOMER c WHERE c.CUST_ID = o.CUST_ID) AS CUST_NAME
            FROM ORDERS o"""),
      new Example("서브쿼리", "다른 표에 있는 것만 (EXISTS)", "상세가 한 줄이라도 있는 주문. 상세가 여러 줄이어도 주문은 한 번만 나옵니다.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE EXISTS (
                  SELECT 1
                    FROM ORDER_ITEM i
                   WHERE i.ORDER_ID = o.ORDER_ID
                 )""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE EXISTS (
                  SELECT 1
                    FROM ORDER_ITEM i
                   WHERE i.ORDER_ID = o.ORDER_ID
                 )"""),
      new Example("서브쿼리", "평균보다 큰 것", "전체 평균 금액보다 큰 주문.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.AMOUNT > (SELECT AVG(x.AMOUNT) FROM ORDERS x)""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.AMOUNT > (SELECT AVG(x.AMOUNT) FROM ORDERS x)"""),
      new Example("서브쿼리", "고객마다 가장 최근 주문 (상관 서브쿼리)", "고객마다 주문일이 가장 늦은 주문. 같은 날이 둘이면 둘 다 나옵니다.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.ORDER_DT = (
                  SELECT MAX(x.ORDER_DT)
                    FROM ORDERS x
                   WHERE x.CUST_ID = o.CUST_ID
                 )""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.ORDER_DT = (
                  SELECT MAX(x.ORDER_DT)
                    FROM ORDERS x
                   WHERE x.CUST_ID = o.CUST_ID
                 )"""),
      new Example("집계", "그룹별 건수 · 합계 · 평균", "고객마다 주문 건수 · 금액 합계 · 평균.",
          """
          SELECT
                 o.CUST_ID
               , COUNT(*) AS CNT
               , SUM(o.AMOUNT) AS SUM_AMT
               , ROUND(AVG(o.AMOUNT), 2) AS AVG_AMT
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID
           ORDER BY
                 SUM_AMT DESC""",
          """
          SELECT
                 o.CUST_ID
               , COUNT(*) AS CNT
               , SUM(o.AMOUNT) AS SUM_AMT
               , ROUND(AVG(o.AMOUNT), 2) AS AVG_AMT
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID
           ORDER BY
                 SUM_AMT DESC"""),
      new Example("집계", "묶은 뒤 거르기 (HAVING)", "주문이 2건 이상인 고객만. 묶기 전 조건은 WHERE, 묶은 뒤 조건은 HAVING 입니다.",
          """
          SELECT
                 o.CUST_ID
               , COUNT(*) AS CNT
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID
          HAVING COUNT(*) >= 2""",
          """
          SELECT
                 o.CUST_ID
               , COUNT(*) AS CNT
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID
          HAVING COUNT(*) >= 2"""),
      new Example("집계", "조건마다 따로 세기 (CASE 집계)", "한 번 읽어 상태마다 건수를 칸으로 나눕니다.",
          """
          SELECT
                 COUNT(*) AS TOTAL_CNT
               , SUM(CASE WHEN o.STATUS = 'NEW' THEN 1 ELSE 0 END) AS NEW_CNT
               , SUM(CASE WHEN o.STATUS = 'DONE' THEN 1 ELSE 0 END) AS DONE_CNT
               , SUM(CASE WHEN o.STATUS = 'DONE' THEN o.AMOUNT ELSE 0 END) AS DONE_AMT
            FROM ORDERS o""",
          """
          SELECT
                 COUNT(*) AS TOTAL_CNT
               , SUM(CASE WHEN o.STATUS = 'NEW' THEN 1 ELSE 0 END) AS NEW_CNT
               , SUM(CASE WHEN o.STATUS = 'DONE' THEN 1 ELSE 0 END) AS DONE_CNT
               , SUM(CASE WHEN o.STATUS = 'DONE' THEN o.AMOUNT ELSE 0 END) AS DONE_AMT
            FROM ORDERS o"""),
      new Example("집계", "소계 · 총계 줄 (ROLLUP)", "고객 · 상태별 합계 아래에 고객 소계와 맨 끝 총계 줄이 붙습니다. GROUPING 이 1 인 칸이 합계 줄입니다.",
          """
          SELECT
                 CASE WHEN GROUPING(o.CUST_ID) = 1 THEN '총계' ELSE TO_CHAR(o.CUST_ID) END AS CUST
               , CASE WHEN GROUPING(o.STATUS) = 1 THEN '소계' ELSE o.STATUS END AS STATUS
               , SUM(o.AMOUNT) AS SUM_AMT
            FROM ORDERS o
           GROUP BY
                 ROLLUP(o.CUST_ID, o.STATUS)""",
          """
          SELECT
                 CASE WHEN GROUPING(o.CUST_ID) = 1 THEN '총계' ELSE CAST(o.CUST_ID AS VARCHAR) END AS CUST
               , CASE WHEN GROUPING(o.STATUS) = 1 THEN '소계' ELSE o.STATUS END AS STATUS
               , SUM(o.AMOUNT) AS SUM_AMT
            FROM ORDERS o
           GROUP BY
                 ROLLUP(o.CUST_ID, o.STATUS)"""),
      new Example("집계", "모든 조합 합계 (CUBE)", "고객별 · 상태별 · 둘 다 · 전체 합계를 한 번에 봅니다.",
          """
          SELECT
                 o.CUST_ID
               , o.STATUS
               , SUM(o.AMOUNT) AS SUM_AMT
               , GROUPING(o.CUST_ID) AS G_CUST
               , GROUPING(o.STATUS) AS G_STATUS
            FROM ORDERS o
           GROUP BY
                 CUBE(o.CUST_ID, o.STATUS)""",
          """
          SELECT
                 o.CUST_ID
               , o.STATUS
               , SUM(o.AMOUNT) AS SUM_AMT
               , GROUPING(o.CUST_ID) AS G_CUST
               , GROUPING(o.STATUS) AS G_STATUS
            FROM ORDERS o
           GROUP BY
                 CUBE(o.CUST_ID, o.STATUS)"""),
      new Example("집계", "골라 묶기 (GROUPING SETS)", "고객별 합계와 상태별 합계만 위아래로 붙입니다(UNION ALL 두 번 대신).",
          """
          SELECT
                 o.CUST_ID
               , o.STATUS
               , SUM(o.AMOUNT) AS SUM_AMT
            FROM ORDERS o
           GROUP BY
                 GROUPING SETS ((o.CUST_ID), (o.STATUS))""",
          """
          SELECT
                 o.CUST_ID
               , o.STATUS
               , SUM(o.AMOUNT) AS SUM_AMT
            FROM ORDERS o
           GROUP BY
                 GROUPING SETS ((o.CUST_ID), (o.STATUS))"""),
      new Example("분석 함수", "순위 (RANK · DENSE_RANK · ROW_NUMBER)", "같은 금액일 때 RANK 는 1,1,3, DENSE_RANK 는 1,1,2, ROW_NUMBER 는 1,2,3.",
          """
          SELECT
                 o.ORDER_ID
               , o.AMOUNT
               , RANK() OVER (ORDER BY o.AMOUNT DESC) AS RNK
               , DENSE_RANK() OVER (ORDER BY o.AMOUNT DESC) AS DRNK
               , ROW_NUMBER() OVER (ORDER BY o.AMOUNT DESC) AS RN
            FROM ORDERS o""",
          """
          SELECT
                 o.ORDER_ID
               , o.AMOUNT
               , RANK() OVER (ORDER BY o.AMOUNT DESC) AS RNK
               , DENSE_RANK() OVER (ORDER BY o.AMOUNT DESC) AS DRNK
               , ROW_NUMBER() OVER (ORDER BY o.AMOUNT DESC) AS RN
            FROM ORDERS o"""),
      new Example("분석 함수", "그룹 안에서 순위 (PARTITION BY)", "고객마다 금액 순위를 따로 매깁니다. 고객마다 1위만 보려면 바깥에서 RN = 1.",
          """
          SELECT
                 o.CUST_ID
               , o.ORDER_ID
               , o.AMOUNT
               , ROW_NUMBER() OVER (PARTITION BY o.CUST_ID ORDER BY o.AMOUNT DESC) AS RN
            FROM ORDERS o""",
          """
          SELECT
                 o.CUST_ID
               , o.ORDER_ID
               , o.AMOUNT
               , ROW_NUMBER() OVER (PARTITION BY o.CUST_ID ORDER BY o.AMOUNT DESC) AS RN
            FROM ORDERS o"""),
      new Example("분석 함수", "앞 줄 · 뒷 줄 값 (LAG · LEAD)", "주문마다 그 고객의 바로 앞 주문일과 며칠 만에 다시 주문했는지.",
          """
          SELECT
                 o.CUST_ID
               , o.ORDER_DT
               , LAG(o.ORDER_DT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT) AS PREV_DT
               , o.ORDER_DT - LAG(o.ORDER_DT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT) AS GAP_DAYS
               , LEAD(o.ORDER_DT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT) AS NEXT_DT
            FROM ORDERS o""",
          """
          SELECT
                 o.CUST_ID
               , o.ORDER_DT
               , LAG(o.ORDER_DT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT) AS PREV_DT
               , o.ORDER_DT - LAG(o.ORDER_DT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT) AS GAP_DAYS
               , LEAD(o.ORDER_DT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT) AS NEXT_DT
            FROM ORDERS o"""),
      new Example("분석 함수", "누적 합", "주문일 순으로 금액을 쌓아 갑니다. 행 수는 그대로입니다.",
          """
          SELECT
                 o.ORDER_DT
               , o.AMOUNT
               , SUM(o.AMOUNT) OVER (ORDER BY o.ORDER_DT, o.ORDER_ID ROWS UNBOUNDED PRECEDING) AS RUN_AMT
            FROM ORDERS o""",
          """
          SELECT
                 o.ORDER_DT
               , o.AMOUNT
               , SUM(o.AMOUNT) OVER (ORDER BY o.ORDER_DT, o.ORDER_ID ROWS UNBOUNDED PRECEDING) AS RUN_AMT
            FROM ORDERS o"""),
      new Example("분석 함수", "이동 평균 (앞 2줄 + 지금 줄)", "줄마다 바로 앞 두 줄과 자기 줄의 평균. 추세를 볼 때 씁니다.",
          """
          SELECT
                 o.ORDER_DT
               , o.AMOUNT
               , ROUND(AVG(o.AMOUNT) OVER (ORDER BY o.ORDER_DT, o.ORDER_ID ROWS BETWEEN 2 PRECEDING AND CURRENT ROW), 2) AS MOVING_AVG
            FROM ORDERS o""",
          """
          SELECT
                 o.ORDER_DT
               , o.AMOUNT
               , ROUND(AVG(o.AMOUNT) OVER (ORDER BY o.ORDER_DT, o.ORDER_ID ROWS BETWEEN 2 PRECEDING AND CURRENT ROW), 2) AS MOVING_AVG
            FROM ORDERS o"""),
      new Example("분석 함수", "전체 중 비율 (%)", "주문마다 전체 금액 중 차지하는 비율.",
          """
          SELECT
                 o.ORDER_ID
               , o.AMOUNT
               , ROUND(RATIO_TO_REPORT(o.AMOUNT) OVER () * 100, 2) AS PCT
            FROM ORDERS o""",
          """
          SELECT
                 o.ORDER_ID
               , o.AMOUNT
               , ROUND(o.AMOUNT * 100.0 / NULLIF(SUM(o.AMOUNT) OVER (), 0), 2) AS PCT
            FROM ORDERS o"""),
      new Example("분석 함수", "그룹의 첫 값 · 끝 값", "고객마다 첫 주문일과 마지막 주문일을 줄마다 붙입니다.",
          """
          SELECT
                 o.CUST_ID
               , o.ORDER_ID
               , FIRST_VALUE(o.ORDER_DT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT) AS FIRST_DT
               , LAST_VALUE(o.ORDER_DT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS LAST_DT
            FROM ORDERS o""",
          """
          SELECT
                 o.CUST_ID
               , o.ORDER_ID
               , FIRST_VALUE(o.ORDER_DT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT) AS FIRST_DT
               , LAST_VALUE(o.ORDER_DT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS LAST_DT
            FROM ORDERS o"""),
      new Example("분석 함수", "몇 등분으로 나누기 (NTILE)", "금액 순으로 4묶음(사분위)으로 나눕니다. 1 이 가장 큰 묶음입니다.",
          """
          SELECT
                 o.ORDER_ID
               , o.AMOUNT
               , NTILE(4) OVER (ORDER BY o.AMOUNT DESC) AS QUARTILE
            FROM ORDERS o""",
          """
          SELECT
                 o.ORDER_ID
               , o.AMOUNT
               , NTILE(4) OVER (ORDER BY o.AMOUNT DESC) AS QUARTILE
            FROM ORDERS o"""),
      new Example("WITH · 재귀", "단계로 나눠 쓰기 (WITH)", "고객별 합계를 먼저 만들고, 그 결과에서 상위 고객만 고릅니다. 단계마다 따로 실행해 볼 수 있습니다(도구 → WITH 조각 실행).",
          """
          WITH cust_sum AS (
          SELECT
                 o.CUST_ID
               , SUM(o.AMOUNT) AS SUM_AMT
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID
          )
          SELECT
                 s.*
            FROM cust_sum s
           WHERE s.SUM_AMT >= 100000""",
          """
          WITH cust_sum AS (
          SELECT
                 o.CUST_ID
               , SUM(o.AMOUNT) AS SUM_AMT
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID
          )
          SELECT
                 s.*
            FROM cust_sum s
           WHERE s.SUM_AMT >= 100000"""),
      new Example("WITH · 재귀", "1 부터 10 까지 숫자 줄", "표 없이 번호 줄을 만듭니다. 빠진 번호 찾기 · 달력에 씁니다.",
          """
          SELECT
                 LEVEL AS N
            FROM DUAL
          CONNECT BY LEVEL <= 10""",
          """
          SELECT
                 g.N
            FROM GENERATE_SERIES(1, 10) AS g(N)"""),
      new Example("WITH · 재귀", "이번 달 날짜 다 만들기 (달력)", "이번 달 1일부터 말일까지 하루 한 줄. 건수와 바깥 조인하면 0 건인 날도 나옵니다.",
          """
          SELECT
                 TRUNC(SYSDATE, 'MM') + LEVEL - 1 AS DT
            FROM DUAL
          CONNECT BY LEVEL <= TO_NUMBER(TO_CHAR(LAST_DAY(SYSDATE), 'DD'))""",
          """
          SELECT
                 CAST(g.DT AS DATE) AS DT
            FROM GENERATE_SERIES(DATE_TRUNC('month', CURRENT_DATE), DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month - 1 day', INTERVAL '1 day') AS g(DT)"""),
      new Example("WITH · 재귀", "빠진 번호 찾기", "주문 번호 1 부터 가장 큰 번호 사이에서 비어 있는 번호.",
          """
          SELECT
                 n.N AS MISSING_ID
            FROM (
                  SELECT
                         LEVEL AS N
                    FROM DUAL
                 CONNECT BY LEVEL <= (SELECT MAX(x.ORDER_ID) FROM ORDERS x)
                 ) n
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM ORDERS o
                   WHERE o.ORDER_ID = n.N
                 )""",
          """
          SELECT
                 n.N AS MISSING_ID
            FROM GENERATE_SERIES(1, (SELECT MAX(x.ORDER_ID) FROM ORDERS x)) AS n(N)
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM ORDERS o
                   WHERE o.ORDER_ID = n.N
                 )"""),
      new Example("계층", "메뉴 나무 펼치기", "맨 위 메뉴부터 아래로 펼치고 단계(LVL)와 경로(PATH)를 붙입니다. PostgreSQL 판은 부모를 잘못 가리켜 고리가 된 데이터에서도 끝없이 돌지 않게 100 단계에서 멈춥니다.",
          """
          SELECT
                 LEVEL AS LVL
               , LPAD(' ', (LEVEL - 1) * 2) || m.MENU_NAME AS MENU_NAME
               , SYS_CONNECT_BY_PATH(m.MENU_NAME, ' > ') AS PATH
               , m.MENU_ID
            FROM MENU m
           START WITH m.PARENT_ID IS NULL
          CONNECT BY PRIOR m.MENU_ID = m.PARENT_ID
           ORDER SIBLINGS BY
                 m.SORT_NO""",
          """
          WITH RECURSIVE tree AS (
          SELECT
                 1 AS LVL
               , m.MENU_ID
               , m.MENU_NAME
               , CAST(m.MENU_NAME AS VARCHAR(4000)) AS PATH
               , CAST(LPAD(CAST(m.SORT_NO AS VARCHAR), 5, '0') AS VARCHAR(4000)) AS SORT_KEY
            FROM MENU m
           WHERE m.PARENT_ID IS NULL
           UNION ALL
          SELECT
                 t.LVL + 1
               , m.MENU_ID
               , m.MENU_NAME
               , CAST(t.PATH || ' > ' || m.MENU_NAME AS VARCHAR(4000))
               , CAST(t.SORT_KEY || '.' || LPAD(CAST(m.SORT_NO AS VARCHAR), 5, '0') AS VARCHAR(4000))
            FROM MENU m
            JOIN tree t
              ON m.PARENT_ID = t.MENU_ID
           WHERE t.LVL < 100
          )
          SELECT
                 t.LVL
               , REPEAT(' ', (t.LVL - 1) * 2) || t.MENU_NAME AS MENU_NAME
               , t.PATH
               , t.MENU_ID
            FROM tree t
           ORDER BY
                 t.SORT_KEY"""),
      new Example("계층", "한 메뉴 아래 전부", "고른 메뉴의 자식 · 손자까지 모두. 위로 올라가려면 조건 방향을 뒤집습니다. PostgreSQL 판의 UNION(ALL 아님)은 고리 데이터에서 같은 줄을 다시 펼치지 않게 합니다.",
          """
          SELECT
                 m.*
            FROM MENU m
           START WITH m.MENU_ID = :menuId
          CONNECT BY PRIOR m.MENU_ID = m.PARENT_ID""",
          """
          WITH RECURSIVE sub AS (
          SELECT
                 m.*
            FROM MENU m
           WHERE m.MENU_ID = CAST(:menuId AS INTEGER)
           UNION
          SELECT
                 m.*
            FROM MENU m
            JOIN sub s
              ON m.PARENT_ID = s.MENU_ID
          )
          SELECT
                 s.*
            FROM sub s"""),
      new Example("피벗", "월을 가로 칸으로", "올해 주문 금액을 1월 · 2월 · 3월 … 칸으로 나눠 고객마다 한 줄에 놓습니다(앞 석 달만 적었습니다).",
          """
          SELECT
                 o.CUST_ID
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '01' THEN o.AMOUNT END) AS M01
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '02' THEN o.AMOUNT END) AS M02
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '03' THEN o.AMOUNT END) AS M03
            FROM ORDERS o
           WHERE o.ORDER_DT >= TRUNC(SYSDATE, 'YYYY')
           GROUP BY
                 o.CUST_ID""",
          """
          SELECT
                 o.CUST_ID
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '01' THEN o.AMOUNT END) AS M01
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '02' THEN o.AMOUNT END) AS M02
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '03' THEN o.AMOUNT END) AS M03
            FROM ORDERS o
           WHERE o.ORDER_DT >= DATE_TRUNC('year', CURRENT_DATE)
           GROUP BY
                 o.CUST_ID"""),
      new Example("피벗", "가로 칸을 세로 줄로 (언피벗)", "한 줄에 있는 금액 · 수량 두 칸을 (항목, 값) 두 줄로 펼칩니다.",
          """
          SELECT
                 i.ORDER_ID
               , 'QTY' AS ITEM
               , i.QTY AS VAL
            FROM ORDER_ITEM i
           UNION ALL
          SELECT
                 i.ORDER_ID
               , 'PRICE'
               , i.PRICE
            FROM ORDER_ITEM i""",
          """
          SELECT
                 i.ORDER_ID
               , 'QTY' AS ITEM
               , i.QTY AS VAL
            FROM ORDER_ITEM i
           UNION ALL
          SELECT
                 i.ORDER_ID
               , 'PRICE'
               , i.PRICE
            FROM ORDER_ITEM i"""),
      new Example("집합", "위아래로 붙이기 (UNION ALL · UNION)", "두 결과를 이어 붙입니다. UNION 은 겹친 줄을 지우느라 정렬을 한 번 더 하므로, 겹칠 일이 없으면 UNION ALL.",
          """
          SELECT
                 o.ORDER_ID
               , 'NOW' AS SRC
            FROM ORDERS o
           UNION ALL
          SELECT
                 b.ORDER_ID
               , 'BAK'
            FROM ORDERS_BAK b""",
          """
          SELECT
                 o.ORDER_ID
               , 'NOW' AS SRC
            FROM ORDERS o
           UNION ALL
          SELECT
                 b.ORDER_ID
               , 'BAK'
            FROM ORDERS_BAK b"""),
      new Example("집합", "양쪽에 다 있는 것 (INTERSECT)", "주문 표와 백업 표에 둘 다 있는 주문 번호.",
          """
          SELECT
                 o.ORDER_ID
            FROM ORDERS o
          INTERSECT
          SELECT
                 b.ORDER_ID
            FROM ORDERS_BAK b""",
          """
          SELECT
                 o.ORDER_ID
            FROM ORDERS o
          INTERSECT
          SELECT
                 b.ORDER_ID
            FROM ORDERS_BAK b"""),
      new Example("집합", "한쪽에만 있는 것 (MINUS · EXCEPT)", "주문 표에는 있고 백업 표에는 없는 주문 번호. Oracle 은 MINUS, PostgreSQL 은 EXCEPT.",
          """
          SELECT
                 o.ORDER_ID
            FROM ORDERS o
           MINUS
          SELECT
                 b.ORDER_ID
            FROM ORDERS_BAK b""",
          """
          SELECT
                 o.ORDER_ID
            FROM ORDERS o
          EXCEPT
          SELECT
                 b.ORDER_ID
            FROM ORDERS_BAK b"""),
      new Example("페이징 · 개수", "앞에서 10건만", "정렬한 뒤 앞의 10건만 봅니다. 정렬은 안쪽에서 먼저 해야 맞습니다.",
          """
          SELECT *
            FROM (
                  SELECT o.*
                    FROM ORDERS o
                   ORDER BY
                         o.ORDER_DT DESC
                 )
           WHERE ROWNUM <= 10""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           ORDER BY
                 o.ORDER_DT DESC
           LIMIT 10"""),
      new Example("페이징 · 개수", "페이지 나누기 (21~30번째)", "3쪽(한 쪽 10건)을 봅니다. 시작 · 끝 번호를 바꿔 씁니다.",
          """
          SELECT *
            FROM (
                  SELECT q.*
                       , ROWNUM AS RN
                    FROM (
                          SELECT o.*
                            FROM ORDERS o
                           ORDER BY
                                 o.ORDER_ID DESC
                         ) q
                   WHERE ROWNUM <= 30
                 )
           WHERE RN > 20""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           ORDER BY
                 o.ORDER_ID DESC
           LIMIT 10 OFFSET 20"""),
      new Example("페이징 · 개수", "목록과 전체 건수를 한 번에", "줄마다 전체 건수(TOTAL_CNT)를 함께 붙입니다. 페이지 목록 화면에서 건수 쿼리를 따로 안 돌려도 됩니다.",
          """
          SELECT
                 o.*
               , COUNT(*) OVER () AS TOTAL_CNT
            FROM ORDERS o
           WHERE o.STATUS = 'DONE'""",
          """
          SELECT
                 o.*
               , COUNT(*) OVER () AS TOTAL_CNT
            FROM ORDERS o
           WHERE o.STATUS = 'DONE'"""),
      new Example("페이징 · 개수", "줄 번호 붙이기", "정렬 순서대로 1, 2, 3 … 번호를 붙입니다.",
          """
          SELECT
                 ROW_NUMBER() OVER (ORDER BY o.ORDER_DT DESC) AS NO
               , o.*
            FROM ORDERS o""",
          """
          SELECT
                 ROW_NUMBER() OVER (ORDER BY o.ORDER_DT DESC) AS NO
               , o.*
            FROM ORDERS o"""),
      new Example("페이징 · 개수", "다음 쪽 빨리 넘기기 (키 기준)", "OFFSET 은 뒤쪽일수록 느립니다. 앞 쪽 마지막 번호보다 작은 것부터 10건을 읽습니다.",
          """
          SELECT *
            FROM (
                  SELECT o.*
                    FROM ORDERS o
                   WHERE o.ORDER_ID < :lastId
                   ORDER BY
                         o.ORDER_ID DESC
                 )
           WHERE ROWNUM <= 10""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.ORDER_ID < CAST(:lastId AS INTEGER)
           ORDER BY
                 o.ORDER_ID DESC
           LIMIT 10"""),
      new Example("날짜", "오늘 들어온 것", "오늘 0시부터 내일 0시 전까지. 칼럼에 함수를 씌우지 않아 색인을 탈 수 있습니다.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.ORDER_DT >= TRUNC(SYSDATE)
             AND o.ORDER_DT < TRUNC(SYSDATE) + 1""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.ORDER_DT >= CURRENT_DATE
             AND o.ORDER_DT < CURRENT_DATE + 1"""),
      new Example("날짜", "최근 7일", "오늘을 포함한 7일입니다.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.ORDER_DT >= TRUNC(SYSDATE) - 6""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.ORDER_DT >= CURRENT_DATE - 6"""),
      new Example("날짜", "이번 달 · 지난달", "이번 달 1일부터 다음 달 1일 전까지(지난달은 주석 줄).",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.ORDER_DT >= TRUNC(SYSDATE, 'MM')
             AND o.ORDER_DT < ADD_MONTHS(TRUNC(SYSDATE, 'MM'), 1)
          -- 지난달: >= ADD_MONTHS(TRUNC(SYSDATE, 'MM'), -1) AND < TRUNC(SYSDATE, 'MM')""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.ORDER_DT >= DATE_TRUNC('month', CURRENT_DATE)
             AND o.ORDER_DT < DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month'
          -- 지난달: >= DATE_TRUNC('month', CURRENT_DATE) - INTERVAL '1 month' AND < DATE_TRUNC('month', CURRENT_DATE)"""),
      new Example("날짜", "날짜 범위 (끝일 하루 전체 포함)", "BETWEEN 은 끝일 0시까지만 잡혀 그날 낮의 행이 빠집니다. 끝일 + 1 미만으로 씁니다.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.ORDER_DT >= TO_DATE(:fromDt, 'YYYY-MM-DD')
             AND o.ORDER_DT < TO_DATE(:toDt, 'YYYY-MM-DD') + 1""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.ORDER_DT >= CAST(:fromDt AS DATE)
             AND o.ORDER_DT < CAST(:toDt AS DATE) + 1"""),
      new Example("날짜", "날짜 ↔ 글자 바꾸기", "날짜를 '2026-11-07 14:30' 꼴 글자로, 글자를 날짜로.",
          """
          SELECT
                 TO_CHAR(o.ORDER_DT, 'YYYY-MM-DD HH24:MI') AS ORDER_DT_TEXT
               , TO_DATE('2026-11-07', 'YYYY-MM-DD') AS SOME_DAY
            FROM ORDERS o""",
          """
          SELECT
                 TO_CHAR(o.ORDER_DT, 'YYYY-MM-DD HH24:MI') AS ORDER_DT_TEXT
               , TO_DATE('2026-11-07', 'YYYY-MM-DD') AS SOME_DAY
            FROM ORDERS o"""),
      new Example("날짜", "날짜 더하기 · 빼기 · 차이", "3일 뒤, 2달 뒤, 두 날짜 사이 일수.",
          """
          SELECT
                 o.ORDER_DT + 3 AS AFTER_3_DAYS
               , ADD_MONTHS(o.ORDER_DT, 2) AS AFTER_2_MONTHS
               , TRUNC(SYSDATE) - TRUNC(o.ORDER_DT) AS DAYS_AGO
            FROM ORDERS o""",
          """
          SELECT
                 o.ORDER_DT + INTERVAL '3 day' AS AFTER_3_DAYS
               , o.ORDER_DT + INTERVAL '2 month' AS AFTER_2_MONTHS
               , CURRENT_DATE - CAST(o.ORDER_DT AS DATE) AS DAYS_AGO
            FROM ORDERS o"""),
      new Example("날짜", "요일 · 주말 거르기", "요일 이름을 붙이고 토 · 일은 뺍니다.",
          """
          SELECT
                 o.ORDER_DT
               , TO_CHAR(o.ORDER_DT, 'DY', 'NLS_DATE_LANGUAGE=KOREAN') AS DOW
            FROM ORDERS o
           WHERE TO_CHAR(o.ORDER_DT, 'DY', 'NLS_DATE_LANGUAGE=ENGLISH') NOT IN ('SAT', 'SUN')""",
          """
          SELECT
                 o.ORDER_DT
               , TO_CHAR(o.ORDER_DT, 'Dy') AS DOW
            FROM ORDERS o
           WHERE EXTRACT(ISODOW FROM o.ORDER_DT) < 6"""),
      new Example("날짜", "그달 말일 · 다음 달 1일", "날짜가 든 달의 마지막 날과 다음 달 첫날.",
          """
          SELECT
                 o.ORDER_DT
               , LAST_DAY(o.ORDER_DT) AS MONTH_END
               , TRUNC(ADD_MONTHS(o.ORDER_DT, 1), 'MM') AS NEXT_MONTH
            FROM ORDERS o""",
          """
          SELECT
                 o.ORDER_DT
               , CAST(DATE_TRUNC('month', o.ORDER_DT) + INTERVAL '1 month - 1 day' AS DATE) AS MONTH_END
               , CAST(DATE_TRUNC('month', o.ORDER_DT) + INTERVAL '1 month' AS DATE) AS NEXT_MONTH
            FROM ORDERS o"""),
      new Example("날짜", "몇 달 · 몇 년 지났는지", "가입일부터 오늘까지 지난 개월 수와 햇수(만).",
          """
          SELECT
                 c.CUST_ID
               , TRUNC(MONTHS_BETWEEN(SYSDATE, c.REG_DT)) AS MONTHS
               , TRUNC(MONTHS_BETWEEN(SYSDATE, c.REG_DT) / 12) AS YEARS
            FROM CUSTOMER c""",
          """
          SELECT
                 c.CUST_ID
               , EXTRACT(YEAR FROM AGE(CURRENT_DATE, c.REG_DT)) * 12 + EXTRACT(MONTH FROM AGE(CURRENT_DATE, c.REG_DT)) AS MONTHS
               , EXTRACT(YEAR FROM AGE(CURRENT_DATE, c.REG_DT)) AS YEARS
            FROM CUSTOMER c"""),
      new Example("날짜", "분기 · 주 단위로 묶기", "분기마다, 주(월요일 시작)마다 건수.",
          """
          SELECT
                 TO_CHAR(o.ORDER_DT, 'YYYY') || '-Q' || TO_CHAR(o.ORDER_DT, 'Q') AS QTR
               , TRUNC(o.ORDER_DT, 'IW') AS WEEK_START
               , COUNT(*) AS CNT
            FROM ORDERS o
           GROUP BY
                 TO_CHAR(o.ORDER_DT, 'YYYY') || '-Q' || TO_CHAR(o.ORDER_DT, 'Q')
               , TRUNC(o.ORDER_DT, 'IW')""",
          """
          SELECT
                 TO_CHAR(o.ORDER_DT, 'YYYY') || '-Q' || TO_CHAR(o.ORDER_DT, 'Q') AS QTR
               , CAST(DATE_TRUNC('week', o.ORDER_DT) AS DATE) AS WEEK_START
               , COUNT(*) AS CNT
            FROM ORDERS o
           GROUP BY
                 TO_CHAR(o.ORDER_DT, 'YYYY') || '-Q' || TO_CHAR(o.ORDER_DT, 'Q')
               , CAST(DATE_TRUNC('week', o.ORDER_DT) AS DATE)"""),
      new Example("날짜", "지금 시각 · 시간 더하기", "지금 시각, 2시간 뒤, 30분 전.",
          """
          SELECT
                 SYSDATE AS NOW
               , SYSDATE + 2 / 24 AS AFTER_2H
               , SYSDATE - 30 / 1440 AS BEFORE_30M
            FROM DUAL""",
          """
          SELECT
                 LOCALTIMESTAMP AS NOW
               , LOCALTIMESTAMP + INTERVAL '2 hours' AS AFTER_2H
               , LOCALTIMESTAMP - INTERVAL '30 minutes' AS BEFORE_30M"""),
      new Example("조건", "튜플 IN (두 칼럼을 짝으로)", "(주문번호, 줄번호) 짝이 목록 중 하나와 같은 행. 칼럼마다 IN 을 따로 쓰면 엉뚱한 짝도 걸립니다. 도구 → IN 목록 만들기에서 엑셀 값을 붙여 만들 수 있습니다.",
          """
          SELECT
                 i.*
            FROM ORDER_ITEM i
           WHERE (i.ORDER_ID, i.LINE_NO) IN ((1001, 1), (1001, 2), (1002, 1))""",
          """
          SELECT
                 i.*
            FROM ORDER_ITEM i
           WHERE (i.ORDER_ID, i.LINE_NO) IN ((1001, 1), (1001, 2), (1002, 1))"""),
      new Example("조건", "서브쿼리로 IN", "다른 표에서 고른 값 목록으로 거릅니다.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.CUST_ID IN (
                  SELECT c.CUST_ID
                    FROM CUSTOMER c
                   WHERE c.GRADE = 'VIP'
                 )""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.CUST_ID IN (
                  SELECT c.CUST_ID
                    FROM CUSTOMER c
                   WHERE c.GRADE = 'VIP'
                 )"""),
      new Example("조건", "값이 있을 때만 거르기 (선택 조건)", "검색 화면에서 비워 두면 조건을 건너뜁니다. MyBatis 라면 <if> 를 쓰는 편이 빠릅니다.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE (:status IS NULL OR o.STATUS = :status)""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE (CAST(:status AS VARCHAR) IS NULL OR o.STATUS = :status)"""),
      new Example("값 바꾸기", "NULL 이면 다른 값", "비어 있으면 0 이나 '-' 로 보입니다.",
          """
          SELECT
                 NVL(o.AMOUNT, 0) AS AMOUNT
               , NVL(o.MEMO, '-') AS MEMO
            FROM ORDERS o""",
          """
          SELECT
                 COALESCE(o.AMOUNT, 0) AS AMOUNT
               , COALESCE(o.MEMO, '-') AS MEMO
            FROM ORDERS o"""),
      new Example("값 바꾸기", "코드 → 이름 (CASE)", "상태 코드를 화면 글로 바꿉니다.",
          """
          SELECT
                 o.ORDER_ID
               , CASE o.STATUS
                      WHEN 'R' THEN '접수'
                      WHEN 'P' THEN '진행'
                      WHEN 'D' THEN '완료'
                      ELSE '기타'
                 END AS STATUS_NAME
            FROM ORDERS o""",
          """
          SELECT
                 o.ORDER_ID
               , CASE o.STATUS
                      WHEN 'R' THEN '접수'
                      WHEN 'P' THEN '진행'
                      WHEN 'D' THEN '완료'
                      ELSE '기타'
                 END AS STATUS_NAME
            FROM ORDERS o"""),
      new Example("값 바꾸기", "여러 줄을 한 칸에 합치기", "고객마다 주문번호를 쉼표로 이어 한 칸에 둡니다.",
          """
          SELECT
                 o.CUST_ID
               , LISTAGG(o.ORDER_ID, ', ') WITHIN GROUP (ORDER BY o.ORDER_ID) AS ORDER_IDS
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID""",
          """
          SELECT
                 o.CUST_ID
               , STRING_AGG(CAST(o.ORDER_ID AS VARCHAR), ', ' ORDER BY o.ORDER_ID) AS ORDER_IDS
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID"""),
      new Example("값 바꾸기", "글자 자르기 · 붙이기 · 공백 빼기", "앞 3글자, 이어 붙이기, 앞뒤 공백 빼기, 길이.",
          """
          SELECT
                 SUBSTR(c.CUST_NAME, 1, 3) AS HEAD
               , c.CUST_NAME || '(' || c.CUST_ID || ')' AS LABEL
               , TRIM(c.CUST_NAME) AS TRIMMED
               , LENGTH(c.CUST_NAME) AS LEN
            FROM CUSTOMER c""",
          """
          SELECT
                 SUBSTR(c.CUST_NAME, 1, 3) AS HEAD
               , c.CUST_NAME || '(' || c.CUST_ID || ')' AS LABEL
               , TRIM(c.CUST_NAME) AS TRIMMED
               , LENGTH(c.CUST_NAME) AS LEN
            FROM CUSTOMER c"""),
      new Example("값 바꾸기", "반올림 · 버림 · 천 단위 쉼표", "소수 둘째 자리 반올림, 버림, 1,234,567 꼴 글자.",
          """
          SELECT
                 ROUND(o.AMOUNT, 2) AS ROUNDED
               , TRUNC(o.AMOUNT) AS CUT
               , TO_CHAR(o.AMOUNT, 'FM999,999,999,990') AS WITH_COMMA
            FROM ORDERS o""",
          """
          SELECT
                 ROUND(o.AMOUNT, 2) AS ROUNDED
               , TRUNC(o.AMOUNT) AS CUT
               , TO_CHAR(o.AMOUNT, 'FM999,999,999,990') AS WITH_COMMA
            FROM ORDERS o"""),
      new Example("문자", "대문자 · 소문자 · 첫 글자만 대문자", "이름을 바꿔 보입니다.",
          """
          SELECT
                 UPPER(c.CUST_NAME) AS UP
               , LOWER(c.CUST_NAME) AS LOW
               , INITCAP(c.CUST_NAME) AS CAP
            FROM CUSTOMER c""",
          """
          SELECT
                 UPPER(c.CUST_NAME) AS UP
               , LOWER(c.CUST_NAME) AS LOW
               , INITCAP(c.CUST_NAME) AS CAP
            FROM CUSTOMER c"""),
      new Example("문자", "앞자리 0 채우기", "번호를 6자리로 맞춥니다(7 → 000007).",
          """
          SELECT
                 LPAD(TO_CHAR(o.ORDER_ID), 6, '0') AS ORDER_NO
            FROM ORDERS o""",
          """
          SELECT
                 LPAD(CAST(o.ORDER_ID AS VARCHAR), 6, '0') AS ORDER_NO
            FROM ORDERS o"""),
      new Example("문자", "글자 바꾸기 · 지우기", "하이픈을 지우고, 줄바꿈을 빈칸으로 바꿉니다.",
          """
          SELECT
                 REPLACE(o.MEMO, '-', '') AS NO_DASH
               , REPLACE(REPLACE(o.MEMO, CHR(13), ''), CHR(10), ' ') AS ONE_LINE
            FROM ORDERS o""",
          """
          SELECT
                 REPLACE(o.MEMO, '-', '') AS NO_DASH
               , REPLACE(REPLACE(o.MEMO, CHR(13), ''), CHR(10), ' ') AS ONE_LINE
            FROM ORDERS o"""),
      new Example("문자", "정규식으로 찾기 · 꺼내기", "메모에 숫자가 있는 줄만, 그리고 첫 숫자 덩어리를 꺼냅니다.",
          """
          SELECT
                 o.MEMO
               , REGEXP_SUBSTR(o.MEMO, '[0-9]+') AS FIRST_NUM
            FROM ORDERS o
           WHERE REGEXP_LIKE(o.MEMO, '[0-9]')""",
          """
          SELECT
                 o.MEMO
               , SUBSTRING(o.MEMO FROM '[0-9]+') AS FIRST_NUM
            FROM ORDERS o
           WHERE o.MEMO ~ '[0-9]'"""),
      new Example("문자", "쉼표 글자를 여러 줄로 나누기", "'A,B,C' 한 칸을 A · B · C 세 줄로 펼칩니다.",
          """
          SELECT
                 REGEXP_SUBSTR(:csv, '[^,]+', 1, LEVEL) AS VAL
            FROM DUAL
          CONNECT BY REGEXP_SUBSTR(:csv, '[^,]+', 1, LEVEL) IS NOT NULL""",
          """
          SELECT
                 TRIM(s.VAL) AS VAL
            FROM UNNEST(STRING_TO_ARRAY(:csv, ',')) AS s(VAL)"""),
      new Example("문자", "글자 수 · 바이트 수", "한글은 글자 수와 바이트 수가 다릅니다. 칼럼 길이를 넘는지 볼 때 씁니다.",
          """
          SELECT
                 c.CUST_NAME
               , LENGTH(c.CUST_NAME) AS CHARS
               , LENGTHB(c.CUST_NAME) AS BYTES
            FROM CUSTOMER c""",
          """
          SELECT
                 c.CUST_NAME
               , LENGTH(c.CUST_NAME) AS CHARS
               , OCTET_LENGTH(c.CUST_NAME) AS BYTES
            FROM CUSTOMER c"""),
      new Example("숫자", "0 으로 나누기 막기", "나누는 값이 0 이면 오류 대신 NULL.",
          """
          SELECT
                 i.ORDER_ID
               , i.PRICE / NULLIF(i.QTY, 0) AS UNIT
            FROM ORDER_ITEM i""",
          """
          SELECT
                 i.ORDER_ID
               , i.PRICE / NULLIF(i.QTY, 0) AS UNIT
            FROM ORDER_ITEM i"""),
      new Example("숫자", "나머지 · 짝수 홀수", "번호가 짝수인 줄만.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE MOD(o.ORDER_ID, 2) = 0""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE MOD(o.ORDER_ID, 2) = 0"""),
      new Example("숫자", "올림 · 내림 · 단위 맞추기", "올림, 내림, 백 원 단위 버림.",
          """
          SELECT
                 o.AMOUNT
               , CEIL(o.AMOUNT / 1000) AS UP_K
               , FLOOR(o.AMOUNT / 1000) AS DOWN_K
               , TRUNC(o.AMOUNT, -2) AS CUT_100
            FROM ORDERS o""",
          """
          SELECT
                 o.AMOUNT
               , CEIL(o.AMOUNT / 1000) AS UP_K
               , FLOOR(o.AMOUNT / 1000) AS DOWN_K
               , TRUNC(o.AMOUNT, -2) AS CUT_100
            FROM ORDERS o"""),
      new Example("NULL", "비어 있는 것 찾기 (IS NULL)", "= NULL 은 늘 거짓이라 아무것도 안 나옵니다. IS NULL 로 씁니다.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.MEMO IS NULL""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.MEMO IS NULL"""),
      new Example("NULL", "빈 값을 맨 뒤로 정렬", "NULL 을 정렬 끝에 둡니다. Oracle 내림차순은 NULL 이 맨 앞이 기본입니다.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           ORDER BY
                 o.AMOUNT DESC NULLS LAST""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           ORDER BY
                 o.AMOUNT DESC NULLS LAST"""),
      new Example("NULL", "빈 글자와 NULL 같이 보기", "Oracle 은 '' 를 NULL 로 보지만 PostgreSQL 은 다릅니다. 공백만 있는 값까지 비었다고 봅니다.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE TRIM(o.MEMO) IS NULL""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE NULLIF(TRIM(o.MEMO), '') IS NULL"""),
      new Example("넣기 · 고치기", "다른 표에서 골라 넣기", "SELECT 결과를 그대로 다른 표에 넣습니다(백업 표 만들기 등).",
          """
          INSERT INTO ORDERS_BAK (ORDER_ID, CUST_ID, AMOUNT)
          SELECT
                 o.ORDER_ID
               , o.CUST_ID
               , o.AMOUNT
            FROM ORDERS o
           WHERE o.ORDER_DT < TO_DATE('2026-01-01', 'YYYY-MM-DD')""",
          """
          INSERT INTO ORDERS_BAK (ORDER_ID, CUST_ID, AMOUNT)
          SELECT
                 o.ORDER_ID
               , o.CUST_ID
               , o.AMOUNT
            FROM ORDERS o
           WHERE o.ORDER_DT < DATE '2026-01-01'"""),
      new Example("넣기 · 고치기", "다른 표 값으로 고치기", "고객 표의 등급을 주문 표에 옮겨 적습니다. 맞는 짝이 있는 행만 고칩니다.",
          """
          UPDATE ORDERS o
             SET o.GRADE = (
                  SELECT c.GRADE
                    FROM CUSTOMER c
                   WHERE c.CUST_ID = o.CUST_ID
                 )
           WHERE EXISTS (
                  SELECT 1
                    FROM CUSTOMER c
                   WHERE c.CUST_ID = o.CUST_ID
                 )""",
          """
          UPDATE ORDERS o
             SET GRADE = c.GRADE
            FROM CUSTOMER c
           WHERE c.CUST_ID = o.CUST_ID"""),
      new Example("넣기 · 고치기", "한 줄 넣기", "칼럼 이름을 꼭 적습니다. 표에 칼럼이 늘어도 깨지지 않습니다.",
          """
          INSERT INTO ORDERS (
                 ORDER_ID
               , CUST_ID
               , AMOUNT
               , STATUS
               , ORDER_DT
          ) VALUES (
                 ORDERS_SEQ.NEXTVAL
               , :custId
               , :amount
               , 'NEW'
               , SYSDATE
          )""",
          """
          INSERT INTO ORDERS (
                 ORDER_ID
               , CUST_ID
               , AMOUNT
               , STATUS
               , ORDER_DT
          ) VALUES (
                 NEXTVAL('ORDERS_SEQ')
               , CAST(:custId AS INTEGER)
               , CAST(:amount AS NUMERIC)
               , 'NEW'
               , CURRENT_DATE
          )"""),
      new Example("넣기 · 고치기", "여러 줄 한 번에 넣기", "값 줄 여러 개를 한 문장으로 넣습니다.",
          """
          INSERT ALL
            INTO MENU (MENU_ID, PARENT_ID, MENU_NAME, SORT_NO) VALUES (101, 1, '보고서', 3)
            INTO MENU (MENU_ID, PARENT_ID, MENU_NAME, SORT_NO) VALUES (102, 101, '월 보고서', 1)
          SELECT 1 FROM DUAL""",
          """
          INSERT INTO MENU (MENU_ID, PARENT_ID, MENU_NAME, SORT_NO)
          VALUES (101, 1, '보고서', 3)
               , (102, 101, '월 보고서', 1)"""),
      new Example("넣기 · 고치기", "조건에 맞는 줄 고치기", "고치기 전에 같은 WHERE 로 SELECT 해서 건수를 먼저 보세요(도구 → SELECT → UPDATE).",
          """
          UPDATE ORDERS
             SET STATUS = 'DONE'
           WHERE STATUS = 'WORK'
             AND ORDER_DT < TRUNC(SYSDATE) - 30""",
          """
          UPDATE ORDERS
             SET STATUS = 'DONE'
           WHERE STATUS = 'WORK'
             AND ORDER_DT < CURRENT_DATE - 30"""),
      new Example("넣기 · 고치기", "조건에 맞는 줄 지우기", "지우기 전에 같은 WHERE 로 건수를 먼저 보세요. WHERE 를 빼면 표 전체가 지워집니다.",
          """
          DELETE FROM ORDERS_BAK
           WHERE ORDER_DT < ADD_MONTHS(TRUNC(SYSDATE), -12)""",
          """
          DELETE FROM ORDERS_BAK
           WHERE ORDER_DT < CURRENT_DATE - INTERVAL '12 months'"""),
      new Example("넣기 · 고치기", "있으면 고치고 없으면 넣기 (MERGE)", "백업 표에 같은 주문이 있으면 금액을 고치고, 없으면 넣습니다. PostgreSQL 은 15 판부터 MERGE 가 됩니다(그 전 판은 ON CONFLICT 로, 기준 칼럼에 고유 키가 있어야 합니다).",
          """
          MERGE INTO ORDERS_BAK b
          USING ORDERS o
             ON (b.ORDER_ID = o.ORDER_ID)
           WHEN MATCHED THEN
                UPDATE SET b.AMOUNT = o.AMOUNT
           WHEN NOT MATCHED THEN
                INSERT (ORDER_ID, CUST_ID, AMOUNT, STATUS, ORDER_DT)
                VALUES (o.ORDER_ID, o.CUST_ID, o.AMOUNT, o.STATUS, o.ORDER_DT)""",
          """
          MERGE INTO ORDERS_BAK b
          USING ORDERS o
             ON b.ORDER_ID = o.ORDER_ID
           WHEN MATCHED THEN
                UPDATE SET AMOUNT = o.AMOUNT
           WHEN NOT MATCHED THEN
                INSERT (ORDER_ID, CUST_ID, AMOUNT, STATUS, ORDER_DT)
                VALUES (o.ORDER_ID, o.CUST_ID, o.AMOUNT, o.STATUS, o.ORDER_DT)"""),
      new Example("넣기 · 고치기", "고치기 전에 표 통째로 복사해 두기", "작업 전 백업 표를 만듭니다. 색인 · 제약은 따라오지 않습니다.",
          """
          CREATE TABLE ORDERS_BACKUP AS
          SELECT
                 o.*
            FROM ORDERS o""",
          """
          CREATE TABLE ORDERS_BACKUP AS
          SELECT
                 o.*
            FROM ORDERS o"""),
      new Example("표 만들기 · 바꾸기", "표 만들기 (기본 키 포함)", "칼럼 · 기본 키 · 기본값을 함께 정합니다.",
          """
          CREATE TABLE ORDER_NOTE (
                 NOTE_ID      NUMBER(10)      NOT NULL
               , ORDER_ID     NUMBER(10)      NOT NULL
               , NOTE_TEXT    VARCHAR2(1000)
               , REG_DT       DATE            DEFAULT SYSDATE NOT NULL
               , CONSTRAINT PK_ORDER_NOTE PRIMARY KEY (NOTE_ID)
          )""",
          """
          CREATE TABLE ORDER_NOTE (
                 NOTE_ID      INTEGER         NOT NULL
               , ORDER_ID     INTEGER         NOT NULL
               , NOTE_TEXT    VARCHAR(1000)
               , REG_DT       TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL
               , CONSTRAINT PK_ORDER_NOTE PRIMARY KEY (NOTE_ID)
          )"""),
      new Example("표 만들기 · 바꾸기", "칼럼 더하기 · 길이 늘리기", "칼럼을 하나 더하고, 다른 칼럼의 길이를 늘립니다.",
          """
          ALTER TABLE ORDERS ADD (DELIVERY_DT DATE)
          ;
          ALTER TABLE ORDERS MODIFY (MEMO VARCHAR2(2000))""",
          """
          ALTER TABLE ORDERS ADD COLUMN DELIVERY_DT DATE
          ;
          ALTER TABLE ORDERS ALTER COLUMN MEMO TYPE VARCHAR(2000)"""),
      new Example("표 만들기 · 바꾸기", "색인 만들기", "자주 거르는 칼럼에 색인을 겁니다. 여러 칼럼이면 같다(=)로 거르는 칼럼을 앞에.",
          """
          CREATE INDEX IX_ORDERS_CUST_DT ON ORDERS (CUST_ID, ORDER_DT)""",
          """
          CREATE INDEX IX_ORDERS_CUST_DT ON ORDERS (CUST_ID, ORDER_DT)"""),
      new Example("표 만들기 · 바꾸기", "표 · 칼럼 설명 달기", "표와 칼럼에 한글 설명을 답니다. 작업대 · 자동 완성에 이 설명이 보입니다.",
          """
          COMMENT ON TABLE ORDERS IS '주문'
          ;
          COMMENT ON COLUMN ORDERS.ORDER_DT IS '주문일'""",
          """
          COMMENT ON TABLE ORDERS IS '주문'
          ;
          COMMENT ON COLUMN ORDERS.ORDER_DT IS '주문일'"""),
      new Example("표 만들기 · 바꾸기", "외래 키 걸기", "주문 상세의 주문 번호가 주문 표에 꼭 있도록 묶습니다.",
          """
          ALTER TABLE ORDER_ITEM ADD CONSTRAINT FK_ORDER_ITEM_ORDER FOREIGN KEY (ORDER_ID) REFERENCES ORDERS (ORDER_ID)""",
          """
          ALTER TABLE ORDER_ITEM ADD CONSTRAINT FK_ORDER_ITEM_ORDER FOREIGN KEY (ORDER_ID) REFERENCES ORDERS (ORDER_ID)"""),
      new Example("표 · 칼럼 정보", "표의 칼럼 목록과 설명", "표 이름을 바꿔 실행하면 칼럼 · 형식 · 설명을 봅니다.",
          """
          SELECT
                 c.COLUMN_ID
               , c.COLUMN_NAME
               , c.DATA_TYPE
               , c.DATA_LENGTH
               , c.NULLABLE
               , m.COMMENTS
            FROM USER_TAB_COLUMNS c
               , USER_COL_COMMENTS m
           WHERE c.TABLE_NAME = 'ORDERS'
             AND c.TABLE_NAME = m.TABLE_NAME(+)
             AND c.COLUMN_NAME = m.COLUMN_NAME(+)
           ORDER BY
                 c.COLUMN_ID""",
          """
          SELECT
                 c.ORDINAL_POSITION
               , c.COLUMN_NAME
               , c.DATA_TYPE
               , c.CHARACTER_MAXIMUM_LENGTH
               , c.IS_NULLABLE
               , COL_DESCRIPTION(CAST(c.TABLE_NAME AS REGCLASS), c.ORDINAL_POSITION) AS COMMENTS
            FROM INFORMATION_SCHEMA.COLUMNS c
           WHERE c.TABLE_NAME = 'orders'
           ORDER BY
                 c.ORDINAL_POSITION"""),
      new Example("표 · 칼럼 정보", "칼럼 이름으로 표 찾기", "칼럼 이름에 글자가 들어간 표를 모두 찾습니다.",
          """
          SELECT
                 c.TABLE_NAME
               , c.COLUMN_NAME
            FROM USER_TAB_COLUMNS c
           WHERE c.COLUMN_NAME LIKE '%CUST%'
           ORDER BY
                 c.TABLE_NAME""",
          """
          SELECT
                 c.TABLE_NAME
               , c.COLUMN_NAME
            FROM INFORMATION_SCHEMA.COLUMNS c
           WHERE c.TABLE_SCHEMA = CURRENT_SCHEMA()
             AND c.COLUMN_NAME LIKE '%cust%'
           ORDER BY
                 c.TABLE_NAME"""),
      new Example("표 · 칼럼 정보", "다음 번호 (시퀀스)", "시퀀스에서 다음 번호를 받습니다.",
          """
          SELECT ORDERS_SEQ.NEXTVAL AS NEXT_ID
            FROM DUAL""",
          """
          SELECT NEXTVAL('orders_seq') AS NEXT_ID"""),
      new Example("표 · 칼럼 정보", "표의 색인 목록", "표에 걸린 색인과 칼럼 순서.",
          """
          SELECT
                 ic.INDEX_NAME
               , ic.COLUMN_POSITION
               , ic.COLUMN_NAME
               , i.UNIQUENESS
            FROM USER_INDEXES i, USER_IND_COLUMNS ic
           WHERE i.INDEX_NAME = ic.INDEX_NAME
             AND i.TABLE_NAME = UPPER(:tableName)
           ORDER BY
                 ic.INDEX_NAME
               , ic.COLUMN_POSITION""",
          """
          SELECT
                 i.INDEXNAME AS INDEX_NAME
               , i.INDEXDEF
            FROM PG_INDEXES i
           WHERE i.SCHEMANAME = CURRENT_SCHEMA()
             AND i.TABLENAME = LOWER(:tableName)
           ORDER BY
                 i.INDEXNAME"""),
      new Example("표 · 칼럼 정보", "기본 키 · 외래 키 목록", "표에 걸린 제약 조건과 가리키는 표.",
          """
          SELECT
                 c.CONSTRAINT_NAME
               , c.CONSTRAINT_TYPE
               , cc.COLUMN_NAME
               , r.TABLE_NAME AS REF_TABLE
            FROM USER_CONSTRAINTS c, USER_CONS_COLUMNS cc, USER_CONSTRAINTS r
           WHERE c.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
             AND c.R_CONSTRAINT_NAME = r.CONSTRAINT_NAME(+)
             AND c.TABLE_NAME = UPPER(:tableName)
             AND c.CONSTRAINT_TYPE IN ('P', 'R', 'U')
           ORDER BY
                 c.CONSTRAINT_NAME
               , cc.POSITION""",
          """
          SELECT
                 tc.CONSTRAINT_NAME
               , tc.CONSTRAINT_TYPE
               , kcu.COLUMN_NAME
               , ccu.TABLE_NAME AS REF_TABLE
            FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
            JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
              ON kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
             AND kcu.TABLE_SCHEMA = tc.TABLE_SCHEMA
            LEFT JOIN INFORMATION_SCHEMA.CONSTRAINT_COLUMN_USAGE ccu
              ON ccu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
             AND tc.CONSTRAINT_TYPE = 'FOREIGN KEY'
           WHERE tc.TABLE_SCHEMA = CURRENT_SCHEMA()
             AND tc.TABLE_NAME = LOWER(:tableName)
           ORDER BY
                 tc.CONSTRAINT_NAME
               , kcu.ORDINAL_POSITION"""),
      new Example("표 · 칼럼 정보", "표 크기 (용량 큰 순)", "표가 디스크를 얼마나 쓰는지 큰 순으로.",
          """
          SELECT
                 s.SEGMENT_NAME
               , s.SEGMENT_TYPE
               , ROUND(SUM(s.BYTES) / 1024 / 1024, 1) AS MB
            FROM USER_SEGMENTS s
           GROUP BY
                 s.SEGMENT_NAME
               , s.SEGMENT_TYPE
           ORDER BY
                 MB DESC""",
          """
          SELECT
                 c.RELNAME AS TABLE_NAME
               , ROUND(PG_TOTAL_RELATION_SIZE(c.OID) / 1024.0 / 1024, 1) AS MB
            FROM PG_CLASS c
            JOIN PG_NAMESPACE n
              ON n.OID = c.RELNAMESPACE
           WHERE n.NSPNAME = CURRENT_SCHEMA()
             AND c.RELKIND = 'r'
           ORDER BY
                 MB DESC"""),
      new Example("표 · 칼럼 정보", "표 건수 어림 (통계 기준)", "COUNT(*) 없이 통계에 적힌 건수를 봅니다. 통계를 모은 때 기준이라 정확하지 않습니다.",
          """
          SELECT
                 t.TABLE_NAME
               , t.NUM_ROWS
               , t.LAST_ANALYZED
            FROM USER_TABLES t
           ORDER BY
                 t.NUM_ROWS DESC NULLS LAST""",
          """
          SELECT
                 s.RELNAME AS TABLE_NAME
               , s.N_LIVE_TUP AS NUM_ROWS
               , s.LAST_ANALYZE
            FROM PG_STAT_USER_TABLES s
           ORDER BY
                 s.N_LIVE_TUP DESC"""),
      new Example("표 · 칼럼 정보", "뷰 · 함수 소스에서 글자 찾기", "어느 뷰 · 프로시저가 이 표를 쓰는지 찾습니다.",
          """
          SELECT
                 s.NAME
               , s.TYPE
               , s.LINE
               , s.TEXT
            FROM USER_SOURCE s
           WHERE UPPER(s.TEXT) LIKE '%' || UPPER(:keyword) || '%'
           ORDER BY
                 s.NAME
               , s.LINE""",
          """
          SELECT
                 p.PRONAME AS NAME
               , 'FUNCTION' AS TYPE
            FROM PG_PROC p
            JOIN PG_NAMESPACE n
              ON n.OID = p.PRONAMESPACE
           WHERE n.NSPNAME = CURRENT_SCHEMA()
             AND p.PROSRC ILIKE '%' || :keyword || '%'
           UNION ALL
          SELECT
                 v.VIEWNAME
               , 'VIEW'
            FROM PG_VIEWS v
           WHERE v.SCHEMANAME = CURRENT_SCHEMA()
             AND v.DEFINITION ILIKE '%' || :keyword || '%'"""),
      new Example("세션 · 락", "지금 돌고 있는 쿼리", "이 DB 에서 지금 실행 중인 세션과 문장. 볼 권한이 있어야 합니다.",
          """
          SELECT
                 s.SID
               , s.SERIAL#
               , s.USERNAME
               , s.STATUS
               , s.LAST_CALL_ET AS SEC
               , q.SQL_TEXT
            FROM V$SESSION s, V$SQL q
           WHERE s.SQL_ID = q.SQL_ID(+)
             AND s.SQL_CHILD_NUMBER = q.CHILD_NUMBER(+)
             AND s.STATUS = 'ACTIVE'
             AND s.TYPE = 'USER'""",
          """
          SELECT
                 a.PID
               , a.USENAME
               , a.STATE
               , NOW() - a.QUERY_START AS ELAPSED
               , a.QUERY
            FROM PG_STAT_ACTIVITY a
           WHERE a.STATE = 'active'
             AND a.PID <> PG_BACKEND_PID()"""),
      new Example("세션 · 락", "누가 누구를 막고 있나 (락 대기)", "기다리는 세션과 막고 있는 세션. 막는 세션이 커밋 · 롤백을 안 하면 계속 기다립니다.",
          """
          SELECT
                 w.SID AS WAITING_SID
               , w.BLOCKING_SESSION AS BLOCKING_SID
               , w.EVENT
               , w.SECONDS_IN_WAIT
            FROM V$SESSION w
           WHERE w.BLOCKING_SESSION IS NOT NULL""",
          """
          SELECT
                 a.PID AS WAITING_PID
               , PG_BLOCKING_PIDS(a.PID) AS BLOCKING_PIDS
               , a.WAIT_EVENT
               , a.QUERY
            FROM PG_STAT_ACTIVITY a
           WHERE CARDINALITY(PG_BLOCKING_PIDS(a.PID)) > 0"""),
      new Example("세션 · 락", "오래 걸린 쿼리 순위", "실행 시간이 많이 쌓인 문장 순. PostgreSQL 은 pg_stat_statements 확장이 켜져 있어야 합니다.",
          """
          SELECT *
            FROM (
                  SELECT
                         q.SQL_ID
                       , q.EXECUTIONS
                       , ROUND(q.ELAPSED_TIME / 1000000, 1) AS TOTAL_SEC
                       , ROUND(q.ELAPSED_TIME / NULLIF(q.EXECUTIONS, 0) / 1000, 1) AS AVG_MS
                       , q.SQL_TEXT
                    FROM V$SQL q
                   ORDER BY
                         q.ELAPSED_TIME DESC
                 )
           WHERE ROWNUM <= 20""",
          """
          SELECT
                 s.CALLS
               , ROUND(CAST(s.TOTAL_EXEC_TIME / 1000 AS NUMERIC), 1) AS TOTAL_SEC
               , ROUND(CAST(s.MEAN_EXEC_TIME AS NUMERIC), 1) AS AVG_MS
               , s.QUERY
            FROM PG_STAT_STATEMENTS s
           ORDER BY
                 s.TOTAL_EXEC_TIME DESC
           LIMIT 20"""),
      new Example("성능", "실행 계획 보기", "색인을 타는지, 전체를 읽는지 봅니다. 콘솔의 「실행 계획」 단추와 같습니다.",
          """
          EXPLAIN PLAN FOR
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.CUST_ID = :custId
          ;
          SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY)""",
          """
          EXPLAIN
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.CUST_ID = CAST(:custId AS INTEGER)"""),
      new Example("성능", "통계 다시 모으기", "데이터를 많이 넣고 지운 뒤 실행 계획이 이상하면 통계를 다시 모읍니다.",
          """
          BEGIN
            DBMS_STATS.GATHER_TABLE_STATS(USER, 'ORDERS');
          END;""",
          """
          ANALYZE ORDERS"""),
      new Example("성능", "칼럼에 함수 씌우지 않기", "TO_CHAR(날짜) = '…' 는 색인을 못 탑니다. 범위로 바꿔 쓴 꼴입니다.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.ORDER_DT >= TO_DATE(:day, 'YYYY-MM-DD')
             AND o.ORDER_DT < TO_DATE(:day, 'YYYY-MM-DD') + 1
          -- 피할 꼴: WHERE TO_CHAR(o.ORDER_DT, 'YYYY-MM-DD') = :day""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.ORDER_DT >= CAST(:day AS DATE)
             AND o.ORDER_DT < CAST(:day AS DATE) + 1
          -- 피할 꼴: WHERE TO_CHAR(o.ORDER_DT, 'YYYY-MM-DD') = :day"""),
      new Example("정합성 점검", "부모 없는 자식 (고아 데이터)", "주문 표에 없는 주문 번호를 가진 상세 줄.",
          """
          SELECT
                 i.*
            FROM ORDER_ITEM i
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM ORDERS o
                   WHERE o.ORDER_ID = i.ORDER_ID
                 )""",
          """
          SELECT
                 i.*
            FROM ORDER_ITEM i
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM ORDERS o
                   WHERE o.ORDER_ID = i.ORDER_ID
                 )"""),
      new Example("정합성 점검", "합계가 안 맞는 주문", "주문 금액과 상세 줄 합계가 다른 주문.",
          """
          SELECT
                 o.ORDER_ID
               , o.AMOUNT
               , s.ITEM_AMT
               , o.AMOUNT - s.ITEM_AMT AS DIFF
            FROM ORDERS o
               , (
                  SELECT
                         i.ORDER_ID
                       , SUM(i.QTY * i.PRICE) AS ITEM_AMT
                    FROM ORDER_ITEM i
                   GROUP BY
                         i.ORDER_ID
                 ) s
           WHERE o.ORDER_ID = s.ORDER_ID
             AND o.AMOUNT <> s.ITEM_AMT""",
          """
          SELECT
                 o.ORDER_ID
               , o.AMOUNT
               , s.ITEM_AMT
               , o.AMOUNT - s.ITEM_AMT AS DIFF
            FROM ORDERS o
            JOIN (
                  SELECT
                         i.ORDER_ID
                       , SUM(i.QTY * i.PRICE) AS ITEM_AMT
                    FROM ORDER_ITEM i
                   GROUP BY
                         i.ORDER_ID
                 ) s
              ON s.ORDER_ID = o.ORDER_ID
           WHERE o.AMOUNT <> s.ITEM_AMT"""),
      new Example("정합성 점검", "같은 키가 두 번 (중복 키)", "고유해야 할 칼럼 짝(주문 번호, 줄 번호)이 겹친 것.",
          """
          SELECT
                 i.ORDER_ID
               , i.LINE_NO
               , COUNT(*) AS CNT
            FROM ORDER_ITEM i
           GROUP BY
                 i.ORDER_ID
               , i.LINE_NO
          HAVING COUNT(*) > 1""",
          """
          SELECT
                 i.ORDER_ID
               , i.LINE_NO
               , COUNT(*) AS CNT
            FROM ORDER_ITEM i
           GROUP BY
                 i.ORDER_ID
               , i.LINE_NO
          HAVING COUNT(*) > 1"""),
      new Example("JSON", "JSON 칸에서 값 꺼내기", "메모 칸에 JSON 이 들어 있을 때 키 값을 꺼냅니다(Oracle 12c 이상).",
          """
          SELECT
                 o.ORDER_ID
               , JSON_VALUE(o.MEMO, '$.channel') AS CHANNEL
            FROM ORDERS o
           WHERE o.MEMO IS JSON""",
          """
          SELECT
                 o.ORDER_ID
               , CAST(o.MEMO AS JSONB) ->> 'channel' AS CHANNEL
            FROM ORDERS o
           WHERE o.MEMO IS JSON"""),
      new Example("JSON", "줄을 JSON 으로 만들기", "주문 한 줄을 JSON 글자로 만듭니다. API 시험 값을 만들 때 씁니다.",
          """
          SELECT
                 JSON_OBJECT('orderId' VALUE o.ORDER_ID, 'amount' VALUE o.AMOUNT, 'status' VALUE o.STATUS) AS JSON
            FROM ORDERS o""",
          """
          SELECT
                 JSON_BUILD_OBJECT('orderId', o.ORDER_ID, 'amount', o.AMOUNT, 'status', o.STATUS) AS JSON
            FROM ORDERS o"""));
}
