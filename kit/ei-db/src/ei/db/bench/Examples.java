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
           WHERE c.CUST_ID = o.CUST_ID"""));
}
