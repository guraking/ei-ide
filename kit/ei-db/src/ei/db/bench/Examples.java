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
      new Example("기본 조회", "값 목록을 표처럼 쓰기", "표 없이 몇 줄짜리 값 목록을 만들어 조인 · 비교에 씁니다.",
          """
          SELECT 'NEW' AS CODE, '접수' AS NAME FROM DUAL
           UNION ALL
          SELECT 'WORK', '진행' FROM DUAL
           UNION ALL
          SELECT 'DONE', '완료' FROM DUAL""",
          """
          SELECT
                 v.CODE
               , v.NAME
            FROM (VALUES ('NEW', '접수'), ('WORK', '진행'), ('DONE', '완료')) AS v(CODE, NAME)"""),
      new Example("기본 조회", "조건 여러 개 (AND · OR 괄호)", "OR 는 괄호로 꼭 묶습니다. 괄호가 없으면 AND 가 먼저 묶여 다른 뜻이 됩니다.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.CUST_ID = :custId
             AND (o.STATUS = 'NEW' OR o.STATUS = 'WORK')""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.CUST_ID = CAST(:custId AS INTEGER)
             AND (o.STATUS = 'NEW' OR o.STATUS = 'WORK')"""),
      new Example("기본 조회", "NULL 이 아닌 것 · 빈 글자가 아닌 것", "메모가 들어 있는 줄만.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.MEMO IS NOT NULL""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE NULLIF(TRIM(o.MEMO), '') IS NOT NULL"""),
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
      new Example("조인", "양쪽 다 보기 (FULL OUTER JOIN)", "주문 표와 백업 표 어느 한쪽에만 있어도 다 나옵니다. 옛 방식 (+) 로는 쓸 수 없습니다.",
          """
          SELECT
                 NVL(o.ORDER_ID, b.ORDER_ID) AS ORDER_ID
               , o.AMOUNT AS NOW_AMT
               , b.AMOUNT AS BAK_AMT
            FROM ORDERS o
            FULL OUTER JOIN ORDERS_BAK b
              ON b.ORDER_ID = o.ORDER_ID""",
          """
          SELECT
                 COALESCE(o.ORDER_ID, b.ORDER_ID) AS ORDER_ID
               , o.AMOUNT AS NOW_AMT
               , b.AMOUNT AS BAK_AMT
            FROM ORDERS o
            FULL OUTER JOIN ORDERS_BAK b
              ON b.ORDER_ID = o.ORDER_ID"""),
      new Example("조인", "모든 조합 만들기 (CROSS JOIN)", "고객 × 상태 모든 짝을 만들고 건수를 붙여, 없는 조합도 0 으로 봅니다.",
          """
          SELECT
                 c.CUST_ID
               , s.STATUS
               , COUNT(o.ORDER_ID) AS CNT
            FROM CUSTOMER c
               , (SELECT DISTINCT x.STATUS FROM ORDERS x) s
               , ORDERS o
           WHERE o.CUST_ID(+) = c.CUST_ID
             AND o.STATUS(+) = s.STATUS
           GROUP BY
                 c.CUST_ID
               , s.STATUS""",
          """
          SELECT
                 c.CUST_ID
               , s.STATUS
               , COUNT(o.ORDER_ID) AS CNT
            FROM CUSTOMER c
           CROSS JOIN (SELECT DISTINCT x.STATUS FROM ORDERS x) s
            LEFT JOIN ORDERS o
              ON o.CUST_ID = c.CUST_ID
             AND o.STATUS = s.STATUS
           GROUP BY
                 c.CUST_ID
               , s.STATUS"""),
      new Example("조인", "아우터 조인에서 조건 자리 (ON 과 WHERE)", "짝 표의 조건을 WHERE 에 두면 짝 없는 행이 빠져 안쪽 조인이 됩니다. 짝 표 조건은 ON(옛 방식은 (+) 를 조건에도) 에 둡니다.",
          """
          SELECT
                 c.CUST_ID
               , COUNT(o.ORDER_ID) AS DONE_CNT
            FROM CUSTOMER c, ORDERS o
           WHERE c.CUST_ID = o.CUST_ID(+)
             AND o.STATUS(+) = 'DONE'
           GROUP BY
                 c.CUST_ID""",
          """
          SELECT
                 c.CUST_ID
               , COUNT(o.ORDER_ID) AS DONE_CNT
            FROM CUSTOMER c
            LEFT JOIN ORDERS o
              ON o.CUST_ID = c.CUST_ID
             AND o.STATUS = 'DONE'
           GROUP BY
                 c.CUST_ID"""),
      new Example("조인", "고객마다 최근 주문 하나만 붙이기", "고객 한 줄에 가장 최근 주문 한 건을 붙입니다(주문이 여러 건이어도 고객 줄이 늘지 않음).",
          """
          SELECT
                 c.CUST_ID
               , c.CUST_NAME
               , o.ORDER_ID
               , o.ORDER_DT
            FROM CUSTOMER c
               , (
                  SELECT
                         x.*
                       , ROW_NUMBER() OVER (PARTITION BY x.CUST_ID ORDER BY x.ORDER_DT DESC, x.ORDER_ID DESC) AS RN
                    FROM ORDERS x
                 ) o
           WHERE o.CUST_ID(+) = c.CUST_ID
             AND o.RN(+) = 1""",
          """
          SELECT
                 c.CUST_ID
               , c.CUST_NAME
               , o.ORDER_ID
               , o.ORDER_DT
            FROM CUSTOMER c
            LEFT JOIN LATERAL (
                  SELECT
                         x.*
                    FROM ORDERS x
                   WHERE x.CUST_ID = c.CUST_ID
                   ORDER BY
                         x.ORDER_DT DESC
                       , x.ORDER_ID DESC
                   LIMIT 1
                 ) o
              ON TRUE"""),
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
      new Example("서브쿼리", "다른 표에 없는 것만 (NOT EXISTS)", "주문이 한 건도 없는 고객. 짝이 없는 행을 찾을 때는 NOT EXISTS 가 가장 안전합니다.",
          """
          SELECT
                 c.*
            FROM CUSTOMER c
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM ORDERS o
                   WHERE o.CUST_ID = c.CUST_ID
                 )""",
          """
          SELECT
                 c.*
            FROM CUSTOMER c
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM ORDERS o
                   WHERE o.CUST_ID = c.CUST_ID
                 )"""),
      new Example("서브쿼리", "NOT IN 의 NULL 함정", "서브쿼리 결과에 NULL 이 하나라도 있으면 NOT IN 은 아무것도 내지 않습니다. NULL 을 빼거나 NOT EXISTS 로 씁니다.",
          """
          SELECT
                 c.*
            FROM CUSTOMER c
           WHERE c.CUST_ID NOT IN (
                  SELECT o.CUST_ID
                    FROM ORDERS o
                   WHERE o.CUST_ID IS NOT NULL
                 )""",
          """
          SELECT
                 c.*
            FROM CUSTOMER c
           WHERE c.CUST_ID NOT IN (
                  SELECT o.CUST_ID
                    FROM ORDERS o
                   WHERE o.CUST_ID IS NOT NULL
                 )"""),
      new Example("서브쿼리", "기간 안에 주문이 없는 고객", "최근 90일 동안 주문하지 않은 고객(휴면 고객).",
          """
          SELECT
                 c.*
            FROM CUSTOMER c
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM ORDERS o
                   WHERE o.CUST_ID = c.CUST_ID
                     AND o.ORDER_DT >= TRUNC(SYSDATE) - 90
                 )""",
          """
          SELECT
                 c.*
            FROM CUSTOMER c
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM ORDERS o
                   WHERE o.CUST_ID = c.CUST_ID
                     AND o.ORDER_DT >= CURRENT_DATE - 90
                 )"""),
      new Example("서브쿼리", "모두보다 큰 · 하나라도 큰 (ALL · ANY)", "VIP 고객의 어느 주문보다도 큰 주문(ALL), 하나보다만 크면 되는 주문(ANY).",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.AMOUNT > ALL (
                  SELECT x.AMOUNT
                    FROM ORDERS x, CUSTOMER c
                   WHERE x.CUST_ID = c.CUST_ID
                     AND c.GRADE = 'VIP'
                 )""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.AMOUNT > ALL (
                  SELECT x.AMOUNT
                    FROM ORDERS x
                    JOIN CUSTOMER c
                      ON c.CUST_ID = x.CUST_ID
                   WHERE c.GRADE = 'VIP'
                 )"""),
      new Example("서브쿼리", "두 조건을 다 만족하는 것 (EXISTS 두 개)", "상세에 A 부품도 있고 B 부품도 있는 주문.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE EXISTS (SELECT 1 FROM ORDER_ITEM i WHERE i.ORDER_ID = o.ORDER_ID AND i.PART_NO = :partA)
             AND EXISTS (SELECT 1 FROM ORDER_ITEM i WHERE i.ORDER_ID = o.ORDER_ID AND i.PART_NO = :partB)""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE EXISTS (SELECT 1 FROM ORDER_ITEM i WHERE i.ORDER_ID = o.ORDER_ID AND i.PART_NO = :partA)
             AND EXISTS (SELECT 1 FROM ORDER_ITEM i WHERE i.ORDER_ID = o.ORDER_ID AND i.PART_NO = :partB)"""),
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
      new Example("집계", "합계 상위 N 개 그룹", "금액 합계가 큰 고객 10명.",
          """
          SELECT *
            FROM (
                  SELECT
                         o.CUST_ID
                       , SUM(o.AMOUNT) AS SUM_AMT
                    FROM ORDERS o
                   GROUP BY
                         o.CUST_ID
                   ORDER BY
                         SUM_AMT DESC
                 )
           WHERE ROWNUM <= 10""",
          """
          SELECT
                 o.CUST_ID
               , SUM(o.AMOUNT) AS SUM_AMT
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID
           ORDER BY
                 SUM_AMT DESC
           LIMIT 10"""),
      new Example("집계", "그룹마다 상위 3건", "고객마다 금액이 큰 주문 3건씩.",
          """
          SELECT *
            FROM (
                  SELECT
                         o.*
                       , ROW_NUMBER() OVER (PARTITION BY o.CUST_ID ORDER BY o.AMOUNT DESC) AS RN
                    FROM ORDERS o
                 )
           WHERE RN <= 3""",
          """
          SELECT *
            FROM (
                  SELECT
                         o.*
                       , ROW_NUMBER() OVER (PARTITION BY o.CUST_ID ORDER BY o.AMOUNT DESC) AS RN
                    FROM ORDERS o
                 ) x
           WHERE x.RN <= 3"""),
      new Example("집계", "그룹마다 최댓값인 행 하나 (전체 칼럼)", "고객마다 가장 최근 주문 한 줄. Oracle 은 KEEP, PostgreSQL 은 DISTINCT ON 으로 짧게 씁니다.",
          """
          SELECT
                 o.CUST_ID
               , MAX(o.ORDER_ID) KEEP (DENSE_RANK LAST ORDER BY o.ORDER_DT) AS LAST_ORDER_ID
               , MAX(o.ORDER_DT) AS LAST_DT
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID""",
          """
          SELECT DISTINCT ON (o.CUST_ID)
                 o.*
            FROM ORDERS o
           ORDER BY
                 o.CUST_ID
               , o.ORDER_DT DESC"""),
      new Example("집계", "중앙값 · 백분위", "금액의 중앙값과 상위 10% 경계값.",
          """
          SELECT
                 MEDIAN(o.AMOUNT) AS MED
               , PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY o.AMOUNT) AS P90
            FROM ORDERS o""",
          """
          SELECT
                 PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY o.AMOUNT) AS MED
               , PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY o.AMOUNT) AS P90
            FROM ORDERS o"""),
      new Example("집계", "금액 구간별 분포", "1만 미만 · 1만~5만 · 5만~10만 · 10만 이상 구간마다 건수.",
          """
          SELECT
                 CASE
                      WHEN o.AMOUNT < 10000 THEN '1) 1만 미만'
                      WHEN o.AMOUNT < 50000 THEN '2) 1만~5만'
                      WHEN o.AMOUNT < 100000 THEN '3) 5만~10만'
                      ELSE '4) 10만 이상'
                 END AS BAND
               , COUNT(*) AS CNT
            FROM ORDERS o
           GROUP BY
                 CASE
                      WHEN o.AMOUNT < 10000 THEN '1) 1만 미만'
                      WHEN o.AMOUNT < 50000 THEN '2) 1만~5만'
                      WHEN o.AMOUNT < 100000 THEN '3) 5만~10만'
                      ELSE '4) 10만 이상'
                 END
           ORDER BY
                 BAND""",
          """
          SELECT
                 CASE
                      WHEN o.AMOUNT < 10000 THEN '1) 1만 미만'
                      WHEN o.AMOUNT < 50000 THEN '2) 1만~5만'
                      WHEN o.AMOUNT < 100000 THEN '3) 5만~10만'
                      ELSE '4) 10만 이상'
                 END AS BAND
               , COUNT(*) AS CNT
            FROM ORDERS o
           GROUP BY
                 CASE
                      WHEN o.AMOUNT < 10000 THEN '1) 1만 미만'
                      WHEN o.AMOUNT < 50000 THEN '2) 1만~5만'
                      WHEN o.AMOUNT < 100000 THEN '3) 5만~10만'
                      ELSE '4) 10만 이상'
                 END
           ORDER BY
                 BAND"""),
      new Example("집계", "그룹별 비율 (%)", "상태마다 건수와 전체 중 비율.",
          """
          SELECT
                 o.STATUS
               , COUNT(*) AS CNT
               , ROUND(COUNT(*) * 100 / SUM(COUNT(*)) OVER (), 1) AS PCT
            FROM ORDERS o
           GROUP BY
                 o.STATUS""",
          """
          SELECT
                 o.STATUS
               , COUNT(*) AS CNT
               , ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1) AS PCT
            FROM ORDERS o
           GROUP BY
                 o.STATUS"""),
      new Example("집계", "가장 많이 나온 값 (최빈값)", "주문이 가장 많은 부품 번호.",
          """
          SELECT *
            FROM (
                  SELECT
                         i.PART_NO
                       , COUNT(*) AS CNT
                    FROM ORDER_ITEM i
                   GROUP BY
                         i.PART_NO
                   ORDER BY
                         CNT DESC
                 )
           WHERE ROWNUM = 1""",
          """
          SELECT
                 i.PART_NO
               , COUNT(*) AS CNT
            FROM ORDER_ITEM i
           GROUP BY
                 i.PART_NO
           ORDER BY
                 CNT DESC
           LIMIT 1"""),
      new Example("집계", "COUNT(*) 와 COUNT(칼럼) 차이", "COUNT(*) 는 모든 행, COUNT(칼럼) 은 그 칼럼이 비지 않은 행, COUNT(DISTINCT) 는 서로 다른 값 수입니다.",
          """
          SELECT
                 COUNT(*) AS ALL_ROWS
               , COUNT(o.MEMO) AS MEMO_FILLED
               , COUNT(DISTINCT o.CUST_ID) AS CUSTOMERS
            FROM ORDERS o""",
          """
          SELECT
                 COUNT(*) AS ALL_ROWS
               , COUNT(o.MEMO) AS MEMO_FILLED
               , COUNT(DISTINCT o.CUST_ID) AS CUSTOMERS
            FROM ORDERS o"""),
      new Example("기간별 통계", "시간별 건수 (0~23시)", "등록 시각(REG_DT)의 시만 떼어 시마다 건수를 셉니다. 행이 없는 시는 안 나옵니다(0 건까지 보려면 다음 예제).",
          """
          SELECT
                 TO_CHAR(o.REG_DT, 'HH24') AS HH
               , COUNT(*) AS CNT
            FROM ORDERS o
           WHERE o.REG_DT >= TRUNC(SYSDATE)
             AND o.REG_DT < TRUNC(SYSDATE) + 1
           GROUP BY
                 TO_CHAR(o.REG_DT, 'HH24')
           ORDER BY
                 HH""",
          """
          SELECT
                 TO_CHAR(o.REG_DT, 'HH24') AS HH
               , COUNT(*) AS CNT
            FROM ORDERS o
           WHERE o.REG_DT >= CURRENT_DATE
             AND o.REG_DT < CURRENT_DATE + 1
           GROUP BY
                 TO_CHAR(o.REG_DT, 'HH24')
           ORDER BY
                 HH"""),
      new Example("기간별 통계", "시간별 건수 (0 건인 시도 빠짐없이)", "0시부터 23시까지 24줄을 먼저 만들고 건수를 붙입니다. 그래프용.",
          """
          SELECT
                 h.HH
               , COUNT(o.ORDER_ID) AS CNT
            FROM (
                  SELECT
                         LPAD(LEVEL - 1, 2, '0') AS HH
                    FROM DUAL
                 CONNECT BY LEVEL <= 24
                 ) h
               , ORDERS o
           WHERE TO_CHAR(o.REG_DT(+), 'HH24') = h.HH
             AND o.REG_DT(+) >= TRUNC(SYSDATE)
             AND o.REG_DT(+) < TRUNC(SYSDATE) + 1
           GROUP BY
                 h.HH
           ORDER BY
                 h.HH""",
          """
          SELECT
                 LPAD(CAST(h.H AS VARCHAR), 2, '0') AS HH
               , COUNT(o.ORDER_ID) AS CNT
            FROM GENERATE_SERIES(0, 23) AS h(H)
            LEFT JOIN ORDERS o
              ON EXTRACT(HOUR FROM o.REG_DT) = h.H
             AND o.REG_DT >= CURRENT_DATE
             AND o.REG_DT < CURRENT_DATE + 1
           GROUP BY
                 h.H
           ORDER BY
                 h.H"""),
      new Example("기간별 통계", "10분 · 30분 단위 건수", "시각을 10분 단위로 내려 묶습니다(30분이면 10 을 30 으로).",
          """
          SELECT
                 TRUNC(o.REG_DT, 'HH24') + FLOOR(TO_NUMBER(TO_CHAR(o.REG_DT, 'MI')) / 10) * 10 / 1440 AS SLOT
               , COUNT(*) AS CNT
            FROM ORDERS o
           WHERE o.REG_DT >= TRUNC(SYSDATE)
           GROUP BY
                 TRUNC(o.REG_DT, 'HH24') + FLOOR(TO_NUMBER(TO_CHAR(o.REG_DT, 'MI')) / 10) * 10 / 1440
           ORDER BY
                 SLOT""",
          """
          SELECT
                 DATE_TRUNC('hour', o.REG_DT) + FLOOR(EXTRACT(MINUTE FROM o.REG_DT) / 10) * INTERVAL '10 minutes' AS SLOT
               , COUNT(*) AS CNT
            FROM ORDERS o
           WHERE o.REG_DT >= CURRENT_DATE
           GROUP BY
                 DATE_TRUNC('hour', o.REG_DT) + FLOOR(EXTRACT(MINUTE FROM o.REG_DT) / 10) * INTERVAL '10 minutes'
           ORDER BY
                 SLOT"""),
      new Example("기간별 통계", "시간대 구간별 (오전 · 오후 · 야간)", "시를 구간으로 나눠 셉니다.",
          """
          SELECT
                 CASE
                      WHEN TO_NUMBER(TO_CHAR(o.REG_DT, 'HH24')) BETWEEN 6 AND 11 THEN '오전'
                      WHEN TO_NUMBER(TO_CHAR(o.REG_DT, 'HH24')) BETWEEN 12 AND 17 THEN '오후'
                      WHEN TO_NUMBER(TO_CHAR(o.REG_DT, 'HH24')) BETWEEN 18 AND 23 THEN '저녁'
                      ELSE '야간'
                 END AS PART
               , COUNT(*) AS CNT
            FROM ORDERS o
           GROUP BY
                 CASE
                      WHEN TO_NUMBER(TO_CHAR(o.REG_DT, 'HH24')) BETWEEN 6 AND 11 THEN '오전'
                      WHEN TO_NUMBER(TO_CHAR(o.REG_DT, 'HH24')) BETWEEN 12 AND 17 THEN '오후'
                      WHEN TO_NUMBER(TO_CHAR(o.REG_DT, 'HH24')) BETWEEN 18 AND 23 THEN '저녁'
                      ELSE '야간'
                 END""",
          """
          SELECT
                 CASE
                      WHEN EXTRACT(HOUR FROM o.REG_DT) BETWEEN 6 AND 11 THEN '오전'
                      WHEN EXTRACT(HOUR FROM o.REG_DT) BETWEEN 12 AND 17 THEN '오후'
                      WHEN EXTRACT(HOUR FROM o.REG_DT) BETWEEN 18 AND 23 THEN '저녁'
                      ELSE '야간'
                 END AS PART
               , COUNT(*) AS CNT
            FROM ORDERS o
           GROUP BY
                 1"""),
      new Example("기간별 통계", "일별 건수 · 합계", "기간 안의 날마다 건수와 금액 합계.",
          """
          SELECT
                 TRUNC(o.ORDER_DT) AS DT
               , COUNT(*) AS CNT
               , SUM(o.AMOUNT) AS SUM_AMT
            FROM ORDERS o
           WHERE o.ORDER_DT >= TO_DATE(:fromDt, 'YYYY-MM-DD')
             AND o.ORDER_DT < TO_DATE(:toDt, 'YYYY-MM-DD') + 1
           GROUP BY
                 TRUNC(o.ORDER_DT)
           ORDER BY
                 DT""",
          """
          SELECT
                 CAST(o.ORDER_DT AS DATE) AS DT
               , COUNT(*) AS CNT
               , SUM(o.AMOUNT) AS SUM_AMT
            FROM ORDERS o
           WHERE o.ORDER_DT >= CAST(:fromDt AS DATE)
             AND o.ORDER_DT < CAST(:toDt AS DATE) + 1
           GROUP BY
                 CAST(o.ORDER_DT AS DATE)
           ORDER BY
                 DT"""),
      new Example("기간별 통계", "일별 건수 (0 건인 날도 빠짐없이)", "시작일 ~ 끝일의 날짜를 모두 만들고 건수를 붙입니다.",
          """
          SELECT
                 d.DT
               , COUNT(o.ORDER_ID) AS CNT
            FROM (
                  SELECT
                         TO_DATE(:fromDt, 'YYYY-MM-DD') + LEVEL - 1 AS DT
                    FROM DUAL
                 CONNECT BY LEVEL <= TO_DATE(:toDt, 'YYYY-MM-DD') - TO_DATE(:fromDt, 'YYYY-MM-DD') + 1
                 ) d
               , ORDERS o
           WHERE TRUNC(o.ORDER_DT(+)) = d.DT
           GROUP BY
                 d.DT
           ORDER BY
                 d.DT""",
          """
          SELECT
                 CAST(d.DT AS DATE) AS DT
               , COUNT(o.ORDER_ID) AS CNT
            FROM GENERATE_SERIES(CAST(:fromDt AS DATE), CAST(:toDt AS DATE), INTERVAL '1 day') AS d(DT)
            LEFT JOIN ORDERS o
              ON o.ORDER_DT = CAST(d.DT AS DATE)
           GROUP BY
                 d.DT
           ORDER BY
                 d.DT"""),
      new Example("기간별 통계", "주별 건수 (월요일 시작)", "주의 월요일 날짜로 묶습니다.",
          """
          SELECT
                 TRUNC(o.ORDER_DT, 'IW') AS WEEK_START
               , COUNT(*) AS CNT
            FROM ORDERS o
           GROUP BY
                 TRUNC(o.ORDER_DT, 'IW')
           ORDER BY
                 WEEK_START""",
          """
          SELECT
                 CAST(DATE_TRUNC('week', o.ORDER_DT) AS DATE) AS WEEK_START
               , COUNT(*) AS CNT
            FROM ORDERS o
           GROUP BY
                 CAST(DATE_TRUNC('week', o.ORDER_DT) AS DATE)
           ORDER BY
                 WEEK_START"""),
      new Example("기간별 통계", "월별 건수 · 합계", "'2026-11' 꼴 달마다 건수와 금액.",
          """
          SELECT
                 TO_CHAR(o.ORDER_DT, 'YYYY-MM') AS YM
               , COUNT(*) AS CNT
               , SUM(o.AMOUNT) AS SUM_AMT
            FROM ORDERS o
           GROUP BY
                 TO_CHAR(o.ORDER_DT, 'YYYY-MM')
           ORDER BY
                 YM""",
          """
          SELECT
                 TO_CHAR(o.ORDER_DT, 'YYYY-MM') AS YM
               , COUNT(*) AS CNT
               , SUM(o.AMOUNT) AS SUM_AMT
            FROM ORDERS o
           GROUP BY
                 TO_CHAR(o.ORDER_DT, 'YYYY-MM')
           ORDER BY
                 YM"""),
      new Example("기간별 통계", "올해 1~12월 빠짐없이", "열두 달을 먼저 만들고 붙여, 주문이 없는 달도 0 으로 나옵니다.",
          """
          SELECT
                 m.YM
               , COUNT(o.ORDER_ID) AS CNT
               , NVL(SUM(o.AMOUNT), 0) AS SUM_AMT
            FROM (
                  SELECT
                         TO_CHAR(ADD_MONTHS(TRUNC(SYSDATE, 'YYYY'), LEVEL - 1), 'YYYY-MM') AS YM
                    FROM DUAL
                 CONNECT BY LEVEL <= 12
                 ) m
               , ORDERS o
           WHERE TO_CHAR(o.ORDER_DT(+), 'YYYY-MM') = m.YM
           GROUP BY
                 m.YM
           ORDER BY
                 m.YM""",
          """
          SELECT
                 TO_CHAR(m.MON, 'YYYY-MM') AS YM
               , COUNT(o.ORDER_ID) AS CNT
               , COALESCE(SUM(o.AMOUNT), 0) AS SUM_AMT
            FROM GENERATE_SERIES(DATE_TRUNC('year', CURRENT_DATE), DATE_TRUNC('year', CURRENT_DATE) + INTERVAL '11 months', INTERVAL '1 month') AS m(MON)
            LEFT JOIN ORDERS o
              ON o.ORDER_DT >= m.MON
             AND o.ORDER_DT < m.MON + INTERVAL '1 month'
           GROUP BY
                 m.MON
           ORDER BY
                 m.MON"""),
      new Example("기간별 통계", "분기별 · 반기별", "분기(Q1~Q4)와 상 · 하반기마다 건수.",
          """
          SELECT
                 TO_CHAR(o.ORDER_DT, 'YYYY') AS YY
               , TO_CHAR(o.ORDER_DT, 'Q') AS QTR
               , CASE WHEN TO_CHAR(o.ORDER_DT, 'Q') IN ('1', '2') THEN '상반기' ELSE '하반기' END AS HALF
               , COUNT(*) AS CNT
            FROM ORDERS o
           GROUP BY
                 TO_CHAR(o.ORDER_DT, 'YYYY')
               , TO_CHAR(o.ORDER_DT, 'Q')
           ORDER BY
                 YY
               , QTR""",
          """
          SELECT
                 TO_CHAR(o.ORDER_DT, 'YYYY') AS YY
               , TO_CHAR(o.ORDER_DT, 'Q') AS QTR
               , CASE WHEN TO_CHAR(o.ORDER_DT, 'Q') IN ('1', '2') THEN '상반기' ELSE '하반기' END AS HALF
               , COUNT(*) AS CNT
            FROM ORDERS o
           GROUP BY
                 TO_CHAR(o.ORDER_DT, 'YYYY')
               , TO_CHAR(o.ORDER_DT, 'Q')
           ORDER BY
                 YY
               , QTR"""),
      new Example("기간별 통계", "연도별 건수 · 합계", "해마다 건수와 금액.",
          """
          SELECT
                 TO_CHAR(o.ORDER_DT, 'YYYY') AS YY
               , COUNT(*) AS CNT
               , SUM(o.AMOUNT) AS SUM_AMT
            FROM ORDERS o
           GROUP BY
                 TO_CHAR(o.ORDER_DT, 'YYYY')
           ORDER BY
                 YY""",
          """
          SELECT
                 TO_CHAR(o.ORDER_DT, 'YYYY') AS YY
               , COUNT(*) AS CNT
               , SUM(o.AMOUNT) AS SUM_AMT
            FROM ORDERS o
           GROUP BY
                 TO_CHAR(o.ORDER_DT, 'YYYY')
           ORDER BY
                 YY"""),
      new Example("기간별 통계", "요일별 건수 (월~일 순서)", "요일마다 건수. 1 이 월요일이 되게 순서를 맞춥니다.",
          """
          SELECT
                 TO_CHAR(o.ORDER_DT, 'D', 'NLS_DATE_LANGUAGE=ENGLISH') AS DOW_NO
               , TO_CHAR(o.ORDER_DT, 'DY', 'NLS_DATE_LANGUAGE=KOREAN') AS DOW
               , COUNT(*) AS CNT
            FROM ORDERS o
           GROUP BY
                 TO_CHAR(o.ORDER_DT, 'D', 'NLS_DATE_LANGUAGE=ENGLISH')
               , TO_CHAR(o.ORDER_DT, 'DY', 'NLS_DATE_LANGUAGE=KOREAN')
               , TRUNC(o.ORDER_DT) - TRUNC(o.ORDER_DT, 'IW')
           ORDER BY
                 TRUNC(o.ORDER_DT) - TRUNC(o.ORDER_DT, 'IW')""",
          """
          SELECT
                 EXTRACT(ISODOW FROM o.ORDER_DT) AS DOW_NO
               , TO_CHAR(o.ORDER_DT, 'Dy') AS DOW
               , COUNT(*) AS CNT
            FROM ORDERS o
           GROUP BY
                 EXTRACT(ISODOW FROM o.ORDER_DT)
               , TO_CHAR(o.ORDER_DT, 'Dy')
           ORDER BY
                 DOW_NO"""),
      new Example("기간별 통계", "요일 × 시간 표 (언제 몰리나)", "줄은 요일, 칸은 시간대. 몇 시에 몰리는지 한눈에 봅니다(앞 몇 칸만 적었습니다).",
          """
          SELECT
                 TO_CHAR(o.REG_DT, 'DY', 'NLS_DATE_LANGUAGE=KOREAN') AS DOW
               , SUM(CASE WHEN TO_CHAR(o.REG_DT, 'HH24') BETWEEN '09' AND '11' THEN 1 ELSE 0 END) AS H09_11
               , SUM(CASE WHEN TO_CHAR(o.REG_DT, 'HH24') BETWEEN '12' AND '14' THEN 1 ELSE 0 END) AS H12_14
               , SUM(CASE WHEN TO_CHAR(o.REG_DT, 'HH24') BETWEEN '15' AND '17' THEN 1 ELSE 0 END) AS H15_17
               , SUM(CASE WHEN TO_CHAR(o.REG_DT, 'HH24') BETWEEN '18' AND '23' THEN 1 ELSE 0 END) AS H18_23
            FROM ORDERS o
           GROUP BY
                 TO_CHAR(o.REG_DT, 'DY', 'NLS_DATE_LANGUAGE=KOREAN')""",
          """
          SELECT
                 TO_CHAR(o.REG_DT, 'Dy') AS DOW
               , SUM(CASE WHEN TO_CHAR(o.REG_DT, 'HH24') BETWEEN '09' AND '11' THEN 1 ELSE 0 END) AS H09_11
               , SUM(CASE WHEN TO_CHAR(o.REG_DT, 'HH24') BETWEEN '12' AND '14' THEN 1 ELSE 0 END) AS H12_14
               , SUM(CASE WHEN TO_CHAR(o.REG_DT, 'HH24') BETWEEN '15' AND '17' THEN 1 ELSE 0 END) AS H15_17
               , SUM(CASE WHEN TO_CHAR(o.REG_DT, 'HH24') BETWEEN '18' AND '23' THEN 1 ELSE 0 END) AS H18_23
            FROM ORDERS o
           GROUP BY
                 TO_CHAR(o.REG_DT, 'Dy')"""),
      new Example("기간별 통계", "월별 가로 표 (1월 ~ 12월 칸)", "올해 고객마다 1월부터 12월까지 금액을 칸으로 펼치고 맨 끝에 합계.",
          """
          SELECT
                 o.CUST_ID
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '01' THEN o.AMOUNT ELSE 0 END) AS M01
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '02' THEN o.AMOUNT ELSE 0 END) AS M02
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '03' THEN o.AMOUNT ELSE 0 END) AS M03
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '04' THEN o.AMOUNT ELSE 0 END) AS M04
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '05' THEN o.AMOUNT ELSE 0 END) AS M05
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '06' THEN o.AMOUNT ELSE 0 END) AS M06
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '07' THEN o.AMOUNT ELSE 0 END) AS M07
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '08' THEN o.AMOUNT ELSE 0 END) AS M08
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '09' THEN o.AMOUNT ELSE 0 END) AS M09
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '10' THEN o.AMOUNT ELSE 0 END) AS M10
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '11' THEN o.AMOUNT ELSE 0 END) AS M11
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '12' THEN o.AMOUNT ELSE 0 END) AS M12
               , SUM(o.AMOUNT) AS TOTAL
            FROM ORDERS o
           WHERE o.ORDER_DT >= TRUNC(SYSDATE, 'YYYY')
             AND o.ORDER_DT < ADD_MONTHS(TRUNC(SYSDATE, 'YYYY'), 12)
           GROUP BY
                 o.CUST_ID""",
          """
          SELECT
                 o.CUST_ID
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '01' THEN o.AMOUNT ELSE 0 END) AS M01
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '02' THEN o.AMOUNT ELSE 0 END) AS M02
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '03' THEN o.AMOUNT ELSE 0 END) AS M03
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '04' THEN o.AMOUNT ELSE 0 END) AS M04
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '05' THEN o.AMOUNT ELSE 0 END) AS M05
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '06' THEN o.AMOUNT ELSE 0 END) AS M06
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '07' THEN o.AMOUNT ELSE 0 END) AS M07
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '08' THEN o.AMOUNT ELSE 0 END) AS M08
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '09' THEN o.AMOUNT ELSE 0 END) AS M09
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '10' THEN o.AMOUNT ELSE 0 END) AS M10
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '11' THEN o.AMOUNT ELSE 0 END) AS M11
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'MM') = '12' THEN o.AMOUNT ELSE 0 END) AS M12
               , SUM(o.AMOUNT) AS TOTAL
            FROM ORDERS o
           WHERE o.ORDER_DT >= DATE_TRUNC('year', CURRENT_DATE)
             AND o.ORDER_DT < DATE_TRUNC('year', CURRENT_DATE) + INTERVAL '1 year'
           GROUP BY
                 o.CUST_ID"""),
      new Example("기간별 통계", "전월 대비 증감 · 증감률", "달마다 건수와 바로 앞 달 대비 늘고 준 건수 · 비율(%).",
          """
          SELECT
                 m.YM
               , m.CNT
               , m.CNT - LAG(m.CNT) OVER (ORDER BY m.YM) AS DIFF
               , ROUND((m.CNT - LAG(m.CNT) OVER (ORDER BY m.YM)) * 100 / NULLIF(LAG(m.CNT) OVER (ORDER BY m.YM), 0), 1) AS PCT
            FROM (
                  SELECT
                         TO_CHAR(o.ORDER_DT, 'YYYY-MM') AS YM
                       , COUNT(*) AS CNT
                    FROM ORDERS o
                   GROUP BY
                         TO_CHAR(o.ORDER_DT, 'YYYY-MM')
                 ) m
           ORDER BY
                 m.YM""",
          """
          SELECT
                 m.YM
               , m.CNT
               , m.CNT - LAG(m.CNT) OVER (ORDER BY m.YM) AS DIFF
               , ROUND((m.CNT - LAG(m.CNT) OVER (ORDER BY m.YM)) * 100.0 / NULLIF(LAG(m.CNT) OVER (ORDER BY m.YM), 0), 1) AS PCT
            FROM (
                  SELECT
                         TO_CHAR(o.ORDER_DT, 'YYYY-MM') AS YM
                       , COUNT(*) AS CNT
                    FROM ORDERS o
                   GROUP BY
                         TO_CHAR(o.ORDER_DT, 'YYYY-MM')
                 ) m
           ORDER BY
                 m.YM"""),
      new Example("기간별 통계", "전년 같은 달 대비", "올해 달마다 금액과 작년 같은 달 금액을 나란히 봅니다.",
          """
          SELECT
                 TO_CHAR(o.ORDER_DT, 'MM') AS MM
               , SUM(CASE WHEN o.ORDER_DT >= TRUNC(SYSDATE, 'YYYY') THEN o.AMOUNT ELSE 0 END) AS THIS_YEAR
               , SUM(CASE WHEN o.ORDER_DT < TRUNC(SYSDATE, 'YYYY') THEN o.AMOUNT ELSE 0 END) AS LAST_YEAR
            FROM ORDERS o
           WHERE o.ORDER_DT >= ADD_MONTHS(TRUNC(SYSDATE, 'YYYY'), -12)
             AND o.ORDER_DT < ADD_MONTHS(TRUNC(SYSDATE, 'YYYY'), 12)
           GROUP BY
                 TO_CHAR(o.ORDER_DT, 'MM')
           ORDER BY
                 MM""",
          """
          SELECT
                 TO_CHAR(o.ORDER_DT, 'MM') AS MM
               , SUM(CASE WHEN o.ORDER_DT >= DATE_TRUNC('year', CURRENT_DATE) THEN o.AMOUNT ELSE 0 END) AS THIS_YEAR
               , SUM(CASE WHEN o.ORDER_DT < DATE_TRUNC('year', CURRENT_DATE) THEN o.AMOUNT ELSE 0 END) AS LAST_YEAR
            FROM ORDERS o
           WHERE o.ORDER_DT >= DATE_TRUNC('year', CURRENT_DATE) - INTERVAL '1 year'
             AND o.ORDER_DT < DATE_TRUNC('year', CURRENT_DATE) + INTERVAL '1 year'
           GROUP BY
                 TO_CHAR(o.ORDER_DT, 'MM')
           ORDER BY
                 MM"""),
      new Example("기간별 통계", "월별 누적 (연초부터 쌓기)", "달마다 금액과 그해 1월부터 쌓인 누적 금액.",
          """
          SELECT
                 m.YM
               , m.SUM_AMT
               , SUM(m.SUM_AMT) OVER (PARTITION BY SUBSTR(m.YM, 1, 4) ORDER BY m.YM) AS YTD_AMT
            FROM (
                  SELECT
                         TO_CHAR(o.ORDER_DT, 'YYYY-MM') AS YM
                       , SUM(o.AMOUNT) AS SUM_AMT
                    FROM ORDERS o
                   GROUP BY
                         TO_CHAR(o.ORDER_DT, 'YYYY-MM')
                 ) m
           ORDER BY
                 m.YM""",
          """
          SELECT
                 m.YM
               , m.SUM_AMT
               , SUM(m.SUM_AMT) OVER (PARTITION BY SUBSTR(m.YM, 1, 4) ORDER BY m.YM) AS YTD_AMT
            FROM (
                  SELECT
                         TO_CHAR(o.ORDER_DT, 'YYYY-MM') AS YM
                       , SUM(o.AMOUNT) AS SUM_AMT
                    FROM ORDERS o
                   GROUP BY
                         TO_CHAR(o.ORDER_DT, 'YYYY-MM')
                 ) m
           ORDER BY
                 m.YM"""),
      new Example("기간별 통계", "최근 3개월 이동 합", "달마다 그달 포함 앞 석 달 금액 합.",
          """
          SELECT
                 m.YM
               , m.SUM_AMT
               , SUM(m.SUM_AMT) OVER (ORDER BY m.YM ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) AS SUM_3M
            FROM (
                  SELECT
                         TO_CHAR(o.ORDER_DT, 'YYYY-MM') AS YM
                       , SUM(o.AMOUNT) AS SUM_AMT
                    FROM ORDERS o
                   GROUP BY
                         TO_CHAR(o.ORDER_DT, 'YYYY-MM')
                 ) m
           ORDER BY
                 m.YM""",
          """
          SELECT
                 m.YM
               , m.SUM_AMT
               , SUM(m.SUM_AMT) OVER (ORDER BY m.YM ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) AS SUM_3M
            FROM (
                  SELECT
                         TO_CHAR(o.ORDER_DT, 'YYYY-MM') AS YM
                       , SUM(o.AMOUNT) AS SUM_AMT
                    FROM ORDERS o
                   GROUP BY
                         TO_CHAR(o.ORDER_DT, 'YYYY-MM')
                 ) m
           ORDER BY
                 m.YM"""),
      new Example("기간별 통계", "오늘 · 어제 · 이번 주 · 이번 달 한 줄", "대시보드 숫자 카드에 바로 쓰는 한 줄 요약.",
          """
          SELECT
                 SUM(CASE WHEN o.ORDER_DT >= TRUNC(SYSDATE) THEN 1 ELSE 0 END) AS TODAY
               , SUM(CASE WHEN o.ORDER_DT >= TRUNC(SYSDATE) - 1 AND o.ORDER_DT < TRUNC(SYSDATE) THEN 1 ELSE 0 END) AS YESTERDAY
               , SUM(CASE WHEN o.ORDER_DT >= TRUNC(SYSDATE, 'IW') THEN 1 ELSE 0 END) AS THIS_WEEK
               , SUM(CASE WHEN o.ORDER_DT >= TRUNC(SYSDATE, 'MM') THEN 1 ELSE 0 END) AS THIS_MONTH
            FROM ORDERS o
           WHERE o.ORDER_DT >= LEAST(TRUNC(SYSDATE, 'MM'), TRUNC(SYSDATE, 'IW'), TRUNC(SYSDATE) - 1)""",
          """
          SELECT
                 SUM(CASE WHEN o.ORDER_DT >= CURRENT_DATE THEN 1 ELSE 0 END) AS TODAY
               , SUM(CASE WHEN o.ORDER_DT >= CURRENT_DATE - 1 AND o.ORDER_DT < CURRENT_DATE THEN 1 ELSE 0 END) AS YESTERDAY
               , SUM(CASE WHEN o.ORDER_DT >= DATE_TRUNC('week', CURRENT_DATE) THEN 1 ELSE 0 END) AS THIS_WEEK
               , SUM(CASE WHEN o.ORDER_DT >= DATE_TRUNC('month', CURRENT_DATE) THEN 1 ELSE 0 END) AS THIS_MONTH
            FROM ORDERS o
           WHERE o.ORDER_DT >= LEAST(DATE_TRUNC('month', CURRENT_DATE), DATE_TRUNC('week', CURRENT_DATE), CURRENT_DATE - 1)"""),
      new Example("기간별 통계", "기간 중 하루 평균", "기간 안 건수 합을 날짜 수로 나눕니다(주문이 없는 날도 날짜 수에 넣음).",
          """
          SELECT
                 COUNT(*) AS CNT
               , ROUND(COUNT(*) / (TO_DATE(:toDt, 'YYYY-MM-DD') - TO_DATE(:fromDt, 'YYYY-MM-DD') + 1), 2) AS PER_DAY
            FROM ORDERS o
           WHERE o.ORDER_DT >= TO_DATE(:fromDt, 'YYYY-MM-DD')
             AND o.ORDER_DT < TO_DATE(:toDt, 'YYYY-MM-DD') + 1""",
          """
          SELECT
                 COUNT(*) AS CNT
               , ROUND(COUNT(*) * 1.0 / NULLIF(CAST(:toDt AS DATE) - CAST(:fromDt AS DATE) + 1, 0), 2) AS PER_DAY
            FROM ORDERS o
           WHERE o.ORDER_DT >= CAST(:fromDt AS DATE)
             AND o.ORDER_DT < CAST(:toDt AS DATE) + 1"""),
      new Example("기간별 통계", "월별 신규 고객 수 (첫 주문 기준)", "고객마다 첫 주문한 달을 구해 달마다 셉니다.",
          """
          SELECT
                 f.YM
               , COUNT(*) AS NEW_CUST
            FROM (
                  SELECT
                         o.CUST_ID
                       , TO_CHAR(MIN(o.ORDER_DT), 'YYYY-MM') AS YM
                    FROM ORDERS o
                   GROUP BY
                         o.CUST_ID
                 ) f
           GROUP BY
                 f.YM
           ORDER BY
                 f.YM""",
          """
          SELECT
                 f.YM
               , COUNT(*) AS NEW_CUST
            FROM (
                  SELECT
                         o.CUST_ID
                       , TO_CHAR(MIN(o.ORDER_DT), 'YYYY-MM') AS YM
                    FROM ORDERS o
                   GROUP BY
                         o.CUST_ID
                 ) f
           GROUP BY
                 f.YM
           ORDER BY
                 f.YM"""),
      new Example("기간별 통계", "월별 재구매 고객 (그달 2번 이상)", "한 달에 두 번 이상 주문한 고객 수.",
          """
          SELECT
                 r.YM
               , COUNT(*) AS REPEAT_CUST
            FROM (
                  SELECT
                         TO_CHAR(o.ORDER_DT, 'YYYY-MM') AS YM
                       , o.CUST_ID
                    FROM ORDERS o
                   GROUP BY
                         TO_CHAR(o.ORDER_DT, 'YYYY-MM')
                       , o.CUST_ID
                  HAVING COUNT(*) >= 2
                 ) r
           GROUP BY
                 r.YM
           ORDER BY
                 r.YM""",
          """
          SELECT
                 r.YM
               , COUNT(*) AS REPEAT_CUST
            FROM (
                  SELECT
                         TO_CHAR(o.ORDER_DT, 'YYYY-MM') AS YM
                       , o.CUST_ID
                    FROM ORDERS o
                   GROUP BY
                         TO_CHAR(o.ORDER_DT, 'YYYY-MM')
                       , o.CUST_ID
                  HAVING COUNT(*) >= 2
                 ) r
           GROUP BY
                 r.YM
           ORDER BY
                 r.YM"""),
      new Example("기간별 통계", "기간의 영업일 수 (주말 빼고)", "시작일 ~ 끝일 중 토 · 일을 뺀 날 수. 공휴일 표가 있으면 NOT EXISTS 로 더 뺍니다.",
          """
          SELECT
                 COUNT(*) AS WORK_DAYS
            FROM (
                  SELECT
                         TO_DATE(:fromDt, 'YYYY-MM-DD') + LEVEL - 1 AS DT
                    FROM DUAL
                 CONNECT BY LEVEL <= TO_DATE(:toDt, 'YYYY-MM-DD') - TO_DATE(:fromDt, 'YYYY-MM-DD') + 1
                 ) d
           WHERE TO_CHAR(d.DT, 'DY', 'NLS_DATE_LANGUAGE=ENGLISH') NOT IN ('SAT', 'SUN')""",
          """
          SELECT
                 COUNT(*) AS WORK_DAYS
            FROM GENERATE_SERIES(CAST(:fromDt AS DATE), CAST(:toDt AS DATE), INTERVAL '1 day') AS d(DT)
           WHERE EXTRACT(ISODOW FROM d.DT) < 6"""),
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
      new Example("계층", "조직도 위에서 아래로 (CONNECT BY 기본)", "대표부터 아래로 펼칩니다. START WITH 는 시작할 줄, CONNECT BY PRIOR 자식칼럼 쪽이 아래로 내려가는 방향입니다.",
          """
          SELECT
                 LEVEL AS LVL
               , LPAD(' ', (LEVEL - 1) * 4) || e.EMP_NAME AS EMP_NAME
               , e.EMP_ID
               , e.MGR_ID
            FROM EMP e
           START WITH e.MGR_ID IS NULL
          CONNECT BY PRIOR e.EMP_ID = e.MGR_ID
           ORDER SIBLINGS BY
                 e.EMP_NAME""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 1 AS LVL
               , e.EMP_ID
               , e.MGR_ID
               , e.EMP_NAME
               , CAST(e.EMP_NAME AS VARCHAR(4000)) AS SORT_PATH
            FROM EMP e
           WHERE e.MGR_ID IS NULL
           UNION ALL
          SELECT
                 t.LVL + 1
               , e.EMP_ID
               , e.MGR_ID
               , e.EMP_NAME
               , CAST(t.SORT_PATH || '/' || e.EMP_NAME AS VARCHAR(4000))
            FROM EMP e
            JOIN t
              ON e.MGR_ID = t.EMP_ID
           WHERE t.LVL < 100
          )
          SELECT
                 t.LVL
               , REPEAT(' ', (t.LVL - 1) * 4) || t.EMP_NAME AS EMP_NAME
               , t.EMP_ID
               , t.MGR_ID
            FROM t
           ORDER BY
                 t.SORT_PATH"""),
      new Example("계층", "아래에서 위로 (내 상사 줄줄이)", "한 사람에서 시작해 대표까지 올라갑니다. PRIOR 를 부모 칼럼 쪽에 붙이면 위로 갑니다.",
          """
          SELECT
                 LEVEL AS UP
               , e.EMP_ID
               , e.EMP_NAME
            FROM EMP e
           START WITH e.EMP_ID = :empId
          CONNECT BY e.EMP_ID = PRIOR e.MGR_ID""",
          """
          WITH RECURSIVE up AS (
          SELECT
                 1 AS UP
               , e.EMP_ID
               , e.MGR_ID
               , e.EMP_NAME
            FROM EMP e
           WHERE e.EMP_ID = CAST(:empId AS INTEGER)
           UNION ALL
          SELECT
                 u.UP + 1
               , e.EMP_ID
               , e.MGR_ID
               , e.EMP_NAME
            FROM EMP e
            JOIN up u
              ON e.EMP_ID = u.MGR_ID
           WHERE u.UP < 100
          )
          SELECT
                 u.UP
               , u.EMP_ID
               , u.EMP_NAME
            FROM up u"""),
      new Example("계층", "경로를 한 칸에 (SYS_CONNECT_BY_PATH)", "'본사 > 개발본부 > 개발1팀' 처럼 맨 위부터의 경로를 붙입니다.",
          """
          SELECT
                 d.DEPT_ID
               , d.DEPT_NAME
               , LTRIM(SYS_CONNECT_BY_PATH(d.DEPT_NAME, ' > '), ' > ') AS PATH
            FROM DEPT d
           START WITH d.PARENT_DEPT_ID IS NULL
          CONNECT BY PRIOR d.DEPT_ID = d.PARENT_DEPT_ID""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 d.DEPT_ID
               , d.DEPT_NAME
               , CAST(d.DEPT_NAME AS VARCHAR(4000)) AS PATH
            FROM DEPT d
           WHERE d.PARENT_DEPT_ID IS NULL
           UNION ALL
          SELECT
                 d.DEPT_ID
               , d.DEPT_NAME
               , CAST(t.PATH || ' > ' || d.DEPT_NAME AS VARCHAR(4000))
            FROM DEPT d
            JOIN t
              ON d.PARENT_DEPT_ID = t.DEPT_ID
          )
          SELECT
                 t.*
            FROM t
           ORDER BY
                 t.PATH"""),
      new Example("계층", "맨 위 조상 붙이기 (CONNECT_BY_ROOT)", "줄마다 그 줄이 속한 맨 위(최상위) 부서를 붙입니다.",
          """
          SELECT
                 d.DEPT_ID
               , d.DEPT_NAME
               , CONNECT_BY_ROOT d.DEPT_NAME AS TOP_DEPT
            FROM DEPT d
           START WITH d.PARENT_DEPT_ID IS NULL
          CONNECT BY PRIOR d.DEPT_ID = d.PARENT_DEPT_ID""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 d.DEPT_ID
               , d.DEPT_NAME
               , d.DEPT_NAME AS TOP_DEPT
            FROM DEPT d
           WHERE d.PARENT_DEPT_ID IS NULL
           UNION ALL
          SELECT
                 d.DEPT_ID
               , d.DEPT_NAME
               , t.TOP_DEPT
            FROM DEPT d
            JOIN t
              ON d.PARENT_DEPT_ID = t.DEPT_ID
          )
          SELECT
                 t.*
            FROM t"""),
      new Example("계층", "끝 줄(자식 없는 것)만 (CONNECT_BY_ISLEAF)", "아래에 아무것도 없는 메뉴 · 부서 · 부품(최하위)만 봅니다.",
          """
          SELECT
                 m.MENU_ID
               , m.MENU_NAME
               , LEVEL AS LVL
            FROM MENU m
           WHERE CONNECT_BY_ISLEAF = 1
           START WITH m.PARENT_ID IS NULL
          CONNECT BY PRIOR m.MENU_ID = m.PARENT_ID""",
          """
          SELECT
                 m.MENU_ID
               , m.MENU_NAME
            FROM MENU m
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM MENU c
                   WHERE c.PARENT_ID = m.MENU_ID
                 )"""),
      new Example("계층", "자식 수 붙이기", "메뉴마다 바로 아래 자식이 몇 개인지.",
          """
          SELECT
                 m.MENU_ID
               , m.MENU_NAME
               , (SELECT COUNT(*) FROM MENU c WHERE c.PARENT_ID = m.MENU_ID) AS CHILD_CNT
            FROM MENU m""",
          """
          SELECT
                 m.MENU_ID
               , m.MENU_NAME
               , (SELECT COUNT(*) FROM MENU c WHERE c.PARENT_ID = m.MENU_ID) AS CHILD_CNT
            FROM MENU m"""),
      new Example("계층", "아래 전체 수 붙이기 (자손 수)", "메뉴마다 그 아래에 달린 모든 자손(손자까지) 수.",
          """
          SELECT
                 m.MENU_ID
               , m.MENU_NAME
               , (
                  SELECT COUNT(*) - 1
                    FROM MENU s
                   START WITH s.MENU_ID = m.MENU_ID
                 CONNECT BY PRIOR s.MENU_ID = s.PARENT_ID
                 ) AS DESC_CNT
            FROM MENU m""",
          """
          WITH RECURSIVE pair AS (
          SELECT
                 m.MENU_ID AS ROOT_ID
               , m.MENU_ID
            FROM MENU m
           UNION ALL
          SELECT
                 p.ROOT_ID
               , c.MENU_ID
            FROM MENU c
            JOIN pair p
              ON c.PARENT_ID = p.MENU_ID
          )
          SELECT
                 m.MENU_ID
               , m.MENU_NAME
               , COUNT(*) - 1 AS DESC_CNT
            FROM pair p
            JOIN MENU m
              ON m.MENU_ID = p.ROOT_ID
           GROUP BY
                 m.MENU_ID
               , m.MENU_NAME"""),
      new Example("계층", "몇 단계까지만 (깊이 제한)", "맨 위에서 2단계까지만 펼칩니다.",
          """
          SELECT
                 LEVEL AS LVL
               , m.MENU_NAME
            FROM MENU m
           START WITH m.PARENT_ID IS NULL
          CONNECT BY PRIOR m.MENU_ID = m.PARENT_ID
             AND LEVEL <= 2""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 1 AS LVL
               , m.MENU_ID
               , m.MENU_NAME
            FROM MENU m
           WHERE m.PARENT_ID IS NULL
           UNION ALL
          SELECT
                 t.LVL + 1
               , m.MENU_ID
               , m.MENU_NAME
            FROM MENU m
            JOIN t
              ON m.PARENT_ID = t.MENU_ID
           WHERE t.LVL < 2
          )
          SELECT
                 t.LVL
               , t.MENU_NAME
            FROM t"""),
      new Example("계층", "가지 통째로 빼기 vs 그 줄만 빼기", "CONNECT BY 에 쓴 조건은 그 줄과 아래 가지를 통째로 빼고, WHERE 에 쓴 조건은 그 줄만 빼고 자식은 남깁니다.",
          """
          SELECT
                 LEVEL AS LVL
               , d.DEPT_NAME
            FROM DEPT d
           START WITH d.PARENT_DEPT_ID IS NULL
          CONNECT BY PRIOR d.DEPT_ID = d.PARENT_DEPT_ID
             AND d.DEPT_NAME <> '개발본부'
          -- 그 줄만 빼려면 위 AND 줄 대신: WHERE d.DEPT_NAME <> '개발본부' (START WITH 앞에)""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 1 AS LVL
               , d.DEPT_ID
               , d.DEPT_NAME
            FROM DEPT d
           WHERE d.PARENT_DEPT_ID IS NULL
           UNION ALL
          SELECT
                 t.LVL + 1
               , d.DEPT_ID
               , d.DEPT_NAME
            FROM DEPT d
            JOIN t
              ON d.PARENT_DEPT_ID = t.DEPT_ID
           WHERE d.DEPT_NAME <> '개발본부'
          )
          SELECT
                 t.LVL
               , t.DEPT_NAME
            FROM t
          -- 그 줄만 빼려면 재귀 안 조건 대신 바깥에: WHERE t.DEPT_NAME <> '개발본부'"""),
      new Example("계층", "고리 데이터 막기 (NOCYCLE)", "부모를 잘못 넣어 고리가 생기면 CONNECT BY 는 ORA-01436 으로 멈춥니다. NOCYCLE 로 건너뛰고 CONNECT_BY_ISCYCLE 로 고리 난 줄을 찾습니다.",
          """
          SELECT
                 m.MENU_ID
               , m.PARENT_ID
               , CONNECT_BY_ISCYCLE AS IS_CYCLE
            FROM MENU m
           START WITH m.PARENT_ID IS NULL
          CONNECT BY NOCYCLE PRIOR m.MENU_ID = m.PARENT_ID""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 m.MENU_ID
               , m.PARENT_ID
               , ARRAY[m.MENU_ID] AS PATH
               , FALSE AS IS_CYCLE
            FROM MENU m
           WHERE m.PARENT_ID IS NULL
           UNION ALL
          SELECT
                 m.MENU_ID
               , m.PARENT_ID
               , t.PATH || m.MENU_ID
               , m.MENU_ID = ANY(t.PATH)
            FROM MENU m
            JOIN t
              ON m.PARENT_ID = t.MENU_ID
           WHERE NOT t.IS_CYCLE
          )
          SELECT
                 t.MENU_ID
               , t.PARENT_ID
               , t.IS_CYCLE
            FROM t"""),
      new Example("계층", "고리 난 데이터 찾기 (점검)", "어느 줄이 자기 조상을 부모로 가리키는지 찾습니다. 나오면 데이터를 고쳐야 합니다.",
          """
          SELECT
                 m.MENU_ID
               , m.PARENT_ID
            FROM MENU m
           WHERE CONNECT_BY_ISCYCLE = 1
           START WITH m.MENU_ID IN (SELECT x.MENU_ID FROM MENU x)
          CONNECT BY NOCYCLE PRIOR m.MENU_ID = m.PARENT_ID""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 m.MENU_ID AS START_ID
               , m.MENU_ID
               , ARRAY[m.MENU_ID] AS PATH
               , FALSE AS IS_CYCLE
            FROM MENU m
           UNION ALL
          SELECT
                 t.START_ID
               , m.MENU_ID
               , t.PATH || m.MENU_ID
               , m.MENU_ID = ANY(t.PATH)
            FROM MENU m
            JOIN t
              ON m.PARENT_ID = t.MENU_ID
           WHERE NOT t.IS_CYCLE
          )
          SELECT DISTINCT
                 t.START_ID AS MENU_ID
            FROM t
           WHERE t.IS_CYCLE"""),
      new Example("계층", "부모를 잃은 줄 찾기 (점검)", "부모 번호가 있는데 그 부모가 표에 없는 줄. 트리에서 빠져 화면에 안 나옵니다.",
          """
          SELECT
                 m.*
            FROM MENU m
           WHERE m.PARENT_ID IS NOT NULL
             AND NOT EXISTS (
                  SELECT 1
                    FROM MENU p
                   WHERE p.MENU_ID = m.PARENT_ID
                 )""",
          """
          SELECT
                 m.*
            FROM MENU m
           WHERE m.PARENT_ID IS NOT NULL
             AND NOT EXISTS (
                  SELECT 1
                    FROM MENU p
                   WHERE p.MENU_ID = m.PARENT_ID
                 )"""),
      new Example("계층", "형제 순서대로 정렬 (ORDER SIBLINGS BY)", "트리 모양은 지키면서 같은 부모 아래끼리만 정렬 칼럼 순으로. 그냥 ORDER BY 를 쓰면 트리가 흩어집니다.",
          """
          SELECT
                 LEVEL AS LVL
               , LPAD(' ', (LEVEL - 1) * 2) || m.MENU_NAME AS MENU_NAME
               , m.SORT_NO
            FROM MENU m
           START WITH m.PARENT_ID IS NULL
          CONNECT BY PRIOR m.MENU_ID = m.PARENT_ID
           ORDER SIBLINGS BY
                 m.SORT_NO""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 1 AS LVL
               , m.MENU_ID
               , m.MENU_NAME
               , m.SORT_NO
               , ARRAY[m.SORT_NO] AS SORT_KEY
            FROM MENU m
           WHERE m.PARENT_ID IS NULL
           UNION ALL
          SELECT
                 t.LVL + 1
               , m.MENU_ID
               , m.MENU_NAME
               , m.SORT_NO
               , t.SORT_KEY || m.SORT_NO
            FROM MENU m
            JOIN t
              ON m.PARENT_ID = t.MENU_ID
           WHERE t.LVL < 100
          )
          SELECT
                 t.LVL
               , REPEAT(' ', (t.LVL - 1) * 2) || t.MENU_NAME AS MENU_NAME
               , t.SORT_NO
            FROM t
           ORDER BY
                 t.SORT_KEY"""),
      new Example("계층", "BOM 정전개 (완제품 → 부품)", "완제품 하나에 들어가는 부품을 아래로 펼치고, 단계마다 수량을 곱해 완제품 1개당 필요 수량(TOTAL_QTY)을 냅니다. 곱셈이 필요해 CONNECT BY 대신 재귀 WITH 로 씁니다.",
          """
          WITH t (LVL, CHILD_PART, QTY, TOTAL_QTY, SEQ) AS (
          SELECT
                 1
               , b.CHILD_PART
               , b.QTY
               , b.QTY
               , b.SEQ
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.LVL + 1
               , b.CHILD_PART
               , b.QTY
               , t.TOTAL_QTY * b.QTY
               , b.SEQ
            FROM BOM b, t
           WHERE b.PARENT_PART = t.CHILD_PART
          )
          SEARCH DEPTH FIRST BY SEQ SET ORD
          SELECT
                 t.LVL
               , LPAD(' ', (t.LVL - 1) * 2) || t.CHILD_PART AS PART_NO
               , t.QTY
               , t.TOTAL_QTY
            FROM t
           ORDER BY
                 t.ORD""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 1 AS LVL
               , b.CHILD_PART
               , b.QTY
               , b.QTY AS TOTAL_QTY
               , ARRAY[b.SEQ] AS SORT_KEY
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.LVL + 1
               , b.CHILD_PART
               , b.QTY
               , t.TOTAL_QTY * b.QTY
               , t.SORT_KEY || b.SEQ
            FROM BOM b
            JOIN t
              ON b.PARENT_PART = t.CHILD_PART
           WHERE t.LVL < 100
          )
          SELECT
                 t.LVL
               , REPEAT(' ', (t.LVL - 1) * 2) || t.CHILD_PART AS PART_NO
               , t.QTY
               , t.TOTAL_QTY
            FROM t
           ORDER BY
                 t.SORT_KEY"""),
      new Example("계층", "BOM 부품별 총 필요 수량 (최하위 합계)", "완제품 1개에 최하위 부품이 모두 몇 개 필요한지 부품마다 합칩니다(여러 곳에 쓰인 볼트를 합산).",
          """
          WITH t (PART_NO, TOTAL_QTY) AS (
          SELECT
                 b.CHILD_PART
               , b.QTY
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 b.CHILD_PART
               , t.TOTAL_QTY * b.QTY
            FROM BOM b, t
           WHERE b.PARENT_PART = t.PART_NO
          )
          SELECT
                 t.PART_NO
               , SUM(t.TOTAL_QTY) AS NEED_QTY
            FROM t
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM BOM c
                   WHERE c.PARENT_PART = t.PART_NO
                 )
           GROUP BY
                 t.PART_NO
           ORDER BY
                 t.PART_NO""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.CHILD_PART AS PART_NO
               , b.QTY AS TOTAL_QTY
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 b.CHILD_PART
               , t.TOTAL_QTY * b.QTY
            FROM BOM b
            JOIN t
              ON b.PARENT_PART = t.PART_NO
          )
          SELECT
                 t.PART_NO
               , SUM(t.TOTAL_QTY) AS NEED_QTY
            FROM t
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM BOM c
                   WHERE c.PARENT_PART = t.PART_NO
                 )
           GROUP BY
                 t.PART_NO
           ORDER BY
                 t.PART_NO"""),
      new Example("계층", "BOM 원가 쌓기 (부품 단가 × 수량)", "최하위 부품 단가에 필요 수량을 곱해 완제품 1개 원가를 냅니다.",
          """
          WITH t (PART_NO, TOTAL_QTY) AS (
          SELECT
                 b.CHILD_PART
               , b.QTY
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 b.CHILD_PART
               , t.TOTAL_QTY * b.QTY
            FROM BOM b, t
           WHERE b.PARENT_PART = t.PART_NO
          )
          SELECT
                 SUM(t.TOTAL_QTY * p.UNIT_COST) AS PRODUCT_COST
            FROM t, PART p
           WHERE p.PART_NO = t.PART_NO
             AND NOT EXISTS (
                  SELECT 1
                    FROM BOM c
                   WHERE c.PARENT_PART = t.PART_NO
                 )""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.CHILD_PART AS PART_NO
               , b.QTY AS TOTAL_QTY
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 b.CHILD_PART
               , t.TOTAL_QTY * b.QTY
            FROM BOM b
            JOIN t
              ON b.PARENT_PART = t.PART_NO
          )
          SELECT
                 SUM(t.TOTAL_QTY * p.UNIT_COST) AS PRODUCT_COST
            FROM t
            JOIN PART p
              ON p.PART_NO = t.PART_NO
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM BOM c
                   WHERE c.PARENT_PART = t.PART_NO
                 )"""),
      new Example("계층", "BOM 역전개 (이 부품이 어디에 쓰이나)", "부품 하나에서 위로 올라가 그 부품이 들어가는 모든 상위 품목을 봅니다(설계 변경 영향 범위).",
          """
          SELECT
                 LEVEL AS UP
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
            FROM BOM b
           WHERE b.PARENT_PART IS NOT NULL
           START WITH b.CHILD_PART = :partNo
          CONNECT BY b.CHILD_PART = PRIOR b.PARENT_PART""",
          """
          WITH RECURSIVE up AS (
          SELECT
                 1 AS UP
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
            FROM BOM b
           WHERE b.CHILD_PART = :partNo
           UNION ALL
          SELECT
                 u.UP + 1
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
            FROM BOM b
            JOIN up u
              ON b.CHILD_PART = u.PARENT_PART
           WHERE u.UP < 100
          )
          SELECT
                 u.*
            FROM up u
           WHERE u.PARENT_PART IS NOT NULL"""),
      new Example("계층", "부서 아래 직원 모두 (하위 부서 포함)", "고른 부서와 그 아래 모든 부서에 속한 직원.",
          """
          SELECT
                 e.*
            FROM EMP e
           WHERE e.DEPT_ID IN (
                  SELECT d.DEPT_ID
                    FROM DEPT d
                   START WITH d.DEPT_ID = :deptId
                 CONNECT BY PRIOR d.DEPT_ID = d.PARENT_DEPT_ID
                 )""",
          """
          WITH RECURSIVE sub AS (
          SELECT
                 d.DEPT_ID
            FROM DEPT d
           WHERE d.DEPT_ID = CAST(:deptId AS INTEGER)
           UNION
          SELECT
                 d.DEPT_ID
            FROM DEPT d
            JOIN sub s
              ON d.PARENT_DEPT_ID = s.DEPT_ID
          )
          SELECT
                 e.*
            FROM EMP e
           WHERE e.DEPT_ID IN (SELECT s.DEPT_ID FROM sub s)"""),
      new Example("계층", "부서별 인원 · 급여 합 (하위 부서까지 합침)", "부서마다 그 아래 부서까지 합친 인원과 급여 합.",
          """
          SELECT
                 d.DEPT_ID
               , d.DEPT_NAME
               , (
                  SELECT COUNT(*)
                    FROM EMP e
                   WHERE e.DEPT_ID IN (
                          SELECT s.DEPT_ID
                            FROM DEPT s
                           START WITH s.DEPT_ID = d.DEPT_ID
                         CONNECT BY PRIOR s.DEPT_ID = s.PARENT_DEPT_ID
                         )
                 ) AS EMP_CNT
               , (
                  SELECT SUM(e.SAL)
                    FROM EMP e
                   WHERE e.DEPT_ID IN (
                          SELECT s.DEPT_ID
                            FROM DEPT s
                           START WITH s.DEPT_ID = d.DEPT_ID
                         CONNECT BY PRIOR s.DEPT_ID = s.PARENT_DEPT_ID
                         )
                 ) AS SAL_SUM
            FROM DEPT d""",
          """
          WITH RECURSIVE pair AS (
          SELECT
                 d.DEPT_ID AS ROOT_ID
               , d.DEPT_ID
            FROM DEPT d
           UNION
          SELECT
                 p.ROOT_ID
               , d.DEPT_ID
            FROM DEPT d
            JOIN pair p
              ON d.PARENT_DEPT_ID = p.DEPT_ID
          )
          SELECT
                 d.DEPT_ID
               , d.DEPT_NAME
               , COUNT(e.EMP_ID) AS EMP_CNT
               , SUM(e.SAL) AS SAL_SUM
            FROM DEPT d
            JOIN pair p
              ON p.ROOT_ID = d.DEPT_ID
            LEFT JOIN EMP e
              ON e.DEPT_ID = p.DEPT_ID
           GROUP BY
                 d.DEPT_ID
               , d.DEPT_NAME"""),
      new Example("계층", "같은 부모의 형제들", "고른 메뉴와 부모가 같은 다른 메뉴.",
          """
          SELECT
                 s.*
            FROM MENU m, MENU s
           WHERE m.MENU_ID = :menuId
             AND s.PARENT_ID = m.PARENT_ID
             AND s.MENU_ID <> m.MENU_ID
           ORDER BY
                 s.SORT_NO""",
          """
          SELECT
                 s.*
            FROM MENU m
            JOIN MENU s
              ON s.PARENT_ID = m.PARENT_ID
             AND s.MENU_ID <> m.MENU_ID
           WHERE m.MENU_ID = CAST(:menuId AS INTEGER)
           ORDER BY
                 s.SORT_NO"""),
      new Example("계층", "트리를 화면용 JSON 꼴 순서로 (부모 · 단계 · 끝 여부)", "화면 트리 컴포넌트에 넘길 칸(ID · 부모 ID · 단계 · 자식 있음)을 위에서 아래 순서로 만듭니다.",
          """
          SELECT
                 m.MENU_ID AS ID
               , m.PARENT_ID AS PID
               , m.MENU_NAME AS TEXT
               , LEVEL AS LVL
               , CASE WHEN CONNECT_BY_ISLEAF = 1 THEN 'N' ELSE 'Y' END AS HAS_CHILD
            FROM MENU m
           START WITH m.PARENT_ID IS NULL
          CONNECT BY PRIOR m.MENU_ID = m.PARENT_ID
           ORDER SIBLINGS BY
                 m.SORT_NO""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 m.MENU_ID
               , m.PARENT_ID
               , m.MENU_NAME
               , 1 AS LVL
               , ARRAY[m.SORT_NO] AS SORT_KEY
            FROM MENU m
           WHERE m.PARENT_ID IS NULL
           UNION ALL
          SELECT
                 m.MENU_ID
               , m.PARENT_ID
               , m.MENU_NAME
               , t.LVL + 1
               , t.SORT_KEY || m.SORT_NO
            FROM MENU m
            JOIN t
              ON m.PARENT_ID = t.MENU_ID
           WHERE t.LVL < 100
          )
          SELECT
                 t.MENU_ID AS ID
               , t.PARENT_ID AS PID
               , t.MENU_NAME AS TEXT
               , t.LVL
               , CASE WHEN EXISTS (SELECT 1 FROM MENU c WHERE c.PARENT_ID = t.MENU_ID) THEN 'Y' ELSE 'N' END AS HAS_CHILD
            FROM t
           ORDER BY
                 t.SORT_KEY"""),
      new Example("계층", "재귀 WITH 로 쓴 Oracle 계층 (11g R2 이상)", "Oracle 도 CONNECT BY 대신 표준 재귀 WITH 를 씁니다. PostgreSQL 로 옮길 때 RECURSIVE 한 낱말만 더하면 됩니다.",
          """
          WITH t (LVL, MENU_ID, MENU_NAME) AS (
          SELECT
                 1
               , m.MENU_ID
               , m.MENU_NAME
            FROM MENU m
           WHERE m.PARENT_ID IS NULL
           UNION ALL
          SELECT
                 t.LVL + 1
               , m.MENU_ID
               , m.MENU_NAME
            FROM MENU m, t
           WHERE m.PARENT_ID = t.MENU_ID
          )
          SEARCH DEPTH FIRST BY MENU_ID SET ORD
          CYCLE MENU_ID SET IS_CYCLE TO 'Y' DEFAULT 'N'
          SELECT
                 t.LVL
               , t.MENU_NAME
            FROM t
           ORDER BY
                 t.ORD""",
          """
          WITH RECURSIVE t (LVL, MENU_ID, MENU_NAME) AS (
          SELECT
                 1
               , m.MENU_ID
               , m.MENU_NAME
            FROM MENU m
           WHERE m.PARENT_ID IS NULL
           UNION ALL
          SELECT
                 t.LVL + 1
               , m.MENU_ID
               , m.MENU_NAME
            FROM MENU m, t
           WHERE m.PARENT_ID = t.MENU_ID
          )
          SEARCH DEPTH FIRST BY MENU_ID SET ORD
          CYCLE MENU_ID SET IS_CYCLE USING CYCLE_PATH
          SELECT
                 t.LVL
               , t.MENU_NAME
            FROM t
           ORDER BY
                 t.ORD"""),
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
      new Example("중복 · 정리", "중복 행 보기 (몇 번째 중복인지)", "같은 고객 · 같은 날 주문이 여러 건이면 몇 건인지와 순번을 붙여 다 봅니다.",
          """
          SELECT
                 o.*
               , COUNT(*) OVER (PARTITION BY o.CUST_ID, o.ORDER_DT) AS DUP_CNT
               , ROW_NUMBER() OVER (PARTITION BY o.CUST_ID, o.ORDER_DT ORDER BY o.ORDER_ID) AS DUP_NO
            FROM ORDERS o""",
          """
          SELECT
                 o.*
               , COUNT(*) OVER (PARTITION BY o.CUST_ID, o.ORDER_DT) AS DUP_CNT
               , ROW_NUMBER() OVER (PARTITION BY o.CUST_ID, o.ORDER_DT ORDER BY o.ORDER_ID) AS DUP_NO
            FROM ORDERS o"""),
      new Example("중복 · 정리", "중복 중 첫 줄만 보기", "같은 고객 · 같은 날 주문 중 번호가 가장 작은 것만.",
          """
          SELECT *
            FROM (
                  SELECT
                         o.*
                       , ROW_NUMBER() OVER (PARTITION BY o.CUST_ID, o.ORDER_DT ORDER BY o.ORDER_ID) AS RN
                    FROM ORDERS o
                 )
           WHERE RN = 1""",
          """
          SELECT *
            FROM (
                  SELECT
                         o.*
                       , ROW_NUMBER() OVER (PARTITION BY o.CUST_ID, o.ORDER_DT ORDER BY o.ORDER_ID) AS RN
                    FROM ORDERS o
                 ) x
           WHERE x.RN = 1"""),
      new Example("중복 · 정리", "중복 중 하나만 남기고 지우기", "같은 값인 행 중 하나만 남깁니다. 지우기 전에 SELECT 로 지울 대상을 꼭 먼저 보세요. Oracle 은 ROWID, PostgreSQL 은 ctid 로 행을 가립니다.",
          """
          DELETE FROM ORDERS_BAK
           WHERE ROWID IN (
                  SELECT RID
                    FROM (
                          SELECT
                                 ROWID AS RID
                               , ROW_NUMBER() OVER (PARTITION BY ORDER_ID ORDER BY ROWID) AS RN
                            FROM ORDERS_BAK
                         )
                   WHERE RN > 1
                 )""",
          """
          DELETE FROM ORDERS_BAK
           WHERE CTID IN (
                  SELECT x.CTID
                    FROM (
                          SELECT
                                 b.CTID
                               , ROW_NUMBER() OVER (PARTITION BY b.ORDER_ID ORDER BY b.CTID) AS RN
                            FROM ORDERS_BAK b
                         ) x
                   WHERE x.RN > 1
                 )"""),
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
      new Example("페이징 · 개수", "페이지 나누기 (OFFSET · FETCH)", "Oracle 12c 이상은 PostgreSQL 처럼 짧게 씁니다(21~30번째).",
          """
          SELECT
                 o.*
            FROM ORDERS o
           ORDER BY
                 o.ORDER_ID DESC
          OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           ORDER BY
                 o.ORDER_ID DESC
          OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY"""),
      new Example("페이징 · 개수", "아무거나 몇 건 뽑기 (샘플)", "무작위로 5건. 큰 표에서는 느립니다.",
          """
          SELECT *
            FROM (
                  SELECT
                         o.*
                    FROM ORDERS o
                   ORDER BY
                         DBMS_RANDOM.VALUE
                 )
           WHERE ROWNUM <= 5""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           ORDER BY
                 RANDOM()
           LIMIT 5"""),
      new Example("페이징 · 개수", "두 표 건수 한 줄로 비교", "이관 · 백업 뒤 건수가 맞는지 봅니다.",
          """
          SELECT
                 (SELECT COUNT(*) FROM ORDERS) AS NOW_CNT
               , (SELECT COUNT(*) FROM ORDERS_BAK) AS BAK_CNT
            FROM DUAL""",
          """
          SELECT
                 (SELECT COUNT(*) FROM ORDERS) AS NOW_CNT
               , (SELECT COUNT(*) FROM ORDERS_BAK) AS BAK_CNT"""),
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
      new Example("날짜", "두 시각 차이 (시간 · 분)", "등록 시각부터 지금까지 몇 시간 · 몇 분 지났는지.",
          """
          SELECT
                 o.REG_DT
               , ROUND((SYSDATE - o.REG_DT) * 24, 1) AS HOURS
               , ROUND((SYSDATE - o.REG_DT) * 1440) AS MINUTES
            FROM ORDERS o""",
          """
          SELECT
                 o.REG_DT
               , ROUND(CAST(EXTRACT(EPOCH FROM LOCALTIMESTAMP - o.REG_DT) / 3600 AS NUMERIC), 1) AS HOURS
               , ROUND(CAST(EXTRACT(EPOCH FROM LOCALTIMESTAMP - o.REG_DT) / 60 AS NUMERIC)) AS MINUTES
            FROM ORDERS o"""),
      new Example("날짜", "'YYYYMMDD' 글자 칼럼을 날짜로", "글자로 둔 날짜 칼럼을 날짜로 바꿔 비교합니다. 색인을 타려면 반대로 비교 값을 글자로 바꾸세요.",
          """
          SELECT
                 o.YMD
               , TO_DATE(o.YMD, 'YYYYMMDD') AS DT
            FROM ORDERS o
           WHERE o.YMD >= TO_CHAR(TRUNC(SYSDATE) - 7, 'YYYYMMDD')""",
          """
          SELECT
                 o.YMD
               , TO_DATE(o.YMD, 'YYYYMMDD') AS DT
            FROM ORDERS o
           WHERE o.YMD >= TO_CHAR(CURRENT_DATE - 7, 'YYYYMMDD')"""),
      new Example("날짜", "이번 주 월요일 ~ 일요일", "오늘이 든 주의 첫날과 끝날.",
          """
          SELECT
                 TRUNC(SYSDATE, 'IW') AS MON
               , TRUNC(SYSDATE, 'IW') + 6 AS SUN
            FROM DUAL""",
          """
          SELECT
                 CAST(DATE_TRUNC('week', CURRENT_DATE) AS DATE) AS MON
               , CAST(DATE_TRUNC('week', CURRENT_DATE) AS DATE) + 6 AS SUN"""),
      new Example("날짜", "올해 첫날 · 끝날 · 지난해 같은 날", "1월 1일, 12월 31일, 1년 전 오늘.",
          """
          SELECT
                 TRUNC(SYSDATE, 'YYYY') AS FIRST_DAY
               , ADD_MONTHS(TRUNC(SYSDATE, 'YYYY'), 12) - 1 AS LAST_DAY
               , ADD_MONTHS(TRUNC(SYSDATE), -12) AS YEAR_AGO
            FROM DUAL""",
          """
          SELECT
                 CAST(DATE_TRUNC('year', CURRENT_DATE) AS DATE) AS FIRST_DAY
               , CAST(DATE_TRUNC('year', CURRENT_DATE) + INTERVAL '1 year - 1 day' AS DATE) AS LAST_DAY
               , CAST(CURRENT_DATE - INTERVAL '1 year' AS DATE) AS YEAR_AGO"""),
      new Example("날짜", "유닉스 시각(초) ↔ 날짜", "1970-01-01 부터 센 초를 날짜로, 날짜를 초로.",
          """
          SELECT
                 TO_DATE('1970-01-01', 'YYYY-MM-DD') + c.LOGIN_TS / 86400 AS LOGIN_DT
               , ROUND((SYSDATE - TO_DATE('1970-01-01', 'YYYY-MM-DD')) * 86400) AS NOW_TS
            FROM CUSTOMER c""",
          """
          SELECT
                 TO_TIMESTAMP(c.LOGIN_TS) AS LOGIN_DT
               , EXTRACT(EPOCH FROM NOW()) AS NOW_TS
            FROM CUSTOMER c"""),
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
      new Example("값 바꾸기", "여러 칼럼 중 처음 비지 않은 값", "메모가 없으면 이메일, 그것도 없으면 '-'.",
          """
          SELECT
                 COALESCE(o.MEMO, o.EMAIL, '-') AS INFO
            FROM ORDERS o""",
          """
          SELECT
                 COALESCE(o.MEMO, o.EMAIL, '-') AS INFO
            FROM ORDERS o"""),
      new Example("값 바꾸기", "구간으로 등급 매기기 (CASE)", "합계 금액으로 고객 등급을 매깁니다. 위에서부터 처음 맞는 줄이 쓰입니다.",
          """
          SELECT
                 o.CUST_ID
               , SUM(o.AMOUNT) AS SUM_AMT
               , CASE
                      WHEN SUM(o.AMOUNT) >= 1000000 THEN 'VIP'
                      WHEN SUM(o.AMOUNT) >= 100000 THEN 'GOLD'
                      ELSE 'NORMAL'
                 END AS GRADE
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID""",
          """
          SELECT
                 o.CUST_ID
               , SUM(o.AMOUNT) AS SUM_AMT
               , CASE
                      WHEN SUM(o.AMOUNT) >= 1000000 THEN 'VIP'
                      WHEN SUM(o.AMOUNT) >= 100000 THEN 'GOLD'
                      ELSE 'NORMAL'
                 END AS GRADE
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID"""),
      new Example("값 바꾸기", "합계가 없으면 0 (NULL 합계)", "행이 없거나 다 비면 SUM 은 NULL 입니다. 바깥에서 0 으로 바꿉니다.",
          """
          SELECT
                 c.CUST_ID
               , NVL(SUM(o.AMOUNT), 0) AS SUM_AMT
            FROM CUSTOMER c, ORDERS o
           WHERE c.CUST_ID = o.CUST_ID(+)
           GROUP BY
                 c.CUST_ID""",
          """
          SELECT
                 c.CUST_ID
               , COALESCE(SUM(o.AMOUNT), 0) AS SUM_AMT
            FROM CUSTOMER c
            LEFT JOIN ORDERS o
              ON o.CUST_ID = c.CUST_ID
           GROUP BY
                 c.CUST_ID"""),
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
      new Example("문자", "글자 위치 찾기", "메모에서 '@' 가 몇 번째 글자인지(없으면 0).",
          """
          SELECT
                 o.MEMO
               , INSTR(o.MEMO, '@') AS AT_POS
            FROM ORDERS o""",
          """
          SELECT
                 o.MEMO
               , STRPOS(o.MEMO, '@') AS AT_POS
            FROM ORDERS o"""),
      new Example("문자", "앞뒤 특정 글자 지우기", "앞뒤 공백, 앞의 0, 뒤의 쉼표를 지웁니다.",
          """
          SELECT
                 TRIM(o.MEMO) AS T
               , LTRIM(o.YMD, '0') AS NO_ZERO
               , RTRIM(o.MEMO, ',') AS NO_COMMA
            FROM ORDERS o""",
          """
          SELECT
                 TRIM(o.MEMO) AS T
               , LTRIM(o.YMD, '0') AS NO_ZERO
               , RTRIM(o.MEMO, ',') AS NO_COMMA
            FROM ORDERS o"""),
      new Example("문자", "전화번호 꼴 맞추기", "숫자만 있는 01012345678 을 010-1234-5678 로.",
          """
          SELECT
                 REGEXP_REPLACE(o.TEL, '^(\\d{3})(\\d{3,4})(\\d{4})$', '\\1-\\2-\\3') AS TEL
            FROM ORDERS o""",
          """
          SELECT
                 REGEXP_REPLACE(o.TEL, '^([0-9]{3})([0-9]{3,4})([0-9]{4})$', '\\1-\\2-\\3') AS TEL
            FROM ORDERS o"""),
      new Example("문자", "이메일 꼴이 아닌 값 찾기", "@ 와 . 이 제자리에 없는 값.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE NOT REGEXP_LIKE(o.EMAIL, '^[^@ ]+@[^@ ]+\\.[^@ ]+$')""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.EMAIL !~ '^[^@ ]+@[^@ ]+[.][^@ ]+$'"""),
      new Example("문자", "숫자만 남기기", "하이픈 · 공백 등 숫자가 아닌 것을 다 지웁니다.",
          """
          SELECT
                 REGEXP_REPLACE(o.TEL, '[^0-9]', '') AS DIGITS
            FROM ORDERS o""",
          """
          SELECT
                 REGEXP_REPLACE(o.TEL, '[^0-9]', '', 'g') AS DIGITS
            FROM ORDERS o"""),
      new Example("문자", "여러 줄을 한 칸에 (겹친 값 빼고)", "고객마다 상태 값을 겹치지 않게 쉼표로 잇습니다(Oracle 19c 이상 DISTINCT).",
          """
          SELECT
                 o.CUST_ID
               , LISTAGG(DISTINCT o.STATUS, ', ') WITHIN GROUP (ORDER BY o.STATUS) AS STATUSES
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID""",
          """
          SELECT
                 o.CUST_ID
               , STRING_AGG(DISTINCT o.STATUS, ', ' ORDER BY o.STATUS) AS STATUSES
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID"""),
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
      new Example("넣기 · 고치기", "없을 때만 넣기", "같은 번호가 이미 있으면 넣지 않습니다.",
          """
          INSERT INTO ORDERS_BAK (ORDER_ID, CUST_ID, AMOUNT, STATUS, ORDER_DT)
          SELECT
                 o.ORDER_ID
               , o.CUST_ID
               , o.AMOUNT
               , o.STATUS
               , o.ORDER_DT
            FROM ORDERS o
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM ORDERS_BAK b
                   WHERE b.ORDER_ID = o.ORDER_ID
                 )""",
          """
          INSERT INTO ORDERS_BAK (ORDER_ID, CUST_ID, AMOUNT, STATUS, ORDER_DT)
          SELECT
                 o.ORDER_ID
               , o.CUST_ID
               , o.AMOUNT
               , o.STATUS
               , o.ORDER_DT
            FROM ORDERS o
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM ORDERS_BAK b
                   WHERE b.ORDER_ID = o.ORDER_ID
                 )"""),
      new Example("넣기 · 고치기", "다른 표에 없는 것 지우기", "주문 표에 없는 상세 줄(고아)을 지웁니다. 먼저 SELECT 로 건수를 보세요.",
          """
          DELETE FROM ORDER_ITEM i
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM ORDERS o
                   WHERE o.ORDER_ID = i.ORDER_ID
                 )""",
          """
          DELETE FROM ORDER_ITEM i
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM ORDERS o
                   WHERE o.ORDER_ID = i.ORDER_ID
                 )"""),
      new Example("넣기 · 고치기", "다른 표 값으로 여러 칼럼 고치기 (MERGE)", "백업 표의 금액 · 상태로 주문 표를 고칩니다. 짝이 있는 행만 바뀝니다.",
          """
          MERGE INTO ORDERS o
          USING ORDERS_BAK b
             ON (o.ORDER_ID = b.ORDER_ID)
           WHEN MATCHED THEN
                UPDATE SET o.AMOUNT = b.AMOUNT
                         , o.STATUS = b.STATUS""",
          """
          UPDATE ORDERS o
             SET AMOUNT = b.AMOUNT
               , STATUS = b.STATUS
            FROM ORDERS_BAK b
           WHERE b.ORDER_ID = o.ORDER_ID"""),
      new Example("넣기 · 고치기", "순번 다시 매기기", "주문마다 상세 줄 번호(LINE_NO)를 1, 2, 3 … 으로 다시 매깁니다.",
          """
          MERGE INTO ORDER_ITEM t
          USING (
                  SELECT
                         i.ROWID AS RID
                       , ROW_NUMBER() OVER (PARTITION BY i.ORDER_ID ORDER BY i.ITEM_ID) AS RN
                    FROM ORDER_ITEM i
                ) s
             ON (t.ROWID = s.RID)
           WHEN MATCHED THEN
                UPDATE SET t.LINE_NO = s.RN""",
          """
          UPDATE ORDER_ITEM t
             SET LINE_NO = s.RN
            FROM (
                  SELECT
                         i.ITEM_ID
                       , ROW_NUMBER() OVER (PARTITION BY i.ORDER_ID ORDER BY i.ITEM_ID) AS RN
                    FROM ORDER_ITEM i
                 ) s
           WHERE s.ITEM_ID = t.ITEM_ID"""),
      new Example("넣기 · 고치기", "두 값 서로 바꾸기 (CASE UPDATE)", "상태 'A' 는 'B' 로, 'B' 는 'A' 로 한 번에 바꿉니다.",
          """
          UPDATE ORDERS
             SET STATUS = CASE STATUS WHEN 'A' THEN 'B' WHEN 'B' THEN 'A' END
           WHERE STATUS IN ('A', 'B')""",
          """
          UPDATE ORDERS
             SET STATUS = CASE STATUS WHEN 'A' THEN 'B' WHEN 'B' THEN 'A' END
           WHERE STATUS IN ('A', 'B')"""),
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
