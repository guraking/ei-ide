package ei.db.bench;

import java.util.Map;

/**
 * MySQL 8 로 따로 쓴 예제 SQL(예제 제목 → SQL). PostgreSQL 글을 MysqlConvert 로 바꿀 수 없는 것만 둔다
 * (FULL OUTER JOIN · GENERATE_SERIES · 배열 · 카탈로그 표 · 익명 블록 · MERGE 등).
 * 여기 없는 예제는 MysqlConvert.convert(PostgreSQL 글)를 보인다. 모든 예제는 MySQL 8.0 에서 실행해 확인했다.
 */
final class MysqlExamples {
  private MysqlExamples() {}

  static final Map<String, String> BY_TITLE = Map.ofEntries(
      Map.entry("값 목록을 테이블처럼 쓰기",
          """
          SELECT
                 v.CODE
               , v.NAME
            FROM (
                  SELECT 'NEW' AS CODE, '접수' AS NAME
                   UNION ALL
                  SELECT 'WORK', '진행'
                   UNION ALL
                  SELECT 'DONE', '완료'
                 ) v"""),
      Map.entry("여러 줄 저장 (화면 테이블 → MERGE, 지운 줄은 DELETE)",
          """
          -- MySQL 에는 MERGE 가 없어 INSERT … ON DUPLICATE KEY UPDATE 로 씁니다(ORDER_ID · LINE_NO 에 UNIQUE 제약이 있어야 함). ITEM_ID 는 AUTO_INCREMENT 로 둡니다
          INSERT INTO ORDER_ITEM (ORDER_ID, LINE_NO, PART_NO, QTY, PRICE)
          VALUES (:orderId, :lineNo, :partNo, :qty, :price)
              ON DUPLICATE KEY UPDATE
                 PART_NO = VALUES(PART_NO)
               , QTY = VALUES(QTY)
               , PRICE = VALUES(PRICE)"""),
      Map.entry("양쪽 다 보기 (FULL OUTER JOIN)",
          """
          -- MySQL 에는 FULL OUTER JOIN 이 없어 LEFT JOIN 과 「오른쪽에만 있는 것」을 UNION ALL 로 붙입니다
          SELECT
                 o.ORDER_ID
               , o.AMOUNT AS NOW_AMT
               , b.AMOUNT AS BAK_AMT
            FROM ORDERS o
            LEFT JOIN ORDERS_BAK b
              ON b.ORDER_ID = o.ORDER_ID
           UNION ALL
          SELECT
                 b.ORDER_ID
               , NULL
               , b.AMOUNT
            FROM ORDERS_BAK b
           WHERE NOT EXISTS (SELECT 1 FROM ORDERS o WHERE o.ORDER_ID = b.ORDER_ID)"""),
      Map.entry("모든 조합 합계 (CUBE)",
          """
          -- MySQL 에는 CUBE 가 없어 WITH ROLLUP 두 번(앞뒤 순서 바꿔)을 합칩니다
          SELECT
                 o.CUST_ID
               , o.STATUS
               , SUM(o.AMOUNT) AS SUM_AMT
               , GROUPING(o.CUST_ID) AS G_CUST
               , GROUPING(o.STATUS) AS G_STATUS
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID
               , o.STATUS
            WITH ROLLUP
           UNION
          SELECT
                 o.CUST_ID
               , o.STATUS
               , SUM(o.AMOUNT)
               , GROUPING(o.CUST_ID)
               , GROUPING(o.STATUS)
            FROM ORDERS o
           GROUP BY
                 o.STATUS
               , o.CUST_ID
            WITH ROLLUP"""),
      Map.entry("골라 묶기 (GROUPING SETS)",
          """
          -- MySQL 에는 GROUPING SETS 가 없어 묶음마다 따로 집계해 UNION ALL 로 붙입니다
          SELECT
                 o.CUST_ID
               , NULL AS STATUS
               , SUM(o.AMOUNT) AS SUM_AMT
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID
           UNION ALL
          SELECT
                 NULL
               , o.STATUS
               , SUM(o.AMOUNT)
            FROM ORDERS o
           GROUP BY
                 o.STATUS"""),
      Map.entry("그룹마다 최댓값인 행 하나 (전체 칼럼)",
          """
          SELECT
                 x.*
            FROM (
                  SELECT
                         o.*
                       , ROW_NUMBER() OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT DESC) AS RN
                    FROM ORDERS o
                 ) x
           WHERE x.RN = 1
           ORDER BY
                 x.CUST_ID"""),
      Map.entry("중앙값 · 백분위",
          """
          -- MySQL 에는 PERCENTILE_CONT 가 없어 순번으로 가운데 · 90% 자리 값을 고릅니다
          SELECT
                 AVG(CASE WHEN x.RN IN (FLOOR((x.CNT + 1) / 2), CEIL((x.CNT + 1) / 2)) THEN x.AMOUNT END) AS MED
               , MIN(CASE WHEN x.RN >= CEIL(x.CNT * 0.9) THEN x.AMOUNT END) AS P90
            FROM (
                  SELECT
                         o.AMOUNT
                       , ROW_NUMBER() OVER (ORDER BY o.AMOUNT) AS RN
                       , COUNT(*) OVER () AS CNT
                    FROM ORDERS o
                   WHERE o.AMOUNT IS NOT NULL
                 ) x"""),
      Map.entry("시간별 건수 (0 건인 시도 빠짐없이)",
          """
          WITH RECURSIVE h (H) AS (
              SELECT 0
               UNION ALL
              SELECT H + 1 FROM h WHERE H < 23
          )
          SELECT
                 LPAD(h.H, 2, '0') AS HH
               , COUNT(o.ORDER_ID) AS CNT
            FROM h
            LEFT JOIN ORDERS o
              ON HOUR(o.REG_DT) = h.H
             AND o.REG_DT >= CURRENT_DATE
             AND o.REG_DT < CURRENT_DATE + INTERVAL 1 DAY
           GROUP BY
                 h.H
           ORDER BY
                 h.H"""),
      Map.entry("일별 건수 (0 건인 날도 빠짐없이)",
          """
          WITH RECURSIVE d (DT) AS (
              SELECT CAST(:fromDt AS DATE)
               UNION ALL
              SELECT DT + INTERVAL 1 DAY FROM d WHERE DT < CAST(:toDt AS DATE)
          )
          SELECT
                 d.DT
               , COUNT(o.ORDER_ID) AS CNT
            FROM d
            LEFT JOIN ORDERS o
              ON o.ORDER_DT = d.DT
           GROUP BY
                 d.DT
           ORDER BY
                 d.DT"""),
      Map.entry("올해 1~12월 빠짐없이",
          """
          WITH RECURSIVE m (MON) AS (
              SELECT MAKEDATE(YEAR(CURRENT_DATE), 1)
               UNION ALL
              SELECT MON + INTERVAL 1 MONTH FROM m WHERE MON < MAKEDATE(YEAR(CURRENT_DATE), 1) + INTERVAL 11 MONTH
          )
          SELECT
                 DATE_FORMAT(m.MON, '%Y-%m') AS YM
               , COUNT(o.ORDER_ID) AS CNT
               , COALESCE(SUM(o.AMOUNT), 0) AS SUM_AMT
            FROM m
            LEFT JOIN ORDERS o
              ON o.ORDER_DT >= m.MON
             AND o.ORDER_DT < m.MON + INTERVAL 1 MONTH
           GROUP BY
                 m.MON
           ORDER BY
                 m.MON"""),
      Map.entry("분기별 · 반기별",
          """
          SELECT
                 YEAR(o.ORDER_DT) AS YY
               , QUARTER(o.ORDER_DT) AS QTR
               , CASE WHEN QUARTER(o.ORDER_DT) IN (1, 2) THEN '상반기' ELSE '하반기' END AS HALF
               , COUNT(*) AS CNT
            FROM ORDERS o
           GROUP BY
                 YY
               , QTR
               , HALF
           ORDER BY
                 YY
               , QTR"""),
      Map.entry("기간의 영업일 수 (주말 빼고)",
          """
          WITH RECURSIVE d (DT) AS (
              SELECT CAST(:fromDt AS DATE)
               UNION ALL
              SELECT DT + INTERVAL 1 DAY FROM d WHERE DT < CAST(:toDt AS DATE)
          )
          SELECT
                 COUNT(*) AS WORK_DAYS
            FROM d
           WHERE WEEKDAY(d.DT) < 5"""),
      Map.entry("1 부터 10 까지 숫자 줄",
          """
          WITH RECURSIVE g (N) AS (
              SELECT 1
               UNION ALL
              SELECT N + 1 FROM g WHERE N < 10
          )
          SELECT
                 g.N
            FROM g"""),
      Map.entry("이번 달 날짜 다 만들기 (달력)",
          """
          WITH RECURSIVE g (DT) AS (
              SELECT DATE(DATE_FORMAT(CURRENT_DATE, '%Y-%m-01'))
               UNION ALL
              SELECT DT + INTERVAL 1 DAY FROM g WHERE DT < LAST_DAY(CURRENT_DATE)
          )
          SELECT
                 g.DT
            FROM g"""),
      Map.entry("빠진 번호 찾기",
          """
          -- 재귀 깊이는 기본 1000 까지입니다. 번호가 더 크면 SET SESSION cte_max_recursion_depth = 100000 을 먼저 실행하세요
          WITH RECURSIVE n (N) AS (
              SELECT 1
               UNION ALL
              SELECT N + 1 FROM n WHERE N < (SELECT MAX(x.ORDER_ID) FROM ORDERS x)
          )
          SELECT
                 n.N AS MISSING_ID
            FROM n
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM ORDERS o
                   WHERE o.ORDER_ID = n.N
                 )"""),
      Map.entry("수량만큼 줄 늘리기 (라벨 출력용)",
          """
          WITH RECURSIVE n (N) AS (
              SELECT 1
               UNION ALL
              SELECT N + 1 FROM n WHERE N < 1000
          )
          SELECT
                 i.ORDER_ID
               , i.PART_NO
               , n.N AS LABEL_NO
            FROM ORDER_ITEM i
            JOIN n
              ON n.N <= i.QTY
           ORDER BY
                 i.ORDER_ID
               , i.PART_NO
               , n.N"""),
      Map.entry("월 목록 만들기 (최근 12개월)",
          """
          WITH RECURSIVE g (MON) AS (
              SELECT DATE(DATE_FORMAT(CURRENT_DATE, '%Y-%m-01')) - INTERVAL 11 MONTH
               UNION ALL
              SELECT MON + INTERVAL 1 MONTH FROM g WHERE MON < DATE(DATE_FORMAT(CURRENT_DATE, '%Y-%m-01'))
          )
          SELECT
                 DATE_FORMAT(g.MON, '%Y-%m') AS YM
            FROM g
           ORDER BY
                 YM"""),
      Map.entry("고리 데이터 막기 (NOCYCLE)",
          """
          -- 지나온 ID 를 쉼표 글로 들고 다니며, 이미 지나온 ID 가 또 나오면 고리로 보고 멈춥니다
          WITH RECURSIVE t AS (
          SELECT
                 m.MENU_ID
               , m.PARENT_ID
               , CAST(m.MENU_ID AS CHAR(4000)) AS PATH
               , 0 AS IS_CYCLE
            FROM MENU m
           WHERE m.PARENT_ID IS NULL
           UNION ALL
          SELECT
                 m.MENU_ID
               , m.PARENT_ID
               , CONCAT(t.PATH, ',', m.MENU_ID)
               , FIND_IN_SET(m.MENU_ID, t.PATH) > 0
            FROM MENU m
            JOIN t
              ON m.PARENT_ID = t.MENU_ID
           WHERE t.IS_CYCLE = 0
          )
          SELECT
                 t.MENU_ID
               , t.PARENT_ID
               , t.IS_CYCLE
            FROM t"""),
      Map.entry("고리 난 데이터 찾기 (점검)",
          """
          WITH RECURSIVE t AS (
          SELECT
                 m.MENU_ID AS START_ID
               , m.MENU_ID
               , CAST(m.MENU_ID AS CHAR(4000)) AS PATH
               , 0 AS IS_CYCLE
            FROM MENU m
           UNION ALL
          SELECT
                 t.START_ID
               , m.MENU_ID
               , CONCAT(t.PATH, ',', m.MENU_ID)
               , FIND_IN_SET(m.MENU_ID, t.PATH) > 0
            FROM MENU m
            JOIN t
              ON m.PARENT_ID = t.MENU_ID
           WHERE t.IS_CYCLE = 0
          )
          SELECT DISTINCT
                 t.START_ID AS MENU_ID
            FROM t
           WHERE t.IS_CYCLE = 1"""),
      Map.entry("형제 순서대로 정렬 (ORDER SIBLINGS BY)",
          """
          -- 형제 순서(SORT_NO)를 0 채운 글로 이어 붙여 정렬 키로 씁니다
          WITH RECURSIVE t AS (
          SELECT
                 1 AS LVL
               , m.MENU_ID
               , m.MENU_NAME
               , m.SORT_NO
               , CAST(LPAD(m.SORT_NO, 5, '0') AS CHAR(4000)) AS SORT_KEY
            FROM MENU m
           WHERE m.PARENT_ID IS NULL
           UNION ALL
          SELECT
                 t.LVL + 1
               , m.MENU_ID
               , m.MENU_NAME
               , m.SORT_NO
               , CONCAT(t.SORT_KEY, '/', LPAD(m.SORT_NO, 5, '0'))
            FROM MENU m
            JOIN t
              ON m.PARENT_ID = t.MENU_ID
           WHERE t.LVL < 100
          )
          SELECT
                 t.LVL
               , CONCAT(REPEAT(' ', (t.LVL - 1) * 2), t.MENU_NAME) AS MENU_NAME
               , t.SORT_NO
            FROM t
           ORDER BY
                 t.SORT_KEY"""),
      Map.entry("트리를 화면용 JSON 꼴 순서로 (부모 · 단계 · 끝 여부)",
          """
          WITH RECURSIVE t AS (
          SELECT
                 m.MENU_ID
               , m.PARENT_ID
               , m.MENU_NAME
               , 1 AS LVL
               , CAST(LPAD(m.SORT_NO, 5, '0') AS CHAR(4000)) AS SORT_KEY
            FROM MENU m
           WHERE m.PARENT_ID IS NULL
           UNION ALL
          SELECT
                 m.MENU_ID
               , m.PARENT_ID
               , m.MENU_NAME
               , t.LVL + 1
               , CONCAT(t.SORT_KEY, '/', LPAD(m.SORT_NO, 5, '0'))
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
      Map.entry("재귀 WITH 로 쓴 Oracle 계층 (11g R2 이상)",
          """
          -- MySQL 에는 SEARCH DEPTH FIRST · CYCLE 절이 없어 정렬 키 글과 깊이 제한으로 같은 결과를 냅니다
          WITH RECURSIVE t (LVL, MENU_ID, MENU_NAME, ORD) AS (
          SELECT
                 1
               , m.MENU_ID
               , m.MENU_NAME
               , CAST(LPAD(m.MENU_ID, 10, '0') AS CHAR(4000))
            FROM MENU m
           WHERE m.PARENT_ID IS NULL
           UNION ALL
          SELECT
                 t.LVL + 1
               , m.MENU_ID
               , m.MENU_NAME
               , CONCAT(t.ORD, '/', LPAD(m.MENU_ID, 10, '0'))
            FROM MENU m, t
           WHERE m.PARENT_ID = t.MENU_ID
             AND t.LVL < 100
          )
          SELECT
                 t.LVL
               , t.MENU_NAME
            FROM t
           ORDER BY
                 t.ORD"""),
      Map.entry("페이지 나누기 (ROWNUM 세 겹, Oracle 11g 이하)",
          """
          -- MySQL 의 LIMIT · OFFSET 에는 식을 쓸 수 없어 숫자를 넣습니다: OFFSET = (페이지 번호 - 1) × 페이지 크기
          SELECT
                 o.ORDER_ID
               , o.ORDER_DT
               , o.AMOUNT
            FROM ORDERS o
           ORDER BY
                 o.ORDER_DT DESC
               , o.ORDER_ID DESC
           LIMIT 10 OFFSET 20"""),
      Map.entry("몇 달 · 몇 년 지났는지",
          """
          SELECT
                 c.CUST_ID
               , TIMESTAMPDIFF(MONTH, c.REG_DT, CURRENT_DATE) AS MONTHS
               , TIMESTAMPDIFF(YEAR, c.REG_DT, CURRENT_DATE) AS YEARS
            FROM CUSTOMER c"""),
      Map.entry("유닉스 시각(초) ↔ 날짜",
          """
          SELECT
                 FROM_UNIXTIME(c.LOGIN_TS) AS LOGIN_DT
               , UNIX_TIMESTAMP(NOW()) AS NOW_TS
            FROM CUSTOMER c"""),
      Map.entry("나이 · 근속 연수 (만 나이)",
          """
          SELECT
                 p.PROJ_ID
               , TIMESTAMPDIFF(YEAR, p.START_DT, CURRENT_DATE) AS YEARS
            FROM PROJECT p"""),
      Map.entry("영업일 N 일 뒤 (주말 건너뛰기)",
          """
          WITH RECURSIVE g (DT) AS (
              SELECT CURRENT_DATE + INTERVAL 1 DAY
               UNION ALL
              SELECT DT + INTERVAL 1 DAY FROM g WHERE DT < CURRENT_DATE + INTERVAL 30 DAY
          )
          SELECT
                 MAX(d.DT) AS DUE_DT
            FROM (
                  SELECT
                         g.DT
                    FROM g
                   WHERE WEEKDAY(g.DT) < 5
                   ORDER BY
                         g.DT
                   LIMIT 5
                 ) d"""),
      Map.entry("대문자 · 소문자 · 첫 글자만 대문자",
          """
          -- MySQL 에는 INITCAP 이 없어 첫 글자만 대문자로 바꿉니다(낱말마다는 아님)
          SELECT
                 UPPER(c.CUST_NAME) AS UP
               , LOWER(c.CUST_NAME) AS LOW
               , CONCAT(UPPER(LEFT(c.CUST_NAME, 1)), LOWER(SUBSTRING(c.CUST_NAME, 2))) AS CAP
            FROM CUSTOMER c"""),
      Map.entry("쉼표 글자를 여러 줄로 나누기",
          """
          -- 쉼표 수 + 1 만큼 번호 줄을 만들어 n 번째 조각을 꺼냅니다(조각 1000개까지)
          WITH RECURSIVE n (N) AS (
              SELECT 1
               UNION ALL
              SELECT N + 1 FROM n WHERE N < 1000
          )
          SELECT
                 TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(:csv, ',', n.N), ',', -1)) AS VAL
            FROM n
           WHERE n.N <= CHAR_LENGTH(:csv) - CHAR_LENGTH(REPLACE(:csv, ',', '')) + 1"""),
      Map.entry("끝수 맞추기 (나눈 금액 합이 원래 금액과 같게)",
          """
          WITH RECURSIVE x (N) AS (
              SELECT 1
               UNION ALL
              SELECT N + 1 FROM x WHERE N < 3
          )
          SELECT
                 x.N
               , CASE WHEN x.N = 3 THEN 10000 - FLOOR(10000 / 3) * 2 ELSE FLOOR(10000 / 3) END AS AMT
            FROM x"""),
      Map.entry("다른 테이블 값으로 고치기",
          """
          UPDATE ORDERS o
            JOIN CUSTOMER c
              ON c.CUST_ID = o.CUST_ID
             SET o.GRADE = c.GRADE"""),
      Map.entry("한 줄 넣기",
          """
          -- MySQL 은 시퀀스 대신 AUTO_INCREMENT 칼럼에 맡깁니다(ORDER_ID 를 빼고 넣음). 넣은 번호는 SELECT LAST_INSERT_ID()
          INSERT INTO ORDERS (
                 CUST_ID
               , AMOUNT
               , STATUS
               , ORDER_DT
          ) VALUES (
                 :custId
               , :amount
               , 'NEW'
               , CURRENT_DATE
          )"""),
      Map.entry("있으면 고치고 없으면 넣기 (MERGE)",
          """
          -- MySQL 에는 MERGE 가 없어 INSERT … SELECT … ON DUPLICATE KEY UPDATE 로 씁니다(ORDER_ID 가 PK 여야 함)
          INSERT INTO ORDERS_BAK (ORDER_ID, CUST_ID, AMOUNT, STATUS, ORDER_DT)
          SELECT
                 o.ORDER_ID
               , o.CUST_ID
               , o.AMOUNT
               , o.STATUS
               , o.ORDER_DT
            FROM ORDERS o
              ON DUPLICATE KEY UPDATE
                 AMOUNT = VALUES(AMOUNT)"""),
      Map.entry("다른 테이블 값으로 여러 칼럼 고치기 (MERGE)",
          """
          UPDATE ORDERS o
            JOIN ORDERS_BAK b
              ON b.ORDER_ID = o.ORDER_ID
             SET o.AMOUNT = b.AMOUNT
               , o.STATUS = b.STATUS"""),
      Map.entry("순번 다시 매기기",
          """
          UPDATE ORDER_ITEM t
            JOIN (
                  SELECT
                         i.ITEM_ID
                       , ROW_NUMBER() OVER (PARTITION BY i.ORDER_ID ORDER BY i.ITEM_ID) AS RN
                    FROM ORDER_ITEM i
                 ) s
              ON s.ITEM_ID = t.ITEM_ID
             SET t.LINE_NO = s.RN"""),
      Map.entry("큰 테이블 나눠 고치기 (한 번에 1만 줄씩)",
          """
          -- 고친 줄이 0 이 될 때까지 되풀이해 실행합니다
          UPDATE IF_PART
             SET STATUS = 'ARCHIVED'
           WHERE STATUS = 'DONE'
             AND REG_DT < NOW() - INTERVAL 365 DAY
           LIMIT 10000"""),
      Map.entry("백업 테이블 값으로 고친 칼럼 되돌리기",
          """
          UPDATE ORDERS o
            JOIN ORDERS_BAK b
              ON b.ORDER_ID = o.ORDER_ID
             SET o.STATUS = b.STATUS
           WHERE NOT (b.STATUS <=> o.STATUS)"""),
      Map.entry("칼럼 더하기 · 길이 늘리기",
          """
          ALTER TABLE ORDERS ADD COLUMN DELIVERY_DT DATE
          ;
          -- MODIFY 는 칼럼 정의 전체를 다시 씁니다. NOT NULL · DEFAULT · COMMENT 가 있었으면 같이 적어야 합니다
          ALTER TABLE ORDERS MODIFY COLUMN MEMO VARCHAR(2000)"""),
      Map.entry("테이블 · 칼럼 설명 달기",
          """
          -- MODIFY 는 칼럼 정의 전체를 다시 씁니다. 기존 타입 · NOT NULL · DEFAULT 를 그대로 적고 COMMENT 만 더합니다
          ALTER TABLE ORDERS COMMENT = '주문'
          ;
          ALTER TABLE ORDERS MODIFY COLUMN ORDER_DT DATE COMMENT '주문일'"""),
      Map.entry("기본값 주기 (넣을 때 비우면 이 값)",
          """
          -- DATE 칼럼의 식 기본값은 8.0.13 부터 괄호로 감쌉니다
          ALTER TABLE PART ALTER COLUMN REG_DT SET DEFAULT (CURRENT_DATE)"""),
      Map.entry("NOT NULL 걸기 · 풀기",
          """
          -- MySQL 은 MODIFY 로 칼럼 정의 전체를 다시 씁니다. 풀 때는 NOT NULL 을 빼고 같은 문장
          ALTER TABLE PART MODIFY COLUMN PART_NAME VARCHAR(200) NOT NULL"""),
      Map.entry("시퀀스 만들기 · 다음 번호 맞추기",
          """
          -- MySQL 에는 시퀀스가 없어 AUTO_INCREMENT 시작 값을 맞춥니다
          ALTER TABLE ISSUE AUTO_INCREMENT = 1000"""),
      Map.entry("자동 번호 칼럼 (IDENTITY, 12c 이상)",
          """
          CREATE TABLE TB_MEMO (
              MEMO_ID BIGINT AUTO_INCREMENT PRIMARY KEY
            , MEMO_TEXT VARCHAR(4000)
            , REG_DT DATE DEFAULT (CURRENT_DATE)
          )"""),
      Map.entry("임시 테이블 (세션 · 트랜잭션 동안만)",
          """
          -- MySQL 임시 테이블은 접속(세션)이 끝날 때 사라집니다. 트랜잭션마다 비우는 꼴은 없습니다
          CREATE TEMPORARY TABLE TMP_PART_LIST (
              PART_NO VARCHAR(30)
          )"""),
      Map.entry("다른 이름으로 부르기 (시노님 · 검색 경로)",
          """
          -- MySQL 에는 시노님 · 검색 경로가 없습니다. 다른 DB 의 테이블은 DB이름.테이블 로 부르거나 USE DB이름 으로 기본 DB 를 바꿉니다
          SELECT
                 DATABASE() AS NOW_SCHEMA"""),
      Map.entry("테이블의 칼럼 목록과 설명",
          """
          SELECT
                 c.ORDINAL_POSITION
               , c.COLUMN_NAME
               , c.DATA_TYPE
               , c.CHARACTER_MAXIMUM_LENGTH
               , c.IS_NULLABLE
               , c.COLUMN_COMMENT AS COMMENTS
            FROM INFORMATION_SCHEMA.COLUMNS c
           WHERE c.TABLE_SCHEMA = DATABASE()
             AND c.TABLE_NAME = 'orders'
           ORDER BY
                 c.ORDINAL_POSITION"""),
      Map.entry("다음 번호 (시퀀스)",
          """
          -- MySQL 에는 시퀀스가 없습니다. 테이블의 다음 AUTO_INCREMENT 값을 봅니다(통계 값이라 ANALYZE TABLE 뒤에 정확합니다)
          SELECT
                 t.AUTO_INCREMENT AS NEXT_ID
            FROM INFORMATION_SCHEMA.TABLES t
           WHERE t.TABLE_SCHEMA = DATABASE()
             AND t.TABLE_NAME = 'orders'"""),
      Map.entry("테이블의 인덱스 목록",
          """
          SELECT
                 s.INDEX_NAME
               , GROUP_CONCAT(s.COLUMN_NAME ORDER BY s.SEQ_IN_INDEX SEPARATOR ', ') AS INDEX_COLUMNS
               , MAX(CASE WHEN s.NON_UNIQUE = 0 THEN 'UNIQUE' ELSE '' END) AS UNIQUENESS
            FROM INFORMATION_SCHEMA.STATISTICS s
           WHERE s.TABLE_SCHEMA = DATABASE()
             AND s.TABLE_NAME = LOWER(:tableName)
           GROUP BY
                 s.INDEX_NAME
           ORDER BY
                 s.INDEX_NAME"""),
      Map.entry("기본 키 · FK 목록",
          """
          SELECT
                 tc.CONSTRAINT_NAME
               , tc.CONSTRAINT_TYPE
               , kcu.COLUMN_NAME
               , kcu.REFERENCED_TABLE_NAME AS REF_TABLE
            FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
            JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
              ON kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
             AND kcu.TABLE_SCHEMA = tc.TABLE_SCHEMA
             AND kcu.TABLE_NAME = tc.TABLE_NAME
           WHERE tc.TABLE_SCHEMA = DATABASE()
             AND tc.TABLE_NAME = LOWER(:tableName)
           ORDER BY
                 tc.CONSTRAINT_NAME
               , kcu.ORDINAL_POSITION"""),
      Map.entry("테이블 크기 (용량 큰 순)",
          """
          SELECT
                 t.TABLE_NAME
               , ROUND((t.DATA_LENGTH + t.INDEX_LENGTH) / 1024 / 1024, 1) AS MB
            FROM INFORMATION_SCHEMA.TABLES t
           WHERE t.TABLE_SCHEMA = DATABASE()
             AND t.TABLE_TYPE = 'BASE TABLE'
           ORDER BY
                 MB DESC"""),
      Map.entry("테이블 건수 어림 (통계 기준)",
          """
          -- TABLE_ROWS 는 InnoDB 의 어림값입니다
          SELECT
                 t.TABLE_NAME
               , t.TABLE_ROWS AS NUM_ROWS
               , t.UPDATE_TIME
            FROM INFORMATION_SCHEMA.TABLES t
           WHERE t.TABLE_SCHEMA = DATABASE()
             AND t.TABLE_TYPE = 'BASE TABLE'
           ORDER BY
                 t.TABLE_ROWS DESC"""),
      Map.entry("뷰 · 펑션 소스에서 글자 찾기",
          """
          SELECT
                 r.ROUTINE_NAME AS NAME
               , r.ROUTINE_TYPE AS TYPE
            FROM INFORMATION_SCHEMA.ROUTINES r
           WHERE r.ROUTINE_SCHEMA = DATABASE()
             AND r.ROUTINE_DEFINITION LIKE CONCAT('%', :keyword, '%')
           UNION ALL
          SELECT
                 v.TABLE_NAME
               , 'VIEW'
            FROM INFORMATION_SCHEMA.VIEWS v
           WHERE v.TABLE_SCHEMA = DATABASE()
             AND v.VIEW_DEFINITION LIKE CONCAT('%', :keyword, '%')"""),
      Map.entry("칼럼 설명(한글)으로 칼럼 찾기",
          """
          SELECT
                 c.TABLE_NAME
               , c.COLUMN_NAME
               , c.COLUMN_COMMENT AS COMMENTS
            FROM INFORMATION_SCHEMA.COLUMNS c
           WHERE c.TABLE_SCHEMA = DATABASE()
             AND c.COLUMN_COMMENT LIKE CONCAT('%', :keyword, '%')
           ORDER BY
                 c.TABLE_NAME
               , c.COLUMN_NAME"""),
      Map.entry("테이블 설명(한글)으로 테이블 찾기",
          """
          SELECT
                 t.TABLE_NAME
               , t.TABLE_TYPE
               , t.TABLE_COMMENT AS COMMENTS
            FROM INFORMATION_SCHEMA.TABLES t
           WHERE t.TABLE_SCHEMA = DATABASE()
             AND t.TABLE_COMMENT LIKE CONCAT('%', :keyword, '%')
           ORDER BY
                 t.TABLE_NAME"""),
      Map.entry("이 테이블을 가리키는 테이블 (FK 거꾸로)",
          """
          SELECT
                 k.TABLE_NAME AS CHILD_TABLE
               , k.CONSTRAINT_NAME
               , CONCAT(k.TABLE_NAME, '(', GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ', '), ') → '
                      , k.REFERENCED_TABLE_NAME, '(', GROUP_CONCAT(k.REFERENCED_COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ', '), ')') AS DEFINITION
            FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k
           WHERE k.TABLE_SCHEMA = DATABASE()
             AND k.REFERENCED_TABLE_NAME = :tableName
           GROUP BY
                 k.TABLE_NAME
               , k.CONSTRAINT_NAME
               , k.REFERENCED_TABLE_NAME
           ORDER BY
                 1, 2"""),
      Map.entry("고유 · 검사 제약 목록 (조건 글 포함)",
          """
          SELECT
                 tc.CONSTRAINT_NAME
               , tc.CONSTRAINT_TYPE
               , COALESCE(cc.CHECK_CLAUSE, GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ', ')) AS DEFINITION
            FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
            LEFT JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
              ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
             AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
            LEFT JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE k
              ON k.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
             AND k.TABLE_NAME = tc.TABLE_NAME
             AND k.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
           WHERE tc.TABLE_SCHEMA = DATABASE()
             AND tc.TABLE_NAME = :tableName
             AND tc.CONSTRAINT_TYPE IN ('UNIQUE', 'CHECK')
           GROUP BY
                 tc.CONSTRAINT_NAME
               , tc.CONSTRAINT_TYPE
               , cc.CHECK_CLAUSE
           ORDER BY
                 2, 1"""),
      Map.entry("트리거 목록 (테이블마다 · 켜짐 여부)",
          """
          SELECT
                 t.EVENT_OBJECT_TABLE AS TABLE_NAME
               , t.TRIGGER_NAME
               , CONCAT(t.ACTION_TIMING, ' ', t.EVENT_MANIPULATION, ': ', t.ACTION_STATEMENT) AS DEFINITION
               , 'ENABLED' AS STATUS
            FROM INFORMATION_SCHEMA.TRIGGERS t
           WHERE t.TRIGGER_SCHEMA = DATABASE()
           ORDER BY
                 1, 2"""),
      Map.entry("시노님이 가리키는 대상 (끊긴 시노님 찾기)",
          """
          -- MySQL 에는 시노님 · 검색 경로가 없습니다. 지금 기본 DB 와 사용자를 봅니다
          SELECT
                 DATABASE() AS NOW_SCHEMA
               , CURRENT_USER() AS USER_NAME"""),
      Map.entry("파티션 목록 (경계 값 · 건수 어림)",
          """
          SELECT
                 p.PARTITION_NAME
               , p.PARTITION_DESCRIPTION AS BOUND
               , p.TABLE_ROWS AS NUM_ROWS
            FROM INFORMATION_SCHEMA.PARTITIONS p
           WHERE p.TABLE_SCHEMA = DATABASE()
             AND p.TABLE_NAME = :tableName
             AND p.PARTITION_NAME IS NOT NULL
           ORDER BY
                 p.PARTITION_ORDINAL_POSITION"""),
      Map.entry("테이블 만드는 문장 뽑기 (DDL)",
          """
          -- 결과 칸의 둘째 칸(Create Table)이 만드는 문장입니다
          SHOW CREATE TABLE ORDERS"""),
      Map.entry("이 테이블이 마지막으로 바뀐 때",
          """
          -- UPDATE_TIME 은 서버를 다시 켜면 비워질 수 있습니다
          SELECT
                 t.TABLE_NAME
               , t.CREATE_TIME
               , t.UPDATE_TIME
               , t.TABLE_ROWS
            FROM INFORMATION_SCHEMA.TABLES t
           WHERE t.TABLE_SCHEMA = DATABASE()
             AND t.TABLE_NAME = LOWER(:tableName)"""),
      Map.entry("테이블 칼럼을 SELECT 문으로 펼치기",
          """
          SELECT
                 CONCAT('SELECT', CHAR(10), '       '
                      , GROUP_CONCAT(CONCAT(LOWER(:alias), '.', UPPER(c.COLUMN_NAME)) ORDER BY c.ORDINAL_POSITION SEPARATOR '\\n     , ')
                      , CHAR(10), '  FROM ', UPPER(c.TABLE_NAME), ' ', LOWER(:alias)) AS SELECT_SQL
            FROM INFORMATION_SCHEMA.COLUMNS c
           WHERE c.TABLE_SCHEMA = DATABASE()
             AND c.TABLE_NAME = LOWER(:tableName)
           GROUP BY
                 c.TABLE_NAME"""),
      Map.entry("칼럼 이름을 camelCase 로 (VO · DTO 필드 이름)",
          """
          -- 밑줄 뒤 글자를 대문자로 바꿔 붙입니다(밑줄 다섯 개까지)
          SELECT
                 UPPER(c.COLUMN_NAME) AS COLUMN_NAME
               , CONCAT(
                   LOWER(SUBSTRING_INDEX(c.COLUMN_NAME, '_', 1))
                 , REPLACE(CONCAT(
                     CASE WHEN c.COLUMN_NAME LIKE '%\\_%' THEN CONCAT(UPPER(SUBSTRING(SUBSTRING_INDEX(SUBSTRING_INDEX(c.COLUMN_NAME, '_', 2), '_', -1), 1, 1)), LOWER(SUBSTRING(SUBSTRING_INDEX(SUBSTRING_INDEX(c.COLUMN_NAME, '_', 2), '_', -1), 2))) ELSE '' END
                   , CASE WHEN c.COLUMN_NAME LIKE '%\\_%\\_%' THEN CONCAT(UPPER(SUBSTRING(SUBSTRING_INDEX(SUBSTRING_INDEX(c.COLUMN_NAME, '_', 3), '_', -1), 1, 1)), LOWER(SUBSTRING(SUBSTRING_INDEX(SUBSTRING_INDEX(c.COLUMN_NAME, '_', 3), '_', -1), 2))) ELSE '' END
                   , CASE WHEN c.COLUMN_NAME LIKE '%\\_%\\_%\\_%' THEN CONCAT(UPPER(SUBSTRING(SUBSTRING_INDEX(SUBSTRING_INDEX(c.COLUMN_NAME, '_', 4), '_', -1), 1, 1)), LOWER(SUBSTRING(SUBSTRING_INDEX(SUBSTRING_INDEX(c.COLUMN_NAME, '_', 4), '_', -1), 2))) ELSE '' END
                   , CASE WHEN c.COLUMN_NAME LIKE '%\\_%\\_%\\_%\\_%' THEN CONCAT(UPPER(SUBSTRING(SUBSTRING_INDEX(SUBSTRING_INDEX(c.COLUMN_NAME, '_', 5), '_', -1), 1, 1)), LOWER(SUBSTRING(SUBSTRING_INDEX(SUBSTRING_INDEX(c.COLUMN_NAME, '_', 5), '_', -1), 2))) ELSE '' END
                   , CASE WHEN c.COLUMN_NAME LIKE '%\\_%\\_%\\_%\\_%\\_%' THEN CONCAT(UPPER(SUBSTRING(SUBSTRING_INDEX(SUBSTRING_INDEX(c.COLUMN_NAME, '_', 6), '_', -1), 1, 1)), LOWER(SUBSTRING(SUBSTRING_INDEX(SUBSTRING_INDEX(c.COLUMN_NAME, '_', 6), '_', -1), 2))) ELSE '' END
                   ), '_', '')
                 ) AS FIELD_NAME
               , c.COLUMN_TYPE AS DATA_TYPE
               , c.COLUMN_COMMENT AS COMMENTS
            FROM INFORMATION_SCHEMA.COLUMNS c
           WHERE c.TABLE_SCHEMA = DATABASE()
             AND c.TABLE_NAME = :tableName
           ORDER BY
                 c.ORDINAL_POSITION"""),
      Map.entry("칼럼 값 종류 수 · 빈 값 비율 (인덱스 걸 만한가)",
          """
          -- 인덱스 칼럼의 값 종류 수(어림)입니다. 빈 값 비율은 직접 세야 합니다
          SELECT
                 s.COLUMN_NAME
               , MAX(s.CARDINALITY) AS N_DISTINCT
               , GROUP_CONCAT(DISTINCT s.INDEX_NAME SEPARATOR ', ') AS INDEX_NAMES
            FROM INFORMATION_SCHEMA.STATISTICS s
           WHERE s.TABLE_SCHEMA = DATABASE()
             AND s.TABLE_NAME = LOWER(:tableName)
           GROUP BY
                 s.COLUMN_NAME
           ORDER BY
                 N_DISTINCT DESC"""),
      Map.entry("이 테이블을 쓰는 뷰 · 프로시저 · 패키지",
          """
          SELECT DISTINCT
                 'VIEW' AS TYPE
               , u.VIEW_NAME AS NAME
            FROM INFORMATION_SCHEMA.VIEW_TABLE_USAGE u
           WHERE u.TABLE_SCHEMA = DATABASE()
             AND u.TABLE_NAME = :tableName
           UNION
          SELECT DISTINCT
                 r.ROUTINE_TYPE
               , r.ROUTINE_NAME
            FROM INFORMATION_SCHEMA.ROUTINES r
           WHERE r.ROUTINE_SCHEMA = DATABASE()
             AND r.ROUTINE_DEFINITION LIKE CONCAT('%', :tableName, '%')
           ORDER BY
                 1, 2"""),
      Map.entry("이 프로시저 · 뷰가 쓰는 테이블",
          """
          SELECT DISTINCT
                 'TABLE' AS REFERENCED_TYPE
               , u.TABLE_NAME AS REFERENCED_NAME
            FROM INFORMATION_SCHEMA.VIEW_TABLE_USAGE u
           WHERE u.VIEW_SCHEMA = DATABASE()
             AND u.VIEW_NAME = :objectName
           ORDER BY
                 1, 2"""),
      Map.entry("프로시저 · 펑션 인자 목록",
          """
          SELECT
                 p.SPECIFIC_NAME AS OBJECT_NAME
               , p.ORDINAL_POSITION
               , p.PARAMETER_MODE
               , p.PARAMETER_NAME
               , p.DTD_IDENTIFIER AS DATA_TYPE
            FROM INFORMATION_SCHEMA.PARAMETERS p
           WHERE p.SPECIFIC_SCHEMA = DATABASE()
             AND p.SPECIFIC_NAME = :objectName
           ORDER BY
                 p.ORDINAL_POSITION"""),
      Map.entry("프로시저 · 패키지 소스 전체 보기",
          """
          SELECT
                 r.ROUTINE_NAME AS NAME
               , r.ROUTINE_TYPE
               , r.ROUTINE_DEFINITION AS SOURCE
            FROM INFORMATION_SCHEMA.ROUTINES r
           WHERE r.ROUTINE_SCHEMA = DATABASE()
             AND r.ROUTINE_NAME = :objectName"""),
      Map.entry("뷰 소스 보기",
          """
          SELECT
                 v.VIEW_DEFINITION AS TEXT
            FROM INFORMATION_SCHEMA.VIEWS v
           WHERE v.TABLE_SCHEMA = DATABASE()
             AND v.TABLE_NAME = :viewName"""),
      Map.entry("패키지 안 프로시저 · 펑션 목록",
          """
          SELECT
                 r.ROUTINE_NAME AS PROCEDURE_NAME
               , r.ROUTINE_TYPE
               , r.DTD_IDENTIFIER AS RETURN_TYPE
            FROM INFORMATION_SCHEMA.ROUTINES r
           WHERE r.ROUTINE_SCHEMA = DATABASE()
           ORDER BY
                 r.ROUTINE_NAME"""),
      Map.entry("지금 돌고 있는 쿼리",
          """
          SELECT
                 p.ID AS PID
               , p.USER
               , p.STATE
               , p.TIME AS ELAPSED_SEC
               , p.INFO AS QUERY
            FROM INFORMATION_SCHEMA.PROCESSLIST p
           WHERE p.COMMAND <> 'Sleep'
             AND p.ID <> CONNECTION_ID()"""),
      Map.entry("누가 누구를 막고 있나 (락 대기)",
          """
          SELECT
                 w.waiting_pid AS WAITING_PID
               , w.blocking_pid AS BLOCKING_PID
               , w.wait_age AS WAIT_TIME
               , w.waiting_query AS QUERY
               , w.locked_table AS TABLE_NAME
            FROM sys.innodb_lock_waits w"""),
      Map.entry("오래 걸린 쿼리 순위",
          """
          -- 시간 칼럼은 피코초(1조분의 1초)라 나눠서 봅니다
          SELECT
                 s.COUNT_STAR AS CALLS
               , ROUND(s.SUM_TIMER_WAIT / 1000000000000, 1) AS TOTAL_SEC
               , ROUND(s.AVG_TIMER_WAIT / 1000000000, 1) AS AVG_MS
               , s.DIGEST_TEXT AS QUERY
            FROM performance_schema.events_statements_summary_by_digest s
           ORDER BY
                 s.SUM_TIMER_WAIT DESC
           LIMIT 20"""),
      Map.entry("잠긴 테이블 · 잠근 세션 보기",
          """
          SELECT
                 t.PROCESSLIST_ID AS PID
               , t.PROCESSLIST_USER AS USENAME
               , l.OBJECT_NAME AS TABLE_NAME
               , l.LOCK_TYPE
               , l.LOCK_MODE
               , l.LOCK_STATUS
            FROM performance_schema.data_locks l
            JOIN performance_schema.threads t
              ON t.THREAD_ID = l.THREAD_ID
           WHERE l.OBJECT_SCHEMA = DATABASE()"""),
      Map.entry("SID 로 그 세션의 SQL 글 보기 (지금 · 바로 전)",
          """
          SELECT
                 p.ID AS PID
               , p.USER AS USENAME
               , p.HOST AS CLIENT_ADDR
               , p.COMMAND
               , p.STATE
               , p.TIME AS SEC
               , p.INFO AS QUERY
            FROM INFORMATION_SCHEMA.PROCESSLIST p
           WHERE p.ID = CAST(:pid AS SIGNED)"""),
      Map.entry("오래 열린 트랜잭션 (커밋 안 한 세션)",
          """
          SELECT
                 p.ID AS PID
               , p.USER
               , p.HOST AS CLIENT_ADDR
               , t.trx_state AS STATE
               , t.trx_started AS XACT_START
               , ROUND(TIMESTAMPDIFF(SECOND, t.trx_started, NOW()) / 60) AS OPEN_MIN
               , t.trx_query AS QUERY
            FROM INFORMATION_SCHEMA.INNODB_TRX t
            JOIN INFORMATION_SCHEMA.PROCESSLIST p
              ON p.ID = t.trx_mysql_thread_id
           WHERE p.ID <> CONNECTION_ID()
           ORDER BY
                 t.trx_started"""),
      Map.entry("막힌 줄이 어느 테이블 어느 줄인지 (ROWID)",
          """
          SELECT
                 w.REQUESTING_THREAD_ID AS WAITING_THREAD
               , w.BLOCKING_THREAD_ID AS BLOCKING_THREAD
               , l.OBJECT_NAME AS TABLE_NAME
               , l.INDEX_NAME
               , l.LOCK_TYPE
               , l.LOCK_MODE
               , l.LOCK_DATA
            FROM performance_schema.data_lock_waits w
            JOIN performance_schema.data_locks l
              ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID"""),
      Map.entry("맨 앞에서 막는 세션 (줄줄이 막힐 때)",
          """
          SELECT DISTINCT
                 w.blocking_pid AS ROOT_PID
               , w.blocking_query AS QUERY
            FROM sys.innodb_lock_waits w
           WHERE w.blocking_pid NOT IN (SELECT x.waiting_pid FROM sys.innodb_lock_waits x)"""),
      Map.entry("내 세션 정보 (SID · 사용자 · 스키마)",
          """
          SELECT
                 CONNECTION_ID() AS PID
               , CURRENT_USER() AS USER_NAME
               , DATABASE() AS SCHEMA_NAME
               , USER() AS CLIENT_ADDR
               , @@hostname AS DB_HOST
               , VERSION() AS DB_VERSION"""),
      Map.entry("세션이 지금 무엇을 기다리나 (대기 이벤트)",
          """
          SELECT
                 t.PROCESSLIST_ID AS PID
               , t.PROCESSLIST_USER AS USER_NAME
               , w.EVENT_NAME AS WAIT_EVENT
               , t.PROCESSLIST_TIME AS RUNNING_SEC
               , t.PROCESSLIST_INFO AS QUERY
            FROM performance_schema.threads t
            LEFT JOIN performance_schema.events_waits_current w
              ON w.THREAD_ID = t.THREAD_ID
           WHERE t.PROCESSLIST_COMMAND <> 'Sleep'
             AND t.PROCESSLIST_ID <> CONNECTION_ID()
           ORDER BY
                 t.PROCESSLIST_TIME DESC"""),
      Map.entry("오래 걸리는 작업 진행률 (몇 % · 남은 시간)",
          """
          SELECT
                 s.THREAD_ID
               , s.EVENT_NAME AS PHASE
               , s.WORK_COMPLETED
               , s.WORK_ESTIMATED
               , ROUND(100.0 * s.WORK_COMPLETED / NULLIF(s.WORK_ESTIMATED, 0), 1) AS PCT
            FROM performance_schema.events_stages_current s"""),
      Map.entry("TEMP 많이 쓰는 세션 (정렬 · 해시가 큰 쿼리)",
          """
          SELECT
                 d.SCHEMA_NAME
               , d.DIGEST_TEXT AS QUERY
               , d.SUM_CREATED_TMP_DISK_TABLES AS TMP_DISK_TABLES
               , d.SUM_SORT_MERGE_PASSES AS SORT_MERGE_PASSES
            FROM performance_schema.events_statements_summary_by_digest d
           WHERE d.SUM_CREATED_TMP_DISK_TABLES > 0
              OR d.SUM_SORT_MERGE_PASSES > 0
           ORDER BY
                 d.SUM_CREATED_TMP_DISK_TABLES DESC
           LIMIT 20"""),
      Map.entry("열린 커서 많은 세션 (ORA-01000 최대 커서 넘음)",
          """
          SELECT
                 p.USER
               , SUBSTRING_INDEX(p.HOST, ':', 1) AS CLIENT_ADDR
               , COUNT(*) AS CONN_CNT
               , @@max_connections AS LIMIT_CONN
            FROM INFORMATION_SCHEMA.PROCESSLIST p
           GROUP BY
                 p.USER
               , SUBSTRING_INDEX(p.HOST, ':', 1)
           ORDER BY
                 CONN_CNT DESC"""),
      Map.entry("통계 다시 모으기",
          """
          ANALYZE TABLE ORDERS"""),
      Map.entry("실행 계획 실제 값으로 보기",
          """
          -- EXPLAIN ANALYZE 는 쿼리를 실제로 돌립니다(8.0.18 이상)
          EXPLAIN ANALYZE
          SELECT
                 o.STATUS
               , COUNT(*)
            FROM ORDERS o
           GROUP BY
                 o.STATUS"""),
      Map.entry("SQL_ID 로 실제 실행 계획 (DISPLAY_CURSOR)",
          """
          EXPLAIN FORMAT=TREE
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.STATUS = 'NEW'"""),
      Map.entry("글자로 SQL_ID 찾기 (매퍼 SQL 이 캐시에 있나)",
          """
          SELECT
                 s.DIGEST AS QUERYID
               , s.COUNT_STAR AS CALLS
               , ROUND(s.AVG_TIMER_WAIT / 1000000000, 1) AS AVG_MS
               , s.DIGEST_TEXT AS QUERY
            FROM performance_schema.events_statements_summary_by_digest s
           WHERE s.DIGEST_TEXT LIKE CONCAT('%', :keyword, '%')
           ORDER BY
                 s.COUNT_STAR DESC"""),
      Map.entry("디스크를 많이 읽는 SQL (상위 20)",
          """
          -- MySQL 은 SQL 별 디스크 읽기를 따로 세지 않아, 훑은 줄 수 · 인덱스 없이 돈 횟수로 봅니다
          SELECT
                 s.DIGEST AS QUERYID
               , s.COUNT_STAR AS CALLS
               , s.SUM_ROWS_EXAMINED AS ROWS_EXAMINED
               , s.SUM_NO_INDEX_USED AS NO_INDEX_USED
               , s.DIGEST_TEXT AS QUERY
            FROM performance_schema.events_statements_summary_by_digest s
           ORDER BY
                 s.SUM_ROWS_EXAMINED DESC
           LIMIT 20"""),
      Map.entry("실행 횟수가 많은 SQL (상위 20)",
          """
          SELECT
                 s.DIGEST AS QUERYID
               , s.COUNT_STAR AS CALLS
               , ROUND(s.AVG_TIMER_WAIT / 1000000000, 2) AS AVG_MS
               , ROUND(s.SUM_TIMER_WAIT / 1000000000000, 1) AS TOTAL_SEC
               , s.DIGEST_TEXT AS QUERY
            FROM performance_schema.events_statements_summary_by_digest s
           ORDER BY
                 s.COUNT_STAR DESC
           LIMIT 20"""),
      Map.entry("한 번 돌 때 오래 걸리는 SQL (1회 평균 상위 20)",
          """
          SELECT
                 s.DIGEST AS QUERYID
               , s.COUNT_STAR AS CALLS
               , ROUND(s.AVG_TIMER_WAIT / 1000000000) AS AVG_MS
               , ROUND(s.SUM_ROWS_SENT / s.COUNT_STAR) AS ROWS_PER_EXEC
               , s.DIGEST_TEXT AS QUERY
            FROM performance_schema.events_statements_summary_by_digest s
           WHERE s.COUNT_STAR > 0
           ORDER BY
                 s.AVG_TIMER_WAIT DESC
           LIMIT 20"""),
      Map.entry("SQL 에 들어간 바인드 값 보기",
          """
          -- 바인드 값까지 남기려면 general_log 를 켭니다(운영 서버에서는 잠깐만)
          SELECT
                 v.VARIABLE_NAME AS NAME
               , v.VARIABLE_VALUE AS SETTING
            FROM performance_schema.global_variables v
           WHERE v.VARIABLE_NAME IN ('general_log', 'general_log_file', 'long_query_time', 'slow_query_log')"""),
      Map.entry("테이블 전체를 훑는 SQL (TABLE ACCESS FULL)",
          """
          SELECT
                 s.OBJECT_NAME AS TABLE_NAME
               , s.COUNT_READ
               , s.COUNT_FETCH
               , t.TABLE_ROWS
            FROM performance_schema.table_io_waits_summary_by_index_usage s
            JOIN INFORMATION_SCHEMA.TABLES t
              ON t.TABLE_SCHEMA = s.OBJECT_SCHEMA
             AND t.TABLE_NAME = s.OBJECT_NAME
           WHERE s.OBJECT_SCHEMA = DATABASE()
             AND s.INDEX_NAME IS NULL
           ORDER BY
                 s.COUNT_READ DESC"""),
      Map.entry("이 테이블을 쓰는 SQL (캐시에서)",
          """
          SELECT
                 s.DIGEST AS QUERYID
               , s.COUNT_STAR AS CALLS
               , ROUND(s.AVG_TIMER_WAIT / 1000000000, 1) AS AVG_MS
               , s.DIGEST_TEXT AS QUERY
            FROM performance_schema.events_statements_summary_by_digest s
           WHERE s.DIGEST_TEXT LIKE CONCAT('%', :tableName, '%')
           ORDER BY
                 s.COUNT_STAR DESC"""),
      Map.entry("바인드 없이 값을 박은 SQL (같은 꼴 여러 벌)",
          """
          -- DIGEST_TEXT 는 값을 ? 로 바꾼 꼴이라, 한 번만 돈 꼴이 많으면 값을 박은 SQL 일 수 있습니다
          SELECT
                 s.DIGEST AS QUERYID
               , s.COUNT_STAR AS CALLS
               , s.QUERY_SAMPLE_TEXT AS QUERY
            FROM performance_schema.events_statements_summary_by_digest s
           WHERE s.COUNT_STAR = 1
           ORDER BY
                 s.SUM_TIMER_WAIT DESC
           LIMIT 50"""),
      Map.entry("조인이 안 맞는 키 (앞뒤 공백 · 줄바꿈이 섞인 값)",
          """
          SELECT
                 p.PART_NO
               , CHAR_LENGTH(p.PART_NO) AS LEN
               , HEX(p.PART_NO) AS RAW_CODES
            FROM PART p
           WHERE p.PART_NO <> TRIM(p.PART_NO)
              OR p.PART_NO REGEXP '[\\t\\r\\n]'"""),
      Map.entry("JSON 칸에서 값 꺼내기",
          """
          SELECT
                 o.ORDER_ID
               , o.MEMO ->> '$.channel' AS CHANNEL
            FROM ORDERS o
           WHERE JSON_VALID(o.MEMO)"""),
      Map.entry("줄을 JSON 으로 만들기",
          """
          SELECT
                 JSON_OBJECT('orderId', o.ORDER_ID, 'amount', o.AMOUNT, 'status', o.STATUS) AS JSON
            FROM ORDERS o"""),
      Map.entry("줄 여러 개를 JSON 배열 하나로",
          """
          -- JSON_ARRAYAGG 에는 ORDER BY 를 줄 수 없어 안쪽에서 정렬합니다(정렬이 유지되는 것은 MySQL 이 보장하지 않으므로 순서가 꼭 필요하면 화면에서 정렬)
          SELECT
                 JSON_ARRAYAGG(JSON_OBJECT('partNo', p.PART_NO, 'name', p.PART_NAME)) AS JSON_TEXT
            FROM (
                  SELECT
                         x.PART_NO
                       , x.PART_NAME
                    FROM PART x
                   ORDER BY
                         x.PART_NO
                 ) p"""),
      Map.entry("부모 아래 자식을 JSON 으로 묶기",
          """
          SELECT
                 o.ORDER_ID
               , JSON_OBJECT(
                   'orderId', o.ORDER_ID
                 , 'items', (SELECT JSON_ARRAYAGG(JSON_OBJECT('partNo', i.PART_NO, 'qty', i.QTY)) FROM ORDER_ITEM i WHERE i.ORDER_ID = o.ORDER_ID)
                 ) AS JSON_TEXT
            FROM ORDERS o"""),
      Map.entry("JSON 배열을 줄로 펼치기",
          """
          SELECT
                 j.PART_NO
               , j.QTY
            FROM JSON_TABLE(
                   '[{"partNo":"P001","qty":2},{"partNo":"P002","qty":5}]'
                 , '$[*]' COLUMNS (
                     PART_NO VARCHAR(30) PATH '$.partNo'
                   , QTY DECIMAL(18, 3) PATH '$.qty'
                   )
                 ) j"""),
      Map.entry("BOM 정전개 (완제품 → 부품)",
          """
          WITH RECURSIVE t AS (
          SELECT
                 1 AS LVL
               , b.CHILD_PART
               , b.QTY
               , b.QTY AS TOTAL_QTY
               , CAST(LPAD(b.SEQ, 5, '0') AS CHAR(4000)) AS SORT_KEY
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.LVL + 1
               , b.CHILD_PART
               , b.QTY
               , t.TOTAL_QTY * b.QTY
               , CONCAT(t.SORT_KEY, '/', LPAD(b.SEQ, 5, '0'))
            FROM BOM b
            JOIN t
              ON b.PARENT_PART = t.CHILD_PART
           WHERE t.LVL < 100
          )
          SELECT
                 t.LVL
               , CONCAT(REPEAT(' ', (t.LVL - 1) * 2), t.CHILD_PART) AS PART_NO
               , t.QTY
               , t.TOTAL_QTY
            FROM t
           ORDER BY
                 t.SORT_KEY"""),
      Map.entry("레벨 번호 붙인 BOM 테이블 (1, 1.1, 1.1.2)",
          """
          -- MySQL 재귀 WITH 안에서는 ROW_NUMBER 를 쓸 수 없어, 형제 순번을 먼저 매긴 뒤 재귀로 잇습니다
          WITH RECURSIVE n AS (
          SELECT
                 b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , b.SEQ
               , ROW_NUMBER() OVER (PARTITION BY b.PARENT_PART ORDER BY b.SEQ) AS NO
            FROM BOM b
           WHERE b.REV = :rev
          ), t AS (
          SELECT
                 1 AS LVL
               , n.PARENT_PART
               , n.CHILD_PART
               , n.QTY
               , CAST(n.NO AS CHAR(200)) AS NO_PATH
               , CAST(LPAD(n.SEQ, 4, '0') AS CHAR(400)) AS SORT_KEY
            FROM n
           WHERE n.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.LVL + 1
               , n.PARENT_PART
               , n.CHILD_PART
               , n.QTY
               , CONCAT(t.NO_PATH, '.', n.NO)
               , CONCAT(t.SORT_KEY, '/', LPAD(n.SEQ, 4, '0'))
            FROM n, t
           WHERE n.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.NO_PATH
               , CONCAT(REPEAT(' ', (t.LVL - 1) * 2), t.CHILD_PART) AS PART_NO
               , t.QTY
               , t.LVL
            FROM t
           ORDER BY
                 t.SORT_KEY"""),
      Map.entry("두 BOM 비교 (리비전 A ↔ B: 추가 · 삭제 · 수량 바뀜)",
          """
          -- MySQL 에는 FULL OUTER JOIN 이 없어 「A 기준」 과 「B 에만 있는 것」 을 UNION ALL 로 붙입니다
          SELECT
                 a.CHILD_PART
               , a.QTY AS QTY_A
               , b.QTY AS QTY_B
               , CASE WHEN b.CHILD_PART IS NULL THEN '삭제' ELSE '수량 바뀜' END AS DIFF
            FROM (SELECT x.CHILD_PART, x.QTY FROM BOM x WHERE x.PARENT_PART = :part AND x.REV = :revA) a
            LEFT JOIN (SELECT y.CHILD_PART, y.QTY FROM BOM y WHERE y.PARENT_PART = :part AND y.REV = :revB) b
              ON a.CHILD_PART = b.CHILD_PART
           WHERE b.CHILD_PART IS NULL
              OR a.QTY <> b.QTY
           UNION ALL
          SELECT
                 b.CHILD_PART
               , NULL
               , b.QTY
               , '추가'
            FROM BOM b
           WHERE b.PARENT_PART = :part
             AND b.REV = :revB
             AND NOT EXISTS (SELECT 1 FROM BOM a WHERE a.PARENT_PART = :part AND a.REV = :revA AND a.CHILD_PART = b.CHILD_PART)
           ORDER BY
                 1"""),
      Map.entry("설비 · 담당자 투입 부하 (날짜별 동시 일정 수)",
          """
          WITH RECURSIVE d (DT) AS (
              SELECT CURRENT_DATE
               UNION ALL
              SELECT DT + INTERVAL 1 DAY FROM d WHERE DT < CURRENT_DATE + INTERVAL 29 DAY
          )
          SELECT
                 s.EQUIP_ID
               , d.DT
               , COUNT(*) AS LOAD_CNT
            FROM SCHEDULE s
            JOIN d
              ON d.DT BETWEEN s.PLAN_START AND s.PLAN_END
           WHERE s.EQUIP_ID IS NOT NULL
           GROUP BY
                 s.EQUIP_ID
               , d.DT
          HAVING COUNT(*) >= 2
           ORDER BY
                 s.EQUIP_ID
               , d.DT"""),
      Map.entry("쉼표로 붙은 칸을 여러 행으로 (부품별 태그)",
          """
          -- 쉼표 수 + 1 만큼 번호 줄을 만들어 n 번째 조각을 꺼냅니다(태그 100개까지)
          WITH RECURSIVE n (N) AS (
              SELECT 1
               UNION ALL
              SELECT N + 1 FROM n WHERE N < 100
          )
          SELECT
                 p.PART_NO
               , TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(p.TAGS, ',', n.N), ',', -1)) AS TAG
            FROM PART p
            JOIN n
              ON n.N <= CHAR_LENGTH(p.TAGS) - CHAR_LENGTH(REPLACE(p.TAGS, ',', '')) + 1
           WHERE p.TAGS IS NOT NULL
           ORDER BY
                 p.PART_NO
               , n.N"""),
      Map.entry("업로드 반영 (점검 통과한 줄만 MERGE)",
          """
          -- 같은 부품 번호가 여러 줄이면 첫 줄만 씁니다. PART_NO 가 PK 여야 합니다
          INSERT INTO PART (PART_NO, PART_NAME)
          SELECT
                 x.PART_NO
               , x.PART_NAME
            FROM (
                  SELECT
                         u.PART_NO
                       , u.PART_NAME
                       , ROW_NUMBER() OVER (PARTITION BY u.PART_NO ORDER BY u.PART_NO) AS RN
                    FROM UPLOAD_TMP u
                   WHERE u.PART_NO IS NOT NULL
                     AND u.QTY > 0
                 ) x
           WHERE x.RN = 1
              ON DUPLICATE KEY UPDATE
                 PART_NAME = VALUES(PART_NAME)"""),
      Map.entry("부서별 인원 · 평균 급여 · 최고 급여자",
          """
          SELECT
                 d.DEPT_NAME
               , COUNT(e.EMP_ID) AS EMP_CNT
               , ROUND(AVG(e.SAL)) AS AVG_SAL
               , SUBSTRING_INDEX(GROUP_CONCAT(e.EMP_NAME ORDER BY e.SAL IS NULL, e.SAL DESC SEPARATOR '\\n'), '\\n', 1) AS TOP_EMP
            FROM DEPT d
            LEFT OUTER JOIN EMP e
              ON d.DEPT_ID = e.DEPT_ID
           GROUP BY
                 d.DEPT_NAME"""),
      Map.entry("출근 기록이 없는 날 (결근 후보)",
          """
          WITH RECURSIVE d (DT) AS (
              SELECT DATE(DATE_FORMAT(CURRENT_DATE, '%Y-%m-01'))
               UNION ALL
              SELECT DT + INTERVAL 1 DAY FROM d WHERE DT < CURRENT_DATE
          )
          SELECT
                 e.EMP_ID
               , d.DT
            FROM EMP e
           CROSS JOIN d
           WHERE WEEKDAY(d.DT) < 5
             AND NOT EXISTS (
                  SELECT 1
                    FROM ATTEND a
                   WHERE a.EMP_ID = e.EMP_ID
                     AND a.WORK_DT = d.DT
                 )"""),
      Map.entry("두 시스템 부품 마스터 대조 (한쪽만 있음 · 값 다름)",
          """
          -- MySQL 에는 FULL OUTER JOIN 이 없어 「PLM 기준」 과 「ERP 에만 있는 것」 을 UNION ALL 로 붙입니다
          SELECT
                 p.PART_NO
               , p.PART_NAME AS PLM_NAME
               , e.PART_NAME AS ERP_NAME
               , p.UNIT_COST AS PLM_COST
               , e.UNIT_COST AS ERP_COST
               , CASE
                   WHEN e.PART_NO IS NULL THEN 'PLM 에만'
                   WHEN p.PART_NAME <> e.PART_NAME THEN '이름 다름'
                   ELSE '단가 다름'
                 END AS DIFF
            FROM PART p
            LEFT JOIN ERP_PART e
              ON p.PART_NO = e.PART_NO
           WHERE e.PART_NO IS NULL
              OR p.PART_NAME <> e.PART_NAME
              OR p.UNIT_COST <> e.UNIT_COST
           UNION ALL
          SELECT
                 e.PART_NO
               , NULL
               , e.PART_NAME
               , NULL
               , e.UNIT_COST
               , 'ERP 에만'
            FROM ERP_PART e
           WHERE NOT EXISTS (SELECT 1 FROM PART p WHERE p.PART_NO = e.PART_NO)"""),
      Map.entry("배치 실행 이력: 오래 걸린 날 · 실패한 날 · 안 돈 날",
          """
          WITH RECURSIVE d (DT) AS (
              SELECT STR_TO_DATE(:fromDt, '%Y-%m-%d')
               UNION ALL
              SELECT DT + INTERVAL 1 DAY FROM d WHERE DT < STR_TO_DATE(:toDt, '%Y-%m-%d')
          ), b AS (
          SELECT
                 CAST(l.START_TS AS DATE) AS DT
               , l.STATUS
               , TIMESTAMPDIFF(SECOND, l.START_TS, l.END_TS) / 60 AS MIN
               , AVG(TIMESTAMPDIFF(SECOND, l.START_TS, l.END_TS) / 60) OVER () AS AVG_MIN
            FROM BATCH_LOG l
           WHERE l.JOB_NAME = :jobName
          )
          SELECT
                 d.DT
               , b.STATUS
               , ROUND(b.MIN) AS MIN
               , CASE
                   WHEN b.DT IS NULL THEN '안 돎'
                   WHEN b.STATUS <> 'OK' THEN '실패'
                   WHEN b.MIN > b.AVG_MIN * 2 THEN '오래 걸림'
                 END AS NOTE
            FROM d
            LEFT OUTER JOIN b
              ON d.DT = b.DT
           ORDER BY
                 d.DT"""),
      Map.entry("0 건인 날짜도 빠짐없이",
          """
          -- 시작일 ~ 끝일의 모든 날짜와 건수. 행이 없는 날은 0
          WITH RECURSIVE DAYS (DT) AS (
              SELECT CAST(:fromDt AS DATE)
               UNION ALL
              SELECT DT + INTERVAL 1 DAY FROM DAYS WHERE DT < CAST(:toDt AS DATE)
          )
          , CNT AS (
              SELECT
                     CAST(o.ORDER_DT AS DATE) AS DT
                   , COUNT(*) AS CNT
                FROM ORDERS o
               WHERE o.ORDER_DT >= CAST(:fromDt AS DATE)
                 AND o.ORDER_DT < CAST(:toDt AS DATE) + INTERVAL 1 DAY
               GROUP BY
                     CAST(o.ORDER_DT AS DATE)
          )
          SELECT
                 d.DT
               , COALESCE(c.CNT, 0) AS CNT
            FROM DAYS d
            LEFT OUTER JOIN CNT c
              ON d.DT = c.DT
           ORDER BY
                 d.DT"""),
      Map.entry("최근 12주 주별 등록 · 완료 (빈 주 0)",
          """
          WITH RECURSIVE g (N) AS (
              SELECT 0
               UNION ALL
              SELECT N + 1 FROM g WHERE N < 11
          ), w AS (
          SELECT
                 DATE(CURRENT_DATE) - INTERVAL WEEKDAY(CURRENT_DATE) DAY - INTERVAL (g.N * 7) DAY AS WK
            FROM g
          )
          SELECT
                 DATE_FORMAT(w.WK, '%Y-%m-%d') AS WEEK_START
               , (SELECT COUNT(*) FROM ISSUE i WHERE i.REG_DT >= w.WK AND i.REG_DT < w.WK + INTERVAL 7 DAY) AS REGISTERED
               , (SELECT COUNT(*) FROM ISSUE i WHERE i.CLOSE_DT >= w.WK AND i.CLOSE_DT < w.WK + INTERVAL 7 DAY) AS CLOSED
            FROM w
           ORDER BY
                 w.WK"""),
      Map.entry("날마다 열려 있던 건수 (미결 추이 · 번다운)",
          """
          WITH RECURSIVE g (N) AS (
              SELECT 0
               UNION ALL
              SELECT N + 1 FROM g WHERE N < 59
          ), d AS (
          SELECT
                 CURRENT_DATE - INTERVAL g.N DAY AS DT
            FROM g
          )
          SELECT
                 d.DT
               , (SELECT COUNT(*)
                    FROM ISSUE i
                   WHERE i.REG_DT <= d.DT
                     AND (i.CLOSE_DT IS NULL OR i.CLOSE_DT > d.DT)) AS OPEN_CNT
            FROM d
           ORDER BY
                 d.DT"""),
      Map.entry("월별 처리 시간 추이 (평균 · 중앙값 · 90%)",
          """
          -- MySQL 에는 PERCENTILE_CONT 가 없어 달마다 순번을 매겨 가운데 · 90% 자리 값을 고릅니다
          SELECT
                 x.YM
               , COUNT(*) AS CNT
               , ROUND(AVG(x.DAYS), 1) AS AVG_DAYS
               , AVG(CASE WHEN x.RN IN (FLOOR((x.CNT + 1) / 2), CEIL((x.CNT + 1) / 2)) THEN x.DAYS END) AS MEDIAN_DAYS
               , MIN(CASE WHEN x.RN >= CEIL(x.CNT * 0.9) THEN x.DAYS END) AS P90_DAYS
            FROM (
                  SELECT
                         DATE_FORMAT(i.CLOSE_DT, '%Y-%m') AS YM
                       , DATEDIFF(i.CLOSE_DT, i.REG_DT) AS DAYS
                       , ROW_NUMBER() OVER (PARTITION BY DATE_FORMAT(i.CLOSE_DT, '%Y-%m') ORDER BY DATEDIFF(i.CLOSE_DT, i.REG_DT)) AS RN
                       , COUNT(*) OVER (PARTITION BY DATE_FORMAT(i.CLOSE_DT, '%Y-%m')) AS CNT
                    FROM ISSUE i
                   WHERE i.CLOSE_DT IS NOT NULL
                 ) x
           GROUP BY
                 x.YM
           ORDER BY
                 1"""),
      Map.entry("계획 누적 vs 실적 누적 (S 곡선)",
          """
          WITH RECURSIVE d (DT) AS (
              SELECT STR_TO_DATE(:fromDt, '%Y-%m-%d')
               UNION ALL
              SELECT DT + INTERVAL 1 DAY FROM d WHERE DT < STR_TO_DATE(:toDt, '%Y-%m-%d')
          )
          SELECT
                 d.DT
               , (SELECT COUNT(*) FROM SCHEDULE s WHERE s.PROJ_ID = :projId AND s.PLAN_END <= d.DT) AS PLAN_CUM
               , (SELECT COUNT(*) FROM SCHEDULE s WHERE s.PROJ_ID = :projId AND s.ACT_END <= d.DT) AS ACT_CUM
            FROM d
           ORDER BY
                 d.DT"""),
      Map.entry("이번 달 마감 예정 대비 완료",
          """
          SELECT
                 COUNT(*) AS DUE_THIS_MONTH
               , COUNT(s.ACT_END) AS DONE
               , COUNT(*) - COUNT(s.ACT_END) AS REMAIN
               , DATEDIFF(LAST_DAY(CURRENT_DATE), CURRENT_DATE) AS DAYS_LEFT
            FROM SCHEDULE s
           WHERE s.PLAN_END >= DATE(DATE_FORMAT(CURRENT_DATE, '%Y-%m-01'))
             AND s.PLAN_END < DATE(DATE_FORMAT(CURRENT_DATE, '%Y-%m-01')) + INTERVAL 1 MONTH"""),
      Map.entry("어느 테이블이 이 테이블을 참조하나 (FK 거꾸로 찾기)",
          """
          SELECT
                 k.TABLE_NAME AS CHILD_TABLE
               , k.COLUMN_NAME AS CHILD_COLUMN
               , k.REFERENCED_TABLE_NAME AS PARENT_TABLE
               , k.CONSTRAINT_NAME
            FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k
           WHERE k.TABLE_SCHEMA = DATABASE()
             AND k.REFERENCED_TABLE_NAME = LOWER(:tableName)
           ORDER BY
                 1, 2"""),
      Map.entry("FK 따라 위로 전부 (테이블 관계 계층)",
          """
          WITH RECURSIVE fk AS (
          SELECT DISTINCT
                 k.TABLE_NAME AS CHILD
               , k.REFERENCED_TABLE_NAME AS PARENT
            FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k
           WHERE k.TABLE_SCHEMA = DATABASE()
             AND k.REFERENCED_TABLE_NAME IS NOT NULL
          ), up AS (
          SELECT
                 1 AS UP
               , fk.CHILD
               , fk.PARENT
            FROM fk
           WHERE fk.CHILD = LOWER(:tableName)
           UNION
          SELECT
                 up.UP + 1
               , fk.CHILD
               , fk.PARENT
            FROM fk, up
           WHERE fk.CHILD = up.PARENT
             AND up.UP < 20
          )
          SELECT
                 up.UP
               , up.CHILD AS CHILD_TABLE
               , up.PARENT AS PARENT_TABLE
            FROM up"""),
      Map.entry("UNPIVOT (칸을 줄로)",
          """
          SELECT
                 x.PROJ_ID
               , v.STEP
               , v.PLAN_END
            FROM (
                  SELECT
                         s.PROJ_ID
                       , MAX(CASE WHEN s.STEP_SEQ = 1 THEN s.PLAN_END END) AS S1
                       , MAX(CASE WHEN s.STEP_SEQ = 2 THEN s.PLAN_END END) AS S2
                       , MAX(CASE WHEN s.STEP_SEQ = 3 THEN s.PLAN_END END) AS S3
                    FROM SCHEDULE s
                   GROUP BY
                         s.PROJ_ID
                 ) x
           CROSS JOIN LATERAL (
                  SELECT '1' AS STEP, x.S1 AS PLAN_END
                   UNION ALL
                  SELECT '2', x.S2
                   UNION ALL
                  SELECT '3', x.S3
                 ) v
           WHERE v.PLAN_END IS NOT NULL"""),
      Map.entry("TO_TIMESTAMP · SYSTIMESTAMP · 밀리초",
          """
          -- CURRENT_TIMESTAMP(3) 처럼 자릿수를 줘야 밀리초가 나옵니다. %f 는 6자리라 앞 23 글자만 씁니다
          SELECT
                 CURRENT_TIMESTAMP(3) AS NOW_TS
               , LEFT(DATE_FORMAT(CURRENT_TIMESTAMP(3), '%Y-%m-%d %H:%i:%s.%f'), 23) AS NOW_MS
               , STR_TO_DATE('2026-11-07 13:05:01.123', '%Y-%m-%d %H:%i:%s.%f') AS PARSED"""),
      Map.entry("날짜 빼서 시간 · 분 · 초로 (HH:MI:SS 꼴)",
          """
          -- MySQL 은 시각끼리 빼면 숫자가 되므로 TIMESTAMPDIFF · TIMEDIFF 를 씁니다
          SELECT
                 e.EQUIP_ID
               , ROUND(TIMESTAMPDIFF(SECOND, e.START_TS, e.END_TS) / 3600, 2) AS HOURS
               , ROUND(TIMESTAMPDIFF(SECOND, e.START_TS, e.END_TS) / 60) AS MINUTES
               , TIMEDIFF(e.END_TS, e.START_TS) AS DURATION
               , TIME_FORMAT(TIMEDIFF(e.END_TS, e.START_TS), '%H:%i:%s') AS HHMISS
            FROM EQUIP_LOG e"""),
      Map.entry("RPAD · LPAD · TRANSLATE (고정 길이 · 글자 바꾸기)",
          """
          -- MySQL 에는 TRANSLATE 가 없어 글자마다 REPLACE 를 겹칩니다
          SELECT
                 CONCAT(RPAD(p.PART_NO, 20, ' '), LPAD(p.UNIT_COST, 10, '0')) AS FIXED_LINE
               , REPLACE(REPLACE('2026-11/07', '-', '.'), '/', '.') AS DOTTED
            FROM PART p"""),
      Map.entry("같은 값이면 함께 (FETCH FIRST … WITH TIES)",
          """
          -- MySQL 에는 WITH TIES 가 없어 RANK 로 10 등 안(같은 값은 함께)을 고릅니다
          SELECT
                 x.ORDER_ID
               , x.AMOUNT
            FROM (
                  SELECT
                         o.ORDER_ID
                       , o.AMOUNT
                       , RANK() OVER (ORDER BY o.AMOUNT DESC) AS RNK
                    FROM ORDERS o
                 ) x
           WHERE x.RNK <= 10
           ORDER BY
                 x.AMOUNT DESC"""),
      Map.entry("KEEP (DENSE_RANK FIRST / LAST) — 가장 최근 줄의 다른 칸",
          """
          SELECT
                 r.PART_NO
               , MAX(r.REV) AS LAST_REV
               , SUBSTRING_INDEX(GROUP_CONCAT(r.STATUS ORDER BY r.REV DESC SEPARATOR '\\n'), '\\n', 1) AS LAST_STATUS
               , SUBSTRING_INDEX(GROUP_CONCAT(r.WEIGHT ORDER BY r.REV SEPARATOR '\\n'), '\\n', 1) AS FIRST_WEIGHT
            FROM PART_REV r
           GROUP BY
                 r.PART_NO"""),
      Map.entry("ROWID 로 중복 지우기 · 한 줄 고치기",
          """
          -- MySQL 은 지우는 테이블을 안쪽 SELECT 에서 바로 못 읽어 한 겹 더 감쌉니다. 남길 줄은 부품마다 가장 작은 UPLOAD_ID
          DELETE FROM UPLOAD_TMP
           WHERE UPLOAD_ID NOT IN (
                  SELECT k.UPLOAD_ID
                    FROM (
                          SELECT MIN(x.UPLOAD_ID) AS UPLOAD_ID
                            FROM UPLOAD_TMP x
                           GROUP BY
                                 x.PART_NO
                         ) k
                 )"""),
      Map.entry("ADD_MONTHS · MONTHS_BETWEEN · 말일 처리",
          """
          -- MySQL 의 + INTERVAL 1 MONTH 는 말일을 넘지 않습니다(1/31 → 2/28)
          SELECT
                 DATE '2026-01-31' + INTERVAL 1 MONTH AS FEB_END
               , TIMESTAMPDIFF(MONTH, DATE '2025-03-15', DATE '2026-11-07') AS MONTHS"""),
      Map.entry("TRIM LEADING · LTRIM · RTRIM (앞 0 · 뒤 공백 지우기)",
          """
          SELECT
                 TRIM(LEADING '0' FROM '000123') AS NO_ZERO
               , LTRIM('  abc') AS LEFT_TRIM
               , TRIM(TRAILING '.' FROM 'abc...') AS RIGHT_TRIM"""),
      Map.entry("INITCAP · CONCAT · CHR · ASCII",
          """
          SELECT
                 CONCAT(UPPER(LEFT('hello world', 1)), LOWER(SUBSTRING('hello world', 2))) AS CAP
               , CONCAT('A', 'B') AS JOINED
               , CHAR(65 USING utf8mb4) AS CH
               , ASCII('A') AS CODE"""),
      Map.entry("NUMTODSINTERVAL · NUMTOYMINTERVAL (숫자를 기간으로)",
          """
          SELECT
                 NOW() + INTERVAL 90 MINUTE AS AFTER_90M
               , CURRENT_DATE + INTERVAL 2 MONTH AS AFTER_2M"""),
      Map.entry("CAST · TO_NUMBER · TO_DATE 타입 바꾸기",
          """
          SELECT
                 CAST(REPLACE('1,234', ',', '') AS DECIMAL(18, 2)) AS N
               , STR_TO_DATE('20261107', '%Y%m%d') AS D
               , CAST('123' AS DECIMAL(18, 2)) AS C
               , CAST(CURRENT_DATE AS DATETIME) AS TS"""),
      Map.entry("SYS_GUID · ORA_HASH · STANDARD_HASH (고유 값 · 해시)",
          """
          SELECT
                 UUID() AS GUID
               , MOD(CRC32('P001'), 10) AS BUCKET
               , SHA2('P001', 256) AS SHA"""),
      Map.entry("SYS_CONTEXT · USER (접속 정보)",
          """
          SELECT
                 CURRENT_USER() AS DB_USER
               , SUBSTRING_INDEX(USER(), '@', -1) AS IP
               , CONNECTION_ID() AS SESSION_ID"""),
      Map.entry("MERGE 에 DELETE 절 (고치면서 조건 맞는 줄 지우기)",
          """
          -- MySQL 에는 MERGE 가 없어 지우기와 고치기를 따로 실행합니다
          DELETE e
            FROM ERP_PART e
            JOIN PART p
              ON e.PART_NO = p.PART_NO
           WHERE p.STATUS = 'STOP'
          ;
          UPDATE ERP_PART e
            JOIN PART p
              ON e.PART_NO = p.PART_NO
             SET e.PART_NAME = p.PART_NAME"""),
      Map.entry("MEDIAN · STATS_MODE · PERCENTILE_CONT",
          """
          -- MySQL 에는 PERCENTILE_CONT · MODE 가 없어 순번 · 건수로 고릅니다
          SELECT
                 (SELECT AVG(CASE WHEN x.RN IN (FLOOR((x.CNT + 1) / 2), CEIL((x.CNT + 1) / 2)) THEN x.AMOUNT END)
                    FROM (SELECT o.AMOUNT, ROW_NUMBER() OVER (ORDER BY o.AMOUNT) AS RN, COUNT(*) OVER () AS CNT FROM ORDERS o WHERE o.AMOUNT IS NOT NULL) x) AS MED
               , (SELECT o.STATUS FROM ORDERS o GROUP BY o.STATUS ORDER BY COUNT(*) DESC LIMIT 1) AS MODE_STATUS
               , (SELECT MIN(CASE WHEN x.RN >= CEIL(x.CNT * 0.9) THEN x.AMOUNT END)
                    FROM (SELECT o.AMOUNT, ROW_NUMBER() OVER (ORDER BY o.AMOUNT) AS RN, COUNT(*) OVER () AS CNT FROM ORDERS o WHERE o.AMOUNT IS NOT NULL) x) AS P90"""),
      Map.entry("STDDEV · VARIANCE · CORR (흩어짐 · 상관)",
          """
          -- MySQL 에는 CORR 가 없어 공분산 ÷ (표준편차 × 표준편차) 로 셉니다
          SELECT
                 ROUND(STDDEV_SAMP(i.PRICE), 2) AS SD
               , ROUND(VAR_SAMP(i.PRICE), 2) AS VAR
               , ROUND((AVG(i.QTY * i.PRICE) - AVG(i.QTY) * AVG(i.PRICE)) / NULLIF(STDDEV_POP(i.QTY) * STDDEV_POP(i.PRICE), 0), 3) AS QTY_PRICE_CORR
            FROM ORDER_ITEM i"""),
      Map.entry("XMLTABLE · XMLAGG (XML 꺼내기 · 만들기)",
          """
          -- MySQL 은 ExtractValue 로 XPath 값을 꺼냅니다
          SELECT
                 ExtractValue('<items><item><no>P001</no><qty>2</qty></item></items>', '/items/item[1]/no') AS PART_NO
               , ExtractValue('<items><item><no>P001</no><qty>2</qty></item></items>', '/items/item[1]/qty') AS QTY"""),
      Map.entry("CONNECT BY 로 글자 나누기 (쉼표 글자 → 줄)",
          """
          WITH RECURSIVE n (N) AS (
              SELECT 1
               UNION ALL
              SELECT N + 1 FROM n WHERE N < 100
          )
          SELECT
                 p.PART_NO
               , TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(p.TAGS, ',', n.N), ',', -1)) AS TAG
            FROM PART p
            JOIN n
              ON n.N <= CHAR_LENGTH(p.TAGS) - CHAR_LENGTH(REPLACE(p.TAGS, ',', '')) + 1
           WHERE p.TAGS IS NOT NULL"""),
      Map.entry("DUMP · VSIZE · LENGTHB (보이지 않는 글자 · 실제 크기)",
          """
          SELECT
                 p.PART_NAME
               , HEX(p.PART_NAME) AS BYTES_HEX
               , LENGTH(p.PART_NAME) AS BYTE_SIZE
            FROM PART p
           WHERE p.PART_NO = :partNo"""),
      Map.entry("FROM_TZ · SYS_EXTRACT_UTC (시간대 바꾸기)",
          """
          -- 시간대 이름(Asia/Seoul)은 MySQL 시간대 표가 채워져 있어야 합니다. 비어 있으면 '+09:00' · '+00:00' 꼴로 씁니다
          SELECT
                 CONVERT_TZ(NOW(), '+09:00', '+00:00') AS UTC_AT
               , UTC_TIMESTAMP() AS UTC_NOW"""),
      Map.entry("PL/SQL 결과 찍기 (DBMS_OUTPUT · RAISE NOTICE)",
          """
          -- MySQL 은 익명 블록이 없어 SELECT 로 값을 봅니다(프로시저 안에서는 SELECT 가 결과로 나옵니다)
          SELECT
                 CONCAT('주문 수: ', COUNT(*)) AS MSG
            FROM ORDERS"""),
      Map.entry("RETURNING INTO (넣은 번호 받기, PL/SQL)",
          """
          -- AUTO_INCREMENT 로 넣은 번호는 같은 접속에서 LAST_INSERT_ID() 로 받습니다
          INSERT INTO ORDERS (CUST_ID, STATUS, ORDER_DT, AMOUNT)
          VALUES (1, '접수', CURRENT_DATE, 0)
          ;
          SELECT LAST_INSERT_ID() AS ORDER_ID"""),
      Map.entry("TABLE(시스템 펑션) · 컬렉션 펼치기",
          """
          SELECT
                 j.X AS PART_NO
            FROM JSON_TABLE('["P001", "P002", "P003"]', '$[*]' COLUMNS (X VARCHAR(30) PATH '$')) j"""),
      Map.entry("SAMPLE (테이블에서 몇 % 만 뽑기)",
          """
          -- MySQL 에는 TABLESAMPLE 이 없어 RAND() 로 약 10% 를 고릅니다(테이블 전체를 읽습니다)
          SELECT
                 o.*
            FROM ORDERS o
           WHERE RAND() < 0.1"""),
      Map.entry("번호 1 ~ N 줄 만들기",
          """
          WITH RECURSIVE g (N) AS (
              SELECT 1
               UNION ALL
              SELECT N + 1 FROM g WHERE N < 100
          )
          SELECT
                 g.N
            FROM g"""),
      Map.entry("가짜 주문 100건 만들기 (보기만)",
          """
          WITH RECURSIVE g (N) AS (
              SELECT 1
               UNION ALL
              SELECT N + 1 FROM g WHERE N < 100
          )
          SELECT
                 100000 + g.N AS ORDER_ID
               , MOD(g.N, 10) + 1 AS CUST_ID
               , CURRENT_DATE - INTERVAL MOD(g.N * 7, 90) DAY AS ORDER_DT
               , ROUND(1000 + RAND() * 99000) AS AMOUNT
               , CASE MOD(g.N, 3) WHEN 0 THEN '접수' WHEN 1 THEN '진행' ELSE '완료' END AS STATUS
            FROM g"""),
      Map.entry("부품 번호 꼴 만들기 (P0001 ~)",
          """
          WITH RECURSIVE g (N) AS (
              SELECT 1
               UNION ALL
              SELECT N + 1 FROM g WHERE N < 20
          )
          SELECT
                 CONCAT('P', LPAD(g.N, 4, '0')) AS PART_NO
            FROM g"""),
      Map.entry("다른 계정에 읽기 권한 주기",
          """
          -- MySQL 은 사용자 이름에 접속 호스트를 붙여 씁니다('%' 는 어디서나)
          GRANT SELECT ON ORDERS TO 'report_user'@'%'"""),
      Map.entry("한 번만 돌리는 블록 (반복하며 고치기)",
          """
          -- MySQL 은 익명 블록이 없어 한 문장으로 고칩니다(줄마다 다른 처리가 필요하면 프로시저를 만듭니다)
          UPDATE IF_PART
             SET STATUS = 'READY'
               , RETRY_CNT = RETRY_CNT + 1
           WHERE STATUS = 'ERROR'"""),
      Map.entry("값 하나 돌려주는 펑션 만들기 (코드 → 이름)",
          """
          -- 콘솔에서는 한 문장으로 실행합니다(mysql 명령 줄 도구라면 앞뒤에 DELIMITER 를 바꿉니다)
          CREATE FUNCTION FN_CODE_NAME(P_GROUP VARCHAR(30), P_CODE VARCHAR(30)) RETURNS VARCHAR(200)
          READS SQL DATA
          RETURN (
            SELECT c.CODE_NAME
              FROM COMMON_CODE c
             WHERE c.GROUP_CD = P_GROUP
               AND c.CODE = P_CODE
             LIMIT 1
          )"""),
      Map.entry("오류가 나도 다음 줄 계속 (줄마다 예외 잡기)",
          """
          -- MySQL 은 익명 블록이 없어 프로시저로 만들고 CALL 합니다. 줄마다 오류를 잡아 ERROR 로 남기고 다음 줄로 갑니다
          CREATE PROCEDURE PR_IF_PART_APPLY()
          BEGIN
            DECLARE V_DONE INT DEFAULT 0;
            DECLARE V_IF_ID BIGINT;
            DECLARE V_PART_NO VARCHAR(30);
            DECLARE V_PART_NAME VARCHAR(200);
            DECLARE C CURSOR FOR SELECT f.IF_ID, f.PART_NO, f.PART_NAME FROM IF_PART f WHERE f.STATUS = 'READY';
            DECLARE CONTINUE HANDLER FOR NOT FOUND SET V_DONE = 1;
            OPEN C;
            read_loop: LOOP
              FETCH C INTO V_IF_ID, V_PART_NO, V_PART_NAME;
              IF V_DONE = 1 THEN LEAVE read_loop; END IF;
              BEGIN
                DECLARE EXIT HANDLER FOR SQLEXCEPTION
                  UPDATE IF_PART SET STATUS = 'ERROR' WHERE IF_ID = V_IF_ID;
                UPDATE ERP_PART SET PART_NAME = V_PART_NAME WHERE PART_NO = V_PART_NO;
                UPDATE IF_PART SET STATUS = 'DONE' WHERE IF_ID = V_IF_ID;
              END;
            END LOOP;
            CLOSE C;
          END"""),
      Map.entry("DISTINCT ON (그룹마다 첫 줄 하나)",
          """
          SELECT
                 x.*
            FROM (
                  SELECT
                         o.*
                       , ROW_NUMBER() OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT DESC) AS RN
                    FROM ORDERS o
                 ) x
           WHERE x.RN = 1
           ORDER BY
                 x.CUST_ID"""),
      Map.entry("AGE · EXTRACT (나이 · 경과 기간)",
          """
          SELECT
                 p.PROJ_ID
               , CONCAT(TIMESTAMPDIFF(YEAR, p.START_DT, CURRENT_DATE), '년 ', MOD(TIMESTAMPDIFF(MONTH, p.START_DT, CURRENT_DATE), 12), '개월') AS ELAPSED
               , TIMESTAMPDIFF(MONTH, p.START_DT, CURRENT_DATE) AS MONTHS
               , YEAR(p.START_DT) AS START_YEAR
            FROM PROJECT p"""),
      Map.entry("RETURNING (넣은 · 고친 줄 바로 받기)",
          """
          -- MySQL 에는 RETURNING 이 없어 고칠 줄을 먼저 보고 고칩니다
          SELECT
                 f.IF_ID
               , f.PART_NO
            FROM IF_PART f
           WHERE f.STATUS = 'ERROR'
          ;
          UPDATE IF_PART
             SET STATUS = 'READY'
           WHERE STATUS = 'ERROR'"""),
      Map.entry("UPDATE … FROM (다른 테이블과 이어 고치기)",
          """
          UPDATE ERP_PART e
            JOIN PART p
              ON e.PART_NO = p.PART_NO
             SET e.UNIT_COST = p.UNIT_COST"""),
      Map.entry("DELETE … USING (다른 테이블과 이어 지우기)",
          """
          DELETE b
            FROM ORDERS_BAK b
            JOIN ORDERS o
              ON o.ORDER_ID = b.ORDER_ID"""),
      Map.entry("ARRAY · ANY (값 목록을 배열로)",
          """
          SELECT
                 p.PART_NO
            FROM PART p
           WHERE p.PART_NO IN ('P001', 'P002', 'P003')"""),
      Map.entry("SPLIT_PART · REGEXP_SPLIT_TO_TABLE (글자 나누기)",
          """
          SELECT
                 SUBSTRING_INDEX(SUBSTRING_INDEX('A-B-C', '-', 2), '-', -1) AS SECOND_PART
               , REPLACE('a,b,c', ',', '|') AS SPLIT_JOINED"""),
      Map.entry("GENERATE_SERIES (번호 · 날짜 줄 만들기)",
          """
          WITH RECURSIVE g (DT) AS (
              SELECT CURRENT_DATE
               UNION ALL
              SELECT DT + INTERVAL 1 DAY FROM g WHERE DT < CURRENT_DATE + INTERVAL 6 DAY
          )
          SELECT
                 g.DT
            FROM g"""),
      Map.entry("시퀀스 다음 번호 (NEXTVAL)",
          """
          -- MySQL 에는 시퀀스가 없습니다. 다음 AUTO_INCREMENT 값을 봅니다(통계 값이라 ANALYZE TABLE 뒤에 정확합니다)
          SELECT
                 t.AUTO_INCREMENT AS NEXT_ID
            FROM INFORMATION_SCHEMA.TABLES t
           WHERE t.TABLE_SCHEMA = DATABASE()
             AND t.TABLE_NAME = 'orders'"""),
      Map.entry("지금 접속한 세션 (프로그램 · 사용자별)",
          """
          SELECT
                 p.USER AS USERNAME
               , p.DB AS DB_NAME
               , SUBSTRING_INDEX(p.HOST, ':', 1) AS MACHINE
               , p.COMMAND AS STATUS
               , COUNT(*) AS SESSIONS
            FROM INFORMATION_SCHEMA.PROCESSLIST p
           GROUP BY
                 p.USER
               , p.DB
               , SUBSTRING_INDEX(p.HOST, ':', 1)
               , p.COMMAND
           ORDER BY
                 SESSIONS DESC"""),
      Map.entry("오래 놀고 있는 트랜잭션 (idle in transaction)",
          """
          SELECT
                 p.ID AS PID
               , p.USER AS USENAME
               , p.HOST
               , x.TRX_STARTED AS XACT_START
               , TIMESTAMPDIFF(MINUTE, x.TRX_STARTED, NOW()) AS OPEN_MIN
            FROM INFORMATION_SCHEMA.INNODB_TRX x
            JOIN INFORMATION_SCHEMA.PROCESSLIST p
              ON p.ID = x.TRX_MYSQL_THREAD_ID
           WHERE p.COMMAND = 'Sleep'
             AND x.TRX_STARTED < NOW() - INTERVAL 5 MINUTE"""),
      Map.entry("세션 끊기 (확인 뒤에만)",
          """
          -- 먼저 SHOW PROCESSLIST 로 번호를 확인하세요
          KILL :pid"""),
      Map.entry("안 쓰는 인덱스 (한 번도 안 읽힘)",
          """
          SELECT
                 u.object_name AS TABLE_NAME
               , u.index_name AS INDEX_NAME
            FROM sys.schema_unused_indexes u
           WHERE u.object_schema = DATABASE()
           ORDER BY
                 1, 2"""),
      Map.entry("통계가 오래된 테이블",
          """
          -- 통계를 새로 모으는 문장은 ANALYZE TABLE 테이블이름 입니다
          SELECT
                 s.TABLE_NAME
               , s.LAST_UPDATE
               , s.N_ROWS
            FROM mysql.innodb_table_stats s
           WHERE s.DATABASE_NAME = DATABASE()
           ORDER BY
                 s.LAST_UPDATE"""),
      Map.entry("깨진 객체 (INVALID 뷰 · 프로시저)",
          """
          -- MySQL 은 뷰가 깨져도 표시하지 않습니다. 뷰마다 CHECK TABLE 을 돌려 봅니다
          SELECT
                 v.TABLE_NAME AS VIEW_NAME
               , CONCAT('CHECK TABLE `', v.TABLE_NAME, '`') AS CHECK_SQL
            FROM INFORMATION_SCHEMA.VIEWS v
           WHERE v.TABLE_SCHEMA = DATABASE()
           ORDER BY
                 1"""),
      Map.entry("테이블 공간 · DB 크기",
          """
          SELECT
                 t.TABLE_SCHEMA AS DB_NAME
               , ROUND(SUM(t.DATA_LENGTH + t.INDEX_LENGTH) / 1024 / 1024, 1) AS SIZE_MB
            FROM INFORMATION_SCHEMA.TABLES t
           GROUP BY
                 t.TABLE_SCHEMA
           ORDER BY
                 SIZE_MB DESC"""),
      Map.entry("많이 읽고 많이 쓰는 테이블 (전체 훑기 많은 테이블)",
          """
          SELECT
                 s.OBJECT_NAME AS TABLE_NAME
               , s.COUNT_READ
               , s.COUNT_FETCH
               , s.COUNT_INSERT + s.COUNT_UPDATE + s.COUNT_DELETE AS WRITES
            FROM performance_schema.table_io_waits_summary_by_table s
           WHERE s.OBJECT_SCHEMA = DATABASE()
           ORDER BY
                 s.COUNT_READ DESC
           LIMIT 20"""),
      Map.entry("테이블 · 칼럼 설명 한 번에 보기 (테이블 정의서)",
          """
          SELECT
                 c.TABLE_NAME
               , c.ORDINAL_POSITION
               , c.COLUMN_NAME
               , c.COLUMN_TYPE AS COL_TYPE
               , c.IS_NULLABLE
               , c.COLUMN_COMMENT AS COMMENTS
            FROM INFORMATION_SCHEMA.COLUMNS c
           WHERE c.TABLE_SCHEMA = DATABASE()
             AND c.TABLE_NAME = LOWER(:tableName)
           ORDER BY
                 c.ORDINAL_POSITION"""),
      Map.entry("FK 칼럼에 인덱스가 없는 것",
          """
          -- MySQL(InnoDB) 은 FK 를 걸 때 인덱스를 저절로 만들므로, FK 첫 칼럼으로 시작하는 인덱스가 없는 것만 봅니다
          SELECT
                 k.TABLE_NAME
               , k.COLUMN_NAME
               , k.CONSTRAINT_NAME
            FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k
           WHERE k.TABLE_SCHEMA = DATABASE()
             AND k.REFERENCED_TABLE_NAME IS NOT NULL
             AND k.ORDINAL_POSITION = 1
             AND NOT EXISTS (
                  SELECT 1
                    FROM INFORMATION_SCHEMA.STATISTICS s
                   WHERE s.TABLE_SCHEMA = k.TABLE_SCHEMA
                     AND s.TABLE_NAME = k.TABLE_NAME
                     AND s.COLUMN_NAME = k.COLUMN_NAME
                     AND s.SEQ_IN_INDEX = 1
                 )"""),
      Map.entry("테이블마다 정확한 건수 (모든 테이블 한 번에)",
          """
          -- 정확한 건수를 한 번에 세려면 테이블마다 COUNT 문장이 필요합니다. 이 쿼리는 그 문장을 만들고, 결과를 복사해 실행합니다
          SELECT
                 CONCAT('SELECT ''', t.TABLE_NAME, ''' AS TABLE_NAME, COUNT(*) AS ROW_CNT FROM `', t.TABLE_NAME, '` UNION ALL') AS COUNT_SQL
            FROM INFORMATION_SCHEMA.TABLES t
           WHERE t.TABLE_SCHEMA = DATABASE()
             AND t.TABLE_TYPE = 'BASE TABLE'
           ORDER BY
                 t.TABLE_NAME"""),
      Map.entry("최근에 바뀐 객체 (테이블 · 뷰 · 프로시저)",
          """
          SELECT
                 t.TABLE_TYPE AS OBJECT_TYPE
               , t.TABLE_NAME AS OBJECT_NAME
               , COALESCE(t.UPDATE_TIME, t.CREATE_TIME) AS CHANGED
            FROM INFORMATION_SCHEMA.TABLES t
           WHERE t.TABLE_SCHEMA = DATABASE()
           UNION ALL
          SELECT
                 r.ROUTINE_TYPE
               , r.ROUTINE_NAME
               , r.LAST_ALTERED
            FROM INFORMATION_SCHEMA.ROUTINES r
           WHERE r.ROUTINE_SCHEMA = DATABASE()
           ORDER BY
                 CHANGED IS NULL
               , CHANGED DESC"""),
      Map.entry("시퀀스 지금 값과 테이블 최대 번호 비교",
          """
          -- MySQL 은 시퀀스 대신 AUTO_INCREMENT 를 씁니다(통계 값이라 ANALYZE TABLE 뒤에 정확합니다)
          SELECT
                 t.TABLE_NAME
               , t.AUTO_INCREMENT AS NEXT_ID
               , (SELECT MAX(o.ORDER_ID) FROM ORDERS o) AS MAX_ID
            FROM INFORMATION_SCHEMA.TABLES t
           WHERE t.TABLE_SCHEMA = DATABASE()
             AND t.TABLE_NAME = 'orders'"""),
      Map.entry("테이블 공간 사용률 (% · 자동 늘림 최대)",
          """
          SELECT
                 f.TABLESPACE_NAME
               , f.FILE_NAME AS LOCATION
               , ROUND(f.TOTAL_EXTENTS * f.EXTENT_SIZE / 1024 / 1024, 1) AS USED_MB
               , f.AUTOEXTEND_SIZE
               , f.MAXIMUM_SIZE
            FROM INFORMATION_SCHEMA.FILES f
           ORDER BY
                 USED_MB DESC"""),
      Map.entry("용량 큰 것 (테이블 · 인덱스 · LOB 를 테이블 이름으로)",
          """
          SELECT
                 t.TABLE_NAME
               , ROUND((t.DATA_LENGTH + t.INDEX_LENGTH) / 1024 / 1024, 1) AS TOTAL_MB
               , ROUND(t.DATA_LENGTH / 1024 / 1024, 1) AS TABLE_MB
               , ROUND(t.INDEX_LENGTH / 1024 / 1024, 1) AS INDEX_MB
               , ROUND(t.DATA_FREE / 1024 / 1024, 1) AS FREE_MB
            FROM INFORMATION_SCHEMA.TABLES t
           WHERE t.TABLE_SCHEMA = DATABASE()
             AND t.TABLE_TYPE = 'BASE TABLE'
           ORDER BY
                 TOTAL_MB DESC
           LIMIT 30"""),
      Map.entry("깨진 인덱스 (UNUSABLE) 과 다시 만드는 문장",
          """
          -- InnoDB 인덱스에는 깨진 상태가 없습니다. 테이블 점검은 CHECK TABLE, 다시 만들기는 OPTIMIZE TABLE 입니다
          SELECT
                 t.TABLE_NAME
               , CONCAT('CHECK TABLE `', t.TABLE_NAME, '`') AS CHECK_SQL
               , CONCAT('OPTIMIZE TABLE `', t.TABLE_NAME, '`') AS FIX_SQL
            FROM INFORMATION_SCHEMA.TABLES t
           WHERE t.TABLE_SCHEMA = DATABASE()
             AND t.TABLE_TYPE = 'BASE TABLE'"""),
      Map.entry("배치 작업 (JOB) 목록 · 다음 실행 시각",
          """
          SELECT
                 e.EVENT_NAME
               , e.STATUS
               , COALESCE(e.EXECUTE_AT, CONCAT('매 ', e.INTERVAL_VALUE, ' ', e.INTERVAL_FIELD)) AS SCHEDULE
               , e.LAST_EXECUTED
               , e.EVENT_DEFINITION AS COMMAND
            FROM INFORMATION_SCHEMA.EVENTS e
           WHERE e.EVENT_SCHEMA = DATABASE()
           ORDER BY
                 e.EVENT_NAME"""),
      Map.entry("배치 작업 실행 이력 · 실패 (최근 7일)",
          """
          -- MySQL 이벤트는 실행 이력을 남기지 않습니다. 이벤트 안에서 이력 테이블(BATCH_LOG)에 남긴 것을 봅니다
          SELECT
                 l.JOB_NAME
               , l.START_TS
               , TIMESTAMPDIFF(SECOND, l.START_TS, l.END_TS) AS RUN_SEC
               , l.STATUS
            FROM BATCH_LOG l
           WHERE l.START_TS >= NOW() - INTERVAL 7 DAY
           ORDER BY
                 l.START_TS DESC"""),
      Map.entry("잠긴 계정 · 곧 비밀번호 만료되는 계정",
          """
          SELECT
                 u.User AS USERNAME
               , u.Host
               , u.account_locked AS LOCKED
               , u.password_last_changed
               , u.password_lifetime
            FROM mysql.user u
           WHERE u.account_locked = 'Y'
              OR u.password_expired = 'Y'
              OR (u.password_lifetime IS NOT NULL AND u.password_last_changed + INTERVAL u.password_lifetime DAY < NOW() + INTERVAL 30 DAY)
           ORDER BY
                 u.User"""),
      Map.entry("휴지통에서 지운 테이블 되살리기 (FLASHBACK TO BEFORE DROP)",
          """
          SELECT
                 'MySQL 은 휴지통이 없어 DROP 한 테이블은 백업(mysqldump) 이나 바이너리 로그에서만 되살립니다.' AS NOTE"""),
      Map.entry("DB 버전 · 문자셋 (한글이 몇 바이트인가)",
          """
          -- utf8mb4 는 한글 한 글자가 3바이트입니다
          SELECT
                 VERSION() AS VERSION
               , s.DEFAULT_CHARACTER_SET_NAME AS CHARSET
               , s.DEFAULT_COLLATION_NAME AS COLLATION_NAME
            FROM INFORMATION_SCHEMA.SCHEMATA s
           WHERE s.SCHEMA_NAME = DATABASE()"""),
      Map.entry("DB 링크 목록 (어느 DB 로 이어지나)",
          """
          -- MySQL 은 다른 DB 로 잇는 것을 FEDERATED 엔진 테이블로 만듭니다
          SELECT
                 t.TABLE_NAME
               , t.ENGINE
               , t.CREATE_OPTIONS
            FROM INFORMATION_SCHEMA.TABLES t
           WHERE t.ENGINE = 'FEDERATED'
           ORDER BY
                 t.TABLE_NAME"""),
      Map.entry("통계 뒤로 많이 바뀐 테이블 · 정리(VACUUM) 필요한 테이블",
          """
          -- InnoDB 는 VACUUM 이 없습니다. 빈 공간(DATA_FREE)이 큰 테이블은 OPTIMIZE TABLE 로 줄입니다
          SELECT
                 t.TABLE_NAME
               , t.TABLE_ROWS
               , ROUND(t.DATA_FREE / 1024 / 1024, 1) AS FREE_MB
               , ROUND(100.0 * t.DATA_FREE / NULLIF(t.DATA_LENGTH + t.DATA_FREE, 0), 1) AS FREE_PCT
               , CONCAT('OPTIMIZE TABLE `', t.TABLE_NAME, '`') AS FIX_SQL
            FROM INFORMATION_SCHEMA.TABLES t
           WHERE t.TABLE_SCHEMA = DATABASE()
             AND t.TABLE_TYPE = 'BASE TABLE'
           ORDER BY
                 t.DATA_FREE DESC"""),
      Map.entry("DB 설정값 찾기 (이름 일부로)",
          """
          SELECT
                 v.VARIABLE_NAME AS NAME
               , v.VARIABLE_VALUE AS SETTING
            FROM performance_schema.global_variables v
           WHERE v.VARIABLE_NAME LIKE CONCAT('%', :keyword, '%')
           ORDER BY
                 v.VARIABLE_NAME"""),
      Map.entry("세션 · 프로세스 한도 얼마나 찼나 (ORA-00018 · too many connections)",
          """
          SELECT
                 (SELECT COUNT(*) FROM INFORMATION_SCHEMA.PROCESSLIST) AS NOW_CONN
               , @@max_connections AS LIMIT_CONN
               , (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Max_used_connections') AS MAX_USED"""),
      Map.entry("설치된 옵션 · 확장 목록",
          """
          SELECT
                 p.PLUGIN_NAME
               , p.PLUGIN_VERSION
               , p.PLUGIN_STATUS
               , p.PLUGIN_TYPE
            FROM INFORMATION_SCHEMA.PLUGINS p
           ORDER BY
                 p.PLUGIN_NAME"""),
      Map.entry("설명이 빈 칼럼 (테이블 정의서 빠짐)",
          """
          SELECT
                 c.TABLE_NAME
               , c.COLUMN_NAME
               , c.COLUMN_TYPE AS DATA_TYPE
               , t.TABLE_COMMENT
            FROM INFORMATION_SCHEMA.COLUMNS c
            JOIN INFORMATION_SCHEMA.TABLES t
              ON t.TABLE_SCHEMA = c.TABLE_SCHEMA
             AND t.TABLE_NAME = c.TABLE_NAME
           WHERE c.TABLE_SCHEMA = DATABASE()
             AND t.TABLE_TYPE = 'BASE TABLE'
             AND c.COLUMN_COMMENT = ''
           ORDER BY
                 c.TABLE_NAME
               , c.ORDINAL_POSITION"""),
      Map.entry("테이블 데이터로 INSERT 문 만들기 (코드 테이블 옮길 때)",
          """
          SELECT
                 CONCAT('INSERT INTO CODE_DTL (GRP_CD, CD, CD_NM, SORT_SEQ, USE_YN) VALUES ('
                      , QUOTE(d.GRP_CD), ', ', QUOTE(d.CD), ', ', QUOTE(d.CD_NM), ', '
                      , COALESCE(d.SORT_SEQ, 'NULL'), ', ', QUOTE(d.USE_YN), ');') AS INSERT_SQL
            FROM CODE_DTL d
           WHERE d.GRP_CD = :grpCd
           ORDER BY
                 d.SORT_SEQ"""));

  /** MySQL 로 보일 SQL. 따로 쓴 글이 없으면 PostgreSQL 글을 바꾼다. */
  static String sql(String title, String postgres) {
    String own = BY_TITLE.get(title);
    return own != null ? own : MysqlConvert.convert(postgres);
  }
}
