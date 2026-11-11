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
      new Example("기본 조회", "정렬 여러 기준 (먼저 상태, 같으면 최근 순)", "ORDER BY 에 여러 칼럼을 쓰면 앞 칼럼이 같을 때 다음 칼럼으로 가릅니다.",
          """
          SELECT
                 o.ORDER_ID
               , o.STATUS
               , o.ORDER_DT
            FROM ORDERS o
           ORDER BY
                 o.STATUS
               , o.ORDER_DT DESC""",
          """
          SELECT
                 o.ORDER_ID
               , o.STATUS
               , o.ORDER_DT
            FROM ORDERS o
           ORDER BY
                 o.STATUS
               , o.ORDER_DT DESC"""),
      new Example("기본 조회", "정해 둔 순서로 정렬 (접수 → 진행 → 완료)", "글자 순서가 아니라 업무 순서로 보고 싶을 때 CASE 로 번호를 줍니다.",
          """
          SELECT
                 o.ORDER_ID
               , o.STATUS
            FROM ORDERS o
           ORDER BY
                 CASE o.STATUS WHEN '접수' THEN 1 WHEN '진행' THEN 2 WHEN '완료' THEN 3 ELSE 9 END
               , o.ORDER_ID""",
          """
          SELECT
                 o.ORDER_ID
               , o.STATUS
            FROM ORDERS o
           ORDER BY
                 CASE o.STATUS WHEN '접수' THEN 1 WHEN '진행' THEN 2 WHEN '완료' THEN 3 ELSE 9 END
               , o.ORDER_ID"""),
      new Example("기본 조회", "칼럼에 별칭 붙이기 · 계산 칼럼", "AS 로 화면에 보일 이름을 붙입니다. 계산한 값도 칼럼처럼 나옵니다.",
          """
          SELECT
                 i.ORDER_ID AS 주문번호
               , i.PART_NO AS 부품
               , i.QTY * i.PRICE AS 금액
            FROM ORDER_ITEM i""",
          """
          SELECT
                 i.ORDER_ID AS "주문번호"
               , i.PART_NO AS "부품"
               , i.QTY * i.PRICE AS "금액"
            FROM ORDER_ITEM i"""),
      new Example("기본 조회", "LIKE 로 시작 · 끝 · 포함", "'A%' 는 A 로 시작, '%A' 는 A 로 끝, '%A%' 는 A 가 들어간 것입니다. 앞이 % 면 색인을 못 탑니다.",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
            FROM PART p
           WHERE p.PART_NO LIKE 'P%'
              OR p.PART_NAME LIKE '%볼트'""",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
            FROM PART p
           WHERE p.PART_NO LIKE 'P%'
              OR p.PART_NAME LIKE '%볼트'"""),
      new Example("기본 조회", "% · _ 글자 자체를 찾기 (ESCAPE)", "LIKE 에서 % 와 _ 는 아무 글자라는 뜻이라, 그 글자 자체를 찾으려면 ESCAPE 로 표시합니다.",
          """
          SELECT
                 o.ORDER_ID
               , o.MEMO
            FROM ORDERS o
           WHERE o.MEMO LIKE '%10\\%%' ESCAPE '\\'""",
          """
          SELECT
                 o.ORDER_ID
               , o.MEMO
            FROM ORDERS o
           WHERE o.MEMO LIKE '%10\\%%' ESCAPE '\\'"""),
      new Example("기본 조회", "아닌 것 (NOT · <>)", "상태가 완료가 아닌 것. <> 는 NULL 을 고르지 않으므로 NULL 도 보려면 OR IS NULL 을 붙입니다.",
          """
          SELECT
                 o.ORDER_ID
               , o.STATUS
            FROM ORDERS o
           WHERE (o.STATUS <> '완료' OR o.STATUS IS NULL)""",
          """
          SELECT
                 o.ORDER_ID
               , o.STATUS
            FROM ORDERS o
           WHERE (o.STATUS <> '완료' OR o.STATUS IS NULL)"""),
      new Example("화면 기본 쿼리 (목록 · 상세 · 저장)", "목록 조회 (검색 조건 + 코드 이름 + 등록자 이름)", "화면 목록의 기본 꼴: 선택 검색 조건, 코드 이름, 등록자 이름을 붙이고 최근 순.",
          """
          SELECT
                 i.ISSUE_ID
               , i.TITLE
               , i.STATUS
               , (SELECT c.CODE_NAME FROM COMMON_CODE c WHERE c.GROUP_CD = 'ISSUE_STATUS' AND c.CODE = i.STATUS) AS STATUS_NAME
               , u.USER_NAME AS OWNER_NAME
               , i.REG_DT
            FROM ISSUE i
               , USERS u
           WHERE i.OWNER_ID = u.USER_ID(+)
             AND (:projId IS NULL OR i.PROJ_ID = :projId)
             AND (:status IS NULL OR i.STATUS = :status)
             AND (:keyword IS NULL OR i.TITLE LIKE '%' || :keyword || '%')
           ORDER BY
                 i.REG_DT DESC
               , i.ISSUE_ID DESC""",
          """
          SELECT
                 i.ISSUE_ID
               , i.TITLE
               , i.STATUS
               , (SELECT c.CODE_NAME FROM COMMON_CODE c WHERE c.GROUP_CD = 'ISSUE_STATUS' AND c.CODE = i.STATUS) AS STATUS_NAME
               , u.USER_NAME AS OWNER_NAME
               , i.REG_DT
            FROM ISSUE i
            LEFT OUTER JOIN USERS u
              ON i.OWNER_ID = u.USER_ID
           WHERE (CAST(:projId AS INTEGER) IS NULL OR i.PROJ_ID = CAST(:projId AS INTEGER))
             AND (CAST(:status AS VARCHAR) IS NULL OR i.STATUS = :status)
             AND (CAST(:keyword AS VARCHAR) IS NULL OR i.TITLE LIKE '%' || :keyword || '%')
           ORDER BY
                 i.REG_DT DESC
               , i.ISSUE_ID DESC"""),
      new Example("화면 기본 쿼리 (목록 · 상세 · 저장)", "상세 조회 (한 건 + 이름 · 건수 붙이기)", "키 하나로 한 줄. 상위 이름, 담당자 이름, 딸린 건수를 함께 붙입니다.",
          """
          SELECT
                 p.PROJ_ID
               , p.PROJ_NAME
               , p.STATUS
               , d.DEPT_NAME
               , u.USER_NAME AS OWNER_NAME
               , (SELECT COUNT(*) FROM ISSUE i WHERE i.PROJ_ID = p.PROJ_ID AND i.STATUS <> 'CLOSE') AS OPEN_ISSUES
               , (SELECT COUNT(*) FROM SCHEDULE s WHERE s.PROJ_ID = p.PROJ_ID) AS SCH_CNT
            FROM PROJECT p
               , DEPT d
               , USERS u
           WHERE p.DEPT_ID = d.DEPT_ID(+)
             AND p.OWNER_ID = u.USER_ID(+)
             AND p.PROJ_ID = :projId""",
          """
          SELECT
                 p.PROJ_ID
               , p.PROJ_NAME
               , p.STATUS
               , d.DEPT_NAME
               , u.USER_NAME AS OWNER_NAME
               , (SELECT COUNT(*) FROM ISSUE i WHERE i.PROJ_ID = p.PROJ_ID AND i.STATUS <> 'CLOSE') AS OPEN_ISSUES
               , (SELECT COUNT(*) FROM SCHEDULE s WHERE s.PROJ_ID = p.PROJ_ID) AS SCH_CNT
            FROM PROJECT p
            LEFT OUTER JOIN DEPT d
              ON p.DEPT_ID = d.DEPT_ID
            LEFT OUTER JOIN USERS u
              ON p.OWNER_ID = u.USER_ID
           WHERE p.PROJ_ID = CAST(:projId AS INTEGER)"""),
      new Example("화면 기본 쿼리 (목록 · 상세 · 저장)", "등록 (새 번호 = 지금 가장 큰 번호 + 1)", "시퀀스가 없는 표에서 새 번호를 만들어 넣습니다. 동시에 누르면 같은 번호가 날 수 있어 키 제약이 꼭 있어야 합니다.",
          """
          INSERT INTO ISSUE (ISSUE_ID, PROJ_ID, TITLE, STATUS, OWNER_ID, REG_DT)
          SELECT
                 NVL(MAX(i.ISSUE_ID), 0) + 1
               , :projId
               , :title
               , 'OPEN'
               , :ownerId
               , TRUNC(SYSDATE)
            FROM ISSUE i""",
          """
          INSERT INTO ISSUE (ISSUE_ID, PROJ_ID, TITLE, STATUS, OWNER_ID, REG_DT)
          SELECT
                 COALESCE(MAX(i.ISSUE_ID), 0) + 1
               , CAST(:projId AS INTEGER)
               , :title
               , 'OPEN'
               , CAST(:ownerId AS INTEGER)
               , CURRENT_DATE
            FROM ISSUE i"""),
      new Example("화면 기본 쿼리 (목록 · 상세 · 저장)", "수정 (바뀐 칸만, 빈 값은 그대로 두기)", "화면에서 넘어온 값이 비면 원래 값을 남깁니다. 지우려는 뜻의 빈 값과 구분해야 하면 쓰지 마세요.",
          """
          UPDATE ISSUE i
             SET i.TITLE = NVL(:title, i.TITLE)
               , i.STATUS = NVL(:status, i.STATUS)
               , i.OWNER_ID = NVL(:ownerId, i.OWNER_ID)
           WHERE i.ISSUE_ID = :issueId""",
          """
          UPDATE ISSUE i
             SET TITLE = COALESCE(:title, i.TITLE)
               , STATUS = COALESCE(:status, i.STATUS)
               , OWNER_ID = COALESCE(CAST(:ownerId AS INTEGER), i.OWNER_ID)
           WHERE i.ISSUE_ID = CAST(:issueId AS INTEGER)"""),
      new Example("화면 기본 쿼리 (목록 · 상세 · 저장)", "삭제 대신 사용 안 함 표시 (논리 삭제)", "줄을 지우지 않고 사용 여부만 N 으로. 목록 조회에는 USE_YN = 'Y' 를 꼭 붙입니다.",
          """
          UPDATE USERS u
             SET u.USE_YN = 'N'
           WHERE u.USER_ID = :userId""",
          """
          UPDATE USERS u
             SET USE_YN = 'N'
           WHERE u.USER_ID = CAST(:userId AS INTEGER)"""),
      new Example("화면 기본 쿼리 (목록 · 상세 · 저장)", "고치기 전 값 이력 남기기 (INSERT … SELECT)", "UPDATE 바로 앞에 지금 값을 이력 표에 떠 둡니다. 둘을 같은 트랜잭션에서 실행하세요.",
          """
          INSERT INTO PART_HIST (PART_NO, COL_NAME, OLD_VAL, NEW_VAL, CHG_DT, CHG_USER)
          SELECT
                 p.PART_NO
               , 'UNIT_COST'
               , TO_CHAR(p.UNIT_COST)
               , TO_CHAR(:newCost)
               , SYSTIMESTAMP
               , :userId
            FROM PART p
           WHERE p.PART_NO = :partNo
             AND DECODE(p.UNIT_COST, :newCost, 0, 1) = 1""",
          """
          INSERT INTO PART_HIST (PART_NO, COL_NAME, OLD_VAL, NEW_VAL, CHG_DT, CHG_USER)
          SELECT
                 p.PART_NO
               , 'UNIT_COST'
               , CAST(p.UNIT_COST AS VARCHAR)
               , CAST(:newCost AS VARCHAR)
               , CURRENT_TIMESTAMP
               , CAST(:userId AS INTEGER)
            FROM PART p
           WHERE p.PART_NO = :partNo
             AND p.UNIT_COST IS DISTINCT FROM CAST(:newCost AS NUMERIC)"""),
      new Example("화면 기본 쿼리 (목록 · 상세 · 저장)", "콤보 상자 (코드 · 이름, 「전체」 줄 맨 위)", "선택 상자에 넣을 목록. 맨 위에 「전체」 를 붙이고 정렬 번호대로.",
          """
          SELECT
                 x.CODE
               , x.CODE_NAME
            FROM (
                  SELECT
                         NULL AS CODE
                       , '전체' AS CODE_NAME
                       , -1 AS SORT_NO
                    FROM DUAL
                   UNION ALL
                  SELECT
                         c.CODE
                       , c.CODE_NAME
                       , c.SORT_NO
                    FROM COMMON_CODE c
                   WHERE c.GROUP_CD = :groupCd
                     AND c.USE_YN = 'Y'
                 ) x
           ORDER BY
                 x.SORT_NO""",
          """
          SELECT
                 x.CODE
               , x.CODE_NAME
            FROM (
                  SELECT
                         NULL AS CODE
                       , '전체' AS CODE_NAME
                       , -1 AS SORT_NO
                   UNION ALL
                  SELECT
                         c.CODE
                       , c.CODE_NAME
                       , c.SORT_NO
                    FROM COMMON_CODE c
                   WHERE c.GROUP_CD = :groupCd
                     AND c.USE_YN = 'Y'
                 ) x
           ORDER BY
                 x.SORT_NO"""),
      new Example("화면 기본 쿼리 (목록 · 상세 · 저장)", "자동 완성 (친 글자로 시작하는 것 20건)", "검색 칸에 글자를 칠 때 아래에 띄울 목록. 앞에서부터 맞는 것을 먼저.",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
            FROM PART p
           WHERE p.PART_NO LIKE :typed || '%'
              OR p.PART_NAME LIKE '%' || :typed || '%'
           ORDER BY
                 CASE WHEN p.PART_NO LIKE :typed || '%' THEN 0 ELSE 1 END
               , p.PART_NO
           FETCH FIRST 20 ROWS ONLY""",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
            FROM PART p
           WHERE p.PART_NO LIKE :typed || '%'
              OR p.PART_NAME LIKE '%' || :typed || '%'
           ORDER BY
                 CASE WHEN p.PART_NO LIKE :typed || '%' THEN 0 ELSE 1 END
               , p.PART_NO
           LIMIT 20"""),
      new Example("화면 기본 쿼리 (목록 · 상세 · 저장)", "중복 확인 (저장 전 이 번호가 이미 있나)", "저장 단추 앞 검사. 수정일 때는 자기 자신을 빼고 셉니다.",
          """
          SELECT
                 COUNT(*) AS DUP_CNT
            FROM PART p
           WHERE p.PART_NO = :partNo
             AND (:oldPartNo IS NULL OR p.PART_NO <> :oldPartNo)""",
          """
          SELECT
                 COUNT(*) AS DUP_CNT
            FROM PART p
           WHERE p.PART_NO = :partNo
             AND (CAST(:oldPartNo AS VARCHAR) IS NULL OR p.PART_NO <> :oldPartNo)"""),
      new Example("화면 기본 쿼리 (목록 · 상세 · 저장)", "지우기 전에 딸린 데이터 있나 (삭제 막기)", "자식이 있으면 지우지 못하게 화면에서 막을 때 씁니다.",
          """
          SELECT
                 (SELECT COUNT(*) FROM BOM b WHERE b.CHILD_PART = :partNo OR b.PARENT_PART = :partNo) AS BOM_CNT
               , (SELECT COUNT(*) FROM ORDER_ITEM i WHERE i.PART_NO = :partNo) AS ORDER_CNT
               , (SELECT COUNT(*) FROM STOCK_TXN t WHERE t.PART_NO = :partNo) AS STOCK_CNT
            FROM DUAL""",
          """
          SELECT
                 (SELECT COUNT(*) FROM BOM b WHERE b.CHILD_PART = :partNo OR b.PARENT_PART = :partNo) AS BOM_CNT
               , (SELECT COUNT(*) FROM ORDER_ITEM i WHERE i.PART_NO = :partNo) AS ORDER_CNT
               , (SELECT COUNT(*) FROM STOCK_TXN t WHERE t.PART_NO = :partNo) AS STOCK_CNT"""),
      new Example("화면 기본 쿼리 (목록 · 상세 · 저장)", "엑셀 내려받기 (코드 이름 · 날짜 글자 · 숫자 꼴)", "엑셀에 그대로 붙도록 날짜는 글자로, 금액은 쉼표로, 코드는 이름으로 바꿔 냅니다.",
          """
          SELECT
                 o.ORDER_ID AS "주문번호"
               , TO_CHAR(o.ORDER_DT, 'YYYY-MM-DD') AS "주문일"
               , c.CUST_NAME AS "고객"
               , TO_CHAR(o.AMOUNT, 'FM999,999,999,990') AS "금액"
               , o.STATUS AS "상태"
            FROM ORDERS o
               , CUSTOMER c
           WHERE o.CUST_ID = c.CUST_ID(+)
           ORDER BY
                 o.ORDER_DT DESC""",
          """
          SELECT
                 o.ORDER_ID AS "주문번호"
               , TO_CHAR(o.ORDER_DT, 'YYYY-MM-DD') AS "주문일"
               , c.CUST_NAME AS "고객"
               , TO_CHAR(o.AMOUNT, 'FM999,999,999,990') AS "금액"
               , o.STATUS AS "상태"
            FROM ORDERS o
            LEFT OUTER JOIN CUSTOMER c
              ON o.CUST_ID = c.CUST_ID
           ORDER BY
                 o.ORDER_DT DESC"""),
      new Example("화면 기본 쿼리 (목록 · 상세 · 저장)", "트리 화면 (부서 트리: 레벨 · 끝 여부 · 정렬)", "왼쪽 트리 화면에 그대로 넣는 꼴. 레벨, 자식 여부, 형제 순서.",
          """
          SELECT
                 LEVEL AS LVL
               , d.DEPT_ID
               , d.PARENT_DEPT_ID
               , d.DEPT_NAME
               , CONNECT_BY_ISLEAF AS IS_LEAF
            FROM DEPT d
           START WITH d.PARENT_DEPT_ID IS NULL
          CONNECT BY PRIOR d.DEPT_ID = d.PARENT_DEPT_ID
           ORDER SIBLINGS BY
                 d.DEPT_NAME""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 1 AS LVL
               , d.DEPT_ID
               , d.PARENT_DEPT_ID
               , d.DEPT_NAME
               , CAST(d.DEPT_NAME AS VARCHAR(4000)) AS SORT_KEY
            FROM DEPT d
           WHERE d.PARENT_DEPT_ID IS NULL
           UNION ALL
          SELECT
                 t.LVL + 1
               , d.DEPT_ID
               , d.PARENT_DEPT_ID
               , d.DEPT_NAME
               , CAST(t.SORT_KEY || '/' || d.DEPT_NAME AS VARCHAR(4000))
            FROM DEPT d
               , t
           WHERE d.PARENT_DEPT_ID = t.DEPT_ID
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , t.DEPT_ID
               , t.PARENT_DEPT_ID
               , t.DEPT_NAME
               , CASE WHEN EXISTS (SELECT 1 FROM DEPT c WHERE c.PARENT_DEPT_ID = t.DEPT_ID) THEN 0 ELSE 1 END AS IS_LEAF
            FROM t
           ORDER BY
                 t.SORT_KEY"""),
      new Example("화면 기본 쿼리 (목록 · 상세 · 저장)", "마스터 · 디테일 (주문 한 건과 품목 줄, 합계)", "위 마스터 한 줄 아래 디테일 줄. 디테일 쪽에 줄 금액과 합계를 같이 붙입니다.",
          """
          SELECT
                 i.LINE_NO
               , i.PART_NO
               , p.PART_NAME
               , i.QTY
               , i.PRICE
               , i.QTY * i.PRICE AS AMT
               , SUM(i.QTY * i.PRICE) OVER () AS TOTAL_AMT
            FROM ORDER_ITEM i
               , PART p
           WHERE i.PART_NO = p.PART_NO(+)
             AND i.ORDER_ID = :orderId
           ORDER BY
                 i.LINE_NO""",
          """
          SELECT
                 i.LINE_NO
               , i.PART_NO
               , p.PART_NAME
               , i.QTY
               , i.PRICE
               , i.QTY * i.PRICE AS AMT
               , SUM(i.QTY * i.PRICE) OVER () AS TOTAL_AMT
            FROM ORDER_ITEM i
            LEFT OUTER JOIN PART p
              ON i.PART_NO = p.PART_NO
           WHERE i.ORDER_ID = CAST(:orderId AS INTEGER)
           ORDER BY
                 i.LINE_NO"""),
      new Example("화면 기본 쿼리 (목록 · 상세 · 저장)", "여러 줄 저장 (화면 표 → MERGE, 지운 줄은 DELETE)", "화면 표에서 넘어온 줄로 있으면 고치고 없으면 넣습니다. 화면에서 지운 줄은 따로 DELETE 합니다.",
          """
          MERGE INTO ORDER_ITEM t
          USING (
                  SELECT
                         :orderId AS ORDER_ID
                       , :lineNo AS LINE_NO
                       , :partNo AS PART_NO
                       , :qty AS QTY
                       , :price AS PRICE
                    FROM DUAL
                ) s
             ON (t.ORDER_ID = s.ORDER_ID AND t.LINE_NO = s.LINE_NO)
           WHEN MATCHED THEN
                UPDATE
                   SET t.PART_NO = s.PART_NO
                     , t.QTY = s.QTY
                     , t.PRICE = s.PRICE
           WHEN NOT MATCHED THEN
                INSERT (ITEM_ID, ORDER_ID, LINE_NO, PART_NO, QTY, PRICE)
                VALUES (SEQ_ORDER.NEXTVAL, s.ORDER_ID, s.LINE_NO, s.PART_NO, s.QTY, s.PRICE)""",
          """
          MERGE INTO ORDER_ITEM t
          USING (
                  SELECT
                         CAST(:orderId AS INTEGER) AS ORDER_ID
                       , CAST(:lineNo AS INTEGER) AS LINE_NO
                       , CAST(:partNo AS VARCHAR) AS PART_NO
                       , CAST(:qty AS INTEGER) AS QTY
                       , CAST(:price AS NUMERIC) AS PRICE
                ) s
             ON (t.ORDER_ID = s.ORDER_ID AND t.LINE_NO = s.LINE_NO)
           WHEN MATCHED THEN
                UPDATE
                   SET PART_NO = s.PART_NO
                     , QTY = s.QTY
                     , PRICE = s.PRICE
           WHEN NOT MATCHED THEN
                INSERT (ITEM_ID, ORDER_ID, LINE_NO, PART_NO, QTY, PRICE)
                VALUES (NEXTVAL('seq_order'), s.ORDER_ID, s.LINE_NO, s.PART_NO, s.QTY, s.PRICE)"""),
      new Example("화면 기본 쿼리 (목록 · 상세 · 저장)", "첨부 · 댓글 수 붙인 목록", "목록 줄마다 딸린 건수를 붙입니다. 묶어서 한 번 잇는 편이 스칼라 서브쿼리보다 큰 목록에서 빠릅니다.",
          """
          SELECT
                 d.DOC_ID
               , d.DOC_NO
               , NVL(x.DIST_CNT, 0) AS DIST_CNT
            FROM DOC d
               , (SELECT t.DOC_ID, COUNT(*) AS DIST_CNT FROM DOC_DIST t GROUP BY t.DOC_ID) x
           WHERE d.DOC_ID = x.DOC_ID(+)""",
          """
          SELECT
                 d.DOC_ID
               , d.DOC_NO
               , COALESCE(x.DIST_CNT, 0) AS DIST_CNT
            FROM DOC d
            LEFT OUTER JOIN (SELECT t.DOC_ID, COUNT(*) AS DIST_CNT FROM DOC_DIST t GROUP BY t.DOC_ID) x
              ON d.DOC_ID = x.DOC_ID"""),
      new Example("화면 기본 쿼리 (목록 · 상세 · 저장)", "내 할 일 수 (메인 화면 알림 숫자)", "로그인한 사람의 결재 대기 · 열린 이슈 · 이번 주 일정 수를 한 줄로.",
          """
          SELECT
                 (SELECT COUNT(*) FROM APPROVAL a WHERE a.APPROVER_ID = :userId AND a.STATUS = 'WAIT') AS APPROVAL_CNT
               , (SELECT COUNT(*) FROM ISSUE i WHERE i.OWNER_ID = :userId AND i.STATUS <> 'CLOSE') AS ISSUE_CNT
               , (SELECT COUNT(*) FROM SCHEDULE s WHERE s.OWNER_ID = :userId AND s.ACT_END IS NULL AND s.PLAN_END < TRUNC(SYSDATE, 'IW') + 7) AS SCH_CNT
            FROM DUAL""",
          """
          SELECT
                 (SELECT COUNT(*) FROM APPROVAL a WHERE a.APPROVER_ID = CAST(:userId AS INTEGER) AND a.STATUS = 'WAIT') AS APPROVAL_CNT
               , (SELECT COUNT(*) FROM ISSUE i WHERE i.OWNER_ID = CAST(:userId AS INTEGER) AND i.STATUS <> 'CLOSE') AS ISSUE_CNT
               , (SELECT COUNT(*) FROM SCHEDULE s WHERE s.OWNER_ID = CAST(:userId AS INTEGER) AND s.ACT_END IS NULL AND s.PLAN_END < CAST(DATE_TRUNC('WEEK', CURRENT_DATE) AS DATE) + 7) AS SCH_CNT"""),
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
      new Example("조인", "네 표 이상 잇기 (주문 · 고객 · 품목 · 부품)", "잇는 조건은 표 수 − 1 개가 있어야 합니다. 하나라도 빠지면 건수가 곱해집니다.",
          """
          SELECT
                 o.ORDER_ID
               , c.CUST_NAME
               , i.PART_NO
               , p.PART_NAME
               , i.QTY
            FROM ORDERS o
               , CUSTOMER c
               , ORDER_ITEM i
               , PART p
           WHERE o.CUST_ID = c.CUST_ID
             AND o.ORDER_ID = i.ORDER_ID
             AND i.PART_NO = p.PART_NO""",
          """
          SELECT
                 o.ORDER_ID
               , c.CUST_NAME
               , i.PART_NO
               , p.PART_NAME
               , i.QTY
            FROM ORDERS o
               , CUSTOMER c
               , ORDER_ITEM i
               , PART p
           WHERE o.CUST_ID = c.CUST_ID
             AND o.ORDER_ID = i.ORDER_ID
             AND i.PART_NO = p.PART_NO"""),
      new Example("조인", "아우터 조인 두 개 (짝 없어도 다 보기)", "주문은 다 보이고 고객 · 품목은 있으면 붙습니다. Oracle 옛 방식은 짝 없는 쪽마다 (+) 를 붙입니다.",
          """
          SELECT
                 o.ORDER_ID
               , c.CUST_NAME
               , i.PART_NO
            FROM ORDERS o
               , CUSTOMER c
               , ORDER_ITEM i
           WHERE o.CUST_ID = c.CUST_ID(+)
             AND o.ORDER_ID = i.ORDER_ID(+)""",
          """
          SELECT
                 o.ORDER_ID
               , c.CUST_NAME
               , i.PART_NO
            FROM ORDERS o
            LEFT OUTER JOIN CUSTOMER c
              ON o.CUST_ID = c.CUST_ID
            LEFT OUTER JOIN ORDER_ITEM i
              ON o.ORDER_ID = i.ORDER_ID"""),
      new Example("조인", "아우터 조인에 상수 조건 (짝 쪽 거르기)", "짝 표의 조건에도 (+) 를 붙여야 아우터가 유지됩니다. 빠뜨리면 짝 없는 줄이 사라집니다.",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
               , c.CODE_NAME AS UNIT_NAME
            FROM PART p
               , COMMON_CODE c
           WHERE p.UNIT_CD = c.CODE(+)
             AND c.GROUP_CD(+) = 'UNIT'
             AND c.USE_YN(+) = 'Y'""",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
               , c.CODE_NAME AS UNIT_NAME
            FROM PART p
            LEFT OUTER JOIN COMMON_CODE c
              ON p.UNIT_CD = c.CODE
             AND c.GROUP_CD = 'UNIT'
             AND c.USE_YN = 'Y'"""),
      new Example("조인", "같은 코드 표를 두 번 잇기 (상태 이름 · 등급 이름)", "한 표를 다른 별칭으로 두 번 씁니다. 각 별칭마다 코드 그룹 조건을 따로 겁니다.",
          """
          SELECT
                 i.ISSUE_ID
               , s.CODE_NAME AS STATUS_NAME
               , v.CODE_NAME AS SEVERITY_NAME
            FROM ISSUE i
               , COMMON_CODE s
               , COMMON_CODE v
           WHERE i.STATUS = s.CODE(+)
             AND s.GROUP_CD(+) = 'ISSUE_STATUS'
             AND i.SEVERITY = v.CODE(+)
             AND v.GROUP_CD(+) = 'SEVERITY'""",
          """
          SELECT
                 i.ISSUE_ID
               , s.CODE_NAME AS STATUS_NAME
               , v.CODE_NAME AS SEVERITY_NAME
            FROM ISSUE i
            LEFT OUTER JOIN COMMON_CODE s
              ON i.STATUS = s.CODE
             AND s.GROUP_CD = 'ISSUE_STATUS'
            LEFT OUTER JOIN COMMON_CODE v
              ON i.SEVERITY = v.CODE
             AND v.GROUP_CD = 'SEVERITY'"""),
      new Example("조인", "범위로 잇기 (그날 유효한 단가)", "= 이 아니라 BETWEEN 으로 잇습니다. 유효 기간이 겹치면 줄이 늘어나니 겹침 점검을 먼저 하세요.",
          """
          SELECT
                 i.ORDER_ID
               , i.PART_NO
               , o.ORDER_DT
               , h.PRICE
            FROM ORDERS o
               , ORDER_ITEM i
               , PART_PRICE_HIST h
           WHERE o.ORDER_ID = i.ORDER_ID
             AND i.PART_NO = h.PART_NO(+)
             AND o.ORDER_DT >= h.VALID_FROM(+)
             AND o.ORDER_DT <= h.VALID_TO(+)""",
          """
          SELECT
                 i.ORDER_ID
               , i.PART_NO
               , o.ORDER_DT
               , h.PRICE
            FROM ORDERS o
           INNER JOIN ORDER_ITEM i
              ON o.ORDER_ID = i.ORDER_ID
            LEFT OUTER JOIN PART_PRICE_HIST h
              ON i.PART_NO = h.PART_NO
             AND o.ORDER_DT BETWEEN h.VALID_FROM AND h.VALID_TO"""),
      new Example("조인", "조인이 건수를 늘렸는지 확인", "조인 전 건수와 조인 뒤 건수를 나란히 봅니다. 늘었으면 짝 표에 같은 키가 여러 줄입니다.",
          """
          SELECT
                 (SELECT COUNT(*) FROM ORDERS) AS BEFORE_CNT
               , (SELECT COUNT(*) FROM ORDERS o, CUSTOMER c WHERE o.CUST_ID = c.CUST_ID) AS AFTER_CNT
            FROM DUAL""",
          """
          SELECT
                 (SELECT COUNT(*) FROM ORDERS) AS BEFORE_CNT
               , (SELECT COUNT(*) FROM ORDERS o, CUSTOMER c WHERE o.CUST_ID = c.CUST_ID) AS AFTER_CNT"""),
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
      new Example("서브쿼리", "FROM 안의 서브쿼리 (인라인 뷰)", "묶은 결과를 표처럼 놓고 다시 거릅니다. 고객별 합계가 100만 넘는 고객.",
          """
          SELECT
                 s.CUST_ID
               , s.TOTAL_AMT
            FROM (
                  SELECT
                         o.CUST_ID
                       , SUM(o.AMOUNT) AS TOTAL_AMT
                    FROM ORDERS o
                   GROUP BY
                         o.CUST_ID
                 ) s
           WHERE s.TOTAL_AMT > 1000000""",
          """
          SELECT
                 s.CUST_ID
               , s.TOTAL_AMT
            FROM (
                  SELECT
                         o.CUST_ID
                       , SUM(o.AMOUNT) AS TOTAL_AMT
                    FROM ORDERS o
                   GROUP BY
                         o.CUST_ID
                 ) s
           WHERE s.TOTAL_AMT > 1000000"""),
      new Example("서브쿼리", "그룹 평균보다 큰 것 (상관 서브쿼리)", "자기 상태의 평균 금액보다 큰 주문만. 안쪽 쿼리가 바깥 줄의 상태를 씁니다.",
          """
          SELECT
                 o.ORDER_ID
               , o.STATUS
               , o.AMOUNT
            FROM ORDERS o
           WHERE o.AMOUNT > (
                  SELECT AVG(x.AMOUNT)
                    FROM ORDERS x
                   WHERE x.STATUS = o.STATUS
                 )""",
          """
          SELECT
                 o.ORDER_ID
               , o.STATUS
               , o.AMOUNT
            FROM ORDERS o
           WHERE o.AMOUNT > (
                  SELECT AVG(x.AMOUNT)
                    FROM ORDERS x
                   WHERE x.STATUS = o.STATUS
                 )"""),
      new Example("서브쿼리", "가장 큰 값을 가진 줄 (MAX 서브쿼리)", "금액이 가장 큰 주문. 같은 최댓값이 여럿이면 다 나옵니다.",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.AMOUNT = (SELECT MAX(x.AMOUNT) FROM ORDERS x)""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.AMOUNT = (SELECT MAX(x.AMOUNT) FROM ORDERS x)"""),
      new Example("서브쿼리", "UPDATE 안의 서브쿼리 (다른 표 합계로 고치기)", "주문마다 품목 합계로 주문 금액을 맞춥니다. 품목이 없는 주문은 0 으로.",
          """
          UPDATE ORDERS o
             SET o.AMOUNT = NVL((SELECT SUM(i.QTY * i.PRICE) FROM ORDER_ITEM i WHERE i.ORDER_ID = o.ORDER_ID), 0)
           WHERE o.STATUS = '접수'""",
          """
          UPDATE ORDERS o
             SET AMOUNT = COALESCE((SELECT SUM(i.QTY * i.PRICE) FROM ORDER_ITEM i WHERE i.ORDER_ID = o.ORDER_ID), 0)
           WHERE o.STATUS = '접수'"""),
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
                 DATE_TRUNC('HOUR', o.REG_DT) + FLOOR(EXTRACT(MINUTE FROM o.REG_DT) / 10) * INTERVAL '10 MINUTES' AS SLOT
               , COUNT(*) AS CNT
            FROM ORDERS o
           WHERE o.REG_DT >= CURRENT_DATE
           GROUP BY
                 DATE_TRUNC('HOUR', o.REG_DT) + FLOOR(EXTRACT(MINUTE FROM o.REG_DT) / 10) * INTERVAL '10 MINUTES'
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
            FROM GENERATE_SERIES(CAST(:fromDt AS DATE), CAST(:toDt AS DATE), INTERVAL '1 DAY') AS d(DT)
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
                 CAST(DATE_TRUNC('WEEK', o.ORDER_DT) AS DATE) AS WEEK_START
               , COUNT(*) AS CNT
            FROM ORDERS o
           GROUP BY
                 CAST(DATE_TRUNC('WEEK', o.ORDER_DT) AS DATE)
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
            FROM GENERATE_SERIES(DATE_TRUNC('YEAR', CURRENT_DATE), DATE_TRUNC('YEAR', CURRENT_DATE) + INTERVAL '11 MONTHS', INTERVAL '1 MONTH') AS m(MON)
            LEFT JOIN ORDERS o
              ON o.ORDER_DT >= m.MON
             AND o.ORDER_DT < m.MON + INTERVAL '1 MONTH'
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
           WHERE o.ORDER_DT >= DATE_TRUNC('YEAR', CURRENT_DATE)
             AND o.ORDER_DT < DATE_TRUNC('YEAR', CURRENT_DATE) + INTERVAL '1 YEAR'
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
               , SUM(CASE WHEN o.ORDER_DT >= DATE_TRUNC('YEAR', CURRENT_DATE) THEN o.AMOUNT ELSE 0 END) AS THIS_YEAR
               , SUM(CASE WHEN o.ORDER_DT < DATE_TRUNC('YEAR', CURRENT_DATE) THEN o.AMOUNT ELSE 0 END) AS LAST_YEAR
            FROM ORDERS o
           WHERE o.ORDER_DT >= DATE_TRUNC('YEAR', CURRENT_DATE) - INTERVAL '1 YEAR'
             AND o.ORDER_DT < DATE_TRUNC('YEAR', CURRENT_DATE) + INTERVAL '1 YEAR'
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
               , SUM(CASE WHEN o.ORDER_DT >= DATE_TRUNC('WEEK', CURRENT_DATE) THEN 1 ELSE 0 END) AS THIS_WEEK
               , SUM(CASE WHEN o.ORDER_DT >= DATE_TRUNC('MONTH', CURRENT_DATE) THEN 1 ELSE 0 END) AS THIS_MONTH
            FROM ORDERS o
           WHERE o.ORDER_DT >= LEAST(DATE_TRUNC('MONTH', CURRENT_DATE), DATE_TRUNC('WEEK', CURRENT_DATE), CURRENT_DATE - 1)"""),
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
            FROM GENERATE_SERIES(CAST(:fromDt AS DATE), CAST(:toDt AS DATE), INTERVAL '1 DAY') AS d(DT)
           WHERE EXTRACT(ISODOW FROM d.DT) < 6"""),
      new Example("기간별 통계", "올해 누계 vs 작년 같은 날까지 누계", "1월 1일부터 오늘까지와 작년 1월 1일부터 작년 오늘까지의 합.",
          """
          SELECT
                 SUM(CASE WHEN o.ORDER_DT >= TRUNC(SYSDATE, 'YYYY') AND o.ORDER_DT < TRUNC(SYSDATE) + 1 THEN o.AMOUNT END) AS THIS_YTD
               , SUM(CASE WHEN o.ORDER_DT >= ADD_MONTHS(TRUNC(SYSDATE, 'YYYY'), -12) AND o.ORDER_DT < ADD_MONTHS(TRUNC(SYSDATE), -12) + 1 THEN o.AMOUNT END) AS LAST_YTD
            FROM ORDERS o""",
          """
          SELECT
                 SUM(CASE WHEN o.ORDER_DT >= DATE_TRUNC('YEAR', CURRENT_DATE) AND o.ORDER_DT < CURRENT_DATE + 1 THEN o.AMOUNT END) AS THIS_YTD
               , SUM(CASE WHEN o.ORDER_DT >= DATE_TRUNC('YEAR', CURRENT_DATE) - INTERVAL '1 YEAR' AND o.ORDER_DT < CURRENT_DATE - INTERVAL '1 YEAR' + INTERVAL '1 DAY' THEN o.AMOUNT END) AS LAST_YTD
            FROM ORDERS o"""),
      new Example("기간별 통계", "주차별 올해 · 작년 비교 (ISO 주)", "주 번호마다 올해와 작년 건수를 나란히.",
          """
          SELECT
                 TO_CHAR(o.ORDER_DT, 'IW') AS WK
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'IYYY') = TO_CHAR(SYSDATE, 'IYYY') THEN 1 ELSE 0 END) AS THIS_YEAR
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'IYYY') = TO_CHAR(ADD_MONTHS(SYSDATE, -12), 'IYYY') THEN 1 ELSE 0 END) AS LAST_YEAR
            FROM ORDERS o
           GROUP BY
                 TO_CHAR(o.ORDER_DT, 'IW')
           ORDER BY
                 WK""",
          """
          SELECT
                 TO_CHAR(o.ORDER_DT, 'IW') AS WK
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'IYYY') = TO_CHAR(CURRENT_DATE, 'IYYY') THEN 1 ELSE 0 END) AS THIS_YEAR
               , SUM(CASE WHEN TO_CHAR(o.ORDER_DT, 'IYYY') = TO_CHAR(CURRENT_DATE - INTERVAL '1 YEAR', 'IYYY') THEN 1 ELSE 0 END) AS LAST_YEAR
            FROM ORDERS o
           GROUP BY
                 TO_CHAR(o.ORDER_DT, 'IW')
           ORDER BY
                 WK"""),
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
      new Example("분석 함수", "그룹 합계를 줄마다 붙이기 (SUM OVER PARTITION)", "GROUP BY 없이 줄은 그대로 두고, 고객별 합계와 그 안에서 차지하는 비율을 붙입니다.",
          """
          SELECT
                 o.ORDER_ID
               , o.CUST_ID
               , o.AMOUNT
               , SUM(o.AMOUNT) OVER (PARTITION BY o.CUST_ID) AS CUST_TOTAL
               , ROUND(o.AMOUNT * 100 / NULLIF(SUM(o.AMOUNT) OVER (PARTITION BY o.CUST_ID), 0), 1) AS PCT
            FROM ORDERS o""",
          """
          SELECT
                 o.ORDER_ID
               , o.CUST_ID
               , o.AMOUNT
               , SUM(o.AMOUNT) OVER (PARTITION BY o.CUST_ID) AS CUST_TOTAL
               , ROUND(o.AMOUNT * 100 / NULLIF(SUM(o.AMOUNT) OVER (PARTITION BY o.CUST_ID), 0), 1) AS PCT
            FROM ORDERS o"""),
      new Example("분석 함수", "그룹 안에서 몇 번째 · 전체 몇 건", "고객마다 주문 순번과 그 고객의 전체 주문 수를 함께 봅니다(3건 중 2번째).",
          """
          SELECT
                 o.CUST_ID
               , o.ORDER_ID
               , ROW_NUMBER() OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT) AS SEQ
               , COUNT(*) OVER (PARTITION BY o.CUST_ID) AS CNT
            FROM ORDERS o""",
          """
          SELECT
                 o.CUST_ID
               , o.ORDER_ID
               , ROW_NUMBER() OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT) AS SEQ
               , COUNT(*) OVER (PARTITION BY o.CUST_ID) AS CNT
            FROM ORDERS o"""),
      new Example("분석 함수", "앞 줄과 차이 (간격 일수)", "같은 고객의 앞 주문과 며칠 떨어졌는지. 첫 주문은 비어 있습니다.",
          """
          SELECT
                 o.CUST_ID
               , o.ORDER_DT
               , o.ORDER_DT - LAG(o.ORDER_DT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT) AS GAP_DAYS
            FROM ORDERS o""",
          """
          SELECT
                 o.CUST_ID
               , o.ORDER_DT
               , o.ORDER_DT - LAG(o.ORDER_DT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT) AS GAP_DAYS
            FROM ORDERS o"""),
      new Example("분석 함수", "그룹 안 누적 합 (고객별 누적)", "고객마다 날짜 순으로 쌓이는 금액. PARTITION BY 가 없으면 전체 누적입니다.",
          """
          SELECT
                 o.CUST_ID
               , o.ORDER_DT
               , o.AMOUNT
               , SUM(o.AMOUNT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT, o.ORDER_ID) AS CUM_AMT
            FROM ORDERS o""",
          """
          SELECT
                 o.CUST_ID
               , o.ORDER_DT
               , o.AMOUNT
               , SUM(o.AMOUNT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT, o.ORDER_ID) AS CUM_AMT
            FROM ORDERS o"""),
      new Example("분석 함수", "백분위 순위 (상위 몇 %)", "PERCENT_RANK 는 0~1, CUME_DIST 는 나보다 작거나 같은 비율입니다.",
          """
          SELECT
                 e.EMP_ID
               , e.SAL
               , ROUND(PERCENT_RANK() OVER (ORDER BY e.SAL DESC) * 100, 1) AS TOP_PCT
               , ROUND(CUME_DIST() OVER (ORDER BY e.SAL) * 100, 1) AS CUME_PCT
            FROM EMP e""",
          """
          SELECT
                 e.EMP_ID
               , e.SAL
               , ROUND(CAST(PERCENT_RANK() OVER (ORDER BY e.SAL DESC) AS NUMERIC) * 100, 1) AS TOP_PCT
               , ROUND(CAST(CUME_DIST() OVER (ORDER BY e.SAL) AS NUMERIC) * 100, 1) AS CUME_PCT
            FROM EMP e"""),
      new Example("분석 함수", "그룹 안 최댓값 · 최솟값을 줄마다", "부서마다 가장 높은 · 낮은 급여를 줄마다 붙이고 차이를 봅니다.",
          """
          SELECT
                 e.DEPT_ID
               , e.EMP_ID
               , e.SAL
               , MAX(e.SAL) OVER (PARTITION BY e.DEPT_ID) AS MAX_SAL
               , MIN(e.SAL) OVER (PARTITION BY e.DEPT_ID) AS MIN_SAL
               , MAX(e.SAL) OVER (PARTITION BY e.DEPT_ID) - e.SAL AS GAP
            FROM EMP e""",
          """
          SELECT
                 e.DEPT_ID
               , e.EMP_ID
               , e.SAL
               , MAX(e.SAL) OVER (PARTITION BY e.DEPT_ID) AS MAX_SAL
               , MIN(e.SAL) OVER (PARTITION BY e.DEPT_ID) AS MIN_SAL
               , MAX(e.SAL) OVER (PARTITION BY e.DEPT_ID) - e.SAL AS GAP
            FROM EMP e"""),
      new Example("분석 함수", "값이 바뀌는 지점 찾기 (상태 바뀜)", "앞 줄 상태와 다르면 바뀐 지점입니다. 설비 상태 이력에서 바뀐 줄만.",
          """
          SELECT
                 x.EQUIP_ID
               , x.START_TS
               , x.PREV_STATUS
               , x.STATUS
            FROM (
                  SELECT
                         l.EQUIP_ID
                       , l.START_TS
                       , l.STATUS
                       , LAG(l.STATUS) OVER (PARTITION BY l.EQUIP_ID ORDER BY l.START_TS) AS PREV_STATUS
                    FROM EQUIP_LOG l
                 ) x
           WHERE x.PREV_STATUS IS NULL
              OR x.PREV_STATUS <> x.STATUS""",
          """
          SELECT
                 x.EQUIP_ID
               , x.START_TS
               , x.PREV_STATUS
               , x.STATUS
            FROM (
                  SELECT
                         l.EQUIP_ID
                       , l.START_TS
                       , l.STATUS
                       , LAG(l.STATUS) OVER (PARTITION BY l.EQUIP_ID ORDER BY l.START_TS) AS PREV_STATUS
                    FROM EQUIP_LOG l
                 ) x
           WHERE x.PREV_STATUS IS NULL
              OR x.PREV_STATUS <> x.STATUS"""),
      new Example("분석 함수", "앞의 빈 값을 위 값으로 채우기", "엑셀에서 병합 셀을 풀었을 때처럼 비어 있는 칸을 바로 위 값으로 채웁니다.",
          """
          SELECT
                 u.ROW_NO
               , LAST_VALUE(u.PART_NO IGNORE NULLS) OVER (ORDER BY u.ROW_NO) AS PART_NO
               , u.QTY
            FROM UPLOAD_TMP u""",
          """
          SELECT
                 x.ROW_NO
               , MAX(x.PART_NO) OVER (PARTITION BY x.GRP) AS PART_NO
               , x.QTY
            FROM (
                  SELECT
                         u.ROW_NO
                       , u.PART_NO
                       , u.QTY
                       , COUNT(u.PART_NO) OVER (ORDER BY u.ROW_NO) AS GRP
                    FROM UPLOAD_TMP u
                 ) x"""),
      new Example("분석 함수", "기간 창 합계 (최근 7일 이동 합)", "날짜로 범위를 잡는 창입니다. 줄 수가 아니라 날짜 차이로 앞 6일 + 오늘.",
          """
          SELECT
                 d.DT
               , d.CNT
               , SUM(d.CNT) OVER (ORDER BY d.DT RANGE BETWEEN 6 PRECEDING AND CURRENT ROW) AS CNT_7D
            FROM (
                  SELECT
                         TRUNC(o.ORDER_DT) AS DT
                       , COUNT(*) AS CNT
                    FROM ORDERS o
                   GROUP BY
                         TRUNC(o.ORDER_DT)
                 ) d""",
          """
          SELECT
                 d.DT
               , d.CNT
               , SUM(d.CNT) OVER (ORDER BY d.DT RANGE BETWEEN INTERVAL '6 DAY' PRECEDING AND CURRENT ROW) AS CNT_7D
            FROM (
                  SELECT
                         CAST(o.ORDER_DT AS DATE) AS DT
                       , COUNT(*) AS CNT
                    FROM ORDERS o
                   GROUP BY
                         CAST(o.ORDER_DT AS DATE)
                 ) d"""),
      new Example("WITH · 재귀", "단계로 나눠 쓰기 (WITH)", "고객별 합계를 먼저 만들고, 그 결과에서 상위 고객만 고릅니다. 단계마다 따로 실행해 볼 수 있습니다(도구 → WITH 조각 실행).",
          """
          WITH CUST_SUM AS (
          SELECT
                 o.CUST_ID
               , SUM(o.AMOUNT) AS SUM_AMT
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID
          )
          SELECT
                 s.*
            FROM CUST_SUM s
           WHERE s.SUM_AMT >= 100000""",
          """
          WITH CUST_SUM AS (
          SELECT
                 o.CUST_ID
               , SUM(o.AMOUNT) AS SUM_AMT
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID
          )
          SELECT
                 s.*
            FROM CUST_SUM s
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
            FROM GENERATE_SERIES(DATE_TRUNC('MONTH', CURRENT_DATE), DATE_TRUNC('MONTH', CURRENT_DATE) + INTERVAL '1 month - 1 day', INTERVAL '1 DAY') AS g(DT)"""),
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
      new Example("WITH · 재귀", "WITH 단계 여러 개 (거르기 → 묶기 → 순위)", "단계마다 이름을 붙여 위에서 아래로 읽히게 씁니다. 앞 단계를 뒤에서 씁니다.",
          """
          WITH DONE AS (
              SELECT
                     o.*
                FROM ORDERS o
               WHERE o.STATUS = '완료'
          )
          , BY_CUST AS (
              SELECT
                     d.CUST_ID
                   , SUM(d.AMOUNT) AS AMT
                FROM DONE d
               GROUP BY
                     d.CUST_ID
          )
          SELECT
                 b.CUST_ID
               , b.AMT
               , RANK() OVER (ORDER BY b.AMT DESC) AS RNK
            FROM BY_CUST b""",
          """
          WITH DONE AS (
              SELECT
                     o.*
                FROM ORDERS o
               WHERE o.STATUS = '완료'
          )
          , BY_CUST AS (
              SELECT
                     d.CUST_ID
                   , SUM(d.AMOUNT) AS AMT
                FROM DONE d
               GROUP BY
                     d.CUST_ID
          )
          SELECT
                 b.CUST_ID
               , b.AMT
               , RANK() OVER (ORDER BY b.AMT DESC) AS RNK
            FROM BY_CUST b"""),
      new Example("WITH · 재귀", "날짜 구간을 하루씩 펼치기 (휴가 · 일정 기간)", "시작일 ~ 끝일 한 줄을 날마다 한 줄로 펼칩니다. 날짜별 투입 인원을 셀 때 씁니다.",
          """
          WITH DAYS (SCH_ID, DT, END_DT) AS (
          SELECT
                 s.SCH_ID
               , s.PLAN_START
               , s.PLAN_END
            FROM SCHEDULE s
           WHERE s.PLAN_START IS NOT NULL
             AND s.PLAN_END >= s.PLAN_START
           UNION ALL
          SELECT
                 d.SCH_ID
               , d.DT + 1
               , d.END_DT
            FROM DAYS d
           WHERE d.DT < d.END_DT
          )
          SELECT
                 d.DT
               , COUNT(*) AS SCH_CNT
            FROM DAYS d
           GROUP BY
                 d.DT
           ORDER BY
                 d.DT""",
          """
          WITH RECURSIVE DAYS AS (
          SELECT
                 s.SCH_ID
               , s.PLAN_START AS DT
               , s.PLAN_END AS END_DT
            FROM SCHEDULE s
           WHERE s.PLAN_START IS NOT NULL
             AND s.PLAN_END >= s.PLAN_START
           UNION ALL
          SELECT
                 d.SCH_ID
               , d.DT + 1
               , d.END_DT
            FROM DAYS d
           WHERE d.DT < d.END_DT
          )
          SELECT
                 d.DT
               , COUNT(*) AS SCH_CNT
            FROM DAYS d
           GROUP BY
                 d.DT
           ORDER BY
                 d.DT"""),
      new Example("WITH · 재귀", "수량만큼 줄 늘리기 (라벨 출력용)", "수량이 3이면 3줄로 늘립니다. 바코드 · 라벨을 낱개로 찍을 때 씁니다.",
          """
          SELECT
                 i.ORDER_ID
               , i.PART_NO
               , n.N AS LABEL_NO
            FROM ORDER_ITEM i
               , (SELECT LEVEL AS N FROM DUAL CONNECT BY LEVEL <= 1000) n
           WHERE n.N <= i.QTY
           ORDER BY
                 i.ORDER_ID
               , i.PART_NO
               , n.N""",
          """
          SELECT
                 i.ORDER_ID
               , i.PART_NO
               , n.N AS LABEL_NO
            FROM ORDER_ITEM i
               , GENERATE_SERIES(1, 1000) AS n(N)
           WHERE n.N <= i.QTY
           ORDER BY
                 i.ORDER_ID
               , i.PART_NO
               , n.N"""),
      new Example("WITH · 재귀", "월 목록 만들기 (최근 12개월)", "데이터가 없어도 달 목록을 만들어 아우터 조인의 기준으로 씁니다.",
          """
          SELECT
                 TO_CHAR(ADD_MONTHS(TRUNC(SYSDATE, 'MM'), 1 - LEVEL), 'YYYY-MM') AS YM
            FROM DUAL
          CONNECT BY LEVEL <= 12
           ORDER BY
                 YM""",
          """
          SELECT
                 TO_CHAR(g, 'YYYY-MM') AS YM
            FROM GENERATE_SERIES(DATE_TRUNC('MONTH', CURRENT_DATE) - INTERVAL '11 MONTH', DATE_TRUNC('MONTH', CURRENT_DATE), INTERVAL '1 MONTH') g
           ORDER BY
                 YM"""),
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
          WITH RECURSIVE TREE AS (
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
            JOIN TREE t
              ON m.PARENT_ID = t.MENU_ID
           WHERE t.LVL < 100
          )
          SELECT
                 t.LVL
               , REPEAT(' ', (t.LVL - 1) * 2) || t.MENU_NAME AS MENU_NAME
               , t.PATH
               , t.MENU_ID
            FROM TREE t
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
          WITH RECURSIVE SUB AS (
          SELECT
                 m.*
            FROM MENU m
           WHERE m.MENU_ID = CAST(:menuId AS INTEGER)
           UNION
          SELECT
                 m.*
            FROM MENU m
            JOIN SUB s
              ON m.PARENT_ID = s.MENU_ID
          )
          SELECT
                 s.*
            FROM SUB s"""),
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
          WITH RECURSIVE PAIR AS (
          SELECT
                 m.MENU_ID AS ROOT_ID
               , m.MENU_ID
            FROM MENU m
           UNION ALL
          SELECT
                 p.ROOT_ID
               , c.MENU_ID
            FROM MENU c
            JOIN PAIR p
              ON c.PARENT_ID = p.MENU_ID
          )
          SELECT
                 m.MENU_ID
               , m.MENU_NAME
               , COUNT(*) - 1 AS DESC_CNT
            FROM PAIR p
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
           WHERE o.ORDER_DT >= DATE_TRUNC('YEAR', CURRENT_DATE)
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
      new Example("피벗", "상태별 칸으로 펼치기 (CASE 피벗)", "고객마다 상태별 건수를 칸으로. 어느 DB 에서나 되는 방식입니다.",
          """
          SELECT
                 o.CUST_ID
               , SUM(CASE WHEN o.STATUS = '접수' THEN 1 ELSE 0 END) AS RCV
               , SUM(CASE WHEN o.STATUS = '진행' THEN 1 ELSE 0 END) AS ING
               , SUM(CASE WHEN o.STATUS = '완료' THEN 1 ELSE 0 END) AS DONE
               , COUNT(*) AS TOTAL
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID""",
          """
          SELECT
                 o.CUST_ID
               , SUM(CASE WHEN o.STATUS = '접수' THEN 1 ELSE 0 END) AS RCV
               , SUM(CASE WHEN o.STATUS = '진행' THEN 1 ELSE 0 END) AS ING
               , SUM(CASE WHEN o.STATUS = '완료' THEN 1 ELSE 0 END) AS DONE
               , COUNT(*) AS TOTAL
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID"""),
      new Example("피벗", "요일을 가로 칸으로 (주간 표)", "부품마다 월~일 실적을 칸으로 놓습니다. 1 = 일요일 기준 꼴 차이에 주의합니다.",
          """
          SELECT
                 r.WO_NO
               , SUM(CASE WHEN TO_CHAR(r.WORK_DT, 'D') = '2' THEN r.GOOD_QTY ELSE 0 END) AS MON
               , SUM(CASE WHEN TO_CHAR(r.WORK_DT, 'D') = '3' THEN r.GOOD_QTY ELSE 0 END) AS TUE
               , SUM(CASE WHEN TO_CHAR(r.WORK_DT, 'D') = '4' THEN r.GOOD_QTY ELSE 0 END) AS WED
               , SUM(CASE WHEN TO_CHAR(r.WORK_DT, 'D') = '5' THEN r.GOOD_QTY ELSE 0 END) AS THU
               , SUM(CASE WHEN TO_CHAR(r.WORK_DT, 'D') = '6' THEN r.GOOD_QTY ELSE 0 END) AS FRI
               , SUM(CASE WHEN TO_CHAR(r.WORK_DT, 'D') IN ('1', '7') THEN r.GOOD_QTY ELSE 0 END) AS WEEKEND
            FROM WORK_RESULT r
           GROUP BY
                 r.WO_NO""",
          """
          SELECT
                 r.WO_NO
               , SUM(CASE WHEN EXTRACT(ISODOW FROM r.WORK_DT) = 1 THEN r.GOOD_QTY ELSE 0 END) AS MON
               , SUM(CASE WHEN EXTRACT(ISODOW FROM r.WORK_DT) = 2 THEN r.GOOD_QTY ELSE 0 END) AS TUE
               , SUM(CASE WHEN EXTRACT(ISODOW FROM r.WORK_DT) = 3 THEN r.GOOD_QTY ELSE 0 END) AS WED
               , SUM(CASE WHEN EXTRACT(ISODOW FROM r.WORK_DT) = 4 THEN r.GOOD_QTY ELSE 0 END) AS THU
               , SUM(CASE WHEN EXTRACT(ISODOW FROM r.WORK_DT) = 5 THEN r.GOOD_QTY ELSE 0 END) AS FRI
               , SUM(CASE WHEN EXTRACT(ISODOW FROM r.WORK_DT) IN (6, 7) THEN r.GOOD_QTY ELSE 0 END) AS WEEKEND
            FROM WORK_RESULT r
           GROUP BY
                 r.WO_NO"""),
      new Example("피벗", "칸 여러 개를 줄로 (UNION ALL 언피벗)", "어느 DB 에서나 되는 언피벗. 양품 · 불량 칸을 구분 · 수량 두 칸의 줄로 바꿉니다.",
          """
          SELECT
                 r.WO_NO
               , '양품' AS KIND
               , r.GOOD_QTY AS QTY
            FROM WORK_RESULT r
           UNION ALL
          SELECT
                 r.WO_NO
               , '불량'
               , r.NG_QTY
            FROM WORK_RESULT r""",
          """
          SELECT
                 r.WO_NO
               , '양품' AS KIND
               , r.GOOD_QTY AS QTY
            FROM WORK_RESULT r
           UNION ALL
          SELECT
                 r.WO_NO
               , '불량'
               , r.NG_QTY
            FROM WORK_RESULT r"""),
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
      new Example("집합", "UNION 과 UNION ALL 차이", "UNION 은 겹친 줄을 지우느라 정렬하고 느립니다. 겹칠 일이 없으면 UNION ALL 을 씁니다.",
          """
          SELECT
                 o.CUST_ID
            FROM ORDERS o
           UNION
          SELECT
                 c.CUST_ID
            FROM CUSTOMER c""",
          """
          SELECT
                 o.CUST_ID
            FROM ORDERS o
           UNION
          SELECT
                 c.CUST_ID
            FROM CUSTOMER c"""),
      new Example("집합", "두 결과 합쳐 다시 묶기", "올해 표와 작년 백업 표를 붙여 고객별 합계를 냅니다.",
          """
          SELECT
                 u.CUST_ID
               , SUM(u.AMOUNT) AS AMT
            FROM (
                  SELECT o.CUST_ID, o.AMOUNT FROM ORDERS o
                  UNION ALL
                  SELECT b.CUST_ID, b.AMOUNT FROM ORDERS_BAK b
                 ) u
           GROUP BY
                 u.CUST_ID""",
          """
          SELECT
                 u.CUST_ID
               , SUM(u.AMOUNT) AS AMT
            FROM (
                  SELECT o.CUST_ID, o.AMOUNT FROM ORDERS o
                  UNION ALL
                  SELECT b.CUST_ID, b.AMOUNT FROM ORDERS_BAK b
                 ) u
           GROUP BY
                 u.CUST_ID"""),
      new Example("집합", "어느 쪽에서 왔는지 표시하며 붙이기", "붙일 때 출처 칸을 넣어 두면 나중에 거르거나 셀 수 있습니다.",
          """
          SELECT
                 'ISSUE' AS SRC
               , i.ISSUE_ID AS ID
               , i.OWNER_ID
            FROM ISSUE i
           WHERE i.STATUS <> 'CLOSE'
           UNION ALL
          SELECT
                 'CAPA'
               , NULL
               , c.OWNER_ID
            FROM CAPA c
           WHERE c.STATUS <> 'CLOSE'""",
          """
          SELECT
                 'ISSUE' AS SRC
               , i.ISSUE_ID AS ID
               , i.OWNER_ID
            FROM ISSUE i
           WHERE i.STATUS <> 'CLOSE'
           UNION ALL
          SELECT
                 'CAPA'
               , NULL
               , c.OWNER_ID
            FROM CAPA c
           WHERE c.STATUS <> 'CLOSE'"""),
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
      new Example("중복 · 정리", "여러 칼럼 조합 중복", "부품 · 협력사 조합이 두 번 이상 들어간 것.",
          """
          SELECT
                 v.PART_NO
               , v.VENDOR_ID
               , COUNT(*) AS CNT
            FROM PART_VENDOR v
           GROUP BY
                 v.PART_NO
               , v.VENDOR_ID
          HAVING COUNT(*) > 1""",
          """
          SELECT
                 v.PART_NO
               , v.VENDOR_ID
               , COUNT(*) AS CNT
            FROM PART_VENDOR v
           GROUP BY
                 v.PART_NO
               , v.VENDOR_ID
          HAVING COUNT(*) > 1"""),
      new Example("중복 · 정리", "대소문자 · 공백만 다른 중복", "UPPER · TRIM 으로 맞춘 값이 같으면 사실상 같은 이름입니다.",
          """
          SELECT
                 UPPER(TRIM(p.PART_NAME)) AS NAME_KEY
               , COUNT(*) AS CNT
               , MIN(p.PART_NO) AS FIRST_PART
            FROM PART p
           GROUP BY
                 UPPER(TRIM(p.PART_NAME))
          HAVING COUNT(*) > 1""",
          """
          SELECT
                 UPPER(TRIM(p.PART_NAME)) AS NAME_KEY
               , COUNT(*) AS CNT
               , MIN(p.PART_NO) AS FIRST_PART
            FROM PART p
           GROUP BY
                 UPPER(TRIM(p.PART_NAME))
          HAVING COUNT(*) > 1"""),
      new Example("중복 · 정리", "중복 중 최신만 남기고 지우기 (분석 함수)", "같은 부품 · 협력사 · 시작일 중 가장 최근에 넣은 줄 하나만 남깁니다. 먼저 SELECT 로 건수를 확인하세요.",
          """
          DELETE FROM QUOTE q
           WHERE q.ROWID IN (
                  SELECT x.RID
                    FROM (
                          SELECT
                                 t.ROWID AS RID
                               , ROW_NUMBER() OVER (PARTITION BY t.PART_NO, t.VENDOR_ID ORDER BY t.QUOTE_DT DESC) AS RN
                            FROM QUOTE t
                         ) x
                   WHERE x.RN > 1
                 )""",
          """
          DELETE FROM QUOTE q
           WHERE q.CTID IN (
                  SELECT x.RID
                    FROM (
                          SELECT
                                 t.CTID AS RID
                               , ROW_NUMBER() OVER (PARTITION BY t.PART_NO, t.VENDOR_ID ORDER BY t.QUOTE_DT DESC) AS RN
                            FROM QUOTE t
                         ) x
                   WHERE x.RN > 1
                 )"""),
      new Example("중복 · 정리", "앞뒤 공백 · 이상한 글자 정리 (UPDATE)", "앞뒤 공백과 줄바꿈 · 탭을 지웁니다. 바뀔 건수부터 보세요.",
          """
          UPDATE PART p
             SET p.PART_NAME = TRIM(REPLACE(REPLACE(REPLACE(p.PART_NAME, CHR(13), ''), CHR(10), ''), CHR(9), ' '))
           WHERE p.PART_NAME <> TRIM(REPLACE(REPLACE(REPLACE(p.PART_NAME, CHR(13), ''), CHR(10), ''), CHR(9), ' '))""",
          """
          UPDATE PART p
             SET PART_NAME = TRIM(REPLACE(REPLACE(REPLACE(p.PART_NAME, CHR(13), ''), CHR(10), ''), CHR(9), ' '))
           WHERE p.PART_NAME <> TRIM(REPLACE(REPLACE(REPLACE(p.PART_NAME, CHR(13), ''), CHR(10), ''), CHR(9), ' '))"""),
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
      new Example("페이징 · 개수", "이전 글 · 다음 글 (상세 화면 아래)", "지금 글 번호의 바로 앞 · 뒤 글 번호와 제목. 목록 정렬과 같은 기준(등록일 → 번호)으로 LAG · LEAD 를 씁니다.",
          """
          SELECT
                 x.PREV_ID
               , x.PREV_TITLE
               , x.NEXT_ID
               , x.NEXT_TITLE
            FROM (
                  SELECT
                         i.ISSUE_ID
                       , LAG(i.ISSUE_ID) OVER (ORDER BY i.REG_DT DESC, i.ISSUE_ID DESC) AS PREV_ID
                       , LAG(i.TITLE) OVER (ORDER BY i.REG_DT DESC, i.ISSUE_ID DESC) AS PREV_TITLE
                       , LEAD(i.ISSUE_ID) OVER (ORDER BY i.REG_DT DESC, i.ISSUE_ID DESC) AS NEXT_ID
                       , LEAD(i.TITLE) OVER (ORDER BY i.REG_DT DESC, i.ISSUE_ID DESC) AS NEXT_TITLE
                    FROM ISSUE i
                   WHERE i.PROJ_ID = :projId
                 ) x
           WHERE x.ISSUE_ID = :issueId""",
          """
          SELECT
                 x.PREV_ID
               , x.PREV_TITLE
               , x.NEXT_ID
               , x.NEXT_TITLE
            FROM (
                  SELECT
                         i.ISSUE_ID
                       , LAG(i.ISSUE_ID) OVER (ORDER BY i.REG_DT DESC, i.ISSUE_ID DESC) AS PREV_ID
                       , LAG(i.TITLE) OVER (ORDER BY i.REG_DT DESC, i.ISSUE_ID DESC) AS PREV_TITLE
                       , LEAD(i.ISSUE_ID) OVER (ORDER BY i.REG_DT DESC, i.ISSUE_ID DESC) AS NEXT_ID
                       , LEAD(i.TITLE) OVER (ORDER BY i.REG_DT DESC, i.ISSUE_ID DESC) AS NEXT_TITLE
                    FROM ISSUE i
                   WHERE i.PROJ_ID = :projId
                 ) x
           WHERE x.ISSUE_ID = :issueId"""),
      new Example("페이징 · 개수", "이전 글 · 다음 글 (MAX · MIN 으로, 큰 표에서 빠름)", "번호 순서일 때는 분석 함수 없이 바로 앞 · 뒤 번호를 찾는 편이 색인을 잘 탑니다.",
          """
          SELECT
                 (SELECT MAX(i.ISSUE_ID) FROM ISSUE i WHERE i.ISSUE_ID < :issueId) AS PREV_ID
               , (SELECT MIN(i.ISSUE_ID) FROM ISSUE i WHERE i.ISSUE_ID > :issueId) AS NEXT_ID
            FROM DUAL""",
          """
          SELECT
                 (SELECT MAX(i.ISSUE_ID) FROM ISSUE i WHERE i.ISSUE_ID < CAST(:issueId AS INTEGER)) AS PREV_ID
               , (SELECT MIN(i.ISSUE_ID) FROM ISSUE i WHERE i.ISSUE_ID > CAST(:issueId AS INTEGER)) AS NEXT_ID"""),
      new Example("페이징 · 개수", "페이지 번호 계산 (전체 건수 · 전체 페이지 · 이전 · 다음 페이지 있음)", "화면 아래 페이지 단추용. 한 쪽 건수와 지금 쪽 번호를 넣으면 전체 쪽 수와 이전 · 다음 단추를 켤지 알려 줍니다.",
          """
          SELECT
                 c.TOTAL_CNT
               , CEIL(c.TOTAL_CNT / :pageSize) AS TOTAL_PAGE
               , CASE WHEN :pageNo > 1 THEN 'Y' ELSE 'N' END AS HAS_PREV
               , CASE WHEN :pageNo < CEIL(c.TOTAL_CNT / :pageSize) THEN 'Y' ELSE 'N' END AS HAS_NEXT
            FROM (SELECT COUNT(*) AS TOTAL_CNT FROM ORDERS o WHERE o.STATUS = :status) c""",
          """
          SELECT
                 c.TOTAL_CNT
               , CEIL(c.TOTAL_CNT / CAST(:pageSize AS NUMERIC)) AS TOTAL_PAGE
               , CASE WHEN CAST(:pageNo AS INTEGER) > 1 THEN 'Y' ELSE 'N' END AS HAS_PREV
               , CASE WHEN CAST(:pageNo AS INTEGER) < CEIL(c.TOTAL_CNT / CAST(:pageSize AS NUMERIC)) THEN 'Y' ELSE 'N' END AS HAS_NEXT
            FROM (SELECT COUNT(*) AS TOTAL_CNT FROM ORDERS o WHERE o.STATUS = :status) c"""),
      new Example("페이징 · 개수", "페이지 단추 묶음 (1~10, 11~20 … 지금 묶음의 시작 · 끝)", "단추를 10개씩 보일 때 지금 쪽이 속한 묶음의 첫 쪽 · 끝 쪽과 이전 · 다음 묶음 쪽 번호.",
          """
          SELECT
                 FLOOR((:pageNo - 1) / 10) * 10 + 1 AS BLOCK_START
               , LEAST(FLOOR((:pageNo - 1) / 10) * 10 + 10, CEIL(c.TOTAL_CNT / :pageSize)) AS BLOCK_END
               , GREATEST(FLOOR((:pageNo - 1) / 10) * 10, 1) AS PREV_BLOCK_PAGE
               , LEAST(FLOOR((:pageNo - 1) / 10) * 10 + 11, CEIL(c.TOTAL_CNT / :pageSize)) AS NEXT_BLOCK_PAGE
            FROM (SELECT COUNT(*) AS TOTAL_CNT FROM ORDERS o) c""",
          """
          SELECT
                 FLOOR((CAST(:pageNo AS INTEGER) - 1) / 10) * 10 + 1 AS BLOCK_START
               , LEAST(FLOOR((CAST(:pageNo AS INTEGER) - 1) / 10) * 10 + 10, CEIL(c.TOTAL_CNT / CAST(:pageSize AS NUMERIC))) AS BLOCK_END
               , GREATEST(FLOOR((CAST(:pageNo AS INTEGER) - 1) / 10) * 10, 1) AS PREV_BLOCK_PAGE
               , LEAST(FLOOR((CAST(:pageNo AS INTEGER) - 1) / 10) * 10 + 11, CEIL(c.TOTAL_CNT / CAST(:pageSize AS NUMERIC))) AS NEXT_BLOCK_PAGE
            FROM (SELECT COUNT(*) AS TOTAL_CNT FROM ORDERS o) c"""),
      new Example("페이징 · 개수", "페이지 나누기 (ROWNUM 세 겹, Oracle 11g 이하)", "11g 이하에서 쓰는 꼴. 안쪽에서 정렬 → 가운데서 끝 번호까지 자르고 → 바깥에서 시작 번호부터.",
          """
          SELECT
                 z.*
            FROM (
                  SELECT
                         ROWNUM AS RN
                       , y.*
                    FROM (
                          SELECT
                                 o.ORDER_ID
                               , o.ORDER_DT
                               , o.AMOUNT
                            FROM ORDERS o
                           ORDER BY
                                 o.ORDER_DT DESC
                               , o.ORDER_ID DESC
                         ) y
                   WHERE ROWNUM <= :pageNo * :pageSize
                 ) z
           WHERE z.RN > (:pageNo - 1) * :pageSize""",
          """
          SELECT
                 o.ORDER_ID
               , o.ORDER_DT
               , o.AMOUNT
            FROM ORDERS o
           ORDER BY
                 o.ORDER_DT DESC
               , o.ORDER_ID DESC
           LIMIT CAST(:pageSize AS INTEGER) OFFSET (CAST(:pageNo AS INTEGER) - 1) * CAST(:pageSize AS INTEGER)"""),
      new Example("페이징 · 개수", "페이지 나누기 + 전체 건수 + 줄 번호 (화면 목록 한 방)", "목록 화면 한 번 조회로 줄 번호(역순 번호 포함) · 전체 건수 · 이번 쪽 줄을 다 받습니다.",
          """
          SELECT
                 z.*
            FROM (
                  SELECT
                         ROW_NUMBER() OVER (ORDER BY o.ORDER_DT DESC, o.ORDER_ID DESC) AS RN
                       , COUNT(*) OVER () AS TOTAL_CNT
                       , o.ORDER_ID
                       , o.ORDER_DT
                       , o.STATUS
                       , o.AMOUNT
                    FROM ORDERS o
                   WHERE (:status IS NULL OR o.STATUS = :status)
                 ) z
           WHERE z.RN BETWEEN (:pageNo - 1) * :pageSize + 1 AND :pageNo * :pageSize""",
          """
          SELECT
                 z.*
               , z.TOTAL_CNT - z.RN + 1 AS DESC_NO
            FROM (
                  SELECT
                         ROW_NUMBER() OVER (ORDER BY o.ORDER_DT DESC, o.ORDER_ID DESC) AS RN
                       , COUNT(*) OVER () AS TOTAL_CNT
                       , o.ORDER_ID
                       , o.ORDER_DT
                       , o.STATUS
                       , o.AMOUNT
                    FROM ORDERS o
                   WHERE (CAST(:status AS VARCHAR) IS NULL OR o.STATUS = :status)
                 ) z
           WHERE z.RN BETWEEN (CAST(:pageNo AS INTEGER) - 1) * CAST(:pageSize AS INTEGER) + 1 AND CAST(:pageNo AS INTEGER) * CAST(:pageSize AS INTEGER)"""),
      new Example("페이징 · 개수", "이전 쪽 빨리 (키 기준 거꾸로)", "「다음 쪽 빨리」 의 반대. 지금 쪽 첫 줄보다 앞선 줄을 거꾸로 읽어 10건 가져온 뒤 다시 뒤집습니다.",
          """
          SELECT
                 y.*
            FROM (
                  SELECT
                         o.ORDER_ID
                       , o.ORDER_DT
                    FROM ORDERS o
                   WHERE (o.ORDER_DT > :firstDt OR (o.ORDER_DT = :firstDt AND o.ORDER_ID > :firstId))
                   ORDER BY
                         o.ORDER_DT
                       , o.ORDER_ID
                   FETCH FIRST 10 ROWS ONLY
                 ) y
           ORDER BY
                 y.ORDER_DT DESC
               , y.ORDER_ID DESC""",
          """
          SELECT
                 y.*
            FROM (
                  SELECT
                         o.ORDER_ID
                       , o.ORDER_DT
                    FROM ORDERS o
                   WHERE (o.ORDER_DT, o.ORDER_ID) > (CAST(:firstDt AS DATE), CAST(:firstId AS INTEGER))
                   ORDER BY
                         o.ORDER_DT
                       , o.ORDER_ID
                   LIMIT 10
                 ) y
           ORDER BY
                 y.ORDER_DT DESC
               , y.ORDER_ID DESC"""),
      new Example("페이징 · 개수", "목록에서 이 줄이 몇 쪽에 있나", "상세에서 목록으로 돌아갈 때 그 줄이 있는 쪽으로 가려면 순번을 쪽 크기로 나눕니다.",
          """
          SELECT
                 CEIL(x.RN / :pageSize) AS PAGE_NO
            FROM (
                  SELECT
                         o.ORDER_ID
                       , ROW_NUMBER() OVER (ORDER BY o.ORDER_DT DESC, o.ORDER_ID DESC) AS RN
                    FROM ORDERS o
                 ) x
           WHERE x.ORDER_ID = :orderId""",
          """
          SELECT
                 CEIL(x.RN / CAST(:pageSize AS NUMERIC)) AS PAGE_NO
            FROM (
                  SELECT
                         o.ORDER_ID
                       , ROW_NUMBER() OVER (ORDER BY o.ORDER_DT DESC, o.ORDER_ID DESC) AS RN
                    FROM ORDERS o
                 ) x
           WHERE x.ORDER_ID = CAST(:orderId AS INTEGER)"""),
      new Example("페이징 · 개수", "그룹 안에서 페이지 (고객마다 최근 5건만)", "고객마다 최근 주문 5건씩. 그룹별 상위 N 을 페이징처럼 씁니다.",
          """
          SELECT
                 x.*
            FROM (
                  SELECT
                         o.CUST_ID
                       , o.ORDER_ID
                       , o.ORDER_DT
                       , ROW_NUMBER() OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT DESC) AS RN
                    FROM ORDERS o
                 ) x
           WHERE x.RN <= 5""",
          """
          SELECT
                 x.*
            FROM (
                  SELECT
                         o.CUST_ID
                       , o.ORDER_ID
                       , o.ORDER_DT
                       , ROW_NUMBER() OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT DESC) AS RN
                    FROM ORDERS o
                 ) x
           WHERE x.RN <= 5"""),
      new Example("페이징 · 개수", "정렬 칸을 화면에서 고르기 (동적 ORDER BY)", "정렬 칸 이름을 값으로 받아 CASE 로 고릅니다. 숫자 · 날짜 · 글자는 CASE 를 나눠야 형이 섞이지 않습니다.",
          """
          SELECT
                 o.ORDER_ID
               , o.ORDER_DT
               , o.AMOUNT
               , o.STATUS
            FROM ORDERS o
           ORDER BY
                 CASE WHEN :sortCol = 'AMOUNT' THEN o.AMOUNT END DESC
               , CASE WHEN :sortCol = 'ORDER_DT' THEN o.ORDER_DT END DESC
               , CASE WHEN :sortCol = 'STATUS' THEN o.STATUS END
               , o.ORDER_ID DESC""",
          """
          SELECT
                 o.ORDER_ID
               , o.ORDER_DT
               , o.AMOUNT
               , o.STATUS
            FROM ORDERS o
           ORDER BY
                 CASE WHEN CAST(:sortCol AS VARCHAR) = 'AMOUNT' THEN o.AMOUNT END DESC
               , CASE WHEN CAST(:sortCol AS VARCHAR) = 'ORDER_DT' THEN o.ORDER_DT END DESC
               , CASE WHEN CAST(:sortCol AS VARCHAR) = 'STATUS' THEN o.STATUS END
               , o.ORDER_ID DESC"""),
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
           WHERE o.ORDER_DT >= DATE_TRUNC('MONTH', CURRENT_DATE)
             AND o.ORDER_DT < DATE_TRUNC('MONTH', CURRENT_DATE) + INTERVAL '1 MONTH'
          -- 지난달: >= DATE_TRUNC('MONTH', CURRENT_DATE) - INTERVAL '1 MONTH' AND < DATE_TRUNC('MONTH', CURRENT_DATE)"""),
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
                 o.ORDER_DT + INTERVAL '3 DAY' AS AFTER_3_DAYS
               , o.ORDER_DT + INTERVAL '2 MONTH' AS AFTER_2_MONTHS
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
               , CAST(DATE_TRUNC('MONTH', o.ORDER_DT) + INTERVAL '1 month - 1 day' AS DATE) AS MONTH_END
               , CAST(DATE_TRUNC('MONTH', o.ORDER_DT) + INTERVAL '1 MONTH' AS DATE) AS NEXT_MONTH
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
               , CAST(DATE_TRUNC('WEEK', o.ORDER_DT) AS DATE) AS WEEK_START
               , COUNT(*) AS CNT
            FROM ORDERS o
           GROUP BY
                 TO_CHAR(o.ORDER_DT, 'YYYY') || '-Q' || TO_CHAR(o.ORDER_DT, 'Q')
               , CAST(DATE_TRUNC('WEEK', o.ORDER_DT) AS DATE)"""),
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
               , LOCALTIMESTAMP + INTERVAL '2 HOURS' AS AFTER_2H
               , LOCALTIMESTAMP - INTERVAL '30 MINUTES' AS BEFORE_30M"""),
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
                 CAST(DATE_TRUNC('WEEK', CURRENT_DATE) AS DATE) AS MON
               , CAST(DATE_TRUNC('WEEK', CURRENT_DATE) AS DATE) + 6 AS SUN"""),
      new Example("날짜", "올해 첫날 · 끝날 · 지난해 같은 날", "1월 1일, 12월 31일, 1년 전 오늘.",
          """
          SELECT
                 TRUNC(SYSDATE, 'YYYY') AS FIRST_DAY
               , ADD_MONTHS(TRUNC(SYSDATE, 'YYYY'), 12) - 1 AS LAST_DAY
               , ADD_MONTHS(TRUNC(SYSDATE), -12) AS YEAR_AGO
            FROM DUAL""",
          """
          SELECT
                 CAST(DATE_TRUNC('YEAR', CURRENT_DATE) AS DATE) AS FIRST_DAY
               , CAST(DATE_TRUNC('YEAR', CURRENT_DATE) + INTERVAL '1 year - 1 day' AS DATE) AS LAST_DAY
               , CAST(CURRENT_DATE - INTERVAL '1 YEAR' AS DATE) AS YEAR_AGO"""),
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
      new Example("날짜", "나이 · 근속 연수 (만 나이)", "입사일부터 오늘까지 꽉 찬 해 수. 생일이 안 지났으면 한 해 덜 칩니다.",
          """
          SELECT
                 p.PROJ_ID
               , TRUNC(MONTHS_BETWEEN(TRUNC(SYSDATE), p.START_DT) / 12) AS YEARS
            FROM PROJECT p""",
          """
          SELECT
                 p.PROJ_ID
               , EXTRACT(YEAR FROM AGE(CURRENT_DATE, p.START_DT)) AS YEARS
            FROM PROJECT p"""),
      new Example("날짜", "그달 몇째 주인지", "날짜가 그달의 몇 번째 주인지(1일이 든 주가 1주).",
          """
          SELECT
                 o.ORDER_DT
               , TO_CHAR(o.ORDER_DT, 'W') AS WEEK_OF_MONTH
            FROM ORDERS o""",
          """
          SELECT
                 o.ORDER_DT
               , TO_CHAR(o.ORDER_DT, 'W') AS WEEK_OF_MONTH
            FROM ORDERS o"""),
      new Example("날짜", "마감일까지 남은 날 · 지난 날", "남은 날은 양수, 지났으면 음수. 오늘 마감은 0.",
          """
          SELECT
                 c.CAPA_NO
               , c.DUE_DT
               , c.DUE_DT - TRUNC(SYSDATE) AS LEFT_DAYS
               , CASE WHEN c.DUE_DT < TRUNC(SYSDATE) THEN '지남' WHEN c.DUE_DT = TRUNC(SYSDATE) THEN '오늘' ELSE '남음' END AS STATE
            FROM CAPA c
           WHERE c.CLOSE_DT IS NULL""",
          """
          SELECT
                 c.CAPA_NO
               , c.DUE_DT
               , c.DUE_DT - CURRENT_DATE AS LEFT_DAYS
               , CASE WHEN c.DUE_DT < CURRENT_DATE THEN '지남' WHEN c.DUE_DT = CURRENT_DATE THEN '오늘' ELSE '남음' END AS STATE
            FROM CAPA c
           WHERE c.CLOSE_DT IS NULL"""),
      new Example("날짜", "영업일 N 일 뒤 (주말 건너뛰기)", "오늘부터 주말을 빼고 5 영업일 뒤 날짜. 휴일 표가 있으면 같이 뺍니다.",
          """
          SELECT
                 MAX(d.DT) AS DUE_DT
            FROM (
                  SELECT
                         x.DT
                    FROM (SELECT TRUNC(SYSDATE) + LEVEL AS DT FROM DUAL CONNECT BY LEVEL <= 30) x
                   WHERE TO_CHAR(x.DT, 'DY', 'NLS_DATE_LANGUAGE=AMERICAN') NOT IN ('SAT', 'SUN')
                   ORDER BY
                         x.DT
                   FETCH FIRST 5 ROWS ONLY
                 ) d""",
          """
          SELECT
                 MAX(d.DT) AS DUE_DT
            FROM (
                  SELECT
                         CAST(g AS DATE) AS DT
                    FROM GENERATE_SERIES(CURRENT_DATE + 1, CURRENT_DATE + 30, INTERVAL '1 DAY') g
                   WHERE EXTRACT(ISODOW FROM g) < 6
                   ORDER BY
                         1
                   LIMIT 5
                 ) d"""),
      new Example("날짜", "기간이 겹치는지 (두 구간 겹침 조건)", "A 시작 <= B 끝 이고 A 끝 >= B 시작 이면 겹칩니다. 조회 기간에 걸친 일정 찾기.",
          """
          SELECT
                 s.SCH_ID
               , s.PLAN_START
               , s.PLAN_END
            FROM SCHEDULE s
           WHERE s.PLAN_START <= TO_DATE(:toDt, 'YYYY-MM-DD')
             AND s.PLAN_END >= TO_DATE(:fromDt, 'YYYY-MM-DD')""",
          """
          SELECT
                 s.SCH_ID
               , s.PLAN_START
               , s.PLAN_END
            FROM SCHEDULE s
           WHERE s.PLAN_START <= CAST(:toDt AS DATE)
             AND s.PLAN_END >= CAST(:fromDt AS DATE)"""),
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
      new Example("조건", "날짜 · 상태 · 글자 조건을 모두 선택으로", "값을 넣은 조건만 걸립니다. 화면 검색 조건이 여러 개일 때의 기본 꼴입니다.",
          """
          SELECT
                 o.ORDER_ID
               , o.STATUS
               , o.ORDER_DT
            FROM ORDERS o
           WHERE (:status IS NULL OR o.STATUS = :status)
             AND (:fromDt IS NULL OR o.ORDER_DT >= TO_DATE(:fromDt, 'YYYY-MM-DD'))
             AND (:toDt IS NULL OR o.ORDER_DT < TO_DATE(:toDt, 'YYYY-MM-DD') + 1)
             AND (:keyword IS NULL OR o.MEMO LIKE '%' || :keyword || '%')""",
          """
          SELECT
                 o.ORDER_ID
               , o.STATUS
               , o.ORDER_DT
            FROM ORDERS o
           WHERE (CAST(:status AS VARCHAR) IS NULL OR o.STATUS = :status)
             AND (CAST(:fromDt AS DATE) IS NULL OR o.ORDER_DT >= CAST(:fromDt AS DATE))
             AND (CAST(:toDt AS DATE) IS NULL OR o.ORDER_DT < CAST(:toDt AS DATE) + 1)
             AND (CAST(:keyword AS VARCHAR) IS NULL OR o.MEMO LIKE '%' || :keyword || '%')"""),
      new Example("조건", "CASE 로 조건 바꾸기 (구분 값에 따라 다른 칼럼으로 거르기)", "구분이 REG 면 등록일, 아니면 주문일로 거릅니다. 색인은 잘 못 탑니다.",
          """
          SELECT
                 o.ORDER_ID
            FROM ORDERS o
           WHERE CASE WHEN :dtType = 'REG' THEN TRUNC(o.REG_DT) ELSE o.ORDER_DT END >= TO_DATE(:fromDt, 'YYYY-MM-DD')""",
          """
          SELECT
                 o.ORDER_ID
            FROM ORDERS o
           WHERE CASE WHEN CAST(:dtType AS VARCHAR) = 'REG' THEN CAST(o.REG_DT AS DATE) ELSE o.ORDER_DT END >= CAST(:fromDt AS DATE)"""),
      new Example("조건", "여러 낱말 중 하나라도 들어간 것", "이름에 '볼트' 나 '너트' 가 들어간 부품. 낱말이 많으면 정규식이 짧습니다.",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
            FROM PART p
           WHERE REGEXP_LIKE(p.PART_NAME, '볼트|너트')""",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
            FROM PART p
           WHERE p.PART_NAME ~ '볼트|너트'"""),
      new Example("조건", "그룹 안에 이 값이 하나라도 있는 그룹", "품목 중 하나라도 P001 이 있는 주문 전체 품목을 봅니다.",
          """
          SELECT
                 i.*
            FROM ORDER_ITEM i
           WHERE i.ORDER_ID IN (
                  SELECT x.ORDER_ID
                    FROM ORDER_ITEM x
                   WHERE x.PART_NO = :partNo
                 )""",
          """
          SELECT
                 i.*
            FROM ORDER_ITEM i
           WHERE i.ORDER_ID IN (
                  SELECT x.ORDER_ID
                    FROM ORDER_ITEM x
                   WHERE x.PART_NO = :partNo
                 )"""),
      new Example("조건", "그룹 안의 모든 줄이 조건을 만족 (전부 완료인 프로젝트)", "안 끝난 일정이 하나도 없는 프로젝트. 'NOT EXISTS 의 반대' 로 씁니다.",
          """
          SELECT
                 p.PROJ_ID
               , p.PROJ_NAME
            FROM PROJECT p
           WHERE EXISTS (SELECT 1 FROM SCHEDULE s WHERE s.PROJ_ID = p.PROJ_ID)
             AND NOT EXISTS (
                  SELECT 1
                    FROM SCHEDULE s
                   WHERE s.PROJ_ID = p.PROJ_ID
                     AND s.ACT_END IS NULL
                 )""",
          """
          SELECT
                 p.PROJ_ID
               , p.PROJ_NAME
            FROM PROJECT p
           WHERE EXISTS (SELECT 1 FROM SCHEDULE s WHERE s.PROJ_ID = p.PROJ_ID)
             AND NOT EXISTS (
                  SELECT 1
                    FROM SCHEDULE s
                   WHERE s.PROJ_ID = p.PROJ_ID
                     AND s.ACT_END IS NULL
                 )"""),
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
      new Example("문자", "한글 초성 · 첫 글자로 묶기", "이름 첫 글자로 묶어 가나다 색인처럼 셉니다.",
          """
          SELECT
                 SUBSTR(c.CUST_NAME, 1, 1) AS FIRST_CHAR
               , COUNT(*) AS CNT
            FROM CUSTOMER c
           GROUP BY
                 SUBSTR(c.CUST_NAME, 1, 1)
           ORDER BY
                 FIRST_CHAR""",
          """
          SELECT
                 SUBSTR(c.CUST_NAME, 1, 1) AS FIRST_CHAR
               , COUNT(*) AS CNT
            FROM CUSTOMER c
           GROUP BY
                 SUBSTR(c.CUST_NAME, 1, 1)
           ORDER BY
                 FIRST_CHAR"""),
      new Example("문자", "글자 안 특정 글자 수 세기", "쉼표가 몇 개 있는지 = 원래 길이 − 쉼표 지운 길이.",
          """
          SELECT
                 p.PART_NO
               , p.TAGS
               , LENGTH(p.TAGS) - LENGTH(REPLACE(p.TAGS, ',', '')) AS COMMA_CNT
            FROM PART p
           WHERE p.TAGS IS NOT NULL""",
          """
          SELECT
                 p.PART_NO
               , p.TAGS
               , LENGTH(p.TAGS) - LENGTH(REPLACE(p.TAGS, ',', '')) AS COMMA_CNT
            FROM PART p
           WHERE p.TAGS IS NOT NULL"""),
      new Example("문자", "숫자가 든 글자를 숫자 순서로 정렬 (P2 < P10)", "글자 정렬은 P10 이 P2 앞에 옵니다. 숫자 부분을 꺼내 숫자로 정렬합니다.",
          """
          SELECT
                 p.PART_NO
            FROM PART p
           ORDER BY
                 REGEXP_SUBSTR(p.PART_NO, '^[^0-9]*')
               , TO_NUMBER(REGEXP_SUBSTR(p.PART_NO, '[0-9]+'))""",
          """
          SELECT
                 p.PART_NO
            FROM PART p
           ORDER BY
                 SUBSTRING(p.PART_NO FROM '^[^0-9]*')
               , CAST(NULLIF(SUBSTRING(p.PART_NO FROM '[0-9]+'), '') AS INTEGER)"""),
      new Example("문자", "줄바꿈 · 탭이 들어간 값 찾기", "엑셀 붙여 넣기로 줄바꿈이 섞인 값. 화면 · 파일 출력이 깨지는 원인입니다.",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
            FROM PART p
           WHERE INSTR(p.PART_NAME, CHR(10)) > 0
              OR INSTR(p.PART_NAME, CHR(13)) > 0
              OR INSTR(p.PART_NAME, CHR(9)) > 0""",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
            FROM PART p
           WHERE POSITION(CHR(10) IN p.PART_NAME) > 0
              OR POSITION(CHR(13) IN p.PART_NAME) > 0
              OR POSITION(CHR(9) IN p.PART_NAME) > 0"""),
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
      new Example("숫자", "비율을 퍼센트 글자로 (12.3%)", "계산한 비율을 소수 한 자리 퍼센트 글자로 만듭니다.",
          """
          SELECT
                 r.WO_NO
               , TO_CHAR(ROUND(r.NG_QTY * 100 / NULLIF(r.GOOD_QTY + r.NG_QTY, 0), 1), 'FM990.0') || '%' AS NG_RATE
            FROM WORK_RESULT r""",
          """
          SELECT
                 r.WO_NO
               , TO_CHAR(ROUND(r.NG_QTY * 100 / NULLIF(r.GOOD_QTY + r.NG_QTY, 0), 1), 'FM990.0') || '%' AS NG_RATE
            FROM WORK_RESULT r"""),
      new Example("숫자", "숫자인지 확인 (글자 칸에 숫자만 있나)", "글자 칸을 숫자로 바꾸기 전에 숫자가 아닌 값을 먼저 찾습니다.",
          """
          SELECT
                 h.PART_NO
               , h.NEW_VAL
            FROM PART_HIST h
           WHERE h.COL_NAME = 'UNIT_COST'
             AND NOT REGEXP_LIKE(h.NEW_VAL, '^-?[0-9]+(\\.[0-9]+)?$')""",
          """
          SELECT
                 h.PART_NO
               , h.NEW_VAL
            FROM PART_HIST h
           WHERE h.COL_NAME = 'UNIT_COST'
             AND h.NEW_VAL !~ '^-?[0-9]+(\\.[0-9]+)?$'"""),
      new Example("숫자", "가중 평균 (수량으로 무게를 준 평균 단가)", "단가 평균이 아니라 수량을 곱해 더한 뒤 전체 수량으로 나눕니다.",
          """
          SELECT
                 i.PART_NO
               , ROUND(SUM(i.QTY * i.PRICE) / NULLIF(SUM(i.QTY), 0), 2) AS WAVG_PRICE
               , ROUND(AVG(i.PRICE), 2) AS SIMPLE_AVG
            FROM ORDER_ITEM i
           GROUP BY
                 i.PART_NO""",
          """
          SELECT
                 i.PART_NO
               , ROUND(SUM(i.QTY * i.PRICE) / NULLIF(SUM(i.QTY), 0), 2) AS WAVG_PRICE
               , ROUND(AVG(i.PRICE), 2) AS SIMPLE_AVG
            FROM ORDER_ITEM i
           GROUP BY
                 i.PART_NO"""),
      new Example("숫자", "끝수 맞추기 (나눈 금액 합이 원래 금액과 같게)", "3 등분하면 끝수가 남습니다. 마지막 줄에 남은 끝수를 몰아줍니다.",
          """
          SELECT
                 x.N
               , CASE WHEN x.N = 3 THEN 10000 - FLOOR(10000 / 3) * 2 ELSE FLOOR(10000 / 3) END AS AMT
            FROM (SELECT LEVEL AS N FROM DUAL CONNECT BY LEVEL <= 3) x""",
          """
          SELECT
                 x.N
               , CASE WHEN x.N = 3 THEN 10000 - FLOOR(10000 / 3.0) * 2 ELSE FLOOR(10000 / 3.0) END AS AMT
            FROM GENERATE_SERIES(1, 3) AS x(N)"""),
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
      new Example("NULL", "NULL 끼리 같다고 보고 비교", "= 는 NULL 끼리 같지 않다고 봅니다. 둘 다 비었을 때도 같게 보려면 이렇게 씁니다.",
          """
          SELECT
                 a.ORDER_ID
            FROM ORDERS a
               , ORDERS_BAK b
           WHERE a.ORDER_ID = b.ORDER_ID
             AND (a.STATUS <> b.STATUS
                  OR (a.STATUS IS NULL AND b.STATUS IS NOT NULL)
                  OR (a.STATUS IS NOT NULL AND b.STATUS IS NULL))""",
          """
          SELECT
                 a.ORDER_ID
            FROM ORDERS a
               , ORDERS_BAK b
           WHERE a.ORDER_ID = b.ORDER_ID
             AND a.STATUS IS DISTINCT FROM b.STATUS"""),
      new Example("NULL", "NULL 과 계산하면 NULL (더하기 함정)", "한 칸이라도 NULL 이면 합이 NULL 이 됩니다. NVL · COALESCE 로 0 을 채웁니다.",
          """
          SELECT
                 r.WO_NO
               , r.GOOD_QTY + r.NG_QTY AS RAW_SUM
               , NVL(r.GOOD_QTY, 0) + NVL(r.NG_QTY, 0) AS SAFE_SUM
            FROM WORK_RESULT r""",
          """
          SELECT
                 r.WO_NO
               , r.GOOD_QTY + r.NG_QTY AS RAW_SUM
               , COALESCE(r.GOOD_QTY, 0) + COALESCE(r.NG_QTY, 0) AS SAFE_SUM
            FROM WORK_RESULT r"""),
      new Example("NULL", "칼럼마다 빈 칸 수 세기", "어느 칼럼이 얼마나 비었는지 한 줄로 봅니다. 이관 · 업로드 점검에 씁니다.",
          """
          SELECT
                 COUNT(*) AS TOTAL
               , COUNT(*) - COUNT(p.PART_NAME) AS NULL_NAME
               , COUNT(*) - COUNT(p.UNIT_COST) AS NULL_COST
               , COUNT(*) - COUNT(p.UNIT_CD) AS NULL_UNIT
               , COUNT(*) - COUNT(p.OWNER_ID) AS NULL_OWNER
            FROM PART p""",
          """
          SELECT
                 COUNT(*) AS TOTAL
               , COUNT(*) - COUNT(p.PART_NAME) AS NULL_NAME
               , COUNT(*) - COUNT(p.UNIT_COST) AS NULL_COST
               , COUNT(*) - COUNT(p.UNIT_CD) AS NULL_UNIT
               , COUNT(*) - COUNT(p.OWNER_ID) AS NULL_OWNER
            FROM PART p"""),
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
           WHERE ORDER_DT < CURRENT_DATE - INTERVAL '12 MONTHS'"""),
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
      new Example("넣기 · 고치기", "TRUNCATE 와 DELETE 차이", "TRUNCATE 는 표를 통째로 비우고 되돌릴 수 없습니다(Oracle). 조건이 있으면 DELETE 를 씁니다.",
          """
          TRUNCATE TABLE UPLOAD_TMP""",
          """
          TRUNCATE TABLE UPLOAD_TMP"""),
      new Example("넣기 · 고치기", "큰 표 나눠 고치기 (한 번에 1만 줄씩)", "한 번에 너무 많이 고치면 잠금 · 로그가 커집니다. 조건에 맞는 줄을 정해진 수만큼 나눠 고치고 커밋합니다.",
          """
          UPDATE IF_PART f
             SET f.STATUS = 'ARCHIVED'
           WHERE f.STATUS = 'DONE'
             AND f.REG_DT < SYSDATE - 365
             AND ROWNUM <= 10000""",
          """
          UPDATE IF_PART f
             SET STATUS = 'ARCHIVED'
           WHERE f.CTID IN (
                  SELECT x.CTID
                    FROM IF_PART x
                   WHERE x.STATUS = 'DONE'
                     AND x.REG_DT < NOW() - INTERVAL '365 DAY'
                   LIMIT 10000
                 )"""),
      new Example("넣기 · 고치기", "백업 표로 되돌리기 (지운 줄 다시 넣기)", "고치기 전에 떠 둔 백업 표에서 없어진 줄만 다시 넣습니다.",
          """
          INSERT INTO ORDERS (ORDER_ID, CUST_ID, AMOUNT, STATUS, ORDER_DT, PARENT_ID)
          SELECT
                 b.ORDER_ID
               , b.CUST_ID
               , b.AMOUNT
               , b.STATUS
               , b.ORDER_DT
               , b.PARENT_ID
            FROM ORDERS_BAK b
           WHERE NOT EXISTS (SELECT 1 FROM ORDERS o WHERE o.ORDER_ID = b.ORDER_ID)""",
          """
          INSERT INTO ORDERS (ORDER_ID, CUST_ID, AMOUNT, STATUS, ORDER_DT, PARENT_ID)
          SELECT
                 b.ORDER_ID
               , b.CUST_ID
               , b.AMOUNT
               , b.STATUS
               , b.ORDER_DT
               , b.PARENT_ID
            FROM ORDERS_BAK b
           WHERE NOT EXISTS (SELECT 1 FROM ORDERS o WHERE o.ORDER_ID = b.ORDER_ID)"""),
      new Example("넣기 · 고치기", "백업 표 값으로 고친 칼럼 되돌리기", "잘못 고친 칼럼만 백업 표 값으로 되돌립니다.",
          """
          UPDATE ORDERS o
             SET o.STATUS = (SELECT b.STATUS FROM ORDERS_BAK b WHERE b.ORDER_ID = o.ORDER_ID)
           WHERE EXISTS (SELECT 1 FROM ORDERS_BAK b WHERE b.ORDER_ID = o.ORDER_ID AND DECODE(b.STATUS, o.STATUS, 0, 1) = 1)""",
          """
          UPDATE ORDERS o
             SET STATUS = b.STATUS
            FROM ORDERS_BAK b
           WHERE b.ORDER_ID = o.ORDER_ID
             AND b.STATUS IS DISTINCT FROM o.STATUS"""),
      new Example("넣기 · 고치기", "순서 칼럼 끼워 넣기 (뒤 번호 하나씩 밀기)", "정렬 번호 3 자리에 새 줄을 넣으려고 3 이상인 줄의 번호를 1 씩 올립니다.",
          """
          UPDATE COMMON_CODE c
             SET c.SORT_NO = c.SORT_NO + 1
           WHERE c.GROUP_CD = :groupCd
             AND c.SORT_NO >= :insertAt""",
          """
          UPDATE COMMON_CODE c
             SET SORT_NO = c.SORT_NO + 1
           WHERE c.GROUP_CD = :groupCd
             AND c.SORT_NO >= CAST(:insertAt AS INTEGER)"""),
      new Example("넣기 · 고치기", "부모 지우면 자식도 (외래 키 ON DELETE CASCADE)", "부모 줄을 지울 때 자식 줄이 같이 지워지게 키를 겁니다. 실수로 많이 지워질 수 있어 조심해서 씁니다.",
          """
          ALTER TABLE ORDER_ITEM ADD CONSTRAINT FK_ORDER_ITEM_ORDER FOREIGN KEY (ORDER_ID) REFERENCES ORDERS (ORDER_ID) ON DELETE CASCADE""",
          """
          ALTER TABLE ORDER_ITEM ADD CONSTRAINT FK_ORDER_ITEM_ORDER FOREIGN KEY (ORDER_ID) REFERENCES ORDERS (ORDER_ID) ON DELETE CASCADE"""),
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
      new Example("표 만들기 · 바꾸기", "기본 키 · 고유 제약 · 검사 제약 더하기", "있는 표에 키와 값 검사를 겁니다. 이미 어긋난 데이터가 있으면 실패하니 먼저 점검하세요.",
          """
          ALTER TABLE PART_VENDOR ADD CONSTRAINT UK_PART_VENDOR UNIQUE (PART_NO, VENDOR_ID)""",
          """
          ALTER TABLE PART_VENDOR ADD CONSTRAINT UK_PART_VENDOR UNIQUE (PART_NO, VENDOR_ID)"""),
      new Example("표 만들기 · 바꾸기", "검사 제약 (값 범위 · 허용 값)", "수량은 0 이상, 상태는 정한 값만 들어가게 합니다.",
          """
          ALTER TABLE ORDER_ITEM ADD CONSTRAINT CK_ORDER_ITEM_QTY CHECK (QTY >= 0)""",
          """
          ALTER TABLE ORDER_ITEM ADD CONSTRAINT CK_ORDER_ITEM_QTY CHECK (QTY >= 0)"""),
      new Example("표 만들기 · 바꾸기", "칼럼 이름 바꾸기 · 지우기", "이름을 바꾸면 그 칼럼을 쓰는 쿼리 · 뷰가 모두 깨지니 먼저 찾아보세요(「뷰 · 함수 소스에서 글자 찾기」).",
          """
          ALTER TABLE PART RENAME COLUMN TAGS TO TAG_LIST""",
          """
          ALTER TABLE PART RENAME COLUMN TAGS TO TAG_LIST"""),
      new Example("표 만들기 · 바꾸기", "기본값 주기 (넣을 때 비우면 이 값)", "등록일 칼럼에 기본값을 줍니다. 이미 들어간 줄은 바뀌지 않습니다.",
          """
          ALTER TABLE PART MODIFY (REG_DT DEFAULT SYSDATE)""",
          """
          ALTER TABLE PART ALTER COLUMN REG_DT SET DEFAULT CURRENT_DATE"""),
      new Example("표 만들기 · 바꾸기", "NOT NULL 걸기 · 풀기", "비면 안 되는 칼럼에 NOT NULL. 빈 줄이 있으면 먼저 채워야 합니다.",
          """
          ALTER TABLE PART MODIFY (PART_NAME NOT NULL)""",
          """
          ALTER TABLE PART ALTER COLUMN PART_NAME SET NOT NULL"""),
      new Example("표 만들기 · 바꾸기", "시퀀스 만들기 · 다음 번호 맞추기", "번호용 시퀀스. 이미 데이터가 있으면 가장 큰 번호 다음부터 시작하게 만듭니다.",
          """
          CREATE SEQUENCE SEQ_ISSUE START WITH 1000 INCREMENT BY 1 NOCACHE""",
          """
          CREATE SEQUENCE SEQ_ISSUE START WITH 1000 INCREMENT BY 1"""),
      new Example("표 만들기 · 바꾸기", "자동 번호 칼럼 (IDENTITY, 12c 이상)", "시퀀스 없이 넣을 때마다 번호가 붙는 칼럼.",
          """
          CREATE TABLE TB_MEMO (
              MEMO_ID NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY
            , MEMO_TEXT VARCHAR2(4000)
            , REG_DT DATE DEFAULT SYSDATE
          )""",
          """
          CREATE TABLE TB_MEMO (
              MEMO_ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY
            , MEMO_TEXT VARCHAR(4000)
            , REG_DT DATE DEFAULT CURRENT_DATE
          )"""),
      new Example("표 만들기 · 바꾸기", "임시 표 (세션 · 트랜잭션 동안만)", "중간 결과를 잠깐 담는 표. 커밋하면 비워집니다.",
          """
          CREATE GLOBAL TEMPORARY TABLE TMP_PART_LIST (
              PART_NO VARCHAR2(30)
          ) ON COMMIT DELETE ROWS""",
          """
          CREATE TEMPORARY TABLE TMP_PART_LIST (
              PART_NO VARCHAR(30)
          ) ON COMMIT DELETE ROWS"""),
      new Example("표 만들기 · 바꾸기", "여러 칼럼 색인 · 함수 색인", "자주 같이 거르는 칼럼 묶음, 또는 UPPER(이름) 처럼 함수를 씌워 거를 때의 색인.",
          """
          CREATE INDEX IX_ORDERS_CUST_DT ON ORDERS (CUST_ID, ORDER_DT)""",
          """
          CREATE INDEX IX_ORDERS_CUST_DT ON ORDERS (CUST_ID, ORDER_DT)"""),
      new Example("표 만들기 · 바꾸기", "다른 이름으로 부르기 (동의어 · 검색 경로)", "다른 계정의 표를 계정 이름 없이 부르게 합니다.",
          """
          CREATE SYNONYM ORDERS_REMOTE FOR APP_OWNER.ORDERS""",
          """
          SET SEARCH_PATH TO PUBLIC"""),
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
      new Example("표 · 칼럼 정보", "칼럼 설명(한글)으로 칼럼 찾기", "「납기」처럼 한글 설명에 든 낱말로 어느 표의 어느 칼럼인지 찾습니다.",
          """
          SELECT
                 m.TABLE_NAME
               , m.COLUMN_NAME
               , m.COMMENTS
            FROM USER_COL_COMMENTS m
           WHERE m.COMMENTS LIKE '%' || :keyword || '%'
           ORDER BY
                 m.TABLE_NAME
               , m.COLUMN_NAME""",
          """
          SELECT
                 t.RELNAME AS TABLE_NAME
               , a.ATTNAME AS COLUMN_NAME
               , d.DESCRIPTION AS COMMENTS
            FROM PG_DESCRIPTION d
               , PG_CLASS t
               , PG_ATTRIBUTE a
           WHERE d.CLASSOID = 'pg_class'::REGCLASS
             AND d.OBJOID = t.OID
             AND a.ATTRELID = t.OID
             AND a.ATTNUM = d.OBJSUBID
             AND t.RELNAMESPACE = CURRENT_SCHEMA()::REGNAMESPACE
             AND d.DESCRIPTION ILIKE '%' || :keyword || '%'
           ORDER BY
                 t.RELNAME
               , a.ATTNAME"""),
      new Example("표 · 칼럼 정보", "표 설명(한글)으로 표 찾기", "「부품」 「협력사」처럼 표 설명에 든 낱말로 표 · 뷰를 찾습니다.",
          """
          SELECT
                 t.TABLE_NAME
               , t.TABLE_TYPE
               , t.COMMENTS
            FROM USER_TAB_COMMENTS t
           WHERE t.COMMENTS LIKE '%' || :keyword || '%'
           ORDER BY
                 t.TABLE_NAME""",
          """
          SELECT
                 t.RELNAME AS TABLE_NAME
               , t.RELKIND AS TABLE_TYPE
               , d.DESCRIPTION AS COMMENTS
            FROM PG_CLASS t
               , PG_DESCRIPTION d
           WHERE d.CLASSOID = 'pg_class'::REGCLASS
             AND d.OBJOID = t.OID
             AND d.OBJSUBID = 0
             AND t.RELNAMESPACE = CURRENT_SCHEMA()::REGNAMESPACE
             AND d.DESCRIPTION ILIKE '%' || :keyword || '%'
           ORDER BY
                 t.RELNAME"""),
      new Example("표 · 칼럼 정보", "이 표를 가리키는 표 (외래 키 거꾸로)", "이 표의 줄을 지우거나 키를 바꾸기 전에, 어느 표가 이 표를 참조하는지 봅니다.",
          """
          SELECT
                 c.TABLE_NAME AS CHILD_TABLE
               , c.CONSTRAINT_NAME
               , cc.COLUMN_NAME AS CHILD_COLUMN
               , cc.POSITION
               , c.DELETE_RULE
               , c.STATUS
            FROM USER_CONSTRAINTS p
               , USER_CONSTRAINTS c
               , USER_CONS_COLUMNS cc
           WHERE p.TABLE_NAME = UPPER(:tableName)
             AND p.CONSTRAINT_TYPE IN ('P', 'U')
             AND c.R_CONSTRAINT_NAME = p.CONSTRAINT_NAME
             AND c.CONSTRAINT_TYPE = 'R'
             AND cc.CONSTRAINT_NAME = c.CONSTRAINT_NAME
           ORDER BY
                 c.TABLE_NAME
               , c.CONSTRAINT_NAME
               , cc.POSITION""",
          """
          SELECT
                 c.CONRELID::REGCLASS AS CHILD_TABLE
               , c.CONNAME AS CONSTRAINT_NAME
               , PG_GET_CONSTRAINTDEF(c.OID) AS DEFINITION
            FROM PG_CONSTRAINT c
           WHERE c.CONTYPE = 'f'
             AND c.CONFRELID = CAST(:tableName AS REGCLASS)
           ORDER BY
                 1, 2"""),
      new Example("표 · 칼럼 정보", "고유 · 검사 제약 목록 (조건 글 포함)", "표에 걸린 UNIQUE 와 CHECK 제약, 조건 글. Oracle 은 NOT NULL 도 검사 제약(C)으로 나옵니다.",
          """
          SELECT
                 c.CONSTRAINT_NAME
               , c.CONSTRAINT_TYPE
               , c.SEARCH_CONDITION
               , c.STATUS
            FROM USER_CONSTRAINTS c
           WHERE c.TABLE_NAME = UPPER(:tableName)
             AND c.CONSTRAINT_TYPE IN ('U', 'C')
           ORDER BY
                 c.CONSTRAINT_TYPE
               , c.CONSTRAINT_NAME""",
          """
          SELECT
                 c.CONNAME AS CONSTRAINT_NAME
               , c.CONTYPE AS CONSTRAINT_TYPE
               , PG_GET_CONSTRAINTDEF(c.OID) AS DEFINITION
            FROM PG_CONSTRAINT c
           WHERE c.CONRELID = CAST(:tableName AS REGCLASS)
             AND c.CONTYPE IN ('u', 'c')
           ORDER BY
                 2, 1"""),
      new Example("표 · 칼럼 정보", "트리거 목록 (표마다 · 켜짐 여부)", "INSERT · UPDATE 때 몰래 값이 바뀐다면 트리거부터 봅니다.",
          """
          SELECT
                 t.TABLE_NAME
               , t.TRIGGER_NAME
               , t.TRIGGER_TYPE
               , t.TRIGGERING_EVENT
               , t.STATUS
            FROM USER_TRIGGERS t
           ORDER BY
                 t.TABLE_NAME
               , t.TRIGGER_NAME""",
          """
          SELECT
                 t.TGRELID::REGCLASS AS TABLE_NAME
               , t.TGNAME AS TRIGGER_NAME
               , PG_GET_TRIGGERDEF(t.OID) AS DEFINITION
               , t.TGENABLED AS STATUS
            FROM PG_TRIGGER t
               , PG_CLASS c
           WHERE t.TGRELID = c.OID
             AND NOT t.TGISINTERNAL
             AND c.RELNAMESPACE = CURRENT_SCHEMA()::REGNAMESPACE
           ORDER BY
                 1, 2"""),
      new Example("표 · 칼럼 정보", "동의어가 가리키는 대상 (끊긴 동의어 찾기)", "OBJECT_TYPE 이 비면 대상이 없거나 볼 권한이 없습니다. PostgreSQL 은 동의어 대신 검색 경로를 봅니다.",
          """
          SELECT
                 s.SYNONYM_NAME
               , s.TABLE_OWNER
               , s.TABLE_NAME
               , s.DB_LINK
               , o.OBJECT_TYPE
               , o.STATUS
            FROM USER_SYNONYMS s
               , ALL_OBJECTS o
           WHERE s.TABLE_OWNER = o.OWNER(+)
             AND s.TABLE_NAME = o.OBJECT_NAME(+)
           ORDER BY
                 s.SYNONYM_NAME""",
          """
          SELECT
                 CURRENT_SETTING('search_path') AS SEARCH_PATH
               , CURRENT_SCHEMA() AS NOW_SCHEMA"""),
      new Example("표 · 칼럼 정보", "파티션 목록 (경계 값 · 건수 어림)", "파티션 표가 어떻게 나뉘어 있고 파티션마다 몇 건쯤인지.",
          """
          SELECT
                 p.PARTITION_POSITION
               , p.PARTITION_NAME
               , p.HIGH_VALUE
               , p.NUM_ROWS
               , p.LAST_ANALYZED
            FROM USER_TAB_PARTITIONS p
           WHERE p.TABLE_NAME = UPPER(:tableName)
           ORDER BY
                 p.PARTITION_POSITION""",
          """
          SELECT
                 c.RELNAME AS PARTITION_NAME
               , PG_GET_EXPR(c.RELPARTBOUND, c.OID) AS BOUND
               , c.RELTUPLES::BIGINT AS NUM_ROWS
            FROM PG_INHERITS i
               , PG_CLASS c
           WHERE i.INHRELID = c.OID
             AND i.INHPARENT = CAST(:tableName AS REGCLASS)
           ORDER BY
                 c.RELNAME"""),
      new Example("표 · 칼럼 정보", "표 만드는 문장 뽑기 (DDL)", "운영 표를 개발 DB 에 똑같이 만들 때. Oracle 은 'TABLE' 대신 'VIEW' · 'PROCEDURE' · 'INDEX' 도 됩니다. PostgreSQL 은 칼럼 · NOT NULL 만 만들고 키 · 색인은 pg_dump 로 봅니다.",
          """
          SELECT
                 DBMS_METADATA.GET_DDL('TABLE', UPPER(:tableName)) AS DDL
            FROM DUAL""",
          """
          SELECT
                 'CREATE TABLE ' || c.RELNAME || ' (' || STRING_AGG(a.ATTNAME || ' ' || FORMAT_TYPE(a.ATTTYPID, a.ATTTYPMOD) || CASE WHEN a.ATTNOTNULL THEN ' NOT NULL' ELSE '' END, ', ' ORDER BY a.ATTNUM) || ');' AS DDL
            FROM PG_CLASS c
               , PG_ATTRIBUTE a
           WHERE a.ATTRELID = c.OID
             AND c.OID = CAST(:tableName AS REGCLASS)
             AND a.ATTNUM > 0
             AND NOT a.ATTISDROPPED
           GROUP BY
                 c.RELNAME"""),
      new Example("표 · 칼럼 정보", "이 표가 마지막으로 바뀐 때", "Oracle 은 ORA_ROWSCN 으로 어림합니다(블록 단위, 며칠 넘게 지나면 ORA-08181). PostgreSQL 은 통계 수집 뒤 넣기 · 고치기 · 지우기 건수를 봅니다.",
          """
          SELECT
                 SCN_TO_TIMESTAMP(MAX(o.ORA_ROWSCN)) AS LAST_CHANGED
            FROM ORDERS o""",
          """
          SELECT
                 s.RELNAME AS TABLE_NAME
               , s.N_TUP_INS
               , s.N_TUP_UPD
               , s.N_TUP_DEL
               , s.N_MOD_SINCE_ANALYZE
               , GREATEST(s.LAST_ANALYZE, s.LAST_AUTOANALYZE) AS LAST_ANALYZED
            FROM PG_STAT_USER_TABLES s
           WHERE s.RELNAME = LOWER(:tableName)"""),
      new Example("표 · 칼럼 정보", "표 칼럼을 SELECT 문으로 펼치기", "SELECT * 대신 칼럼을 다 적은 문장을 만들어 매퍼에 붙입니다. 칼럼은 줄 앞 쉼표로 나옵니다.",
          """
          SELECT
                 'SELECT' || CHR(10) || '       ' || LISTAGG(LOWER(:alias) || '.' || c.COLUMN_NAME, CHR(10) || '     , ') WITHIN GROUP (ORDER BY c.COLUMN_ID) || CHR(10) || '  FROM ' || c.TABLE_NAME || ' ' || LOWER(:alias) AS SELECT_SQL
            FROM USER_TAB_COLUMNS c
           WHERE c.TABLE_NAME = UPPER(:tableName)
           GROUP BY
                 c.TABLE_NAME""",
          """
          SELECT
                 'SELECT' || CHR(10) || '       ' || STRING_AGG(LOWER(:alias) || '.' || UPPER(c.COLUMN_NAME), CHR(10) || '     , ' ORDER BY c.ORDINAL_POSITION) || CHR(10) || '  FROM ' || UPPER(c.TABLE_NAME) || ' ' || LOWER(:alias) AS SELECT_SQL
            FROM INFORMATION_SCHEMA.COLUMNS c
           WHERE c.TABLE_SCHEMA = CURRENT_SCHEMA()
             AND c.TABLE_NAME = LOWER(:tableName)
           GROUP BY
                 c.TABLE_NAME"""),
      new Example("표 · 칼럼 정보", "칼럼 이름을 camelCase 로 (VO · DTO 필드 이름)", "PART_NO → partNo. resultMap · VO 를 손으로 만들 때 칼럼과 필드 이름을 나란히 봅니다.",
          """
          SELECT
                 c.COLUMN_NAME
               , LOWER(SUBSTR(REPLACE(INITCAP(c.COLUMN_NAME), '_'), 1, 1)) || SUBSTR(REPLACE(INITCAP(c.COLUMN_NAME), '_'), 2) AS FIELD_NAME
               , c.DATA_TYPE
               , m.COMMENTS
            FROM USER_TAB_COLUMNS c
               , USER_COL_COMMENTS m
           WHERE c.TABLE_NAME = UPPER(:tableName)
             AND c.TABLE_NAME = m.TABLE_NAME(+)
             AND c.COLUMN_NAME = m.COLUMN_NAME(+)
           ORDER BY
                 c.COLUMN_ID""",
          """
          SELECT
                 UPPER(a.ATTNAME) AS COLUMN_NAME
               , LOWER(SUBSTR(REPLACE(INITCAP(a.ATTNAME), '_', ''), 1, 1)) || SUBSTR(REPLACE(INITCAP(a.ATTNAME), '_', ''), 2) AS FIELD_NAME
               , FORMAT_TYPE(a.ATTTYPID, a.ATTTYPMOD) AS DATA_TYPE
               , COL_DESCRIPTION(a.ATTRELID, a.ATTNUM) AS COMMENTS
            FROM PG_ATTRIBUTE a
           WHERE a.ATTRELID = CAST(:tableName AS REGCLASS)
             AND a.ATTNUM > 0
             AND NOT a.ATTISDROPPED
           ORDER BY
                 a.ATTNUM"""),
      new Example("표 · 칼럼 정보", "칼럼 값 종류 수 · 빈 값 비율 (색인 걸 만한가)", "값 종류가 많고 빈 값이 적을수록 색인이 잘 듣습니다. 통계 기준이라 통계가 오래되면 어긋납니다.",
          """
          SELECT
                 s.COLUMN_NAME
               , s.NUM_DISTINCT
               , s.NUM_NULLS
               , t.NUM_ROWS
               , ROUND(s.NUM_NULLS / NULLIF(t.NUM_ROWS, 0) * 100, 1) AS NULL_PCT
               , s.HISTOGRAM
            FROM USER_TAB_COL_STATISTICS s
               , USER_TABLES t
           WHERE s.TABLE_NAME = t.TABLE_NAME
             AND s.TABLE_NAME = UPPER(:tableName)
           ORDER BY
                 s.NUM_DISTINCT DESC""",
          """
          SELECT
                 s.ATTNAME AS COLUMN_NAME
               , s.N_DISTINCT
               , ROUND(CAST(s.NULL_FRAC * 100 AS NUMERIC), 1) AS NULL_PCT
               , s.MOST_COMMON_VALS
            FROM PG_STATS s
           WHERE s.SCHEMANAME = CURRENT_SCHEMA()
             AND s.TABLENAME = LOWER(:tableName)
           ORDER BY
                 s.N_DISTINCT DESC"""),
      new Example("의존 관계 · 소스", "이 표를 쓰는 뷰 · 프로시저 · 패키지", "표를 고치거나 지우기 전에 영향받는 DB 객체를 봅니다. PostgreSQL 은 뷰만 기록되어 함수는 「뷰 · 함수 소스에서 글자 찾기」로 봅니다.",
          """
          SELECT
                 d.TYPE
               , d.NAME
               , d.DEPENDENCY_TYPE
            FROM USER_DEPENDENCIES d
           WHERE d.REFERENCED_NAME = UPPER(:tableName)
           ORDER BY
                 d.TYPE
               , d.NAME""",
          """
          SELECT DISTINCT
                 v.RELKIND AS TYPE
               , v.RELNAME AS NAME
            FROM PG_DEPEND d
               , PG_REWRITE r
               , PG_CLASS v
           WHERE d.OBJID = r.OID
             AND r.EV_CLASS = v.OID
             AND d.REFOBJID = CAST(:tableName AS REGCLASS)
             AND v.OID <> d.REFOBJID
           ORDER BY
                 1, 2"""),
      new Example("의존 관계 · 소스", "이 프로시저 · 뷰가 쓰는 표", "프로시저 · 패키지 · 뷰가 안에서 읽고 쓰는 표 · 시퀀스.",
          """
          SELECT
                 d.REFERENCED_TYPE
               , d.REFERENCED_OWNER
               , d.REFERENCED_NAME
            FROM USER_DEPENDENCIES d
           WHERE d.NAME = UPPER(:objectName)
             AND d.REFERENCED_TYPE IN ('TABLE', 'VIEW', 'SYNONYM', 'SEQUENCE', 'PACKAGE', 'FUNCTION', 'PROCEDURE')
             AND d.REFERENCED_OWNER <> 'SYS'
           ORDER BY
                 d.REFERENCED_TYPE
               , d.REFERENCED_NAME""",
          """
          SELECT DISTINCT
                 t.RELKIND AS REFERENCED_TYPE
               , t.RELNAME AS REFERENCED_NAME
            FROM PG_REWRITE r
               , PG_DEPEND d
               , PG_CLASS t
           WHERE r.EV_CLASS = CAST(:objectName AS REGCLASS)
             AND d.OBJID = r.OID
             AND d.REFCLASSID = 'pg_class'::REGCLASS
             AND d.REFOBJID = t.OID
             AND t.OID <> r.EV_CLASS
           ORDER BY
                 1, 2"""),
      new Example("의존 관계 · 소스", "프로시저 · 함수 인자 목록", "부를 때 넣을 인자 이름 · 형 · IN/OUT. Oracle 은 POSITION 0 이 함수 돌려주는 값입니다.",
          """
          SELECT
                 a.PACKAGE_NAME
               , a.OBJECT_NAME
               , a.OVERLOAD
               , a.POSITION
               , a.ARGUMENT_NAME
               , a.IN_OUT
               , a.DATA_TYPE
            FROM USER_ARGUMENTS a
           WHERE a.OBJECT_NAME = UPPER(:objectName)
           ORDER BY
                 a.PACKAGE_NAME
               , a.OVERLOAD
               , a.POSITION""",
          """
          SELECT
                 p.PRONAME AS OBJECT_NAME
               , PG_GET_FUNCTION_ARGUMENTS(p.OID) AS ARGUMENTS
               , PG_GET_FUNCTION_RESULT(p.OID) AS RESULT_TYPE
            FROM PG_PROC p
           WHERE p.PRONAMESPACE = CURRENT_SCHEMA()::REGNAMESPACE
             AND p.PRONAME = LOWER(:objectName)"""),
      new Example("의존 관계 · 소스", "컴파일 오류 내용 (몇째 줄 무슨 오류)", "INVALID 가 된 프로시저 · 뷰의 오류 줄과 글. PostgreSQL 은 만들 때 바로 오류를 내서 남는 오류가 없습니다.",
          """
          SELECT
                 e.TYPE
               , e.NAME
               , e.LINE
               , e.POSITION
               , e.TEXT
            FROM USER_ERRORS e
           ORDER BY
                 e.NAME
               , e.TYPE
               , e.SEQUENCE""",
          """
          SELECT
                 'PostgreSQL 은 CREATE FUNCTION 때 바로 오류를 내서 남아 있는 컴파일 오류가 없습니다.' AS NOTE"""),
      new Example("의존 관계 · 소스", "프로시저 · 패키지 소스 전체 보기", "DB 에 들어 있는 소스를 줄 번호와 함께 봅니다. 운영에 실제로 올라간 판을 확인할 때.",
          """
          SELECT
                 s.TYPE
               , s.LINE
               , s.TEXT
            FROM USER_SOURCE s
           WHERE s.NAME = UPPER(:objectName)
           ORDER BY
                 s.TYPE
               , s.LINE""",
          """
          SELECT
                 p.PRONAME AS NAME
               , PG_GET_FUNCTIONDEF(p.OID) AS SOURCE
            FROM PG_PROC p
           WHERE p.PRONAMESPACE = CURRENT_SCHEMA()::REGNAMESPACE
             AND p.PRONAME = LOWER(:objectName)
             AND p.PROKIND IN ('f', 'p')"""),
      new Example("의존 관계 · 소스", "뷰 소스 보기", "뷰가 어떤 SELECT 로 되어 있는지 봅니다.",
          """
          SELECT
                 v.VIEW_NAME
               , v.TEXT
            FROM USER_VIEWS v
           WHERE v.VIEW_NAME = UPPER(:viewName)""",
          """
          SELECT
                 PG_GET_VIEWDEF(CAST(:viewName AS REGCLASS), TRUE) AS TEXT"""),
      new Example("의존 관계 · 소스", "패키지 안 프로시저 · 함수 목록", "패키지에 들어 있는 프로시저 · 함수 이름. PostgreSQL 은 패키지가 없어 스키마의 함수 목록을 봅니다.",
          """
          SELECT
                 p.OBJECT_NAME AS PACKAGE_NAME
               , p.PROCEDURE_NAME
               , p.OVERLOAD
            FROM USER_PROCEDURES p
           WHERE p.OBJECT_NAME = UPPER(:packageName)
             AND p.PROCEDURE_NAME IS NOT NULL
           ORDER BY
                 p.SUBPROGRAM_ID""",
          """
          SELECT
                 p.PRONAME AS PROCEDURE_NAME
               , p.PROKIND
               , PG_GET_FUNCTION_ARGUMENTS(p.OID) AS ARGUMENTS
            FROM PG_PROC p
           WHERE p.PRONAMESPACE = CURRENT_SCHEMA()::REGNAMESPACE
           ORDER BY
                 p.PRONAME"""),
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
      new Example("세션 · 락", "줄 잠그고 읽기 (SELECT … FOR UPDATE)", "읽은 줄을 다른 사람이 못 고치게 잠급니다. COMMIT · ROLLBACK 해야 풀립니다.",
          """
          SELECT
                 o.ORDER_ID
               , o.STATUS
            FROM ORDERS o
           WHERE o.ORDER_ID = :orderId
             FOR UPDATE""",
          """
          SELECT
                 o.ORDER_ID
               , o.STATUS
            FROM ORDERS o
           WHERE o.ORDER_ID = CAST(:orderId AS INTEGER)
             FOR UPDATE"""),
      new Example("세션 · 락", "잠겨 있으면 기다리지 않기 (NOWAIT · SKIP LOCKED)", "NOWAIT 는 잠겨 있으면 바로 오류, SKIP LOCKED 는 잠긴 줄을 건너뜁니다(작업 큐에서 씀).",
          """
          SELECT
                 f.IF_ID
            FROM IF_PART f
           WHERE f.STATUS = 'READY'
             FOR UPDATE SKIP LOCKED""",
          """
          SELECT
                 f.IF_ID
            FROM IF_PART f
           WHERE f.STATUS = 'READY'
             FOR UPDATE SKIP LOCKED"""),
      new Example("세션 · 락", "잠긴 표 · 잠근 세션 보기", "어느 세션이 어떤 표를 잠그고 있는지.",
          """
          SELECT
                 s.SID
               , s.SERIAL#
               , s.USERNAME
               , o.OBJECT_NAME
               , l.LOCKED_MODE
            FROM V$LOCKED_OBJECT l
               , DBA_OBJECTS o
               , V$SESSION s
           WHERE l.OBJECT_ID = o.OBJECT_ID
             AND l.SESSION_ID = s.SID""",
          """
          SELECT
                 a.PID
               , a.USENAME
               , c.RELNAME
               , l.MODE
               , l.GRANTED
            FROM PG_LOCKS l
               , PG_CLASS c
               , PG_STAT_ACTIVITY a
           WHERE l.RELATION = c.OID
             AND l.PID = a.PID
             AND c.RELKIND = 'r'"""),
      new Example("세션 · 락", "SID 로 그 세션의 SQL 글 보기 (지금 · 바로 전)", "락을 잡은 세션이 무엇을 돌렸는지 봅니다. 잠근 문장은 이미 끝났을 수 있어 「바로 전 SQL」 이 잠근 문장이 아닐 수도 있습니다.",
          """
          SELECT
                 s.SID
               , s.SERIAL#
               , s.USERNAME
               , s.MACHINE
               , s.PROGRAM
               , s.STATUS
               , q.SQL_FULLTEXT AS NOW_SQL
               , p.SQL_FULLTEXT AS PREV_SQL
            FROM V$SESSION s
               , V$SQL q
               , V$SQL p
           WHERE s.SID = :sid
             AND s.SQL_ID = q.SQL_ID(+)
             AND s.SQL_CHILD_NUMBER = q.CHILD_NUMBER(+)
             AND s.PREV_SQL_ID = p.SQL_ID(+)
             AND s.PREV_CHILD_NUMBER = p.CHILD_NUMBER(+)""",
          """
          SELECT
                 a.PID
               , a.USENAME
               , a.APPLICATION_NAME
               , a.CLIENT_ADDR
               , a.STATE
               , a.QUERY_START
               , a.QUERY
            FROM PG_STAT_ACTIVITY a
           WHERE a.PID = CAST(:pid AS INTEGER)"""),
      new Example("세션 · 락", "오래 열린 트랜잭션 (커밋 안 한 세션)", "커밋 · 롤백 없이 붙잡고 있는 세션. 락의 주범은 대개 이쪽입니다. IDE 에서 고치고 커밋을 잊은 세션도 여기 나옵니다.",
          """
          SELECT
                 s.SID
               , s.SERIAL#
               , s.USERNAME
               , s.MACHINE
               , s.PROGRAM
               , s.STATUS
               , t.START_DATE
               , ROUND((SYSDATE - t.START_DATE) * 24 * 60) AS OPEN_MIN
               , t.USED_UBLK AS UNDO_BLOCKS
            FROM V$TRANSACTION t
               , V$SESSION s
           WHERE t.ADDR = s.TADDR
           ORDER BY
                 t.START_DATE""",
          """
          SELECT
                 a.PID
               , a.USENAME
               , a.APPLICATION_NAME
               , a.CLIENT_ADDR
               , a.STATE
               , a.XACT_START
               , ROUND(EXTRACT(EPOCH FROM NOW() - a.XACT_START) / 60) AS OPEN_MIN
               , a.QUERY
            FROM PG_STAT_ACTIVITY a
           WHERE a.XACT_START IS NOT NULL
             AND a.PID <> PG_BACKEND_PID()
           ORDER BY
                 a.XACT_START"""),
      new Example("세션 · 락", "막힌 줄이 어느 표 어느 줄인지 (ROWID)", "기다리는 세션이 막힌 표와 줄 위치. 나온 ROWID 로 SELECT * FROM 표 WHERE ROWID = '…' 하면 그 줄이 보입니다. PostgreSQL 은 PAGE · TUPLE 로 WHERE CTID = '(page,tuple)'.",
          """
          SELECT
                 w.SID AS WAITING_SID
               , w.BLOCKING_SESSION AS BLOCKING_SID
               , o.OWNER
               , o.OBJECT_NAME AS TABLE_NAME
               , DBMS_ROWID.ROWID_CREATE(1, o.DATA_OBJECT_ID, w.ROW_WAIT_FILE#, w.ROW_WAIT_BLOCK#, w.ROW_WAIT_ROW#) AS LOCKED_ROWID
               , w.SECONDS_IN_WAIT
            FROM V$SESSION w
               , ALL_OBJECTS o
           WHERE w.ROW_WAIT_OBJ# = o.OBJECT_ID
             AND w.BLOCKING_SESSION IS NOT NULL""",
          """
          SELECT
                 w.PID AS WAITING_PID
               , PG_BLOCKING_PIDS(w.PID) AS BLOCKING_PIDS
               , l.LOCKTYPE
               , l.RELATION::REGCLASS AS TABLE_NAME
               , l.PAGE
               , l.TUPLE
               , l.MODE
               , w.QUERY
            FROM PG_LOCKS l
               , PG_STAT_ACTIVITY w
           WHERE l.PID = w.PID
             AND NOT l.GRANTED"""),
      new Example("세션 · 락", "맨 앞에서 막는 세션 (줄줄이 막힐 때)", "A 가 B 를, B 가 C 를 막을 때 끝까지 거슬러 올라간 첫 세션. 이 세션만 풀면 줄줄이 풀립니다.",
          """
          SELECT
                 s.FINAL_BLOCKING_SESSION AS ROOT_SID
               , COUNT(*) AS WAITING_CNT
               , MAX(s.SECONDS_IN_WAIT) AS MAX_WAIT_SEC
            FROM V$SESSION s
           WHERE s.FINAL_BLOCKING_SESSION IS NOT NULL
           GROUP BY
                 s.FINAL_BLOCKING_SESSION
           ORDER BY
                 WAITING_CNT DESC""",
          """
          SELECT
                 a.PID AS ROOT_PID
               , a.USENAME
               , a.STATE
               , a.XACT_START
               , a.QUERY
            FROM PG_STAT_ACTIVITY a
           WHERE CARDINALITY(PG_BLOCKING_PIDS(a.PID)) = 0
             AND EXISTS (
                  SELECT 1
                    FROM PG_STAT_ACTIVITY w
                   WHERE a.PID = ANY (PG_BLOCKING_PIDS(w.PID))
                 )"""),
      new Example("세션 · 락", "내 세션 정보 (SID · 사용자 · 스키마)", "위 결과에 나온 세션이 지금 이 IDE 접속인지 확인할 때.",
          """
          SELECT
                 SYS_CONTEXT('USERENV', 'SID') AS SID
               , SYS_CONTEXT('USERENV', 'SESSION_USER') AS USER_NAME
               , SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS SCHEMA_NAME
               , SYS_CONTEXT('USERENV', 'HOST') AS HOST
               , SYS_CONTEXT('USERENV', 'MODULE') AS MODULE
               , SYS_CONTEXT('USERENV', 'DB_NAME') AS DB_NAME
            FROM DUAL""",
          """
          SELECT
                 PG_BACKEND_PID() AS PID
               , SESSION_USER AS USER_NAME
               , CURRENT_SCHEMA() AS SCHEMA_NAME
               , INET_CLIENT_ADDR() AS CLIENT_ADDR
               , CURRENT_SETTING('application_name') AS APP_NAME
               , CURRENT_DATABASE() AS DB_NAME"""),
      new Example("세션 · 락", "세션이 지금 무엇을 기다리나 (대기 이벤트)", "느린 쿼리가 디스크 · 락 · 네트워크 중 무엇에 붙잡혀 있는지. 놀고 있는(Idle) 세션은 뺍니다.",
          """
          SELECT
                 s.SID
               , s.USERNAME
               , s.PROGRAM
               , s.EVENT
               , s.WAIT_CLASS
               , s.SECONDS_IN_WAIT
               , s.SQL_ID
            FROM V$SESSION s
           WHERE s.STATUS = 'ACTIVE'
             AND s.TYPE = 'USER'
             AND s.WAIT_CLASS <> 'Idle'
           ORDER BY
                 s.SECONDS_IN_WAIT DESC""",
          """
          SELECT
                 a.PID
               , a.USENAME
               , a.WAIT_EVENT_TYPE
               , a.WAIT_EVENT
               , NOW() - a.QUERY_START AS RUNNING
               , a.QUERY
            FROM PG_STAT_ACTIVITY a
           WHERE a.STATE = 'active'
             AND a.PID <> PG_BACKEND_PID()
           ORDER BY
                 a.QUERY_START"""),
      new Example("세션 · 락", "오래 걸리는 작업 진행률 (몇 % · 남은 시간)", "큰 표 전체 읽기 · 색인 만들기 · 통계 수집이 얼마나 남았는지. PostgreSQL 은 색인 만들기 진행률이고, VACUUM 은 PG_STAT_PROGRESS_VACUUM 에 있습니다.",
          """
          SELECT
                 l.SID
               , l.OPNAME
               , l.TARGET
               , l.SOFAR
               , l.TOTALWORK
               , ROUND(l.SOFAR / NULLIF(l.TOTALWORK, 0) * 100, 1) AS PCT
               , l.ELAPSED_SECONDS
               , l.TIME_REMAINING
            FROM V$SESSION_LONGOPS l
           WHERE l.SOFAR < l.TOTALWORK
           ORDER BY
                 l.START_TIME DESC""",
          """
          SELECT
                 p.PID
               , p.RELID::REGCLASS AS TABLE_NAME
               , p.PHASE
               , p.BLOCKS_DONE
               , p.BLOCKS_TOTAL
               , ROUND(100.0 * p.BLOCKS_DONE / NULLIF(p.BLOCKS_TOTAL, 0), 1) AS PCT
            FROM PG_STAT_PROGRESS_CREATE_INDEX p"""),
      new Example("세션 · 락", "TEMP 많이 쓰는 세션 (정렬 · 해시가 큰 쿼리)", "ORA-01652(TEMP 모자람)가 날 때 누가 쓰는지. PostgreSQL 은 세션별이 아니라 DB 누계입니다.",
          """
          SELECT
                 s.SID
               , s.USERNAME
               , s.PROGRAM
               , u.TABLESPACE
               , u.SEGTYPE
               , ROUND(u.BLOCKS * TO_NUMBER(p.VALUE) / 1024 / 1024) AS USED_MB
               , u.SQL_ID
            FROM V$TEMPSEG_USAGE u
               , V$SESSION s
               , V$PARAMETER p
           WHERE u.SESSION_ADDR = s.SADDR
             AND p.NAME = 'db_block_size'
           ORDER BY
                 USED_MB DESC""",
          """
          SELECT
                 d.DATNAME
               , d.TEMP_FILES
               , PG_SIZE_PRETTY(d.TEMP_BYTES) AS TEMP_SIZE
               , d.STATS_RESET
            FROM PG_STAT_DATABASE d
           WHERE d.DATNAME = CURRENT_DATABASE()"""),
      new Example("세션 · 락", "열린 커서 많은 세션 (ORA-01000 최대 커서 넘음)", "ResultSet · Statement 를 안 닫는 프로그램을 찾습니다. PostgreSQL 은 커서 한도 대신 접속 수와 한도를 봅니다.",
          """
          SELECT
                 s.SID
               , s.USERNAME
               , s.MACHINE
               , s.PROGRAM
               , t.VALUE AS OPEN_CURSORS
               , (SELECT p.VALUE FROM V$PARAMETER p WHERE p.NAME = 'open_cursors') AS LIMIT_CURSORS
            FROM V$SESSTAT t
               , V$STATNAME n
               , V$SESSION s
           WHERE t.STATISTIC# = n.STATISTIC#
             AND n.NAME = 'opened cursors current'
             AND t.SID = s.SID
           ORDER BY
                 t.VALUE DESC""",
          """
          SELECT
                 a.APPLICATION_NAME
               , a.CLIENT_ADDR
               , COUNT(*) AS CONN_CNT
               , CURRENT_SETTING('max_connections') AS LIMIT_CONN
            FROM PG_STAT_ACTIVITY a
           WHERE a.BACKEND_TYPE = 'client backend'
           GROUP BY
                 a.APPLICATION_NAME
               , a.CLIENT_ADDR
           ORDER BY
                 CONN_CNT DESC"""),
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
      new Example("성능", "색인을 타게 날짜 조건 쓰기", "TRUNC(칼럼) = 날짜 는 색인을 못 탑니다. 칼럼은 그대로 두고 범위로 씁니다.",
          """
          SELECT
                 o.ORDER_ID
            FROM ORDERS o
           WHERE o.REG_DT >= TO_DATE(:day, 'YYYY-MM-DD')
             AND o.REG_DT < TO_DATE(:day, 'YYYY-MM-DD') + 1""",
          """
          SELECT
                 o.ORDER_ID
            FROM ORDERS o
           WHERE o.REG_DT >= CAST(:day AS DATE)
             AND o.REG_DT < CAST(:day AS DATE) + 1"""),
      new Example("성능", "형 바뀜 막기 (숫자 칼럼에 글자 비교)", "칼럼 형과 값 형이 다르면 DB 가 칼럼 쪽을 바꿔 색인을 못 탈 수 있습니다. 값 쪽을 칼럼 형에 맞춥니다.",
          """
          SELECT
                 o.ORDER_ID
            FROM ORDERS o
           WHERE o.ORDER_ID = TO_NUMBER(:orderId)""",
          """
          SELECT
                 o.ORDER_ID
            FROM ORDERS o
           WHERE o.ORDER_ID = CAST(:orderId AS INTEGER)"""),
      new Example("성능", "OR 대신 UNION ALL (색인 둘을 각각 타게)", "서로 다른 칼럼의 OR 는 전체 훑기가 되기 쉽습니다. 둘로 나눠 붙이고 겹치는 줄은 뺍니다.",
          """
          SELECT
                 o.ORDER_ID
            FROM ORDERS o
           WHERE o.CUST_ID = :custId
           UNION ALL
          SELECT
                 o.ORDER_ID
            FROM ORDERS o
           WHERE o.STATUS = :status
             AND (o.CUST_ID <> :custId OR o.CUST_ID IS NULL)""",
          """
          SELECT
                 o.ORDER_ID
            FROM ORDERS o
           WHERE o.CUST_ID = CAST(:custId AS INTEGER)
           UNION ALL
          SELECT
                 o.ORDER_ID
            FROM ORDERS o
           WHERE o.STATUS = :status
             AND (o.CUST_ID <> CAST(:custId AS INTEGER) OR o.CUST_ID IS NULL)"""),
      new Example("성능", "COUNT 대신 EXISTS (있는지만 볼 때)", "있는지만 알면 될 때 COUNT(*) 는 끝까지 셉니다. 하나 찾으면 멈추는 EXISTS 가 빠릅니다.",
          """
          SELECT
                 CASE WHEN EXISTS (SELECT 1 FROM ISSUE i WHERE i.PROJ_ID = :projId AND i.STATUS <> 'CLOSE') THEN 'Y' ELSE 'N' END AS HAS_OPEN
            FROM DUAL""",
          """
          SELECT
                 CASE WHEN EXISTS (SELECT 1 FROM ISSUE i WHERE i.PROJ_ID = CAST(:projId AS INTEGER) AND i.STATUS <> 'CLOSE') THEN 'Y' ELSE 'N' END AS HAS_OPEN"""),
      new Example("성능", "실행 계획 실제 값으로 보기", "예상이 아니라 실제로 돌린 시간 · 건수로 계획을 봅니다. 실제로 실행되니 UPDATE · DELETE 에는 쓰지 마세요.",
          """
          SELECT /*+ GATHER_PLAN_STATISTICS */
                 o.STATUS
               , COUNT(*)
            FROM ORDERS o
           GROUP BY
                 o.STATUS""",
          """
          EXPLAIN (ANALYZE, BUFFERS)
          SELECT
                 o.STATUS
               , COUNT(*)
            FROM ORDERS o
           GROUP BY
                 o.STATUS"""),
      new Example("성능", "SQL_ID 로 실제 실행 계획 (DISPLAY_CURSOR)", "이미 돈 SQL 의 실제 계획. 실제 건수(A-Rows)까지 보려면 그 SQL 에 /*+ GATHER_PLAN_STATISTICS */ 를 붙여 돌린 뒤 봅니다. PostgreSQL 은 캐시된 계획을 꺼낼 수 없어 EXPLAIN ANALYZE 로 다시 돌립니다.",
          """
          SELECT
                 p.PLAN_TABLE_OUTPUT
            FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(:sqlId, NULL, 'ALLSTATS LAST')) p""",
          """
          EXPLAIN (ANALYZE, BUFFERS)
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.STATUS = 'NEW'"""),
      new Example("성능", "글자로 SQL_ID 찾기 (매퍼 SQL 이 캐시에 있나)", "표 이름이나 매퍼 SQL 의 특이한 글자로 DB 에 남은 SQL 을 찾습니다. 찾은 SQL_ID 로 실행 계획 · 바인드 값을 봅니다.",
          """
          SELECT
                 q.SQL_ID
               , q.CHILD_NUMBER
               , q.EXECUTIONS
               , ROUND(q.ELAPSED_TIME / NULLIF(q.EXECUTIONS, 0) / 1000, 1) AS AVG_MS
               , q.LAST_ACTIVE_TIME
               , q.SQL_TEXT
            FROM V$SQL q
           WHERE UPPER(q.SQL_TEXT) LIKE '%' || UPPER(:keyword) || '%'
             AND q.SQL_TEXT NOT LIKE '%V$SQL%'
           ORDER BY
                 q.LAST_ACTIVE_TIME DESC""",
          """
          SELECT
                 s.QUERYID
               , s.CALLS
               , ROUND(CAST(s.MEAN_EXEC_TIME AS NUMERIC), 1) AS AVG_MS
               , s.QUERY
            FROM PG_STAT_STATEMENTS s
           WHERE s.QUERY ILIKE '%' || :keyword || '%'
           ORDER BY
                 s.CALLS DESC"""),
      new Example("성능", "디스크를 많이 읽는 SQL (상위 20)", "디스크 읽기 · 메모리 읽기가 많은 SQL 순. 색인이 없거나 범위가 넓은 쿼리가 위로 옵니다.",
          """
          SELECT *
            FROM (
                  SELECT
                         q.SQL_ID
                       , q.EXECUTIONS
                       , q.DISK_READS
                       , q.BUFFER_GETS
                       , ROUND(q.BUFFER_GETS / NULLIF(q.EXECUTIONS, 0)) AS GETS_PER_EXEC
                       , q.SQL_TEXT
                    FROM V$SQL q
                   ORDER BY
                         q.DISK_READS DESC
                 )
           WHERE ROWNUM <= 20""",
          """
          SELECT
                 s.QUERYID
               , s.CALLS
               , s.SHARED_BLKS_READ
               , s.SHARED_BLKS_HIT
               , s.QUERY
            FROM PG_STAT_STATEMENTS s
           ORDER BY
                 s.SHARED_BLKS_READ DESC
           LIMIT 20"""),
      new Example("성능", "실행 횟수가 많은 SQL (상위 20)", "한 번은 빨라도 화면마다 수천 번 부르는 SQL. 반복문 안 조회(N+1)를 찾을 때 씁니다.",
          """
          SELECT *
            FROM (
                  SELECT
                         q.SQL_ID
                       , q.EXECUTIONS
                       , ROUND(q.ELAPSED_TIME / NULLIF(q.EXECUTIONS, 0) / 1000, 2) AS AVG_MS
                       , ROUND(q.ELAPSED_TIME / 1000000, 1) AS TOTAL_SEC
                       , q.SQL_TEXT
                    FROM V$SQL q
                   ORDER BY
                         q.EXECUTIONS DESC
                 )
           WHERE ROWNUM <= 20""",
          """
          SELECT
                 s.QUERYID
               , s.CALLS
               , ROUND(CAST(s.MEAN_EXEC_TIME AS NUMERIC), 2) AS AVG_MS
               , ROUND(CAST(s.TOTAL_EXEC_TIME / 1000 AS NUMERIC), 1) AS TOTAL_SEC
               , s.QUERY
            FROM PG_STAT_STATEMENTS s
           ORDER BY
                 s.CALLS DESC
           LIMIT 20"""),
      new Example("성능", "한 번 돌 때 오래 걸리는 SQL (1회 평균 상위 20)", "실행 횟수와 상관없이 한 번에 느린 SQL. 화면이 느리다는 말이 나올 때 먼저 봅니다.",
          """
          SELECT *
            FROM (
                  SELECT
                         q.SQL_ID
                       , q.EXECUTIONS
                       , ROUND(q.ELAPSED_TIME / q.EXECUTIONS / 1000) AS AVG_MS
                       , ROUND(q.ROWS_PROCESSED / q.EXECUTIONS) AS ROWS_PER_EXEC
                       , q.SQL_TEXT
                    FROM V$SQL q
                   WHERE q.EXECUTIONS > 0
                   ORDER BY
                         q.ELAPSED_TIME / q.EXECUTIONS DESC
                 )
           WHERE ROWNUM <= 20""",
          """
          SELECT
                 s.QUERYID
               , s.CALLS
               , ROUND(CAST(s.MEAN_EXEC_TIME AS NUMERIC)) AS AVG_MS
               , ROUND(s.ROWS::NUMERIC / s.CALLS) AS ROWS_PER_EXEC
               , s.QUERY
            FROM PG_STAT_STATEMENTS s
           WHERE s.CALLS > 0
           ORDER BY
                 s.MEAN_EXEC_TIME DESC
           LIMIT 20"""),
      new Example("성능", "SQL 에 들어간 바인드 값 보기", "느린 SQL 이 어떤 값으로 불렸는지. Oracle 은 잡아 둔 값만(기본 15분 간격) 보입니다. PostgreSQL 은 값을 남기지 않아 느린 SQL 을 로그에 남기는 설정을 봅니다.",
          """
          SELECT
                 b.CHILD_NUMBER
               , b.POSITION
               , b.NAME
               , b.DATATYPE_STRING
               , b.VALUE_STRING
               , b.LAST_CAPTURED
            FROM V$SQL_BIND_CAPTURE b
           WHERE b.SQL_ID = :sqlId
           ORDER BY
                 b.CHILD_NUMBER
               , b.POSITION""",
          """
          SELECT
                 s.NAME
               , s.SETTING
               , s.UNIT
            FROM PG_SETTINGS s
           WHERE s.NAME IN ('log_min_duration_statement', 'log_statement', 'log_parameter_max_length')"""),
      new Example("성능", "표 전체를 훑는 SQL (TABLE ACCESS FULL)", "실행 계획에 전체 읽기가 있는 SQL 을 실행 횟수 순으로. 큰 표가 나오면 색인을 볼 차례입니다.",
          """
          SELECT
                 p.OBJECT_NAME AS TABLE_NAME
               , q.SQL_ID
               , q.EXECUTIONS
               , ROUND(q.ELAPSED_TIME / NULLIF(q.EXECUTIONS, 0) / 1000, 1) AS AVG_MS
               , q.SQL_TEXT
            FROM V$SQL_PLAN p
               , V$SQL q
           WHERE p.SQL_ID = q.SQL_ID
             AND p.CHILD_NUMBER = q.CHILD_NUMBER
             AND p.OPERATION = 'TABLE ACCESS'
             AND p.OPTIONS = 'FULL'
             AND p.OBJECT_OWNER = USER
           ORDER BY
                 q.EXECUTIONS DESC""",
          """
          SELECT
                 s.RELNAME AS TABLE_NAME
               , s.SEQ_SCAN
               , s.SEQ_TUP_READ
               , s.IDX_SCAN
               , s.N_LIVE_TUP
            FROM PG_STAT_USER_TABLES s
           ORDER BY
                 s.SEQ_TUP_READ DESC"""),
      new Example("성능", "이 표를 쓰는 SQL (캐시에서)", "표를 바꾸기 전에 지금 이 표를 읽는 SQL 과 횟수를 봅니다.",
          """
          SELECT
                 q.SQL_ID
               , q.EXECUTIONS
               , ROUND(q.ELAPSED_TIME / NULLIF(q.EXECUTIONS, 0) / 1000, 1) AS AVG_MS
               , q.SQL_TEXT
            FROM V$SQL q
           WHERE q.SQL_ID IN (
                  SELECT p.SQL_ID
                    FROM V$SQL_PLAN p
                   WHERE p.OBJECT_NAME = UPPER(:tableName)
                     AND p.OBJECT_OWNER = USER
                 )
           ORDER BY
                 q.EXECUTIONS DESC""",
          """
          SELECT
                 s.QUERYID
               , s.CALLS
               , ROUND(CAST(s.MEAN_EXEC_TIME AS NUMERIC), 1) AS AVG_MS
               , s.QUERY
            FROM PG_STAT_STATEMENTS s
           WHERE s.QUERY ILIKE '%' || :tableName || '%'
           ORDER BY
                 s.CALLS DESC"""),
      new Example("성능", "바인드 없이 값을 박은 SQL (같은 꼴 여러 벌)", "MyBatis 의 ${} 처럼 값을 글로 박으면 같은 SQL 이 값마다 따로 파싱됩니다. 꼴이 같은 SQL 이 많은 순. PostgreSQL 은 한 번만 돈 SQL 을 봅니다.",
          """
          SELECT *
            FROM (
                  SELECT
                         q.FORCE_MATCHING_SIGNATURE
                       , COUNT(*) AS SQL_CNT
                       , MIN(q.SQL_ID) AS SAMPLE_SQL_ID
                       , MIN(DBMS_LOB.SUBSTR(q.SQL_FULLTEXT, 200, 1)) AS SAMPLE_SQL
                    FROM V$SQL q
                   WHERE q.FORCE_MATCHING_SIGNATURE > 0
                     AND q.FORCE_MATCHING_SIGNATURE <> q.EXACT_MATCHING_SIGNATURE
                   GROUP BY
                         q.FORCE_MATCHING_SIGNATURE
                  HAVING COUNT(*) > 10
                   ORDER BY
                         SQL_CNT DESC
                 )
           WHERE ROWNUM <= 20""",
          """
          SELECT
                 s.QUERYID
               , s.CALLS
               , s.QUERY
            FROM PG_STAT_STATEMENTS s
           WHERE s.CALLS = 1
           ORDER BY
                 s.TOTAL_EXEC_TIME DESC
           LIMIT 50"""),
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
      new Example("정합성 점검", "합계 표와 상세 표 합이 다른 것", "주문 금액과 품목 합계가 다른 주문. 이관 · 배치 뒤 점검에 씁니다.",
          """
          SELECT
                 o.ORDER_ID
               , o.AMOUNT
               , s.ITEM_SUM
               , o.AMOUNT - s.ITEM_SUM AS DIFF
            FROM ORDERS o
               , (SELECT i.ORDER_ID, SUM(i.QTY * i.PRICE) AS ITEM_SUM FROM ORDER_ITEM i GROUP BY i.ORDER_ID) s
           WHERE o.ORDER_ID = s.ORDER_ID
             AND o.AMOUNT <> s.ITEM_SUM""",
          """
          SELECT
                 o.ORDER_ID
               , o.AMOUNT
               , s.ITEM_SUM
               , o.AMOUNT - s.ITEM_SUM AS DIFF
            FROM ORDERS o
               , (SELECT i.ORDER_ID, SUM(i.QTY * i.PRICE) AS ITEM_SUM FROM ORDER_ITEM i GROUP BY i.ORDER_ID) s
           WHERE o.ORDER_ID = s.ORDER_ID
             AND o.AMOUNT <> s.ITEM_SUM"""),
      new Example("정합성 점검", "코드 표에 없는 코드 값", "부품 단위 칼럼에 코드 표에 없는(또는 안 쓰는) 값이 들어간 것.",
          """
          SELECT
                 p.PART_NO
               , p.UNIT_CD
            FROM PART p
           WHERE p.UNIT_CD IS NOT NULL
             AND NOT EXISTS (
                  SELECT 1
                    FROM COMMON_CODE c
                   WHERE c.GROUP_CD = 'UNIT'
                     AND c.CODE = p.UNIT_CD
                     AND c.USE_YN = 'Y'
                 )""",
          """
          SELECT
                 p.PART_NO
               , p.UNIT_CD
            FROM PART p
           WHERE p.UNIT_CD IS NOT NULL
             AND NOT EXISTS (
                  SELECT 1
                    FROM COMMON_CODE c
                   WHERE c.GROUP_CD = 'UNIT'
                     AND c.CODE = p.UNIT_CD
                     AND c.USE_YN = 'Y'
                 )"""),
      new Example("정합성 점검", "시작일이 끝일보다 늦은 것", "날짜 순서가 거꾸로 된 일정 · 이력. 화면 검증이 없던 시절 데이터에서 자주 나옵니다.",
          """
          SELECT
                 s.SCH_ID
               , s.PLAN_START
               , s.PLAN_END
               , s.ACT_START
               , s.ACT_END
            FROM SCHEDULE s
           WHERE s.PLAN_START > s.PLAN_END
              OR s.ACT_START > s.ACT_END""",
          """
          SELECT
                 s.SCH_ID
               , s.PLAN_START
               , s.PLAN_END
               , s.ACT_START
               , s.ACT_END
            FROM SCHEDULE s
           WHERE s.PLAN_START > s.PLAN_END
              OR s.ACT_START > s.ACT_END"""),
      new Example("정합성 점검", "음수 · 0 · 너무 큰 값 찾기", "수량 · 금액이 말이 안 되는 줄. 기준 값은 업무에 맞게 바꿉니다.",
          """
          SELECT
                 i.ITEM_ID
               , i.QTY
               , i.PRICE
            FROM ORDER_ITEM i
           WHERE i.QTY <= 0
              OR i.PRICE < 0
              OR i.QTY * i.PRICE > 100000000""",
          """
          SELECT
                 i.ITEM_ID
               , i.QTY
               , i.PRICE
            FROM ORDER_ITEM i
           WHERE i.QTY <= 0
              OR i.PRICE < 0
              OR i.QTY * i.PRICE > 100000000"""),
      new Example("정합성 점검", "부모 상태와 자식 상태가 어긋난 것", "프로젝트는 완료인데 안 끝난 이슈가 남아 있는 것.",
          """
          SELECT
                 p.PROJ_ID
               , p.STATUS
               , COUNT(*) AS OPEN_ISSUES
            FROM PROJECT p
               , ISSUE i
           WHERE p.PROJ_ID = i.PROJ_ID
             AND p.STATUS = 'DONE'
             AND i.STATUS <> 'CLOSE'
           GROUP BY
                 p.PROJ_ID
               , p.STATUS""",
          """
          SELECT
                 p.PROJ_ID
               , p.STATUS
               , COUNT(*) AS OPEN_ISSUES
            FROM PROJECT p
               , ISSUE i
           WHERE p.PROJ_ID = i.PROJ_ID
             AND p.STATUS = 'DONE'
             AND i.STATUS <> 'CLOSE'
           GROUP BY
                 p.PROJ_ID
               , p.STATUS"""),
      new Example("정합성 점검", "조인이 안 맞는 키 (앞뒤 공백 · 줄바꿈이 섞인 값)", "눈으로는 같은데 조인 · = 비교가 안 맞을 때. 앞뒤 공백, 탭, 줄바꿈이 섞인 키를 찾습니다.",
          """
          SELECT
                 p.PART_NO
               , LENGTH(p.PART_NO) AS LEN
               , DUMP(p.PART_NO) AS RAW_CODES
            FROM PART p
           WHERE p.PART_NO <> TRIM(p.PART_NO)
              OR INSTR(p.PART_NO, CHR(9)) > 0
              OR INSTR(p.PART_NO, CHR(10)) > 0
              OR INSTR(p.PART_NO, CHR(13)) > 0""",
          """
          SELECT
                 p.PART_NO
               , LENGTH(p.PART_NO) AS LEN
               , ENCODE(CONVERT_TO(p.PART_NO, 'UTF8'), 'hex') AS RAW_CODES
            FROM PART p
           WHERE p.PART_NO <> BTRIM(p.PART_NO)
              OR p.PART_NO ~ '[\\t\\r\\n]'"""),
      new Example("정합성 점검", "칼럼 길이가 꽉 찬 값 (잘려 들어간 의심)", "길이 한도에 딱 맞는 값은 넣다가 잘렸을 수 있습니다. Oracle 은 바이트, PostgreSQL 은 글자 수 기준.",
          """
          SELECT
                 o.ORDER_ID
               , o.REMARK
               , LENGTHB(o.REMARK) AS BYTE_LEN
               , c.DATA_LENGTH AS MAX_LEN
            FROM ORDERS o
               , USER_TAB_COLUMNS c
           WHERE c.TABLE_NAME = 'ORDERS'
             AND c.COLUMN_NAME = 'REMARK'
             AND LENGTHB(o.REMARK) >= c.DATA_LENGTH""",
          """
          SELECT
                 o.ORDER_ID
               , o.REMARK
               , LENGTH(o.REMARK) AS CHAR_LEN
               , c.CHARACTER_MAXIMUM_LENGTH AS MAX_LEN
            FROM ORDERS o
               , INFORMATION_SCHEMA.COLUMNS c
           WHERE c.TABLE_SCHEMA = CURRENT_SCHEMA()
             AND c.TABLE_NAME = 'orders'
             AND c.COLUMN_NAME = 'remark'
             AND LENGTH(o.REMARK) >= c.CHARACTER_MAXIMUM_LENGTH"""),
      new Example("정합성 점검", "한글이 깨진 값 (? · � 가 들어간 것)", "문자셋이 다른 곳에서 넘어와 한글이 ? 나 � 로 바뀐 줄을 찾습니다.",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
            FROM PART p
           WHERE p.PART_NAME LIKE '%?%'
              OR INSTR(p.PART_NAME, UNISTR('\\FFFD')) > 0""",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
            FROM PART p
           WHERE p.PART_NAME LIKE '%?%'
              OR POSITION(CHR(65533) IN p.PART_NAME) > 0"""),
      new Example("정합성 점검", "날짜 칼럼에 시각이 섞인 줄 (= 비교가 안 맞을 때)", "날짜만 넣는다던 칼럼에 시 · 분이 들어 있으면 WHERE 날짜 = :day 가 빠뜨립니다.",
          """
          SELECT
                 o.ORDER_ID
               , TO_CHAR(o.ORDER_DATE, 'YYYY-MM-DD HH24:MI:SS') AS ORDER_DATE
            FROM ORDERS o
           WHERE o.ORDER_DATE <> TRUNC(o.ORDER_DATE)""",
          """
          SELECT
                 o.ORDER_ID
               , TO_CHAR(o.ORDER_DATE, 'YYYY-MM-DD HH24:MI:SS') AS ORDER_DATE
            FROM ORDERS o
           WHERE o.ORDER_DATE <> DATE_TRUNC('day', o.ORDER_DATE)"""),
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
            FROM ORDERS o"""),
      new Example("JSON", "줄 여러 개를 JSON 배열 하나로", "부품 목록을 화면에 줄 JSON 배열 하나로 만듭니다.",
          """
          SELECT
                 JSON_ARRAYAGG(JSON_OBJECT('partNo' VALUE p.PART_NO, 'name' VALUE p.PART_NAME) ORDER BY p.PART_NO) AS JSON_TEXT
            FROM PART p""",
          """
          SELECT
                 JSON_AGG(JSON_BUILD_OBJECT('partNo', p.PART_NO, 'name', p.PART_NAME) ORDER BY p.PART_NO) AS JSON_TEXT
            FROM PART p"""),
      new Example("JSON", "부모 아래 자식을 JSON 으로 묶기", "주문마다 품목 배열을 붙여 한 줄로. 화면 상세 응답 꼴입니다.",
          """
          SELECT
                 o.ORDER_ID
               , JSON_OBJECT(
                   'orderId' VALUE o.ORDER_ID
                 , 'items' VALUE (SELECT JSON_ARRAYAGG(JSON_OBJECT('partNo' VALUE i.PART_NO, 'qty' VALUE i.QTY)) FROM ORDER_ITEM i WHERE i.ORDER_ID = o.ORDER_ID)
                 ) AS JSON_TEXT
            FROM ORDERS o""",
          """
          SELECT
                 o.ORDER_ID
               , JSON_BUILD_OBJECT(
                   'orderId', o.ORDER_ID
                 , 'items', (SELECT JSON_AGG(JSON_BUILD_OBJECT('partNo', i.PART_NO, 'qty', i.QTY)) FROM ORDER_ITEM i WHERE i.ORDER_ID = o.ORDER_ID)
                 ) AS JSON_TEXT
            FROM ORDERS o"""),
      new Example("JSON", "JSON 배열을 줄로 펼치기", "글자로 받은 JSON 배열을 표처럼 펼쳐 칸으로 씁니다.",
          """
          SELECT
                 j.PART_NO
               , j.QTY
            FROM JSON_TABLE('[{"partNo":"P001","qty":2},{"partNo":"P002","qty":5}]', '$[*]'
                   COLUMNS (PART_NO VARCHAR2(30) PATH '$.partNo', QTY NUMBER PATH '$.qty')) j""",
          """
          SELECT
                 J ->> 'partNo' AS PART_NO
               , CAST(J ->> 'qty' AS NUMERIC) AS QTY
            FROM JSON_ARRAY_ELEMENTS('[{"partNo":"P001","qty":2},{"partNo":"P002","qty":5}]') AS J"""),
      new Example("BOM · 부품", "BOM 정전개 (완제품 → 부품)", "완제품 하나에 들어가는 부품을 아래로 펼치고, 단계마다 수량을 곱해 완제품 1개당 필요 수량(TOTAL_QTY)을 냅니다. 곱셈이 필요해 CONNECT BY 대신 재귀 WITH 로 씁니다.",
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
      new Example("BOM · 부품", "BOM 부품별 총 필요 수량 (최하위 합계)", "완제품 1개에 최하위 부품이 모두 몇 개 필요한지 부품마다 합칩니다(여러 곳에 쓰인 볼트를 합산).",
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
      new Example("BOM · 부품", "BOM 원가 쌓기 (부품 단가 × 수량)", "최하위 부품 단가에 필요 수량을 곱해 완제품 1개 원가를 냅니다.",
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
      new Example("BOM · 부품", "BOM 역전개 (이 부품이 어디에 쓰이나)", "부품 하나에서 위로 올라가 그 부품이 들어가는 모든 상위 품목을 봅니다(설계 변경 영향 범위).",
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
          WITH RECURSIVE UP AS (
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
            JOIN UP u
              ON b.CHILD_PART = u.PARENT_PART
           WHERE u.UP < 100
          )
          SELECT
                 u.*
            FROM UP u
           WHERE u.PARENT_PART IS NOT NULL"""),
      new Example("BOM · 부품", "레벨 번호 붙인 BOM 표 (1, 1.1, 1.1.2)", "BOM 을 펼치면서 화면에 보이는 번호(1.2.1)를 만듭니다. 형제 순서는 SEQ 로 정합니다.",
          """
          WITH t (LVL, PARENT_PART, CHILD_PART, QTY, NO_PATH, SORT_KEY) AS (
          SELECT
                 1
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , CAST(ROW_NUMBER() OVER (ORDER BY b.SEQ) AS VARCHAR2(200))
               , CAST(LPAD(b.SEQ, 4, '0') AS VARCHAR2(400))
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
             AND b.REV = :rev
           UNION ALL
          SELECT
                 t.LVL + 1
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.NO_PATH || '.' || ROW_NUMBER() OVER (PARTITION BY b.PARENT_PART ORDER BY b.SEQ)
               , t.SORT_KEY || '/' || LPAD(b.SEQ, 4, '0')
            FROM BOM b, t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.NO_PATH
               , LPAD(' ', (t.LVL - 1) * 2) || t.CHILD_PART AS PART_NO
               , t.QTY
               , t.LVL
            FROM t
           ORDER BY
                 t.SORT_KEY""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 1 AS LVL
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , CAST(ROW_NUMBER() OVER (ORDER BY b.SEQ) AS VARCHAR(200)) AS NO_PATH
               , CAST(LPAD(CAST(b.SEQ AS VARCHAR), 4, '0') AS VARCHAR(400)) AS SORT_KEY
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
             AND b.REV = :rev
           UNION ALL
          SELECT
                 t.LVL + 1
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , CAST(t.NO_PATH || '.' || ROW_NUMBER() OVER (PARTITION BY b.PARENT_PART ORDER BY b.SEQ) AS VARCHAR(200))
               , CAST(t.SORT_KEY || '/' || LPAD(CAST(b.SEQ AS VARCHAR), 4, '0') AS VARCHAR(400))
            FROM BOM b, t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.NO_PATH
               , REPEAT(' ', (t.LVL - 1) * 2) || t.CHILD_PART AS PART_NO
               , t.QTY
               , t.LVL
            FROM t
           ORDER BY
                 t.SORT_KEY"""),
      new Example("BOM · 부품", "두 BOM 비교 (리비전 A ↔ B: 추가 · 삭제 · 수량 바뀜)", "같은 모부품의 두 리비전을 나란히 놓고 달라진 줄만 봅니다. 양쪽 다 보려고 FULL OUTER JOIN 을 씁니다.",
          """
          SELECT
                 NVL(a.CHILD_PART, b.CHILD_PART) AS CHILD_PART
               , a.QTY AS QTY_A
               , b.QTY AS QTY_B
               , CASE
                   WHEN a.CHILD_PART IS NULL THEN '추가'
                   WHEN b.CHILD_PART IS NULL THEN '삭제'
                   ELSE '수량 바뀜'
                 END AS DIFF
            FROM (SELECT x.CHILD_PART, x.QTY FROM BOM x WHERE x.PARENT_PART = :part AND x.REV = :revA) a
            FULL OUTER JOIN (SELECT y.CHILD_PART, y.QTY FROM BOM y WHERE y.PARENT_PART = :part AND y.REV = :revB) b
              ON a.CHILD_PART = b.CHILD_PART
           WHERE a.CHILD_PART IS NULL
              OR b.CHILD_PART IS NULL
              OR a.QTY <> b.QTY
           ORDER BY
                 1""",
          """
          SELECT
                 COALESCE(a.CHILD_PART, b.CHILD_PART) AS CHILD_PART
               , a.QTY AS QTY_A
               , b.QTY AS QTY_B
               , CASE
                   WHEN a.CHILD_PART IS NULL THEN '추가'
                   WHEN b.CHILD_PART IS NULL THEN '삭제'
                   ELSE '수량 바뀜'
                 END AS DIFF
            FROM (SELECT x.CHILD_PART, x.QTY FROM BOM x WHERE x.PARENT_PART = :part AND x.REV = :revA) a
            FULL OUTER JOIN (SELECT y.CHILD_PART, y.QTY FROM BOM y WHERE y.PARENT_PART = :part AND y.REV = :revB) b
              ON a.CHILD_PART = b.CHILD_PART
           WHERE a.CHILD_PART IS NULL
              OR b.CHILD_PART IS NULL
              OR a.QTY <> b.QTY
           ORDER BY
                 1"""),
      new Example("BOM · 부품", "유효 일자 기준 BOM 전개 (그날 살아 있던 부품만)", "BOM 줄마다 VALID_FROM ~ VALID_TO 가 있을 때 기준일에 유효한 줄만 따라 내려갑니다.",
          """
          SELECT
                 LEVEL AS LVL
               , LPAD(' ', (LEVEL - 1) * 2) || b.CHILD_PART AS PART_NO
               , b.QTY
               , b.VALID_FROM
               , b.VALID_TO
            FROM BOM b
           START WITH b.PARENT_PART = :topPart
                  AND TO_DATE(:baseDt, 'YYYY-MM-DD') BETWEEN b.VALID_FROM AND b.VALID_TO
          CONNECT BY PRIOR b.CHILD_PART = b.PARENT_PART
                 AND TO_DATE(:baseDt, 'YYYY-MM-DD') BETWEEN b.VALID_FROM AND b.VALID_TO
           ORDER SIBLINGS BY
                 b.SEQ""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 1 AS LVL
               , b.CHILD_PART
               , b.QTY
               , b.VALID_FROM
               , b.VALID_TO
               , CAST(LPAD(CAST(b.SEQ AS VARCHAR), 4, '0') AS VARCHAR(400)) AS SORT_KEY
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
             AND TO_DATE(:baseDt, 'YYYY-MM-DD') BETWEEN b.VALID_FROM AND b.VALID_TO
           UNION ALL
          SELECT
                 t.LVL + 1
               , b.CHILD_PART
               , b.QTY
               , b.VALID_FROM
               , b.VALID_TO
               , CAST(t.SORT_KEY || '/' || LPAD(CAST(b.SEQ AS VARCHAR), 4, '0') AS VARCHAR(400))
            FROM BOM b, t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND TO_DATE(:baseDt, 'YYYY-MM-DD') BETWEEN b.VALID_FROM AND b.VALID_TO
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , REPEAT(' ', (t.LVL - 1) * 2) || t.CHILD_PART AS PART_NO
               , t.QTY
               , t.VALID_FROM
               , t.VALID_TO
            FROM t
           ORDER BY
                 t.SORT_KEY"""),
      new Example("BOM · 부품", "공통 부품 (여러 모부품에 쓰인 부품)", "두 곳 이상에 들어가는 부품. 단가 협상 · 표준화 대상을 고를 때 봅니다(Oracle LISTAGG DISTINCT 는 19c 이상).",
          """
          SELECT
                 b.CHILD_PART
               , p.PART_NAME
               , COUNT(DISTINCT b.PARENT_PART) AS USED_IN
               , LISTAGG(DISTINCT b.PARENT_PART, ', ') WITHIN GROUP (ORDER BY b.PARENT_PART) AS PARENTS
            FROM BOM b, PART p
           WHERE b.CHILD_PART = p.PART_NO
           GROUP BY
                 b.CHILD_PART
               , p.PART_NAME
          HAVING COUNT(DISTINCT b.PARENT_PART) >= 2
           ORDER BY
                 USED_IN DESC""",
          """
          SELECT
                 b.CHILD_PART
               , p.PART_NAME
               , COUNT(DISTINCT b.PARENT_PART) AS USED_IN
               , STRING_AGG(DISTINCT b.PARENT_PART, ', ' ORDER BY b.PARENT_PART) AS PARENTS
            FROM BOM b, PART p
           WHERE b.CHILD_PART = p.PART_NO
           GROUP BY
                 b.CHILD_PART
               , p.PART_NAME
          HAVING COUNT(DISTINCT b.PARENT_PART) >= 2
           ORDER BY
                 USED_IN DESC"""),
      new Example("BOM · 부품", "단종 부품의 영향 범위 (어느 완제품까지 닿나)", "단종(EOL) 부품에서 위로 거슬러 올라가 맨 위 완제품까지 찾습니다. 역전개에 단종 조건을 붙인 꼴입니다.",
          """
          SELECT DISTINCT
                 CONNECT_BY_ROOT b.CHILD_PART AS EOL_PART
               , b.PARENT_PART AS AFFECTED
               , LEVEL AS UP
            FROM BOM b
           START WITH b.CHILD_PART IN (SELECT p.PART_NO FROM PART p WHERE p.STATUS = 'EOL')
          CONNECT BY NOCYCLE b.CHILD_PART = PRIOR b.PARENT_PART
           ORDER BY
                 1
               , 3""",
          """
          WITH RECURSIVE up AS (
          SELECT
                 b.CHILD_PART AS EOL_PART
               , b.PARENT_PART AS AFFECTED
               , 1 AS UP
            FROM BOM b, PART p
           WHERE b.CHILD_PART = p.PART_NO
             AND p.STATUS = 'EOL'
           UNION
          SELECT
                 up.EOL_PART
               , b.PARENT_PART
               , up.UP + 1
            FROM BOM b, up
           WHERE b.CHILD_PART = up.AFFECTED
             AND up.UP < 20
          )
          SELECT DISTINCT
                 up.EOL_PART
               , up.AFFECTED
               , up.UP
            FROM up
           ORDER BY
                 1
               , 3"""),
      new Example("BOM · 부품", "대체 부품 함께 보기", "BOM 부품마다 대체 부품이 있으면 옆에 붙입니다. 대체가 없어도 줄은 남깁니다(아우터).",
          """
          SELECT
                 b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , s.SUB_PART_NO
               , s.PRIORITY
            FROM BOM b, PART_SUB s
           WHERE b.CHILD_PART = s.PART_NO(+)
             AND b.PARENT_PART = :part
           ORDER BY
                 b.SEQ
               , s.PRIORITY""",
          """
          SELECT
                 b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , s.SUB_PART_NO
               , s.PRIORITY
            FROM BOM b
            LEFT OUTER JOIN PART_SUB s
              ON b.CHILD_PART = s.PART_NO
           WHERE b.PARENT_PART = :part
           ORDER BY
                 b.SEQ
               , s.PRIORITY"""),
      new Example("BOM · 부품", "BOM 에 없는 부품 · 어디에도 안 쓰인 부품", "부품 마스터에는 있는데 BOM 의 모부품도 자식도 아닌 것. 정리 대상입니다.",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
               , p.REG_DT
            FROM PART p
           WHERE NOT EXISTS (SELECT 1 FROM BOM b WHERE b.CHILD_PART = p.PART_NO)
             AND NOT EXISTS (SELECT 1 FROM BOM b WHERE b.PARENT_PART = p.PART_NO)
           ORDER BY
                 p.PART_NO""",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
               , p.REG_DT
            FROM PART p
           WHERE NOT EXISTS (SELECT 1 FROM BOM b WHERE b.CHILD_PART = p.PART_NO)
             AND NOT EXISTS (SELECT 1 FROM BOM b WHERE b.PARENT_PART = p.PART_NO)
           ORDER BY
                 p.PART_NO"""),
      new Example("BOM · 부품", "최상위 완제품만 (누구의 자식도 아닌 모부품)", "BOM 맨 위에 있는 제품 목록. 역전개 · 정전개의 시작점을 고를 때 씁니다. 최상위를 모부품 NULL 줄로 두는 BOM 도 함께 거릅니다.",
          """
          SELECT DISTINCT
                 b.PARENT_PART
            FROM BOM b
           WHERE b.PARENT_PART IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM BOM x WHERE x.CHILD_PART = b.PARENT_PART AND x.PARENT_PART IS NOT NULL)
           ORDER BY
                 1""",
          """
          SELECT DISTINCT
                 b.PARENT_PART
            FROM BOM b
           WHERE b.PARENT_PART IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM BOM x WHERE x.CHILD_PART = b.PARENT_PART AND x.PARENT_PART IS NOT NULL)
           ORDER BY
                 1"""),
      new Example("BOM · 부품", "레벨별 부품 수 (BOM 깊이 요약)", "완제품 하나를 펼쳤을 때 단계마다 부품이 몇 개인지.",
          """
          SELECT
                 LEVEL AS LVL
               , COUNT(*) AS PARTS
            FROM BOM b
           START WITH b.PARENT_PART = :topPart
          CONNECT BY NOCYCLE PRIOR b.CHILD_PART = b.PARENT_PART
           GROUP BY
                 LEVEL
           ORDER BY
                 1""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 1 AS LVL
               , b.CHILD_PART
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.LVL + 1
               , b.CHILD_PART
            FROM BOM b, t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , COUNT(*) AS PARTS
            FROM t
           GROUP BY
                 t.LVL
           ORDER BY
                 1"""),
      new Example("BOM · 부품", "부품 상세 한 화면 (기본 · 최신 리비전 · 협력사 · 재고)", "부품 하나에 흩어진 정보를 한 줄로 모읍니다. 상세 화면 조회 쿼리의 기본 꼴.",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
               , (SELECT MAX(r.REV) FROM PART_REV r WHERE r.PART_NO = p.PART_NO) AS LAST_REV
               , (SELECT COUNT(*) FROM PART_VENDOR v WHERE v.PART_NO = p.PART_NO) AS VENDOR_CNT
               , (SELECT NVL(SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END), 0) FROM STOCK_TXN t WHERE t.PART_NO = p.PART_NO) AS STOCK_QTY
               , (SELECT COUNT(*) FROM BOM b WHERE b.CHILD_PART = p.PART_NO) AS USED_IN_CNT
            FROM PART p
           WHERE p.PART_NO = :partNo""",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
               , (SELECT MAX(r.REV) FROM PART_REV r WHERE r.PART_NO = p.PART_NO) AS LAST_REV
               , (SELECT COUNT(*) FROM PART_VENDOR v WHERE v.PART_NO = p.PART_NO) AS VENDOR_CNT
               , (SELECT COALESCE(SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END), 0) FROM STOCK_TXN t WHERE t.PART_NO = p.PART_NO) AS STOCK_QTY
               , (SELECT COUNT(*) FROM BOM b WHERE b.CHILD_PART = p.PART_NO) AS USED_IN_CNT
            FROM PART p
           WHERE p.PART_NO = :partNo"""),
      new Example("BOM · 부품", "모부품별 자식 수 · 자식 목록 한 칸", "BOM 한 단계만: 모부품마다 바로 아래 부품 수와 목록.",
          """
          SELECT
                 b.PARENT_PART
               , COUNT(*) AS CHILD_CNT
               , LISTAGG(b.CHILD_PART || '×' || b.QTY, ', ') WITHIN GROUP (ORDER BY b.SEQ) AS CHILDREN
            FROM BOM b
           WHERE b.PARENT_PART IS NOT NULL
           GROUP BY
                 b.PARENT_PART""",
          """
          SELECT
                 b.PARENT_PART
               , COUNT(*) AS CHILD_CNT
               , STRING_AGG(b.CHILD_PART || '×' || b.QTY, ', ' ORDER BY b.SEQ) AS CHILDREN
            FROM BOM b
           WHERE b.PARENT_PART IS NOT NULL
           GROUP BY
                 b.PARENT_PART"""),
      new Example("BOM · 부품", "BOM 에 자기 자신이 자식으로 (고리 점검 한 단계)", "모부품과 자부품이 같은 줄, 또는 A→B, B→A 처럼 바로 맞물린 줄.",
          """
          SELECT
                 a.PARENT_PART
               , a.CHILD_PART
            FROM BOM a
           WHERE a.PARENT_PART = a.CHILD_PART
              OR EXISTS (
                  SELECT 1
                    FROM BOM b
                   WHERE b.PARENT_PART = a.CHILD_PART
                     AND b.CHILD_PART = a.PARENT_PART
                 )""",
          """
          SELECT
                 a.PARENT_PART
               , a.CHILD_PART
            FROM BOM a
           WHERE a.PARENT_PART = a.CHILD_PART
              OR EXISTS (
                  SELECT 1
                    FROM BOM b
                   WHERE b.PARENT_PART = a.CHILD_PART
                     AND b.CHILD_PART = a.PARENT_PART
                 )"""),
      new Example("BOM · 부품", "리비전 건너뛴 것 (A 다음 C)", "리비전 글자가 한 칸씩 오르지 않은 부품. 리비전이 글자 한 자리일 때.",
          """
          SELECT
                 x.PART_NO
               , x.PREV_REV
               , x.REV
            FROM (
                  SELECT
                         r.PART_NO
                       , r.REV
                       , LAG(r.REV) OVER (PARTITION BY r.PART_NO ORDER BY r.REV) AS PREV_REV
                    FROM PART_REV r
                 ) x
           WHERE x.PREV_REV IS NOT NULL
             AND ASCII(x.REV) - ASCII(x.PREV_REV) > 1""",
          """
          SELECT
                 x.PART_NO
               , x.PREV_REV
               , x.REV
            FROM (
                  SELECT
                         r.PART_NO
                       , r.REV
                       , LAG(r.REV) OVER (PARTITION BY r.PART_NO ORDER BY r.REV) AS PREV_REV
                    FROM PART_REV r
                 ) x
           WHERE x.PREV_REV IS NOT NULL
             AND ASCII(x.REV) - ASCII(x.PREV_REV) > 1"""),
      new Example("BOM · 부품", "승인 상태인데 승인일이 빈 리비전 (점검)", "상태와 날짜가 서로 맞지 않는 줄.",
          """
          SELECT
                 r.PART_NO
               , r.REV
               , r.STATUS
               , r.APPROVED_DT
            FROM PART_REV r
           WHERE (r.STATUS = 'APPROVED' AND r.APPROVED_DT IS NULL)
              OR (r.STATUS <> 'APPROVED' AND r.APPROVED_DT IS NOT NULL)""",
          """
          SELECT
                 r.PART_NO
               , r.REV
               , r.STATUS
               , r.APPROVED_DT
            FROM PART_REV r
           WHERE (r.STATUS = 'APPROVED' AND r.APPROVED_DT IS NULL)
              OR (r.STATUS <> 'APPROVED' AND r.APPROVED_DT IS NOT NULL)"""),
      new Example("BOM · 부품", "재질별 부품 수 · 중량 합계 (최신 리비전 기준)", "부품마다 최신 리비전 하나만 골라 재질별로 묶습니다.",
          """
          SELECT
                 x.MATERIAL
               , COUNT(*) AS PART_CNT
               , SUM(x.WEIGHT) AS TOTAL_WEIGHT
            FROM (
                  SELECT
                         r.PART_NO
                       , r.MATERIAL
                       , r.WEIGHT
                       , ROW_NUMBER() OVER (PARTITION BY r.PART_NO ORDER BY r.REV DESC) AS RN
                    FROM PART_REV r
                 ) x
           WHERE x.RN = 1
           GROUP BY
                 x.MATERIAL
           ORDER BY
                 PART_CNT DESC""",
          """
          SELECT
                 x.MATERIAL
               , COUNT(*) AS PART_CNT
               , SUM(x.WEIGHT) AS TOTAL_WEIGHT
            FROM (
                  SELECT
                         r.PART_NO
                       , r.MATERIAL
                       , r.WEIGHT
                       , ROW_NUMBER() OVER (PARTITION BY r.PART_NO ORDER BY r.REV DESC) AS RN
                    FROM PART_REV r
                 ) x
           WHERE x.RN = 1
           GROUP BY
                 x.MATERIAL
           ORDER BY
                 PART_CNT DESC"""),
      new Example("BOM · 부품", "담당자별 부품 수 · 이번 달 새 부품 수", "설계 담당자마다 맡은 부품과 이번 달 등록 수.",
          """
          SELECT
                 p.OWNER_ID
               , COUNT(*) AS PART_CNT
               , SUM(CASE WHEN p.REG_DT >= TRUNC(SYSDATE, 'MM') THEN 1 ELSE 0 END) AS NEW_THIS_MONTH
            FROM PART p
           GROUP BY
                 p.OWNER_ID
           ORDER BY
                 PART_CNT DESC""",
          """
          SELECT
                 p.OWNER_ID
               , COUNT(*) AS PART_CNT
               , SUM(CASE WHEN p.REG_DT >= CAST(DATE_TRUNC('MONTH', CURRENT_DATE) AS DATE) THEN 1 ELSE 0 END) AS NEW_THIS_MONTH
            FROM PART p
           GROUP BY
                 p.OWNER_ID
           ORDER BY
                 PART_CNT DESC"""),
      new Example("BOM · 부품", "변경이 많은 부품 (이력 건수 순)", "최근 90일 동안 칸 값이 자주 바뀐 부품. 설계가 불안정한 후보입니다.",
          """
          SELECT
                 h.PART_NO
               , COUNT(*) AS CHG_CNT
               , COUNT(DISTINCT h.COL_NAME) AS COL_CNT
               , MAX(h.CHG_DT) AS LAST_CHG
            FROM PART_HIST h
           WHERE h.CHG_DT >= SYSDATE - 90
           GROUP BY
                 h.PART_NO
           ORDER BY
                 CHG_CNT DESC""",
          """
          SELECT
                 h.PART_NO
               , COUNT(*) AS CHG_CNT
               , COUNT(DISTINCT h.COL_NAME) AS COL_CNT
               , MAX(h.CHG_DT) AS LAST_CHG
            FROM PART_HIST h
           WHERE h.CHG_DT >= CURRENT_DATE - 90
           GROUP BY
                 h.PART_NO
           ORDER BY
                 CHG_CNT DESC"""),
      new Example("BOM · 부품", "도면이 없는 부품 · 부품이 없는 도면", "두 방향으로 짝이 없는 것을 한 번에.",
          """
          SELECT
                 '도면 없음' AS KIND
               , p.PART_NO AS KEY_NO
            FROM PART p
           WHERE NOT EXISTS (SELECT 1 FROM DRAWING d WHERE d.PART_NO = p.PART_NO)
           UNION ALL
          SELECT
                 '부품 없음'
               , d.DRAWING_NO
            FROM DRAWING d
           WHERE NOT EXISTS (SELECT 1 FROM PART p WHERE p.PART_NO = d.PART_NO)""",
          """
          SELECT
                 '도면 없음' AS KIND
               , p.PART_NO AS KEY_NO
            FROM PART p
           WHERE NOT EXISTS (SELECT 1 FROM DRAWING d WHERE d.PART_NO = p.PART_NO)
           UNION ALL
          SELECT
                 '부품 없음'
               , d.DRAWING_NO
            FROM DRAWING d
           WHERE NOT EXISTS (SELECT 1 FROM PART p WHERE p.PART_NO = d.PART_NO)"""),
      new Example("BOM · Top 어셈블리", "Top 하나 전개 (레벨 · 들여쓰기 · 누적 수량 · 경로)", "Top 어셈블리 하나 아래를 끝까지 펼칩니다. TOT_QTY 는 Top 1개에 드는 수량(위 수량을 곱해 내려감). 리비전을 쓰면 두 WHERE 에 AND b.REV = … 를 넣으세요.",
          """
          WITH t (TOP_PART, PARENT_PART, CHILD_PART, QTY, LVL, TOT_QTY, PATH) AS (
          SELECT
                 b.PARENT_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , 1
               , b.QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , LPAD(' ', (t.LVL - 1) * 2) || t.CHILD_PART AS PART_NO
               , t.QTY
               , t.TOT_QTY
               , t.PATH
            FROM t
           ORDER BY
                 t.PATH""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.PARENT_PART AS TOP_PART
               , b.PARENT_PART AS PARENT_PART
               , b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , b.QTY AS TOT_QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000)) AS PATH
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , LPAD(' ', (t.LVL - 1) * 2) || t.CHILD_PART AS PART_NO
               , t.QTY
               , t.TOT_QTY
               , t.PATH
            FROM t
           ORDER BY
                 t.PATH"""),
      new Example("BOM · Top 어셈블리", "Top 바로 아래 한 단계만 (단일 레벨 BOM)", "Top 의 바로 아래 자식만. 화면에서 펼침 단추를 누를 때 한 단계씩 읽는 쿼리입니다.",
          """
          SELECT
                 b.SEQ
               , b.CHILD_PART
               , p.PART_NAME
               , b.QTY
               , (SELECT COUNT(*) FROM BOM c WHERE c.PARENT_PART = b.CHILD_PART) AS CHILD_CNT
            FROM BOM b
               , PART p
           WHERE b.CHILD_PART = p.PART_NO(+)
             AND b.PARENT_PART = :topPart
           ORDER BY
                 b.SEQ""",
          """
          SELECT
                 b.SEQ
               , b.CHILD_PART
               , p.PART_NAME
               , b.QTY
               , (SELECT COUNT(*) FROM BOM c WHERE c.PARENT_PART = b.CHILD_PART) AS CHILD_CNT
            FROM BOM b
            LEFT OUTER JOIN PART p
              ON b.CHILD_PART = p.PART_NO
           WHERE b.PARENT_PART = :topPart
           ORDER BY
                 b.SEQ"""),
      new Example("BOM · Top 어셈블리", "Top 아래 N 레벨까지만", "3 레벨까지만 펼칩니다. Oracle 은 CONNECT BY 의 LEVEL 로 자릅니다.",
          """
          SELECT
                 LEVEL AS LVL
               , LPAD(' ', (LEVEL - 1) * 2) || b.CHILD_PART AS PART_NO
               , b.QTY
            FROM BOM b
           START WITH b.PARENT_PART = :topPart
          CONNECT BY PRIOR b.CHILD_PART = b.PARENT_PART
                 AND LEVEL <= :maxLevel
           ORDER SIBLINGS BY
                 b.SEQ""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , CAST(LPAD(CAST(b.SEQ AS VARCHAR), 4, '0') AS VARCHAR(4000)) AS SORT_KEY
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , CAST(t.SORT_KEY || '/' || LPAD(CAST(b.SEQ AS VARCHAR), 4, '0') AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < CAST(:maxLevel AS INTEGER)
          )
          SELECT
                 t.LVL
               , LPAD(' ', (t.LVL - 1) * 2) || t.CHILD_PART AS PART_NO
               , t.QTY
            FROM t
           ORDER BY
                 t.SORT_KEY"""),
      new Example("BOM · Top 어셈블리", "모든 Top 을 한 번에 전개", "Top(누구의 자식도 아닌 모부품)마다 아래를 다 펼칩니다. 맨 앞 칸이 어느 Top 의 줄인지 알려 줍니다.",
          """
          WITH t (TOP_PART, PARENT_PART, CHILD_PART, QTY, LVL, TOT_QTY, PATH) AS (
          SELECT
                 b.PARENT_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , 1
               , b.QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
           WHERE b.PARENT_PART IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM BOM x WHERE x.CHILD_PART = b.PARENT_PART AND x.PARENT_PART IS NOT NULL)
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.TOP_PART
               , t.LVL
               , t.CHILD_PART
               , t.TOT_QTY
            FROM t
           ORDER BY
                 t.TOP_PART
               , t.PATH""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.PARENT_PART AS TOP_PART
               , b.PARENT_PART AS PARENT_PART
               , b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , b.QTY AS TOT_QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000)) AS PATH
            FROM BOM b
           WHERE b.PARENT_PART IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM BOM x WHERE x.CHILD_PART = b.PARENT_PART AND x.PARENT_PART IS NOT NULL)
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.TOP_PART
               , t.LVL
               , t.CHILD_PART
               , t.TOT_QTY
            FROM t
           ORDER BY
                 t.TOP_PART
               , t.PATH"""),
      new Example("BOM · Top 어셈블리", "고른 Top 여러 개 전개 (Top 목록 IN)", "화면에서 고른 Top 몇 개만 펼쳐 Top · 부품마다 총 수량을 냅니다.",
          """
          WITH t (TOP_PART, PARENT_PART, CHILD_PART, QTY, LVL, TOT_QTY, PATH) AS (
          SELECT
                 b.PARENT_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , 1
               , b.QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
           WHERE b.PARENT_PART IN (:top1, :top2, :top3)
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.TOP_PART
               , t.CHILD_PART
               , SUM(t.TOT_QTY) AS TOT_QTY
            FROM t
           GROUP BY
                 t.TOP_PART
               , t.CHILD_PART
           ORDER BY
                 t.TOP_PART
               , t.CHILD_PART""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.PARENT_PART AS TOP_PART
               , b.PARENT_PART AS PARENT_PART
               , b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , b.QTY AS TOT_QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000)) AS PATH
            FROM BOM b
           WHERE b.PARENT_PART IN (:top1, :top2, :top3)
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.TOP_PART
               , t.CHILD_PART
               , SUM(t.TOT_QTY) AS TOT_QTY
            FROM t
           GROUP BY
                 t.TOP_PART
               , t.CHILD_PART
           ORDER BY
                 t.TOP_PART
               , t.CHILD_PART"""),
      new Example("BOM · Top 어셈블리", "Top 별 요약 (부품 수 · 최대 깊이 · 최하위 부품 수)", "Top 마다 BOM 이 얼마나 큰지 한 줄로. 완제품 목록 화면 옆 숫자에 씁니다.",
          """
          WITH t (TOP_PART, PARENT_PART, CHILD_PART, QTY, LVL, TOT_QTY, PATH) AS (
          SELECT
                 b.PARENT_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , 1
               , b.QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
           WHERE b.PARENT_PART IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM BOM x WHERE x.CHILD_PART = b.PARENT_PART AND x.PARENT_PART IS NOT NULL)
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.TOP_PART
               , COUNT(DISTINCT t.CHILD_PART) AS PART_CNT
               , MAX(t.LVL) AS MAX_LEVEL
               , COUNT(DISTINCT CASE WHEN NOT EXISTS (SELECT 1 FROM BOM c WHERE c.PARENT_PART = t.CHILD_PART) THEN t.CHILD_PART END) AS LEAF_CNT
            FROM t
           GROUP BY
                 t.TOP_PART
           ORDER BY
                 t.TOP_PART""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.PARENT_PART AS TOP_PART
               , b.PARENT_PART AS PARENT_PART
               , b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , b.QTY AS TOT_QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000)) AS PATH
            FROM BOM b
           WHERE b.PARENT_PART IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM BOM x WHERE x.CHILD_PART = b.PARENT_PART AND x.PARENT_PART IS NOT NULL)
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.TOP_PART
               , COUNT(DISTINCT t.CHILD_PART) AS PART_CNT
               , MAX(t.LVL) AS MAX_LEVEL
               , COUNT(DISTINCT CASE WHEN NOT EXISTS (SELECT 1 FROM BOM c WHERE c.PARENT_PART = t.CHILD_PART) THEN t.CHILD_PART END) AS LEAF_CNT
            FROM t
           GROUP BY
                 t.TOP_PART
           ORDER BY
                 t.TOP_PART"""),
      new Example("BOM · Top 어셈블리", "Top 별 재료비 (최하위 단가 × 누적 수량)", "모든 Top 의 재료비를 한 번에. 단가가 빈 부품 수도 같이 세어 빠진 단가를 알려 줍니다.",
          """
          WITH t (TOP_PART, PARENT_PART, CHILD_PART, QTY, LVL, TOT_QTY, PATH) AS (
          SELECT
                 b.PARENT_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , 1
               , b.QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
           WHERE b.PARENT_PART IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM BOM x WHERE x.CHILD_PART = b.PARENT_PART AND x.PARENT_PART IS NOT NULL)
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.TOP_PART
               , SUM(t.TOT_QTY * NVL(p.UNIT_COST, 0)) AS MATERIAL_COST
               , SUM(CASE WHEN p.UNIT_COST IS NULL THEN 1 ELSE 0 END) AS NO_COST_CNT
            FROM t
               , PART p
           WHERE t.CHILD_PART = p.PART_NO(+)
             AND NOT EXISTS (SELECT 1 FROM BOM c WHERE c.PARENT_PART = t.CHILD_PART)
           GROUP BY
                 t.TOP_PART
           ORDER BY
                 MATERIAL_COST DESC""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.PARENT_PART AS TOP_PART
               , b.PARENT_PART AS PARENT_PART
               , b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , b.QTY AS TOT_QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000)) AS PATH
            FROM BOM b
           WHERE b.PARENT_PART IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM BOM x WHERE x.CHILD_PART = b.PARENT_PART AND x.PARENT_PART IS NOT NULL)
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.TOP_PART
               , SUM(t.TOT_QTY * COALESCE(p.UNIT_COST, 0)) AS MATERIAL_COST
               , SUM(CASE WHEN p.UNIT_COST IS NULL THEN 1 ELSE 0 END) AS NO_COST_CNT
            FROM t
            LEFT OUTER JOIN PART p
              ON t.CHILD_PART = p.PART_NO
           WHERE NOT EXISTS (SELECT 1 FROM BOM c WHERE c.PARENT_PART = t.CHILD_PART)
           GROUP BY
                 t.TOP_PART
           ORDER BY
                 MATERIAL_COST DESC"""),
      new Example("BOM · Top 어셈블리", "이 Top 에 이 부품이 들어가나 (Y/N · 총 수량)", "Top 아래 어느 깊이에든 그 부품이 있으면 Y. 여러 경로로 들어가면 경로 수와 합친 수량.",
          """
          WITH t (TOP_PART, PARENT_PART, CHILD_PART, QTY, LVL, TOT_QTY, PATH) AS (
          SELECT
                 b.PARENT_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , 1
               , b.QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 CASE WHEN COUNT(*) > 0 THEN 'Y' ELSE 'N' END AS USED_YN
               , COUNT(*) AS PATH_CNT
               , SUM(t.TOT_QTY) AS TOT_QTY
            FROM t
           WHERE t.CHILD_PART = :partNo""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.PARENT_PART AS TOP_PART
               , b.PARENT_PART AS PARENT_PART
               , b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , b.QTY AS TOT_QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000)) AS PATH
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 CASE WHEN COUNT(*) > 0 THEN 'Y' ELSE 'N' END AS USED_YN
               , COUNT(*) AS PATH_CNT
               , SUM(t.TOT_QTY) AS TOT_QTY
            FROM t
           WHERE t.CHILD_PART = :partNo"""),
      new Example("BOM · Top 어셈블리", "Top 에서 이 부품까지 경로 (어디를 거쳐 들어가나)", "CAR > WHEEL > BOLT 처럼 Top 에서 그 부품까지 거쳐 가는 길을 모두 봅니다.",
          """
          WITH t (TOP_PART, PARENT_PART, CHILD_PART, QTY, LVL, TOT_QTY, PATH) AS (
          SELECT
                 b.PARENT_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , 1
               , b.QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , t.PATH
               , t.TOT_QTY
            FROM t
           WHERE t.CHILD_PART = :partNo
           ORDER BY
                 t.PATH""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.PARENT_PART AS TOP_PART
               , b.PARENT_PART AS PARENT_PART
               , b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , b.QTY AS TOT_QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000)) AS PATH
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , t.PATH
               , t.TOT_QTY
            FROM t
           WHERE t.CHILD_PART = :partNo
           ORDER BY
                 t.PATH"""),
      new Example("BOM · Top 어셈블리", "Top 아래 여러 경로로 쓰이는 부품", "같은 부품이 서로 다른 반제품 아래에 들어간 것. 합산 수량을 볼 때 놓치기 쉽습니다.",
          """
          WITH t (TOP_PART, PARENT_PART, CHILD_PART, QTY, LVL, TOT_QTY, PATH) AS (
          SELECT
                 b.PARENT_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , 1
               , b.QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.CHILD_PART
               , COUNT(*) AS PATH_CNT
               , SUM(t.TOT_QTY) AS TOT_QTY
            FROM t
           GROUP BY
                 t.CHILD_PART
          HAVING COUNT(*) > 1
           ORDER BY
                 PATH_CNT DESC""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.PARENT_PART AS TOP_PART
               , b.PARENT_PART AS PARENT_PART
               , b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , b.QTY AS TOT_QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000)) AS PATH
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.CHILD_PART
               , COUNT(*) AS PATH_CNT
               , SUM(t.TOT_QTY) AS TOT_QTY
            FROM t
           GROUP BY
                 t.CHILD_PART
          HAVING COUNT(*) > 1
           ORDER BY
                 PATH_CNT DESC"""),
      new Example("BOM · Top 어셈블리", "부품마다 쓰이는 Top 수 (공용 · 전용)", "Top 수가 1 이면 그 Top 전용 부품, 여러 개면 공용 부품입니다.",
          """
          WITH t (TOP_PART, PARENT_PART, CHILD_PART, QTY, LVL, TOT_QTY, PATH) AS (
          SELECT
                 b.PARENT_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , 1
               , b.QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
           WHERE b.PARENT_PART IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM BOM x WHERE x.CHILD_PART = b.PARENT_PART AND x.PARENT_PART IS NOT NULL)
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 u.CHILD_PART
               , COUNT(DISTINCT u.TOP_PART) AS TOP_CNT
               , MIN(u.TOP_PART) AS FIRST_TOP
            FROM t u
           GROUP BY
                 u.CHILD_PART
           ORDER BY
                 TOP_CNT DESC
               , u.CHILD_PART""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.PARENT_PART AS TOP_PART
               , b.PARENT_PART AS PARENT_PART
               , b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , b.QTY AS TOT_QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000)) AS PATH
            FROM BOM b
           WHERE b.PARENT_PART IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM BOM x WHERE x.CHILD_PART = b.PARENT_PART AND x.PARENT_PART IS NOT NULL)
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 u.CHILD_PART
               , COUNT(DISTINCT u.TOP_PART) AS TOP_CNT
               , MIN(u.TOP_PART) AS FIRST_TOP
            FROM t u
           GROUP BY
                 u.CHILD_PART
           ORDER BY
                 TOP_CNT DESC
               , u.CHILD_PART"""),
      new Example("BOM · Top 어셈블리", "두 Top 비교 (공용 · A 전용 · B 전용 부품)", "차종 · 모델 두 개의 BOM 을 펼쳐 공용 부품과 한쪽에만 있는 부품, 수량 차이를 봅니다.",
          """
          WITH t (TOP_PART, PARENT_PART, CHILD_PART, QTY, LVL, TOT_QTY, PATH) AS (
          SELECT
                 b.PARENT_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , 1
               , b.QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
           WHERE b.PARENT_PART IN (:topA, :topB)
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.CHILD_PART
               , SUM(CASE WHEN t.TOP_PART = :topA THEN t.TOT_QTY ELSE 0 END) AS QTY_A
               , SUM(CASE WHEN t.TOP_PART = :topB THEN t.TOT_QTY ELSE 0 END) AS QTY_B
               , CASE
                   WHEN COUNT(DISTINCT t.TOP_PART) = 2 THEN '공용'
                   WHEN MAX(t.TOP_PART) = :topA THEN 'A 전용'
                   ELSE 'B 전용'
                 END AS KIND
            FROM t
           GROUP BY
                 t.CHILD_PART
           ORDER BY
                 KIND
               , t.CHILD_PART""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.PARENT_PART AS TOP_PART
               , b.PARENT_PART AS PARENT_PART
               , b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , b.QTY AS TOT_QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000)) AS PATH
            FROM BOM b
           WHERE b.PARENT_PART IN (:topA, :topB)
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.CHILD_PART
               , SUM(CASE WHEN t.TOP_PART = :topA THEN t.TOT_QTY ELSE 0 END) AS QTY_A
               , SUM(CASE WHEN t.TOP_PART = :topB THEN t.TOT_QTY ELSE 0 END) AS QTY_B
               , CASE
                   WHEN COUNT(DISTINCT t.TOP_PART) = 2 THEN '공용'
                   WHEN MAX(t.TOP_PART) = :topA THEN 'A 전용'
                   ELSE 'B 전용'
                 END AS KIND
            FROM t
           GROUP BY
                 t.CHILD_PART
           ORDER BY
                 KIND
               , t.CHILD_PART"""),
      new Example("BOM · Top 어셈블리", "Top 아래 단종 · 사용 중지 부품", "Top 을 펼쳐 상태가 단종(EOL) · 중지인 부품이 있는지 봅니다. 생산 전 점검.",
          """
          WITH t (TOP_PART, PARENT_PART, CHILD_PART, QTY, LVL, TOT_QTY, PATH) AS (
          SELECT
                 b.PARENT_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , 1
               , b.QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , t.CHILD_PART
               , t.TOT_QTY
               , p.STATUS
            FROM t
               , PART p
           WHERE t.CHILD_PART = p.PART_NO
             AND p.STATUS IN ('EOL', 'STOP')
           ORDER BY
                 t.PATH""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.PARENT_PART AS TOP_PART
               , b.PARENT_PART AS PARENT_PART
               , b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , b.QTY AS TOT_QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000)) AS PATH
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , t.CHILD_PART
               , t.TOT_QTY
               , p.STATUS
            FROM t
               , PART p
           WHERE t.CHILD_PART = p.PART_NO
             AND p.STATUS IN ('EOL', 'STOP')
           ORDER BY
                 t.PATH"""),
      new Example("BOM · Top 어셈블리", "Top 전개에 대체 부품 붙이기", "펼친 부품마다 등록된 대체 부품을 우선순위대로 옆에 붙입니다. 대체가 없으면 빈 칸.",
          """
          WITH t (TOP_PART, PARENT_PART, CHILD_PART, QTY, LVL, TOT_QTY, PATH) AS (
          SELECT
                 b.PARENT_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , 1
               , b.QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , t.CHILD_PART
               , t.TOT_QTY
               , s.SUB_PART_NO
               , s.PRIORITY
            FROM t
               , PART_SUB s
           WHERE t.CHILD_PART = s.PART_NO(+)
           ORDER BY
                 t.PATH
               , s.PRIORITY""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.PARENT_PART AS TOP_PART
               , b.PARENT_PART AS PARENT_PART
               , b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , b.QTY AS TOT_QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000)) AS PATH
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , t.CHILD_PART
               , t.TOT_QTY
               , s.SUB_PART_NO
               , s.PRIORITY
            FROM t
            LEFT OUTER JOIN PART_SUB s
              ON t.CHILD_PART = s.PART_NO
           ORDER BY
                 t.PATH
               , s.PRIORITY"""),
      new Example("BOM · Top 어셈블리", "Top 아래 반제품만 (자식이 있는 중간 부품)", "아래에 또 부품이 있는 중간 조립품만. 반대로 NOT EXISTS 면 최하위(구매) 부품만입니다.",
          """
          WITH t (TOP_PART, PARENT_PART, CHILD_PART, QTY, LVL, TOT_QTY, PATH) AS (
          SELECT
                 b.PARENT_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , 1
               , b.QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , t.CHILD_PART
               , t.TOT_QTY
            FROM t
           WHERE EXISTS (SELECT 1 FROM BOM c WHERE c.PARENT_PART = t.CHILD_PART)
           ORDER BY
                 t.PATH""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.PARENT_PART AS TOP_PART
               , b.PARENT_PART AS PARENT_PART
               , b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , b.QTY AS TOT_QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000)) AS PATH
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , t.CHILD_PART
               , t.TOT_QTY
            FROM t
           WHERE EXISTS (SELECT 1 FROM BOM c WHERE c.PARENT_PART = t.CHILD_PART)
           ORDER BY
                 t.PATH"""),
      new Example("BOM · Top 어셈블리", "Top BOM 점검 (수량 0 · 빈 수량 · 부품 마스터에 없는 품번)", "펼친 줄 중 수량이 비거나 0 이하, 또는 부품 표에 없는 품번. 출도 · 이관 전 점검.",
          """
          WITH t (TOP_PART, PARENT_PART, CHILD_PART, QTY, LVL, TOT_QTY, PATH) AS (
          SELECT
                 b.PARENT_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , 1
               , b.QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , t.PARENT_PART
               , t.CHILD_PART
               , t.QTY
            FROM t
           WHERE t.QTY IS NULL
              OR t.QTY <= 0
              OR NOT EXISTS (SELECT 1 FROM PART p WHERE p.PART_NO = t.CHILD_PART)
           ORDER BY
                 t.PATH""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.PARENT_PART AS TOP_PART
               , b.PARENT_PART AS PARENT_PART
               , b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , b.QTY AS TOT_QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000)) AS PATH
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , t.PARENT_PART
               , t.CHILD_PART
               , t.QTY
            FROM t
           WHERE t.QTY IS NULL
              OR t.QTY <= 0
              OR NOT EXISTS (SELECT 1 FROM PART p WHERE p.PART_NO = t.CHILD_PART)
           ORDER BY
                 t.PATH"""),
      new Example("BOM · Top 어셈블리", "Top 전개 중 고리 찾기 (CONNECT_BY_ISCYCLE)", "A 아래 B, B 아래 A 처럼 돌아오는 BOM. Oracle 은 NOCYCLE + CONNECT_BY_ISCYCLE, PostgreSQL 은 경로에 이미 있는지로 찾습니다.",
          """
          SELECT
                 SYS_CONNECT_BY_PATH(b.CHILD_PART, ' > ') AS PATH
               , b.PARENT_PART
               , b.CHILD_PART
            FROM BOM b
           WHERE CONNECT_BY_ISCYCLE = 1
           START WITH b.PARENT_PART = :topPart
          CONNECT BY NOCYCLE PRIOR b.CHILD_PART = b.PARENT_PART""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.PARENT_PART
               , b.CHILD_PART
               , CAST(' > ' || b.PARENT_PART || ' > ' || b.CHILD_PART || ' > ' AS VARCHAR(4000)) AS PATH
               , FALSE AS IS_CYCLE
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 b.PARENT_PART
               , b.CHILD_PART
               , CAST(t.PATH || b.CHILD_PART || ' > ' AS VARCHAR(4000))
               , t.PATH LIKE '% > ' || b.CHILD_PART || ' > %'
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND NOT t.IS_CYCLE
          )
          SELECT
                 t.PATH
               , t.PARENT_PART
               , t.CHILD_PART
            FROM t
           WHERE t.IS_CYCLE"""),
      new Example("BOM · Top 어셈블리", "Top BOM 엑셀 내려받기용 (품명 · 단위 · 단가 · 금액 · 주 협력사)", "BOM 표 화면 · 엑셀에 그대로 쓰는 칸을 다 붙인 전개입니다.",
          """
          WITH t (TOP_PART, PARENT_PART, CHILD_PART, QTY, LVL, TOT_QTY, PATH) AS (
          SELECT
                 b.PARENT_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , 1
               , b.QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , LPAD(' ', (t.LVL - 1) * 2) || t.CHILD_PART AS PART_NO
               , p.PART_NAME
               , t.QTY
               , t.TOT_QTY
               , p.UNIT_CD
               , p.UNIT_COST
               , t.TOT_QTY * p.UNIT_COST AS AMT
               , (SELECT MIN(v.VENDOR_ID) KEEP (DENSE_RANK FIRST ORDER BY v.SHARE_PCT DESC) FROM PART_VENDOR v WHERE v.PART_NO = t.CHILD_PART) AS MAIN_VENDOR
            FROM t
               , PART p
           WHERE t.CHILD_PART = p.PART_NO(+)
           ORDER BY
                 t.PATH""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.PARENT_PART AS TOP_PART
               , b.PARENT_PART AS PARENT_PART
               , b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , b.QTY AS TOT_QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000)) AS PATH
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , LPAD(' ', (t.LVL - 1) * 2) || t.CHILD_PART AS PART_NO
               , p.PART_NAME
               , t.QTY
               , t.TOT_QTY
               , p.UNIT_CD
               , p.UNIT_COST
               , t.TOT_QTY * p.UNIT_COST AS AMT
               , (SELECT v.VENDOR_ID FROM PART_VENDOR v WHERE v.PART_NO = t.CHILD_PART ORDER BY v.SHARE_PCT DESC LIMIT 1) AS MAIN_VENDOR
            FROM t
            LEFT OUTER JOIN PART p
              ON t.CHILD_PART = p.PART_NO
           ORDER BY
                 t.PATH"""),
      new Example("BOM · Top 어셈블리", "Top N 대 만들 때 모자라는 부품 (소요 − 재고)", "Top 을 몇 대 만들지 넣으면 최하위 부품마다 필요 수량 · 재고 · 모자라는 수량.",
          """
          WITH t (TOP_PART, PARENT_PART, CHILD_PART, QTY, LVL, TOT_QTY, PATH) AS (
          SELECT
                 b.PARENT_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , 1
               , b.QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.CHILD_PART
               , SUM(t.TOT_QTY) * :buildQty AS NEED_QTY
               , NVL(s.STOCK_QTY, 0) AS STOCK_QTY
               , SUM(t.TOT_QTY) * :buildQty - NVL(s.STOCK_QTY, 0) AS SHORT_QTY
            FROM t
               , (SELECT x.PART_NO, SUM(CASE WHEN x.TXN_TYPE = 'IN' THEN x.QTY ELSE -x.QTY END) AS STOCK_QTY FROM STOCK_TXN x GROUP BY x.PART_NO) s
           WHERE t.CHILD_PART = s.PART_NO(+)
             AND NOT EXISTS (SELECT 1 FROM BOM c WHERE c.PARENT_PART = t.CHILD_PART)
           GROUP BY
                 t.CHILD_PART
               , s.STOCK_QTY
          HAVING SUM(t.TOT_QTY) * :buildQty > NVL(s.STOCK_QTY, 0)""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.PARENT_PART AS TOP_PART
               , b.PARENT_PART AS PARENT_PART
               , b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , b.QTY AS TOT_QTY
               , CAST(b.PARENT_PART || ' > ' || b.CHILD_PART AS VARCHAR(4000)) AS PATH
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 t.TOP_PART
               , b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , t.TOT_QTY * b.QTY
               , CAST(t.PATH || ' > ' || b.CHILD_PART AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 t.CHILD_PART
               , SUM(t.TOT_QTY) * CAST(:buildQty AS NUMERIC) AS NEED_QTY
               , COALESCE(s.STOCK_QTY, 0) AS STOCK_QTY
               , SUM(t.TOT_QTY) * CAST(:buildQty AS NUMERIC) - COALESCE(s.STOCK_QTY, 0) AS SHORT_QTY
            FROM t
            LEFT OUTER JOIN (SELECT x.PART_NO, SUM(CASE WHEN x.TXN_TYPE = 'IN' THEN x.QTY ELSE -x.QTY END) AS STOCK_QTY FROM STOCK_TXN x GROUP BY x.PART_NO) s
              ON t.CHILD_PART = s.PART_NO
           WHERE NOT EXISTS (SELECT 1 FROM BOM c WHERE c.PARENT_PART = t.CHILD_PART)
           GROUP BY
                 t.CHILD_PART
               , s.STOCK_QTY
          HAVING SUM(t.TOT_QTY) * CAST(:buildQty AS NUMERIC) > COALESCE(s.STOCK_QTY, 0)"""),
      new Example("BOM · Top 어셈블리", "역전개 바로 위만 (이 부품을 쓰는 모부품 한 단계)", "이 부품을 직접 자식으로 가진 모부품만. 끝까지 올라가려면 「BOM 역전개」 를 쓰세요.",
          """
          SELECT
                 b.PARENT_PART
               , p.PART_NAME
               , b.QTY
               , b.REV
            FROM BOM b
               , PART p
           WHERE b.PARENT_PART = p.PART_NO(+)
             AND b.CHILD_PART = :partNo
             AND b.PARENT_PART IS NOT NULL""",
          """
          SELECT
                 b.PARENT_PART
               , p.PART_NAME
               , b.QTY
               , b.REV
            FROM BOM b
            LEFT OUTER JOIN PART p
              ON b.PARENT_PART = p.PART_NO
           WHERE b.CHILD_PART = :partNo
             AND b.PARENT_PART IS NOT NULL"""),
      new Example("BOM · Top 어셈블리", "역전개해서 Top 만 (이 부품을 쓰는 최종 완제품)", "위로 끝까지 올라가 더 위가 없는 모부품만 남깁니다. 설계 변경 영향 완제품 목록.",
          """
          SELECT DISTINCT
                 b.PARENT_PART AS TOP_PART
            FROM BOM b
           WHERE b.PARENT_PART IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM BOM x WHERE x.CHILD_PART = b.PARENT_PART AND x.PARENT_PART IS NOT NULL)
           START WITH b.CHILD_PART = :partNo
          CONNECT BY NOCYCLE b.CHILD_PART = PRIOR b.PARENT_PART""",
          """
          WITH RECURSIVE UP AS (
          SELECT
                 b.PARENT_PART
               , 1 AS LVL
            FROM BOM b
           WHERE b.CHILD_PART = :partNo
             AND b.PARENT_PART IS NOT NULL
           UNION ALL
          SELECT
                 b.PARENT_PART
               , u.LVL + 1
            FROM BOM b
               , UP u
           WHERE b.CHILD_PART = u.PARENT_PART
             AND b.PARENT_PART IS NOT NULL
             AND u.LVL < 20
          )
          SELECT DISTINCT
                 u.PARENT_PART AS TOP_PART
            FROM UP u
           WHERE NOT EXISTS (SELECT 1 FROM BOM x WHERE x.CHILD_PART = u.PARENT_PART AND x.PARENT_PART IS NOT NULL)"""),
      new Example("BOM · Top 어셈블리", "Top 리비전 복사 (A 판 BOM → B 판)", "Top 의 한 리비전 BOM 을 새 리비전으로 그대로 복사합니다. 이미 B 판이 있으면 넣지 않습니다.",
          """
          INSERT INTO BOM (PARENT_PART, CHILD_PART, QTY, SEQ, REV, VALID_FROM, VALID_TO)
          SELECT
                 b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , b.SEQ
               , :newRev
               , TRUNC(SYSDATE)
               , b.VALID_TO
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
             AND b.REV = :oldRev
             AND NOT EXISTS (SELECT 1 FROM BOM x WHERE x.PARENT_PART = b.PARENT_PART AND x.REV = :newRev)""",
          """
          INSERT INTO BOM (PARENT_PART, CHILD_PART, QTY, SEQ, REV, VALID_FROM, VALID_TO)
          SELECT
                 b.PARENT_PART
               , b.CHILD_PART
               , b.QTY
               , b.SEQ
               , :newRev
               , CURRENT_DATE
               , b.VALID_TO
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
             AND b.REV = :oldRev
             AND NOT EXISTS (SELECT 1 FROM BOM x WHERE x.PARENT_PART = b.PARENT_PART AND x.REV = :newRev)"""),
      new Example("BOM · Top 어셈블리", "Top 최신 리비전 BOM 만 (모부품마다 가장 큰 REV)", "모부품마다 리비전이 여러 개 있을 때 가장 최근 리비전의 줄만 남겨 한 단계 BOM 을 봅니다.",
          """
          SELECT
                 b.PARENT_PART
               , b.REV
               , b.CHILD_PART
               , b.QTY
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
             AND b.REV = (SELECT MAX(x.REV) FROM BOM x WHERE x.PARENT_PART = b.PARENT_PART)
           ORDER BY
                 b.SEQ""",
          """
          SELECT
                 b.PARENT_PART
               , b.REV
               , b.CHILD_PART
               , b.QTY
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
             AND b.REV = (SELECT MAX(x.REV) FROM BOM x WHERE x.PARENT_PART = b.PARENT_PART)
           ORDER BY
                 b.SEQ"""),
      new Example("BOM · Top 어셈블리", "레벨을 칸으로 (1 레벨 · 2 레벨 · 3 레벨 칸)", "들여쓰기 대신 레벨마다 칸을 둡니다. 엑셀에서 레벨별로 거르기 쉬운 꼴.",
          """
          SELECT
                 DECODE(LEVEL, 1, b.CHILD_PART) AS LV1
               , DECODE(LEVEL, 2, b.CHILD_PART) AS LV2
               , DECODE(LEVEL, 3, b.CHILD_PART) AS LV3
               , DECODE(LEVEL, 4, b.CHILD_PART) AS LV4
               , b.QTY
            FROM BOM b
           START WITH b.PARENT_PART = :topPart
          CONNECT BY PRIOR b.CHILD_PART = b.PARENT_PART
           ORDER SIBLINGS BY
                 b.SEQ""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 b.CHILD_PART AS CHILD_PART
               , b.QTY AS QTY
               , 1 AS LVL
               , CAST(LPAD(CAST(b.SEQ AS VARCHAR), 4, '0') AS VARCHAR(4000)) AS SORT_KEY
            FROM BOM b
           WHERE b.PARENT_PART = :topPart
           UNION ALL
          SELECT
                 b.CHILD_PART
               , b.QTY
               , t.LVL + 1
               , CAST(t.SORT_KEY || '/' || LPAD(CAST(b.SEQ AS VARCHAR), 4, '0') AS VARCHAR(4000))
            FROM BOM b
               , t
           WHERE b.PARENT_PART = t.CHILD_PART
             AND t.LVL < 20
          )
          SELECT
                 CASE WHEN t.LVL = 1 THEN t.CHILD_PART END AS LV1
               , CASE WHEN t.LVL = 2 THEN t.CHILD_PART END AS LV2
               , CASE WHEN t.LVL = 3 THEN t.CHILD_PART END AS LV3
               , CASE WHEN t.LVL = 4 THEN t.CHILD_PART END AS LV4
               , t.QTY
            FROM t
           ORDER BY
                 t.SORT_KEY"""),
      new Example("BOM · Top 어셈블리", "Top 목록 (완제품 · 최상위 어셈블리) 과 자식 수", "누구의 자식도 아닌 모부품을 Top 으로 보고 바로 아래 자식 수와 품명을 붙입니다.",
          """
          SELECT
                 b.PARENT_PART AS TOP_PART
               , p.PART_NAME
               , COUNT(*) AS CHILD_CNT
            FROM BOM b
               , PART p
           WHERE b.PARENT_PART = p.PART_NO(+)
             AND b.PARENT_PART IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM BOM x WHERE x.CHILD_PART = b.PARENT_PART AND x.PARENT_PART IS NOT NULL)
           GROUP BY
                 b.PARENT_PART
               , p.PART_NAME
           ORDER BY
                 b.PARENT_PART""",
          """
          SELECT
                 b.PARENT_PART AS TOP_PART
               , p.PART_NAME
               , COUNT(*) AS CHILD_CNT
            FROM BOM b
            LEFT OUTER JOIN PART p
              ON b.PARENT_PART = p.PART_NO
           WHERE b.PARENT_PART IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM BOM x WHERE x.CHILD_PART = b.PARENT_PART AND x.PARENT_PART IS NOT NULL)
           GROUP BY
                 b.PARENT_PART
               , p.PART_NAME
           ORDER BY
                 b.PARENT_PART"""),
      new Example("리비전 · 이력", "최신 리비전만 보기", "부품마다 리비전이 여러 줄일 때 가장 높은 것 한 줄만. 승인(REL)된 것만 보려면 안쪽에 조건을 넣습니다.",
          """
          SELECT
                 r.*
            FROM (
                  SELECT
                         x.*
                       , ROW_NUMBER() OVER (PARTITION BY x.PART_NO ORDER BY x.REV DESC) AS RN
                    FROM PART_REV x
                   WHERE x.STATUS = 'REL'
                 ) r
           WHERE r.RN = 1""",
          """
          SELECT
                 r.*
            FROM (
                  SELECT
                         x.*
                       , ROW_NUMBER() OVER (PARTITION BY x.PART_NO ORDER BY x.REV DESC) AS RN
                    FROM PART_REV x
                   WHERE x.STATUS = 'REL'
                 ) r
           WHERE r.RN = 1"""),
      new Example("리비전 · 이력", "직전 리비전과 바뀐 칼럼 보기", "LAG 로 바로 앞 리비전 값을 옆에 놓고 다른 것만 표시합니다.",
          """
          SELECT
                 r.PART_NO
               , r.REV
               , LAG(r.REV) OVER (PARTITION BY r.PART_NO ORDER BY r.REV) AS PREV_REV
               , r.WEIGHT
               , LAG(r.WEIGHT) OVER (PARTITION BY r.PART_NO ORDER BY r.REV) AS PREV_WEIGHT
               , r.MATERIAL
               , LAG(r.MATERIAL) OVER (PARTITION BY r.PART_NO ORDER BY r.REV) AS PREV_MATERIAL
            FROM PART_REV r
           WHERE r.PART_NO = :partNo
           ORDER BY
                 r.REV""",
          """
          SELECT
                 r.PART_NO
               , r.REV
               , LAG(r.REV) OVER (PARTITION BY r.PART_NO ORDER BY r.REV) AS PREV_REV
               , r.WEIGHT
               , LAG(r.WEIGHT) OVER (PARTITION BY r.PART_NO ORDER BY r.REV) AS PREV_WEIGHT
               , r.MATERIAL
               , LAG(r.MATERIAL) OVER (PARTITION BY r.PART_NO ORDER BY r.REV) AS PREV_MATERIAL
            FROM PART_REV r
           WHERE r.PART_NO = :partNo
           ORDER BY
                 r.REV"""),
      new Example("리비전 · 이력", "특정 날짜 시점의 값 (유효 기간 이력)", "VALID_FROM ~ VALID_TO 이력 표에서 기준일에 걸리는 한 줄. 단가 · 조직 · 담당자 이력에 다 씁니다.",
          """
          SELECT
                 h.PART_NO
               , h.VENDOR_ID
               , h.PRICE
               , h.VALID_FROM
               , h.VALID_TO
            FROM PART_PRICE_HIST h
           WHERE TO_DATE(:baseDt, 'YYYY-MM-DD') BETWEEN h.VALID_FROM AND h.VALID_TO""",
          """
          SELECT
                 h.PART_NO
               , h.VENDOR_ID
               , h.PRICE
               , h.VALID_FROM
               , h.VALID_TO
            FROM PART_PRICE_HIST h
           WHERE TO_DATE(:baseDt, 'YYYY-MM-DD') BETWEEN h.VALID_FROM AND h.VALID_TO"""),
      new Example("리비전 · 이력", "유효 기간이 겹치는 이력 찾기 (점검)", "같은 키에 기간이 겹치는 두 줄이 있으면 시점 조회가 두 줄을 돌려줍니다. 미리 찾아 고칩니다.",
          """
          SELECT
                 a.PART_NO
               , a.VENDOR_ID
               , a.VALID_FROM AS A_FROM
               , a.VALID_TO AS A_TO
               , b.VALID_FROM AS B_FROM
               , b.VALID_TO AS B_TO
            FROM PART_PRICE_HIST a, PART_PRICE_HIST b
           WHERE a.PART_NO = b.PART_NO
             AND a.VENDOR_ID = b.VENDOR_ID
             AND a.VALID_FROM < b.VALID_FROM
             AND b.VALID_FROM <= a.VALID_TO""",
          """
          SELECT
                 a.PART_NO
               , a.VENDOR_ID
               , a.VALID_FROM AS A_FROM
               , a.VALID_TO AS A_TO
               , b.VALID_FROM AS B_FROM
               , b.VALID_TO AS B_TO
            FROM PART_PRICE_HIST a, PART_PRICE_HIST b
           WHERE a.PART_NO = b.PART_NO
             AND a.VENDOR_ID = b.VENDOR_ID
             AND a.VALID_FROM < b.VALID_FROM
             AND b.VALID_FROM <= a.VALID_TO"""),
      new Example("리비전 · 이력", "변경 이력 한 줄로 (바뀌기 전 → 후)", "칼럼별 변경 로그를 사람이 읽기 좋게. 누가 언제 무엇을 바꿨는지.",
          """
          SELECT
                 h.CHG_DT
               , h.PART_NO
               , h.COL_NAME
               , h.OLD_VAL || ' → ' || h.NEW_VAL AS CHANGE
               , u.USER_NAME
            FROM PART_HIST h, USERS u
           WHERE h.CHG_USER = u.USER_ID(+)
             AND h.PART_NO = :partNo
           ORDER BY
                 h.CHG_DT DESC""",
          """
          SELECT
                 h.CHG_DT
               , h.PART_NO
               , h.COL_NAME
               , h.OLD_VAL || ' → ' || h.NEW_VAL AS CHANGE
               , u.USER_NAME
            FROM PART_HIST h
            LEFT OUTER JOIN USERS u
              ON h.CHG_USER = u.USER_ID
           WHERE h.PART_NO = :partNo
           ORDER BY
                 h.CHG_DT DESC"""),
      new Example("리비전 · 이력", "리비전별 승인까지 걸린 날 (출도 리드타임)", "앞 리비전 승인일과 이번 승인일 사이 날 수.",
          """
          SELECT
                 r.PART_NO
               , r.REV
               , r.APPROVED_DT
               , r.APPROVED_DT - LAG(r.APPROVED_DT) OVER (PARTITION BY r.PART_NO ORDER BY r.REV) AS DAYS_FROM_PREV
            FROM PART_REV r
           WHERE r.APPROVED_DT IS NOT NULL
           ORDER BY
                 r.PART_NO
               , r.REV""",
          """
          SELECT
                 r.PART_NO
               , r.REV
               , r.APPROVED_DT
               , r.APPROVED_DT - LAG(r.APPROVED_DT) OVER (PARTITION BY r.PART_NO ORDER BY r.REV) AS DAYS_FROM_PREV
            FROM PART_REV r
           WHERE r.APPROVED_DT IS NOT NULL
           ORDER BY
                 r.PART_NO
               , r.REV"""),
      new Example("일정 · 진행", "계획 대비 지연 건과 지연 일수", "끝났으면 실제 끝난 날, 안 끝났으면 오늘 기준으로 계획보다 며칠 늦었는지.",
          """
          SELECT
                 s.PROJ_ID
               , s.PART_NO
               , s.STEP_CD
               , s.PLAN_END
               , s.ACT_END
               , NVL(s.ACT_END, TRUNC(SYSDATE)) - s.PLAN_END AS DELAY_DAYS
               , CASE WHEN s.ACT_END IS NULL THEN '진행 중' ELSE '완료' END AS STATE
            FROM SCHEDULE s
           WHERE NVL(s.ACT_END, TRUNC(SYSDATE)) > s.PLAN_END
           ORDER BY
                 DELAY_DAYS DESC""",
          """
          SELECT
                 s.PROJ_ID
               , s.PART_NO
               , s.STEP_CD
               , s.PLAN_END
               , s.ACT_END
               , COALESCE(s.ACT_END, CURRENT_DATE) - s.PLAN_END AS DELAY_DAYS
               , CASE WHEN s.ACT_END IS NULL THEN '진행 중' ELSE '완료' END AS STATE
            FROM SCHEDULE s
           WHERE COALESCE(s.ACT_END, CURRENT_DATE) > s.PLAN_END
           ORDER BY
                 DELAY_DAYS DESC"""),
      new Example("일정 · 진행", "단계별 평균 리드타임 (계획 · 실적)", "단계마다 계획 기간과 실제 걸린 기간의 평균을 나란히.",
          """
          SELECT
                 s.STEP_CD
               , COUNT(*) AS CNT
               , ROUND(AVG(s.PLAN_END - s.PLAN_START), 1) AS PLAN_DAYS
               , ROUND(AVG(s.ACT_END - s.ACT_START), 1) AS ACT_DAYS
            FROM SCHEDULE s
           WHERE s.ACT_END IS NOT NULL
           GROUP BY
                 s.STEP_CD
           ORDER BY
                 s.STEP_CD""",
          """
          SELECT
                 s.STEP_CD
               , COUNT(*) AS CNT
               , ROUND(AVG(s.PLAN_END - s.PLAN_START), 1) AS PLAN_DAYS
               , ROUND(AVG(s.ACT_END - s.ACT_START), 1) AS ACT_DAYS
            FROM SCHEDULE s
           WHERE s.ACT_END IS NOT NULL
           GROUP BY
                 s.STEP_CD
           ORDER BY
                 s.STEP_CD"""),
      new Example("일정 · 진행", "프로젝트 진행률 (끝난 단계 / 전체)", "단계 수 기준 진행률과 지연 단계 수를 한 줄로.",
          """
          SELECT
                 p.PROJ_ID
               , p.PROJ_NAME
               , COUNT(*) AS STEPS
               , SUM(CASE WHEN s.ACT_END IS NOT NULL THEN 1 ELSE 0 END) AS DONE
               , ROUND(SUM(CASE WHEN s.ACT_END IS NOT NULL THEN 1 ELSE 0 END) * 100 / COUNT(*), 1) AS PROGRESS_PCT
               , SUM(CASE WHEN NVL(s.ACT_END, TRUNC(SYSDATE)) > s.PLAN_END THEN 1 ELSE 0 END) AS LATE
            FROM PROJECT p, SCHEDULE s
           WHERE p.PROJ_ID = s.PROJ_ID
           GROUP BY
                 p.PROJ_ID
               , p.PROJ_NAME
           ORDER BY
                 p.PROJ_ID""",
          """
          SELECT
                 p.PROJ_ID
               , p.PROJ_NAME
               , COUNT(*) AS STEPS
               , SUM(CASE WHEN s.ACT_END IS NOT NULL THEN 1 ELSE 0 END) AS DONE
               , ROUND(SUM(CASE WHEN s.ACT_END IS NOT NULL THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 1) AS PROGRESS_PCT
               , SUM(CASE WHEN COALESCE(s.ACT_END, CURRENT_DATE) > s.PLAN_END THEN 1 ELSE 0 END) AS LATE
            FROM PROJECT p, SCHEDULE s
           WHERE p.PROJ_ID = s.PROJ_ID
           GROUP BY
                 p.PROJ_ID
               , p.PROJ_NAME
           ORDER BY
                 p.PROJ_ID"""),
      new Example("일정 · 진행", "같은 담당자 일정이 겹치는 것", "한 사람(또는 설비)에게 기간이 겹치게 잡힌 두 일정. 시작 < 상대 끝 AND 상대 시작 < 끝 이면 겹칩니다.",
          """
          SELECT
                 a.OWNER_ID
               , a.SCH_ID AS SCH_A
               , a.PLAN_START AS A_START
               , a.PLAN_END AS A_END
               , b.SCH_ID AS SCH_B
               , b.PLAN_START AS B_START
               , b.PLAN_END AS B_END
            FROM SCHEDULE a, SCHEDULE b
           WHERE a.OWNER_ID = b.OWNER_ID
             AND a.SCH_ID < b.SCH_ID
             AND a.PLAN_START < b.PLAN_END
             AND b.PLAN_START < a.PLAN_END
           ORDER BY
                 a.OWNER_ID
               , a.PLAN_START""",
          """
          SELECT
                 a.OWNER_ID
               , a.SCH_ID AS SCH_A
               , a.PLAN_START AS A_START
               , a.PLAN_END AS A_END
               , b.SCH_ID AS SCH_B
               , b.PLAN_START AS B_START
               , b.PLAN_END AS B_END
            FROM SCHEDULE a, SCHEDULE b
           WHERE a.OWNER_ID = b.OWNER_ID
             AND a.SCH_ID < b.SCH_ID
             AND a.PLAN_START < b.PLAN_END
             AND b.PLAN_START < a.PLAN_END
           ORDER BY
                 a.OWNER_ID
               , a.PLAN_START"""),
      new Example("일정 · 진행", "연속 구간 묶기 (끊기지 않은 날짜끼리 한 덩어리)", "날짜에서 순번을 빼면 이어진 날끼리 같은 값이 됩니다(gaps and islands). 연속 출근 · 연속 가동 구간에 씁니다.",
          """
          SELECT
                 g.EMP_ID
               , MIN(g.WORK_DT) AS FROM_DT
               , MAX(g.WORK_DT) AS TO_DT
               , COUNT(*) AS DAYS
            FROM (
                  SELECT
                         a.EMP_ID
                       , a.WORK_DT
                       , a.WORK_DT - ROW_NUMBER() OVER (PARTITION BY a.EMP_ID ORDER BY a.WORK_DT) AS GRP
                    FROM ATTEND a
                 ) g
           GROUP BY
                 g.EMP_ID
               , g.GRP
           ORDER BY
                 g.EMP_ID
               , FROM_DT""",
          """
          SELECT
                 g.EMP_ID
               , MIN(g.WORK_DT) AS FROM_DT
               , MAX(g.WORK_DT) AS TO_DT
               , COUNT(*) AS DAYS
            FROM (
                  SELECT
                         a.EMP_ID
                       , a.WORK_DT
                       , a.WORK_DT - CAST(ROW_NUMBER() OVER (PARTITION BY a.EMP_ID ORDER BY a.WORK_DT) AS INTEGER) AS GRP
                    FROM ATTEND a
                 ) g
           GROUP BY
                 g.EMP_ID
               , g.GRP
           ORDER BY
                 g.EMP_ID
               , FROM_DT"""),
      new Example("일정 · 진행", "빈 구간 찾기 (다음 일정까지 비는 날)", "LEAD 로 다음 시작일을 붙여 끝과 다음 시작 사이가 비는 곳만.",
          """
          SELECT
                 x.OWNER_ID
               , x.PLAN_END AS GAP_FROM
               , x.NEXT_START AS GAP_TO
               , x.NEXT_START - x.PLAN_END AS GAP_DAYS
            FROM (
                  SELECT
                         s.OWNER_ID
                       , s.PLAN_END
                       , LEAD(s.PLAN_START) OVER (PARTITION BY s.OWNER_ID ORDER BY s.PLAN_START) AS NEXT_START
                    FROM SCHEDULE s
                 ) x
           WHERE x.NEXT_START > x.PLAN_END
           ORDER BY
                 x.OWNER_ID
               , x.PLAN_END""",
          """
          SELECT
                 x.OWNER_ID
               , x.PLAN_END AS GAP_FROM
               , x.NEXT_START AS GAP_TO
               , x.NEXT_START - x.PLAN_END AS GAP_DAYS
            FROM (
                  SELECT
                         s.OWNER_ID
                       , s.PLAN_END
                       , LEAD(s.PLAN_START) OVER (PARTITION BY s.OWNER_ID ORDER BY s.PLAN_START) AS NEXT_START
                    FROM SCHEDULE s
                 ) x
           WHERE x.NEXT_START > x.PLAN_END
           ORDER BY
                 x.OWNER_ID
               , x.PLAN_END"""),
      new Example("일정 · 진행", "마일스톤 가로표 (부품별 단계 계획 · 실적 한 줄)", "단계가 행으로 쌓인 일정을 부품 한 줄에 단계별 계획일 · 실적일로 펼칩니다.",
          """
          SELECT
                 s.PROJ_ID
               , s.PART_NO
               , MAX(CASE WHEN s.STEP_SEQ = 1 THEN s.PLAN_END END) AS S1_PLAN
               , MAX(CASE WHEN s.STEP_SEQ = 1 THEN s.ACT_END END) AS S1_ACT
               , MAX(CASE WHEN s.STEP_SEQ = 2 THEN s.PLAN_END END) AS S2_PLAN
               , MAX(CASE WHEN s.STEP_SEQ = 2 THEN s.ACT_END END) AS S2_ACT
               , MAX(CASE WHEN s.STEP_SEQ = 3 THEN s.PLAN_END END) AS S3_PLAN
               , MAX(CASE WHEN s.STEP_SEQ = 3 THEN s.ACT_END END) AS S3_ACT
               , MAX(CASE WHEN s.STEP_SEQ = 4 THEN s.PLAN_END END) AS S4_PLAN
               , MAX(CASE WHEN s.STEP_SEQ = 4 THEN s.ACT_END END) AS S4_ACT
            FROM SCHEDULE s
           GROUP BY
                 s.PROJ_ID
               , s.PART_NO
           ORDER BY
                 s.PROJ_ID
               , s.PART_NO""",
          """
          SELECT
                 s.PROJ_ID
               , s.PART_NO
               , MAX(CASE WHEN s.STEP_SEQ = 1 THEN s.PLAN_END END) AS S1_PLAN
               , MAX(CASE WHEN s.STEP_SEQ = 1 THEN s.ACT_END END) AS S1_ACT
               , MAX(CASE WHEN s.STEP_SEQ = 2 THEN s.PLAN_END END) AS S2_PLAN
               , MAX(CASE WHEN s.STEP_SEQ = 2 THEN s.ACT_END END) AS S2_ACT
               , MAX(CASE WHEN s.STEP_SEQ = 3 THEN s.PLAN_END END) AS S3_PLAN
               , MAX(CASE WHEN s.STEP_SEQ = 3 THEN s.ACT_END END) AS S3_ACT
               , MAX(CASE WHEN s.STEP_SEQ = 4 THEN s.PLAN_END END) AS S4_PLAN
               , MAX(CASE WHEN s.STEP_SEQ = 4 THEN s.ACT_END END) AS S4_ACT
            FROM SCHEDULE s
           GROUP BY
                 s.PROJ_ID
               , s.PART_NO
           ORDER BY
                 s.PROJ_ID
               , s.PART_NO"""),
      new Example("일정 · 진행", "이번 주 · 다음 주에 끝나야 할 일", "계획 끝일이 이번 주 월요일 ~ 다음 주 일요일 안이고 아직 안 끝난 것.",
          """
          SELECT
                 s.*
            FROM SCHEDULE s
           WHERE s.ACT_END IS NULL
             AND s.PLAN_END >= TRUNC(SYSDATE, 'IW')
             AND s.PLAN_END < TRUNC(SYSDATE, 'IW') + 14
           ORDER BY
                 s.PLAN_END""",
          """
          SELECT
                 s.*
            FROM SCHEDULE s
           WHERE s.ACT_END IS NULL
             AND s.PLAN_END >= CAST(DATE_TRUNC('WEEK', CURRENT_DATE) AS DATE)
             AND s.PLAN_END < CAST(DATE_TRUNC('WEEK', CURRENT_DATE) AS DATE) + 14
           ORDER BY
                 s.PLAN_END"""),
      new Example("일정 · 진행", "담당자별 이번 주 일정 (요일 칸)", "담당자마다 이번 주 월~금 무슨 일정이 있는지 칸으로.",
          """
          SELECT
                 s.OWNER_ID
               , LISTAGG(CASE WHEN TRUNC(SYSDATE, 'IW') BETWEEN s.PLAN_START AND s.PLAN_END THEN s.STEP_CD END, ',') WITHIN GROUP (ORDER BY s.STEP_SEQ) AS MON
               , LISTAGG(CASE WHEN TRUNC(SYSDATE, 'IW') + 2 BETWEEN s.PLAN_START AND s.PLAN_END THEN s.STEP_CD END, ',') WITHIN GROUP (ORDER BY s.STEP_SEQ) AS WED
               , LISTAGG(CASE WHEN TRUNC(SYSDATE, 'IW') + 4 BETWEEN s.PLAN_START AND s.PLAN_END THEN s.STEP_CD END, ',') WITHIN GROUP (ORDER BY s.STEP_SEQ) AS FRI
            FROM SCHEDULE s
           WHERE s.PLAN_START <= TRUNC(SYSDATE, 'IW') + 4
             AND s.PLAN_END >= TRUNC(SYSDATE, 'IW')
           GROUP BY
                 s.OWNER_ID""",
          """
          SELECT
                 s.OWNER_ID
               , STRING_AGG(s.STEP_CD, ',' ORDER BY s.STEP_SEQ) FILTER (WHERE CAST(DATE_TRUNC('WEEK', CURRENT_DATE) AS DATE) BETWEEN s.PLAN_START AND s.PLAN_END) AS MON
               , STRING_AGG(s.STEP_CD, ',' ORDER BY s.STEP_SEQ) FILTER (WHERE CAST(DATE_TRUNC('WEEK', CURRENT_DATE) AS DATE) + 2 BETWEEN s.PLAN_START AND s.PLAN_END) AS WED
               , STRING_AGG(s.STEP_CD, ',' ORDER BY s.STEP_SEQ) FILTER (WHERE CAST(DATE_TRUNC('WEEK', CURRENT_DATE) AS DATE) + 4 BETWEEN s.PLAN_START AND s.PLAN_END) AS FRI
            FROM SCHEDULE s
           WHERE s.PLAN_START <= CAST(DATE_TRUNC('WEEK', CURRENT_DATE) AS DATE) + 4
             AND s.PLAN_END >= CAST(DATE_TRUNC('WEEK', CURRENT_DATE) AS DATE)
           GROUP BY
                 s.OWNER_ID"""),
      new Example("일정 · 진행", "앞 단계가 안 끝났는데 시작한 단계 (순서 어김)", "같은 부품 일정에서 앞 순번 실적 끝보다 먼저 시작한 단계.",
          """
          SELECT
                 x.PROJ_ID
               , x.PART_NO
               , x.STEP_CD
               , x.ACT_START
               , x.PREV_END
            FROM (
                  SELECT
                         s.PROJ_ID
                       , s.PART_NO
                       , s.STEP_CD
                       , s.ACT_START
                       , LAG(s.ACT_END) OVER (PARTITION BY s.PROJ_ID, s.PART_NO ORDER BY s.STEP_SEQ) AS PREV_END
                    FROM SCHEDULE s
                 ) x
           WHERE x.ACT_START < x.PREV_END""",
          """
          SELECT
                 x.PROJ_ID
               , x.PART_NO
               , x.STEP_CD
               , x.ACT_START
               , x.PREV_END
            FROM (
                  SELECT
                         s.PROJ_ID
                       , s.PART_NO
                       , s.STEP_CD
                       , s.ACT_START
                       , LAG(s.ACT_END) OVER (PARTITION BY s.PROJ_ID, s.PART_NO ORDER BY s.STEP_SEQ) AS PREV_END
                    FROM SCHEDULE s
                 ) x
           WHERE x.ACT_START < x.PREV_END"""),
      new Example("일정 · 진행", "간트 차트용 (계획 · 실적 막대 시작 · 끝 · 지연)", "간트 화면에 그대로 넣는 칸: 계획 막대, 실적 막대, 지연 일수, 오늘 기준 상태.",
          """
          SELECT
                 s.PROJ_ID
               , s.PART_NO
               , s.STEP_SEQ
               , s.STEP_CD
               , s.PLAN_START
               , s.PLAN_END
               , s.ACT_START
               , NVL(s.ACT_END, CASE WHEN s.ACT_START IS NOT NULL THEN TRUNC(SYSDATE) END) AS ACT_END_BAR
               , GREATEST(NVL(s.ACT_END, TRUNC(SYSDATE)) - s.PLAN_END, 0) AS DELAY_DAYS
               , CASE
                   WHEN s.ACT_END IS NOT NULL THEN '완료'
                   WHEN s.ACT_START IS NOT NULL THEN '진행'
                   WHEN s.PLAN_START < TRUNC(SYSDATE) THEN '시작 지연'
                   ELSE '예정'
                 END AS STATE
            FROM SCHEDULE s
           ORDER BY
                 s.PROJ_ID
               , s.PART_NO
               , s.STEP_SEQ""",
          """
          SELECT
                 s.PROJ_ID
               , s.PART_NO
               , s.STEP_SEQ
               , s.STEP_CD
               , s.PLAN_START
               , s.PLAN_END
               , s.ACT_START
               , COALESCE(s.ACT_END, CASE WHEN s.ACT_START IS NOT NULL THEN CURRENT_DATE END) AS ACT_END_BAR
               , GREATEST(COALESCE(s.ACT_END, CURRENT_DATE) - s.PLAN_END, 0) AS DELAY_DAYS
               , CASE
                   WHEN s.ACT_END IS NOT NULL THEN '완료'
                   WHEN s.ACT_START IS NOT NULL THEN '진행'
                   WHEN s.PLAN_START < CURRENT_DATE THEN '시작 지연'
                   ELSE '예정'
                 END AS STATE
            FROM SCHEDULE s
           ORDER BY
                 s.PROJ_ID
               , s.PART_NO
               , s.STEP_SEQ"""),
      new Example("일정 · 진행", "설비 · 담당자 투입 부하 (날짜별 동시 일정 수)", "날짜마다 같은 설비에 겹친 일정 수. 2 이상이면 과부하.",
          """
          SELECT
                 s.EQUIP_ID
               , d.DT
               , COUNT(*) AS LOAD_CNT
            FROM SCHEDULE s
               , (SELECT TRUNC(SYSDATE) + LEVEL - 1 AS DT FROM DUAL CONNECT BY LEVEL <= 30) d
           WHERE d.DT BETWEEN s.PLAN_START AND s.PLAN_END
             AND s.EQUIP_ID IS NOT NULL
           GROUP BY
                 s.EQUIP_ID
               , d.DT
          HAVING COUNT(*) >= 2
           ORDER BY
                 s.EQUIP_ID
               , d.DT""",
          """
          SELECT
                 s.EQUIP_ID
               , d.DT
               , COUNT(*) AS LOAD_CNT
            FROM SCHEDULE s
               , (SELECT CAST(g AS DATE) AS DT FROM GENERATE_SERIES(CURRENT_DATE, CURRENT_DATE + 29, INTERVAL '1 DAY') g) d
           WHERE d.DT BETWEEN s.PLAN_START AND s.PLAN_END
             AND s.EQUIP_ID IS NOT NULL
           GROUP BY
                 s.EQUIP_ID
               , d.DT
          HAVING COUNT(*) >= 2
           ORDER BY
                 s.EQUIP_ID
               , d.DT"""),
      new Example("결재 · 권한 · 코드", "지금 결재할 사람 (대기 중 첫 순번)", "문서마다 아직 대기(WAIT)인 결재선 중 가장 앞 순번. 반려된 문서는 뺍니다.",
          """
          SELECT
                 a.DOC_ID
               , a.LINE_SEQ
               , a.APPROVER_ID
               , u.USER_NAME
            FROM APPROVAL a, USERS u
           WHERE a.APPROVER_ID = u.USER_ID
             AND a.STATUS = 'WAIT'
             AND a.LINE_SEQ = (SELECT MIN(x.LINE_SEQ) FROM APPROVAL x WHERE x.DOC_ID = a.DOC_ID AND x.STATUS = 'WAIT')
             AND NOT EXISTS (SELECT 1 FROM APPROVAL r WHERE r.DOC_ID = a.DOC_ID AND r.STATUS = 'REJECT')""",
          """
          SELECT
                 a.DOC_ID
               , a.LINE_SEQ
               , a.APPROVER_ID
               , u.USER_NAME
            FROM APPROVAL a, USERS u
           WHERE a.APPROVER_ID = u.USER_ID
             AND a.STATUS = 'WAIT'
             AND a.LINE_SEQ = (SELECT MIN(x.LINE_SEQ) FROM APPROVAL x WHERE x.DOC_ID = a.DOC_ID AND x.STATUS = 'WAIT')
             AND NOT EXISTS (SELECT 1 FROM APPROVAL r WHERE r.DOC_ID = a.DOC_ID AND r.STATUS = 'REJECT')"""),
      new Example("결재 · 권한 · 코드", "내가 결재할 문서함", "로그인한 사람 차례인 문서만. 앞 순번이 모두 승인(OK)이어야 내 차례입니다.",
          """
          SELECT
                 a.DOC_ID
               , a.LINE_SEQ
            FROM APPROVAL a
           WHERE a.APPROVER_ID = :userId
             AND a.STATUS = 'WAIT'
             AND NOT EXISTS (
                   SELECT 1
                     FROM APPROVAL p
                    WHERE p.DOC_ID = a.DOC_ID
                      AND p.LINE_SEQ < a.LINE_SEQ
                      AND p.STATUS <> 'OK'
                 )""",
          """
          SELECT
                 a.DOC_ID
               , a.LINE_SEQ
            FROM APPROVAL a
           WHERE a.APPROVER_ID = :userId
             AND a.STATUS = 'WAIT'
             AND NOT EXISTS (
                   SELECT 1
                     FROM APPROVAL p
                    WHERE p.DOC_ID = a.DOC_ID
                      AND p.LINE_SEQ < a.LINE_SEQ
                      AND p.STATUS <> 'OK'
                 )"""),
      new Example("결재 · 권한 · 코드", "결재선 한 줄 요약 (홍길동(승인) > 김철수(대기))", "문서마다 결재선을 순번대로 이어 한 칸에.",
          """
          SELECT
                 a.DOC_ID
               , LISTAGG(u.USER_NAME || '(' || a.STATUS || ')', ' > ') WITHIN GROUP (ORDER BY a.LINE_SEQ) AS LINE
            FROM APPROVAL a, USERS u
           WHERE a.APPROVER_ID = u.USER_ID
           GROUP BY
                 a.DOC_ID""",
          """
          SELECT
                 a.DOC_ID
               , STRING_AGG(u.USER_NAME || '(' || a.STATUS || ')', ' > ' ORDER BY a.LINE_SEQ) AS LINE
            FROM APPROVAL a, USERS u
           WHERE a.APPROVER_ID = u.USER_ID
           GROUP BY
                 a.DOC_ID"""),
      new Example("결재 · 권한 · 코드", "반려 이력과 사유", "반려된 결재 줄, 반려한 사람과 의견.",
          """
          SELECT
                 a.DOC_ID
               , a.LINE_SEQ
               , u.USER_NAME
               , a.ACT_DT
               , a.COMMENT_TXT
            FROM APPROVAL a, USERS u
           WHERE a.APPROVER_ID = u.USER_ID
             AND a.STATUS = 'REJECT'
           ORDER BY
                 a.ACT_DT DESC""",
          """
          SELECT
                 a.DOC_ID
               , a.LINE_SEQ
               , u.USER_NAME
               , a.ACT_DT
               , a.COMMENT_TXT
            FROM APPROVAL a, USERS u
           WHERE a.APPROVER_ID = u.USER_ID
             AND a.STATUS = 'REJECT'
           ORDER BY
                 a.ACT_DT DESC"""),
      new Example("결재 · 권한 · 코드", "사용자가 볼 수 있는 메뉴 (사용자 → 역할 → 메뉴)", "역할이 여럿이면 쓰기 권한은 하나라도 Y 면 Y 로.",
          """
          SELECT
                 m.MENU_ID
               , m.MENU_NAME
               , MAX(rm.CAN_WRITE) AS CAN_WRITE
            FROM ROLE_USER ru, ROLE_MENU rm, MENU m
           WHERE ru.ROLE_ID = rm.ROLE_ID
             AND rm.MENU_ID = m.MENU_ID
             AND ru.USER_ID = :userId
           GROUP BY
                 m.MENU_ID
               , m.MENU_NAME
           ORDER BY
                 m.MENU_ID""",
          """
          SELECT
                 m.MENU_ID
               , m.MENU_NAME
               , MAX(rm.CAN_WRITE) AS CAN_WRITE
            FROM ROLE_USER ru, ROLE_MENU rm, MENU m
           WHERE ru.ROLE_ID = rm.ROLE_ID
             AND rm.MENU_ID = m.MENU_ID
             AND ru.USER_ID = :userId
           GROUP BY
                 m.MENU_ID
               , m.MENU_NAME
           ORDER BY
                 m.MENU_ID"""),
      new Example("결재 · 권한 · 코드", "권한 없는 사용자 · 아무도 못 쓰는 메뉴 (점검)", "역할이 하나도 없는 사용자와 어느 역할에도 안 붙은 메뉴.",
          """
          SELECT
                 '역할 없는 사용자' AS KIND
               , CAST(u.USER_ID AS VARCHAR2(20)) AS ID
               , u.USER_NAME AS NAME
            FROM USERS u
           WHERE NOT EXISTS (SELECT 1 FROM ROLE_USER r WHERE r.USER_ID = u.USER_ID)
           UNION ALL
          SELECT
                 '역할 없는 메뉴'
               , CAST(m.MENU_ID AS VARCHAR2(20))
               , m.MENU_NAME
            FROM MENU m
           WHERE NOT EXISTS (SELECT 1 FROM ROLE_MENU r WHERE r.MENU_ID = m.MENU_ID)""",
          """
          SELECT
                 '역할 없는 사용자' AS KIND
               , CAST(u.USER_ID AS VARCHAR(20)) AS ID
               , u.USER_NAME AS NAME
            FROM USERS u
           WHERE NOT EXISTS (SELECT 1 FROM ROLE_USER r WHERE r.USER_ID = u.USER_ID)
           UNION ALL
          SELECT
                 '역할 없는 메뉴'
               , CAST(m.MENU_ID AS VARCHAR(20))
               , m.MENU_NAME
            FROM MENU m
           WHERE NOT EXISTS (SELECT 1 FROM ROLE_MENU r WHERE r.MENU_ID = m.MENU_ID)"""),
      new Example("결재 · 권한 · 코드", "내 부서와 아래 부서 데이터만 (부서 트리 권한)", "로그인한 사람 부서부터 아래 부서 전부의 프로젝트.",
          """
          SELECT
                 p.*
            FROM PROJECT p
           WHERE p.DEPT_ID IN (
                   SELECT d.DEPT_ID
                     FROM DEPT d
                    START WITH d.DEPT_ID = :myDeptId
                  CONNECT BY PRIOR d.DEPT_ID = d.PARENT_DEPT_ID
                 )""",
          """
          WITH RECURSIVE sub AS (
          SELECT
                 d.DEPT_ID
               , 1 AS LVL
            FROM DEPT d
           WHERE d.DEPT_ID = :myDeptId
           UNION ALL
          SELECT
                 d.DEPT_ID
               , sub.LVL + 1
            FROM DEPT d, sub
           WHERE d.PARENT_DEPT_ID = sub.DEPT_ID
             AND sub.LVL < 20
          )
          SELECT
                 p.*
            FROM PROJECT p
           WHERE p.DEPT_ID IN (SELECT sub.DEPT_ID FROM sub)"""),
      new Example("결재 · 권한 · 코드", "공통코드 이름 붙이기", "코드 칼럼 옆에 공통코드 이름을. 코드가 없어도 줄은 남기고, 이름이 없으면 코드 그대로 보입니다.",
          """
          SELECT
                 p.PROJ_ID
               , p.STATUS
               , NVL(c.CODE_NAME, p.STATUS) AS STATUS_NAME
            FROM PROJECT p, COMMON_CODE c
           WHERE p.STATUS = c.CODE(+)
             AND c.GROUP_CD(+) = 'STATUS'""",
          """
          SELECT
                 p.PROJ_ID
               , p.STATUS
               , COALESCE(c.CODE_NAME, p.STATUS) AS STATUS_NAME
            FROM PROJECT p
            LEFT OUTER JOIN COMMON_CODE c
              ON p.STATUS = c.CODE
             AND c.GROUP_CD = 'STATUS'"""),
      new Example("결재 · 권한 · 코드", "공통코드 이름 (스칼라 서브쿼리로)", "코드 칼럼이 여러 개라 조인이 길어질 때 칸마다 작은 SELECT 로 이름을 꺼냅니다.",
          """
          SELECT
                 p.PROJ_ID
               , p.STATUS
               , (SELECT c.CODE_NAME FROM COMMON_CODE c WHERE c.GROUP_CD = 'STATUS' AND c.CODE = p.STATUS) AS STATUS_NAME
            FROM PROJECT p""",
          """
          SELECT
                 p.PROJ_ID
               , p.STATUS
               , (SELECT c.CODE_NAME FROM COMMON_CODE c WHERE c.GROUP_CD = 'STATUS' AND c.CODE = p.STATUS) AS STATUS_NAME
            FROM PROJECT p"""),
      new Example("결재 · 권한 · 코드", "다국어 이름, 없으면 기본 언어로", "선택한 언어 이름이 없으면 기본(한국어) 이름을 씁니다.",
          """
          SELECT
                 c.GROUP_CD
               , c.CODE
               , NVL(l.CODE_NAME, c.CODE_NAME) AS CODE_NAME
            FROM COMMON_CODE c, CODE_LANG l
           WHERE c.GROUP_CD = l.GROUP_CD(+)
             AND c.CODE = l.CODE(+)
             AND l.LANG(+) = :lang
             AND c.USE_YN = 'Y'
           ORDER BY
                 c.GROUP_CD
               , c.SORT_NO""",
          """
          SELECT
                 c.GROUP_CD
               , c.CODE
               , COALESCE(l.CODE_NAME, c.CODE_NAME) AS CODE_NAME
            FROM COMMON_CODE c
            LEFT OUTER JOIN CODE_LANG l
              ON c.GROUP_CD = l.GROUP_CD
             AND c.CODE = l.CODE
             AND l.LANG = :lang
           WHERE c.USE_YN = 'Y'
           ORDER BY
                 c.GROUP_CD
               , c.SORT_NO"""),
      new Example("결재 · 권한 · 코드", "콤보 상자용 코드 목록", "화면 선택 상자에 넣을 코드 · 이름. 쓰지 않는 코드는 빼고 정렬 순서대로.",
          """
          SELECT
                 c.CODE AS VALUE
               , c.CODE_NAME AS LABEL
            FROM COMMON_CODE c
           WHERE c.GROUP_CD = :groupCd
             AND c.USE_YN = 'Y'
           ORDER BY
                 c.SORT_NO""",
          """
          SELECT
                 c.CODE AS VALUE
               , c.CODE_NAME AS LABEL
            FROM COMMON_CODE c
           WHERE c.GROUP_CD = :groupCd
             AND c.USE_YN = 'Y'
           ORDER BY
                 c.SORT_NO"""),
      new Example("결재 · 권한 · 코드", "결재 걸린 시간 (단계마다 · 문서 전체)", "결재 줄마다 앞 단계 처리부터 이번 처리까지 걸린 시간과 문서 전체 시간.",
          """
          SELECT
                 a.DOC_ID
               , a.LINE_SEQ
               , a.APPROVER_ID
               , a.ACT_DT
               , ROUND((CAST(a.ACT_DT AS DATE) - CAST(LAG(a.ACT_DT) OVER (PARTITION BY a.DOC_ID ORDER BY a.LINE_SEQ) AS DATE)) * 24, 1) AS STEP_HOURS
               , ROUND((CAST(MAX(a.ACT_DT) OVER (PARTITION BY a.DOC_ID) AS DATE) - CAST(MIN(a.ACT_DT) OVER (PARTITION BY a.DOC_ID) AS DATE)) * 24, 1) AS DOC_HOURS
            FROM APPROVAL a
           WHERE a.ACT_DT IS NOT NULL""",
          """
          SELECT
                 a.DOC_ID
               , a.LINE_SEQ
               , a.APPROVER_ID
               , a.ACT_DT
               , ROUND(CAST(EXTRACT(EPOCH FROM (a.ACT_DT - LAG(a.ACT_DT) OVER (PARTITION BY a.DOC_ID ORDER BY a.LINE_SEQ))) / 3600 AS NUMERIC), 1) AS STEP_HOURS
               , ROUND(CAST(EXTRACT(EPOCH FROM (MAX(a.ACT_DT) OVER (PARTITION BY a.DOC_ID) - MIN(a.ACT_DT) OVER (PARTITION BY a.DOC_ID))) / 3600 AS NUMERIC), 1) AS DOC_HOURS
            FROM APPROVAL a
           WHERE a.ACT_DT IS NOT NULL"""),
      new Example("결재 · 권한 · 코드", "결재 대기가 오래된 사람 (3일 넘게 안 본 것)", "앞 단계가 끝났는데 내 차례에서 3일 넘게 멈춘 결재.",
          """
          SELECT
                 a.APPROVER_ID
               , a.DOC_ID
               , TRUNC(SYSDATE) - TRUNC(CAST(p.ACT_DT AS DATE)) AS WAIT_DAYS
            FROM APPROVAL a
               , APPROVAL p
           WHERE a.DOC_ID = p.DOC_ID
             AND p.LINE_SEQ = a.LINE_SEQ - 1
             AND a.STATUS = 'WAIT'
             AND p.STATUS = 'APPROVED'
             AND p.ACT_DT < SYSDATE - 3
           ORDER BY
                 WAIT_DAYS DESC""",
          """
          SELECT
                 a.APPROVER_ID
               , a.DOC_ID
               , CURRENT_DATE - CAST(p.ACT_DT AS DATE) AS WAIT_DAYS
            FROM APPROVAL a
               , APPROVAL p
           WHERE a.DOC_ID = p.DOC_ID
             AND p.LINE_SEQ = a.LINE_SEQ - 1
             AND a.STATUS = 'WAIT'
             AND p.STATUS = 'APPROVED'
             AND p.ACT_DT < NOW() - INTERVAL '3 DAY'
           ORDER BY
                 WAIT_DAYS DESC"""),
      new Example("결재 · 권한 · 코드", "역할별 메뉴 권한 가로표 (읽기 · 쓰기)", "메뉴를 줄로, 역할을 칸으로 놓고 R · W 를 찍습니다. 권한 점검표.",
          """
          SELECT
                 m.MENU_NAME
               , MAX(CASE WHEN r.ROLE_ID = 'ADMIN' THEN DECODE(r.CAN_WRITE, 'Y', 'W', 'R') END) AS ADMIN
               , MAX(CASE WHEN r.ROLE_ID = 'USER' THEN DECODE(r.CAN_WRITE, 'Y', 'W', 'R') END) AS USER_ROLE
               , MAX(CASE WHEN r.ROLE_ID = 'GUEST' THEN DECODE(r.CAN_WRITE, 'Y', 'W', 'R') END) AS GUEST
            FROM MENU m
               , ROLE_MENU r
           WHERE m.MENU_ID = r.MENU_ID(+)
           GROUP BY
                 m.MENU_NAME""",
          """
          SELECT
                 m.MENU_NAME
               , MAX(CASE WHEN r.ROLE_ID = 'ADMIN' THEN CASE r.CAN_WRITE WHEN 'Y' THEN 'W' ELSE 'R' END END) AS ADMIN
               , MAX(CASE WHEN r.ROLE_ID = 'USER' THEN CASE r.CAN_WRITE WHEN 'Y' THEN 'W' ELSE 'R' END END) AS USER_ROLE
               , MAX(CASE WHEN r.ROLE_ID = 'GUEST' THEN CASE r.CAN_WRITE WHEN 'Y' THEN 'W' ELSE 'R' END END) AS GUEST
            FROM MENU m
            LEFT OUTER JOIN ROLE_MENU r
              ON m.MENU_ID = r.MENU_ID
           GROUP BY
                 m.MENU_NAME"""),
      new Example("결재 · 권한 · 코드", "코드 그룹별 코드 수 · 안 쓰는 코드 수", "공통코드 정리용. 그룹마다 전체 · 사용 중 · 사용 안 함.",
          """
          SELECT
                 c.GROUP_CD
               , COUNT(*) AS TOTAL
               , SUM(CASE WHEN c.USE_YN = 'Y' THEN 1 ELSE 0 END) AS USE_CNT
               , SUM(CASE WHEN c.USE_YN = 'N' THEN 1 ELSE 0 END) AS UNUSED_CNT
            FROM COMMON_CODE c
           GROUP BY
                 c.GROUP_CD
           ORDER BY
                 c.GROUP_CD""",
          """
          SELECT
                 c.GROUP_CD
               , COUNT(*) AS TOTAL
               , SUM(CASE WHEN c.USE_YN = 'Y' THEN 1 ELSE 0 END) AS USE_CNT
               , SUM(CASE WHEN c.USE_YN = 'N' THEN 1 ELSE 0 END) AS UNUSED_CNT
            FROM COMMON_CODE c
           GROUP BY
                 c.GROUP_CD
           ORDER BY
                 c.GROUP_CD"""),
      new Example("결재 · 권한 · 코드", "순번을 칸으로 (결재자 1 · 2 · 3 을 한 줄에)", "문서마다 결재 순번 1~3 의 결재자와 상태를 칸으로 놓습니다.",
          """
          SELECT
                 a.DOC_ID
               , MAX(CASE WHEN a.LINE_SEQ = 1 THEN a.APPROVER_ID END) AS APPR1
               , MAX(CASE WHEN a.LINE_SEQ = 1 THEN a.STATUS END) AS STAT1
               , MAX(CASE WHEN a.LINE_SEQ = 2 THEN a.APPROVER_ID END) AS APPR2
               , MAX(CASE WHEN a.LINE_SEQ = 2 THEN a.STATUS END) AS STAT2
               , MAX(CASE WHEN a.LINE_SEQ = 3 THEN a.APPROVER_ID END) AS APPR3
               , MAX(CASE WHEN a.LINE_SEQ = 3 THEN a.STATUS END) AS STAT3
            FROM APPROVAL a
           GROUP BY
                 a.DOC_ID""",
          """
          SELECT
                 a.DOC_ID
               , MAX(CASE WHEN a.LINE_SEQ = 1 THEN a.APPROVER_ID END) AS APPR1
               , MAX(CASE WHEN a.LINE_SEQ = 1 THEN a.STATUS END) AS STAT1
               , MAX(CASE WHEN a.LINE_SEQ = 2 THEN a.APPROVER_ID END) AS APPR2
               , MAX(CASE WHEN a.LINE_SEQ = 2 THEN a.STATUS END) AS STAT2
               , MAX(CASE WHEN a.LINE_SEQ = 3 THEN a.APPROVER_ID END) AS APPR3
               , MAX(CASE WHEN a.LINE_SEQ = 3 THEN a.STATUS END) AS STAT3
            FROM APPROVAL a
           GROUP BY
                 a.DOC_ID"""),
      new Example("채번 · 데이터 손질", "채번: 연월 + 일련번호 (그달 첫 번호 0001)", "R + YYYYMM + 4자리. 그달 가장 큰 번호 + 1, 없으면 1. 동시에 두 사람이 받으면 같은 번호가 나오므로 번호 표를 잠그거나 시퀀스와 함께 씁니다.",
          """
          SELECT
                 'R' || TO_CHAR(SYSDATE, 'YYYYMM')
                 || LPAD(NVL(MAX(TO_NUMBER(SUBSTR(r.REQ_NO, 8))), 0) + 1, 4, '0') AS NEXT_NO
            FROM REQ r
           WHERE r.REQ_NO LIKE 'R' || TO_CHAR(SYSDATE, 'YYYYMM') || '%'""",
          """
          SELECT
                 'R' || TO_CHAR(CURRENT_DATE, 'YYYYMM')
                 || LPAD(CAST(COALESCE(MAX(CAST(SUBSTR(r.REQ_NO, 8) AS INTEGER)), 0) + 1 AS VARCHAR), 4, '0') AS NEXT_NO
            FROM REQ r
           WHERE r.REQ_NO LIKE 'R' || TO_CHAR(CURRENT_DATE, 'YYYYMM') || '%'"""),
      new Example("채번 · 데이터 손질", "채번: 하루 단위 일련번호 (날짜 + 3자리)", "같은 날 안에서 001 부터. 넣는 줄 자체에서 바로 계산하는 꼴입니다.",
          """
          INSERT INTO REQ (REQ_NO, TITLE, REG_DT)
          SELECT
                 TO_CHAR(SYSDATE, 'YYYYMMDD') || LPAD(COUNT(*) + 1, 3, '0')
               , :title
               , SYSDATE
            FROM REQ r
           WHERE r.REG_DT >= TRUNC(SYSDATE)
             AND r.REG_DT < TRUNC(SYSDATE) + 1""",
          """
          INSERT INTO REQ (REQ_NO, TITLE, REG_DT)
          SELECT
                 TO_CHAR(CURRENT_DATE, 'YYYYMMDD') || LPAD(CAST(COUNT(*) + 1 AS VARCHAR), 3, '0')
               , :title
               , CURRENT_DATE
            FROM REQ r
           WHERE r.REG_DT >= CURRENT_DATE
             AND r.REG_DT < CURRENT_DATE + 1"""),
      new Example("채번 · 데이터 손질", "번호가 빠진 곳 찾기 (채번 구멍)", "일련번호 사이에 빠진 번호. LEAD 로 다음 번호를 붙여 1보다 크게 뛰는 곳만.",
          """
          SELECT
                 x.NO + 1 AS MISSING_FROM
               , x.NEXT_NO - 1 AS MISSING_TO
            FROM (
                  SELECT
                         TO_NUMBER(SUBSTR(r.REQ_NO, 8)) AS NO
                       , LEAD(TO_NUMBER(SUBSTR(r.REQ_NO, 8))) OVER (ORDER BY r.REQ_NO) AS NEXT_NO
                    FROM REQ r
                   WHERE r.REQ_NO LIKE 'R' || :yyyymm || '%'
                 ) x
           WHERE x.NEXT_NO - x.NO > 1""",
          """
          SELECT
                 x.NO + 1 AS MISSING_FROM
               , x.NEXT_NO - 1 AS MISSING_TO
            FROM (
                  SELECT
                         CAST(SUBSTR(r.REQ_NO, 8) AS INTEGER) AS NO
                       , LEAD(CAST(SUBSTR(r.REQ_NO, 8) AS INTEGER)) OVER (ORDER BY r.REQ_NO) AS NEXT_NO
                    FROM REQ r
                   WHERE r.REQ_NO LIKE 'R' || :yyyymm || '%'
                 ) x
           WHERE x.NEXT_NO - x.NO > 1"""),
      new Example("채번 · 데이터 손질", "쉼표로 붙은 칸을 여러 행으로 (부품별 태그)", "PART.TAGS = 'A,B,C' 를 부품 · 태그 한 줄씩으로 쪼갭니다.",
          """
          SELECT
                 p.PART_NO
               , REGEXP_SUBSTR(p.TAGS, '[^,]+', 1, n.N) AS TAG
            FROM PART p
               , (SELECT LEVEL AS N FROM DUAL CONNECT BY LEVEL <= 50) n
           WHERE n.N <= REGEXP_COUNT(p.TAGS, ',') + 1
             AND p.TAGS IS NOT NULL
           ORDER BY
                 p.PART_NO
               , n.N""",
          """
          SELECT
                 p.PART_NO
               , t.TAG
            FROM PART p
               , UNNEST(STRING_TO_ARRAY(p.TAGS, ',')) WITH ORDINALITY AS t(TAG, N)
           WHERE p.TAGS IS NOT NULL
           ORDER BY
                 p.PART_NO
               , t.N"""),
      new Example("채번 · 데이터 손질", "프로젝트 통째로 복사 (부모 · 자식 새 번호로)", "프로젝트 한 건과 그 일정들을 새 번호로 복사합니다. 부모를 먼저 넣고 자식은 새 부모 번호로 넣습니다.",
          """
          INSERT INTO PROJECT (PROJ_ID, PROJ_NAME, STATUS, OWNER_ID, DEPT_ID, START_DT, END_DT, CAR_TYPE)
          SELECT
                 :newProjId
               , p.PROJ_NAME || ' (복사)'
               , 'ING'
               , p.OWNER_ID
               , p.DEPT_ID
               , p.START_DT
               , p.END_DT
               , p.CAR_TYPE
            FROM PROJECT p
           WHERE p.PROJ_ID = :srcProjId;

          INSERT INTO SCHEDULE (SCH_ID, PROJ_ID, PART_NO, STEP_CD, STEP_SEQ, PLAN_START, PLAN_END, OWNER_ID, EQUIP_ID)
          SELECT
                 (SELECT NVL(MAX(x.SCH_ID), 0) FROM SCHEDULE x) + ROW_NUMBER() OVER (ORDER BY s.SCH_ID)
               , :newProjId
               , s.PART_NO
               , s.STEP_CD
               , s.STEP_SEQ
               , s.PLAN_START
               , s.PLAN_END
               , s.OWNER_ID
               , s.EQUIP_ID
            FROM SCHEDULE s
           WHERE s.PROJ_ID = :srcProjId""",
          """
          INSERT INTO PROJECT (PROJ_ID, PROJ_NAME, STATUS, OWNER_ID, DEPT_ID, START_DT, END_DT, CAR_TYPE)
          SELECT
                 :newProjId
               , p.PROJ_NAME || ' (복사)'
               , 'ING'
               , p.OWNER_ID
               , p.DEPT_ID
               , p.START_DT
               , p.END_DT
               , p.CAR_TYPE
            FROM PROJECT p
           WHERE p.PROJ_ID = :srcProjId;

          INSERT INTO SCHEDULE (SCH_ID, PROJ_ID, PART_NO, STEP_CD, STEP_SEQ, PLAN_START, PLAN_END, OWNER_ID, EQUIP_ID)
          SELECT
                 (SELECT COALESCE(MAX(x.SCH_ID), 0) FROM SCHEDULE x) + ROW_NUMBER() OVER (ORDER BY s.SCH_ID)
               , :newProjId
               , s.PART_NO
               , s.STEP_CD
               , s.STEP_SEQ
               , s.PLAN_START
               , s.PLAN_END
               , s.OWNER_ID
               , s.EQUIP_ID
            FROM SCHEDULE s
           WHERE s.PROJ_ID = :srcProjId"""),
      new Example("채번 · 데이터 손질", "자식부터 지우기 (부모 · 자식 함께 삭제)", "외래 키가 있으면 자식을 먼저 지워야 부모가 지워집니다. 같은 트랜잭션에서 순서대로.",
          """
          DELETE FROM ISSUE i
           WHERE i.PROJ_ID = :projId;

          DELETE FROM SCHEDULE s
           WHERE s.PROJ_ID = :projId;

          DELETE FROM PROJECT p
           WHERE p.PROJ_ID = :projId""",
          """
          DELETE FROM ISSUE i
           WHERE i.PROJ_ID = :projId;

          DELETE FROM SCHEDULE s
           WHERE s.PROJ_ID = :projId;

          DELETE FROM PROJECT p
           WHERE p.PROJ_ID = :projId"""),
      new Example("채번 · 데이터 손질", "엑셀 업로드 점검 (없는 코드 · 중복 · 빈 값 · 음수)", "임시 표에 올린 줄을 마스터와 비교해 문제 있는 줄과 까닭을 한 번에 봅니다. 반영 전에 돌립니다.",
          """
          SELECT
                 u.ROW_NO
               , u.PART_NO
               , CASE
                   WHEN u.PART_NO IS NULL THEN '부품번호 빈칸'
                   WHEN NOT EXISTS (SELECT 1 FROM PART p WHERE p.PART_NO = u.PART_NO) THEN '없는 부품'
                   WHEN COUNT(*) OVER (PARTITION BY u.PART_NO) > 1 THEN '같은 부품 두 줄'
                   WHEN u.QTY <= 0 THEN '수량 0 이하'
                   WHEN NOT EXISTS (SELECT 1 FROM PART p WHERE p.PART_NO = u.PART_NO AND p.UNIT_CD = u.UNIT_CD) THEN '단위 다름'
                 END AS PROBLEM
            FROM UPLOAD_TMP u
           ORDER BY
                 u.ROW_NO""",
          """
          SELECT
                 u.ROW_NO
               , u.PART_NO
               , CASE
                   WHEN u.PART_NO IS NULL THEN '부품번호 빈칸'
                   WHEN NOT EXISTS (SELECT 1 FROM PART p WHERE p.PART_NO = u.PART_NO) THEN '없는 부품'
                   WHEN COUNT(*) OVER (PARTITION BY u.PART_NO) > 1 THEN '같은 부품 두 줄'
                   WHEN u.QTY <= 0 THEN '수량 0 이하'
                   WHEN NOT EXISTS (SELECT 1 FROM PART p WHERE p.PART_NO = u.PART_NO AND p.UNIT_CD = u.UNIT_CD) THEN '단위 다름'
                 END AS PROBLEM
            FROM UPLOAD_TMP u
           ORDER BY
                 u.ROW_NO"""),
      new Example("채번 · 데이터 손질", "업로드 반영 (점검 통과한 줄만 MERGE)", "문제없는 줄만 마스터에 넣거나 고칩니다. 위 점검을 먼저 돌리세요.",
          """
          MERGE INTO PART p
          USING (
                  SELECT
                         u.PART_NO
                       , u.PART_NAME
                    FROM UPLOAD_TMP u
                   WHERE u.PART_NO IS NOT NULL
                     AND u.QTY > 0
                 ) s
             ON (p.PART_NO = s.PART_NO)
           WHEN MATCHED THEN
                UPDATE SET p.PART_NAME = s.PART_NAME
           WHEN NOT MATCHED THEN
                INSERT (PART_NO, PART_NAME) VALUES (s.PART_NO, s.PART_NAME)""",
          """
          INSERT INTO PART (PART_NO, PART_NAME)
          SELECT DISTINCT ON (u.PART_NO)
                 u.PART_NO
               , u.PART_NAME
            FROM UPLOAD_TMP u
           WHERE u.PART_NO IS NOT NULL
             AND u.QTY > 0
              ON CONFLICT (PART_NO) DO UPDATE
             SET PART_NAME = EXCLUDED.PART_NAME"""),
      new Example("재고 · 수불", "부품별 현재고 (입고 − 출고)", "입출고 거래를 더하고 빼서 지금 재고.",
          """
          SELECT
                 t.PART_NO
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) AS STOCK
            FROM STOCK_TXN t
           GROUP BY
                 t.PART_NO
           ORDER BY
                 t.PART_NO""",
          """
          SELECT
                 t.PART_NO
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) AS STOCK
            FROM STOCK_TXN t
           GROUP BY
                 t.PART_NO
           ORDER BY
                 t.PART_NO"""),
      new Example("재고 · 수불", "월 수불부 (기초 + 입고 − 출고 = 기말)", "기준 월 이전 거래로 기초를, 그달 거래로 입고 · 출고를 구합니다.",
          """
          SELECT
                 t.PART_NO
               , SUM(CASE WHEN t.TXN_DT < TO_DATE(:ym, 'YYYYMM') THEN DECODE(t.TXN_TYPE, 'IN', t.QTY, -t.QTY) ELSE 0 END) AS BEGIN_QTY
               , SUM(CASE WHEN t.TXN_DT >= TO_DATE(:ym, 'YYYYMM') AND t.TXN_TYPE = 'IN' THEN t.QTY ELSE 0 END) AS IN_QTY
               , SUM(CASE WHEN t.TXN_DT >= TO_DATE(:ym, 'YYYYMM') AND t.TXN_TYPE = 'OUT' THEN t.QTY ELSE 0 END) AS OUT_QTY
               , SUM(DECODE(t.TXN_TYPE, 'IN', t.QTY, -t.QTY)) AS END_QTY
            FROM STOCK_TXN t
           WHERE t.TXN_DT < ADD_MONTHS(TO_DATE(:ym, 'YYYYMM'), 1)
           GROUP BY
                 t.PART_NO
           ORDER BY
                 t.PART_NO""",
          """
          SELECT
                 t.PART_NO
               , SUM(CASE WHEN t.TXN_DT < TO_DATE(:ym, 'YYYYMM') THEN CASE t.TXN_TYPE WHEN 'IN' THEN t.QTY ELSE -t.QTY END ELSE 0 END) AS BEGIN_QTY
               , SUM(CASE WHEN t.TXN_DT >= TO_DATE(:ym, 'YYYYMM') AND t.TXN_TYPE = 'IN' THEN t.QTY ELSE 0 END) AS IN_QTY
               , SUM(CASE WHEN t.TXN_DT >= TO_DATE(:ym, 'YYYYMM') AND t.TXN_TYPE = 'OUT' THEN t.QTY ELSE 0 END) AS OUT_QTY
               , SUM(CASE t.TXN_TYPE WHEN 'IN' THEN t.QTY ELSE -t.QTY END) AS END_QTY
            FROM STOCK_TXN t
           WHERE t.TXN_DT < TO_DATE(:ym, 'YYYYMM') + INTERVAL '1 MONTH'
           GROUP BY
                 t.PART_NO
           ORDER BY
                 t.PART_NO"""),
      new Example("재고 · 수불", "거래마다 남은 재고 (누적 합)", "날짜순으로 입출고를 쌓아 각 거래 뒤 재고를 보여 줍니다. 마이너스가 나는 날을 찾을 때 씁니다.",
          """
          SELECT
                 t.PART_NO
               , t.TXN_DT
               , t.TXN_TYPE
               , t.QTY
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END)
                   OVER (PARTITION BY t.PART_NO ORDER BY t.TXN_DT, t.TXN_ID) AS BALANCE
            FROM STOCK_TXN t
           ORDER BY
                 t.PART_NO
               , t.TXN_DT
               , t.TXN_ID""",
          """
          SELECT
                 t.PART_NO
               , t.TXN_DT
               , t.TXN_TYPE
               , t.QTY
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END)
                   OVER (PARTITION BY t.PART_NO ORDER BY t.TXN_DT, t.TXN_ID) AS BALANCE
            FROM STOCK_TXN t
           ORDER BY
                 t.PART_NO
               , t.TXN_DT
               , t.TXN_ID"""),
      new Example("재고 · 수불", "창고 · 위치 · 로트별 재고", "어느 창고 어느 칸에 어떤 로트가 몇 개 있는지. 0 인 칸은 뺍니다.",
          """
          SELECT
                 t.WH_CD
               , t.LOC_CD
               , t.PART_NO
               , t.LOT_NO
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) AS QTY
            FROM STOCK_TXN t
           GROUP BY
                 t.WH_CD
               , t.LOC_CD
               , t.PART_NO
               , t.LOT_NO
          HAVING SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) <> 0
           ORDER BY
                 1, 2, 3, 4""",
          """
          SELECT
                 t.WH_CD
               , t.LOC_CD
               , t.PART_NO
               , t.LOT_NO
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) AS QTY
            FROM STOCK_TXN t
           GROUP BY
                 t.WH_CD
               , t.LOC_CD
               , t.PART_NO
               , t.LOT_NO
          HAVING SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) <> 0
           ORDER BY
                 1, 2, 3, 4"""),
      new Example("재고 · 수불", "유효기간 임박 로트 (30일 안)", "입고된 로트 중 유효기간이 30일 안에 끝나고 재고가 남은 것.",
          """
          SELECT
                 t.PART_NO
               , t.LOT_NO
               , MIN(t.EXP_DT) AS EXP_DT
               , MIN(t.EXP_DT) - TRUNC(SYSDATE) AS DAYS_LEFT
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) AS QTY
            FROM STOCK_TXN t
           GROUP BY
                 t.PART_NO
               , t.LOT_NO
          HAVING MIN(t.EXP_DT) < TRUNC(SYSDATE) + 30
             AND SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) > 0
           ORDER BY
                 DAYS_LEFT""",
          """
          SELECT
                 t.PART_NO
               , t.LOT_NO
               , MIN(t.EXP_DT) AS EXP_DT
               , MIN(t.EXP_DT) - CURRENT_DATE AS DAYS_LEFT
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) AS QTY
            FROM STOCK_TXN t
           GROUP BY
                 t.PART_NO
               , t.LOT_NO
          HAVING MIN(t.EXP_DT) < CURRENT_DATE + 30
             AND SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) > 0
           ORDER BY
                 DAYS_LEFT"""),
      new Example("재고 · 수불", "선입선출 배분 (먼저 들어온 로트부터 출고 수량 나누기)", "출고할 수량을 입고 순서대로 로트에 나눕니다. 앞 로트까지 누적 수량으로 이번 로트에서 뺄 양을 구합니다.",
          """
          SELECT
                 x.LOT_NO
               , x.TXN_DT
               , x.QTY AS LOT_QTY
               , LEAST(x.QTY, GREATEST(:needQty - (x.CUM - x.QTY), 0)) AS TAKE_QTY
            FROM (
                  SELECT
                         t.LOT_NO
                       , t.TXN_DT
                       , t.QTY
                       , SUM(t.QTY) OVER (ORDER BY t.TXN_DT, t.TXN_ID) AS CUM
                    FROM STOCK_TXN t
                   WHERE t.PART_NO = :partNo
                     AND t.TXN_TYPE = 'IN'
                 ) x
           WHERE x.CUM - x.QTY < :needQty
           ORDER BY
                 x.TXN_DT""",
          """
          SELECT
                 x.LOT_NO
               , x.TXN_DT
               , x.QTY AS LOT_QTY
               , LEAST(x.QTY, GREATEST(:needQty - (x.CUM - x.QTY), 0)) AS TAKE_QTY
            FROM (
                  SELECT
                         t.LOT_NO
                       , t.TXN_DT
                       , t.QTY
                       , SUM(t.QTY) OVER (ORDER BY t.TXN_DT, t.TXN_ID) AS CUM
                    FROM STOCK_TXN t
                   WHERE t.PART_NO = :partNo
                     AND t.TXN_TYPE = 'IN'
                 ) x
           WHERE x.CUM - x.QTY < :needQty
           ORDER BY
                 x.TXN_DT"""),
      new Example("재고 · 수불", "안전재고 미달 부품", "현재고가 기준 수량보다 적은 부품. 기준이 부품 표에 없으면 바인드로 넣습니다.",
          """
          SELECT
                 t.PART_NO
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) AS STOCK
               , :safetyQty AS SAFETY_QTY
            FROM STOCK_TXN t
           GROUP BY
                 t.PART_NO
          HAVING SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) < :safetyQty""",
          """
          SELECT
                 t.PART_NO
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) AS STOCK
               , :safetyQty AS SAFETY_QTY
            FROM STOCK_TXN t
           GROUP BY
                 t.PART_NO
          HAVING SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) < :safetyQty"""),
      new Example("재고 · 수불", "재고 회전 일수 (최근 90일 출고 기준)", "지금 재고를 하루 평균 출고량으로 나눈 값. 며칠치 재고인지.",
          """
          SELECT
                 t.PART_NO
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) AS STOCK
               , SUM(CASE WHEN t.TXN_TYPE = 'OUT' AND t.TXN_DT >= TRUNC(SYSDATE) - 90 THEN t.QTY ELSE 0 END) / 90 AS DAILY_OUT
               , ROUND(SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END)
                       / NULLIF(SUM(CASE WHEN t.TXN_TYPE = 'OUT' AND t.TXN_DT >= TRUNC(SYSDATE) - 90 THEN t.QTY ELSE 0 END) / 90, 0), 1) AS DAYS_OF_STOCK
            FROM STOCK_TXN t
           GROUP BY
                 t.PART_NO""",
          """
          SELECT
                 t.PART_NO
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) AS STOCK
               , SUM(CASE WHEN t.TXN_TYPE = 'OUT' AND t.TXN_DT >= CURRENT_DATE - 90 THEN t.QTY ELSE 0 END) / 90 AS DAILY_OUT
               , ROUND(SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END)
                       / NULLIF(SUM(CASE WHEN t.TXN_TYPE = 'OUT' AND t.TXN_DT >= CURRENT_DATE - 90 THEN t.QTY ELSE 0 END) / 90, 0), 1) AS DAYS_OF_STOCK
            FROM STOCK_TXN t
           GROUP BY
                 t.PART_NO"""),
      new Example("재고 · 수불", "출고 대기 · 피킹 목록 (주문 잔량을 위치별 재고에서)", "아직 덜 나간 수주 잔량과 그 부품이 있는 창고 위치를 함께.",
          """
          SELECT
                 so.SO_NO
               , so.PART_NO
               , so.QTY - NVL(sh.SHIP_QTY, 0) AS REMAIN_QTY
               , st.WH_CD
               , st.LOC_CD
               , st.QTY AS LOC_QTY
            FROM SALES_ORDER so
               , (SELECT s.SO_NO, s.LINE_NO, SUM(s.QTY) AS SHIP_QTY FROM SHIPMENT s GROUP BY s.SO_NO, s.LINE_NO) sh
               , (SELECT t.PART_NO, t.WH_CD, t.LOC_CD, SUM(DECODE(t.TXN_TYPE, 'IN', t.QTY, -t.QTY)) AS QTY FROM STOCK_TXN t GROUP BY t.PART_NO, t.WH_CD, t.LOC_CD) st
           WHERE so.SO_NO = sh.SO_NO(+)
             AND so.LINE_NO = sh.LINE_NO(+)
             AND so.PART_NO = st.PART_NO
             AND so.QTY - NVL(sh.SHIP_QTY, 0) > 0
             AND st.QTY > 0
           ORDER BY
                 so.DUE_DT
               , st.WH_CD
               , st.LOC_CD""",
          """
          SELECT
                 so.SO_NO
               , so.PART_NO
               , so.QTY - COALESCE(sh.SHIP_QTY, 0) AS REMAIN_QTY
               , st.WH_CD
               , st.LOC_CD
               , st.QTY AS LOC_QTY
            FROM SALES_ORDER so
            LEFT OUTER JOIN (SELECT s.SO_NO, s.LINE_NO, SUM(s.QTY) AS SHIP_QTY FROM SHIPMENT s GROUP BY s.SO_NO, s.LINE_NO) sh
              ON so.SO_NO = sh.SO_NO
             AND so.LINE_NO = sh.LINE_NO
               , (SELECT t.PART_NO, t.WH_CD, t.LOC_CD, SUM(CASE t.TXN_TYPE WHEN 'IN' THEN t.QTY ELSE -t.QTY END) AS QTY FROM STOCK_TXN t GROUP BY t.PART_NO, t.WH_CD, t.LOC_CD) st
           WHERE so.PART_NO = st.PART_NO
             AND so.QTY - COALESCE(sh.SHIP_QTY, 0) > 0
             AND st.QTY > 0
           ORDER BY
                 so.DUE_DT
               , st.WH_CD
               , st.LOC_CD"""),
      new Example("재고 · 수불", "90일 동안 안 나간 재고 (장기 재고)", "재고는 남았는데 최근 90일 출고가 없는 부품과 그 금액.",
          """
          SELECT
                 t.PART_NO
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) AS STOCK_QTY
               , MAX(CASE WHEN t.TXN_TYPE = 'OUT' THEN t.TXN_DT END) AS LAST_OUT
            FROM STOCK_TXN t
           GROUP BY
                 t.PART_NO
          HAVING SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) > 0
             AND (MAX(CASE WHEN t.TXN_TYPE = 'OUT' THEN t.TXN_DT END) IS NULL
                  OR MAX(CASE WHEN t.TXN_TYPE = 'OUT' THEN t.TXN_DT END) < TRUNC(SYSDATE) - 90)""",
          """
          SELECT
                 t.PART_NO
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) AS STOCK_QTY
               , MAX(CASE WHEN t.TXN_TYPE = 'OUT' THEN t.TXN_DT END) AS LAST_OUT
            FROM STOCK_TXN t
           GROUP BY
                 t.PART_NO
          HAVING SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) > 0
             AND (MAX(CASE WHEN t.TXN_TYPE = 'OUT' THEN t.TXN_DT END) IS NULL
                  OR MAX(CASE WHEN t.TXN_TYPE = 'OUT' THEN t.TXN_DT END) < CURRENT_DATE - 90)"""),
      new Example("재고 · 수불", "창고별 재고 금액", "창고마다 수량 × 부품 단가를 더합니다.",
          """
          SELECT
                 t.WH_CD
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END * NVL(p.UNIT_COST, 0)) AS STOCK_AMT
            FROM STOCK_TXN t
               , PART p
           WHERE t.PART_NO = p.PART_NO
           GROUP BY
                 t.WH_CD
           ORDER BY
                 STOCK_AMT DESC""",
          """
          SELECT
                 t.WH_CD
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END * COALESCE(p.UNIT_COST, 0)) AS STOCK_AMT
            FROM STOCK_TXN t
               , PART p
           WHERE t.PART_NO = p.PART_NO
           GROUP BY
                 t.WH_CD
           ORDER BY
                 STOCK_AMT DESC"""),
      new Example("재고 · 수불", "재고 0 인데 발주 잔량도 없는 부품", "곧 모자랄 부품. 재고가 0 이하이고 아직 안 들어온 발주도 없는 것.",
          """
          SELECT
                 p.PART_NO
            FROM PART p
           WHERE NVL((SELECT SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) FROM STOCK_TXN t WHERE t.PART_NO = p.PART_NO), 0) <= 0
             AND NOT EXISTS (
                  SELECT 1
                    FROM PURCHASE_ORDER o
                   WHERE o.PART_NO = p.PART_NO
                     AND o.QTY > NVL((SELECT SUM(r.QTY) FROM RECEIPT r WHERE r.PO_NO = o.PO_NO AND r.LINE_NO = o.LINE_NO), 0)
                 )""",
          """
          SELECT
                 p.PART_NO
            FROM PART p
           WHERE COALESCE((SELECT SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) FROM STOCK_TXN t WHERE t.PART_NO = p.PART_NO), 0) <= 0
             AND NOT EXISTS (
                  SELECT 1
                    FROM PURCHASE_ORDER o
                   WHERE o.PART_NO = p.PART_NO
                     AND o.QTY > COALESCE((SELECT SUM(r.QTY) FROM RECEIPT r WHERE r.PO_NO = o.PO_NO AND r.LINE_NO = o.LINE_NO), 0)
                 )"""),
      new Example("재고 · 수불", "마이너스 재고 (출고가 입고보다 많은 것)", "수불 순서가 꼬였거나 입고 누락. 창고 · 부품마다.",
          """
          SELECT
                 t.WH_CD
               , t.PART_NO
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) AS STOCK_QTY
            FROM STOCK_TXN t
           GROUP BY
                 t.WH_CD
               , t.PART_NO
          HAVING SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) < 0""",
          """
          SELECT
                 t.WH_CD
               , t.PART_NO
               , SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) AS STOCK_QTY
            FROM STOCK_TXN t
           GROUP BY
                 t.WH_CD
               , t.PART_NO
          HAVING SUM(CASE WHEN t.TXN_TYPE = 'IN' THEN t.QTY ELSE -t.QTY END) < 0"""),
      new Example("영업 · 구매 · 회계", "수주 → 출하 → 청구 잔량 (미출하 · 미청구)", "수주 줄마다 출하한 수량, 청구한 금액, 남은 것. 출하 · 청구가 여러 번이어도 먼저 묶은 뒤 붙여 곱해지지 않게 합니다.",
          """
          SELECT
                 so.SO_NO
               , so.LINE_NO
               , so.PART_NO
               , so.QTY AS ORDER_QTY
               , NVL(sh.QTY, 0) AS SHIP_QTY
               , so.QTY - NVL(sh.QTY, 0) AS UNSHIPPED
               , NVL(sh.QTY, 0) * so.PRICE - NVL(iv.AMT, 0) AS UNBILLED_AMT
            FROM SALES_ORDER so
               , (SELECT s.SO_NO, s.LINE_NO, SUM(s.QTY) AS QTY FROM SHIPMENT s GROUP BY s.SO_NO, s.LINE_NO) sh
               , (SELECT i.SO_NO, i.LINE_NO, SUM(i.AMT) AS AMT FROM INVOICE i GROUP BY i.SO_NO, i.LINE_NO) iv
           WHERE so.SO_NO = sh.SO_NO(+)
             AND so.LINE_NO = sh.LINE_NO(+)
             AND so.SO_NO = iv.SO_NO(+)
             AND so.LINE_NO = iv.LINE_NO(+)
           ORDER BY
                 so.SO_NO""",
          """
          SELECT
                 so.SO_NO
               , so.LINE_NO
               , so.PART_NO
               , so.QTY AS ORDER_QTY
               , COALESCE(sh.QTY, 0) AS SHIP_QTY
               , so.QTY - COALESCE(sh.QTY, 0) AS UNSHIPPED
               , COALESCE(sh.QTY, 0) * so.PRICE - COALESCE(iv.AMT, 0) AS UNBILLED_AMT
            FROM SALES_ORDER so
            LEFT OUTER JOIN (SELECT s.SO_NO, s.LINE_NO, SUM(s.QTY) AS QTY FROM SHIPMENT s GROUP BY s.SO_NO, s.LINE_NO) sh
              ON so.SO_NO = sh.SO_NO
             AND so.LINE_NO = sh.LINE_NO
            LEFT OUTER JOIN (SELECT i.SO_NO, i.LINE_NO, SUM(i.AMT) AS AMT FROM INVOICE i GROUP BY i.SO_NO, i.LINE_NO) iv
              ON so.SO_NO = iv.SO_NO
             AND so.LINE_NO = iv.LINE_NO
           ORDER BY
                 so.SO_NO"""),
      new Example("영업 · 구매 · 회계", "발주 대비 입고 잔량 · 납기 지난 발주", "발주 줄마다 들어온 수량과 남은 수량, 납기가 지났는데 덜 들어온 것 표시.",
          """
          SELECT
                 po.PO_NO
               , po.PART_NO
               , po.QTY AS PO_QTY
               , NVL(r.QTY, 0) AS RCV_QTY
               , po.QTY - NVL(r.QTY, 0) AS REMAIN_QTY
               , po.DUE_DT
               , CASE WHEN po.DUE_DT < TRUNC(SYSDATE) AND po.QTY > NVL(r.QTY, 0) THEN '납기 지남' END AS LATE
            FROM PURCHASE_ORDER po
               , (SELECT x.PO_NO, x.LINE_NO, SUM(x.QTY) AS QTY FROM RECEIPT x GROUP BY x.PO_NO, x.LINE_NO) r
           WHERE po.PO_NO = r.PO_NO(+)
             AND po.LINE_NO = r.LINE_NO(+)
             AND po.QTY > NVL(r.QTY, 0)
           ORDER BY
                 po.DUE_DT""",
          """
          SELECT
                 po.PO_NO
               , po.PART_NO
               , po.QTY AS PO_QTY
               , COALESCE(r.QTY, 0) AS RCV_QTY
               , po.QTY - COALESCE(r.QTY, 0) AS REMAIN_QTY
               , po.DUE_DT
               , CASE WHEN po.DUE_DT < CURRENT_DATE AND po.QTY > COALESCE(r.QTY, 0) THEN '납기 지남' END AS LATE
            FROM PURCHASE_ORDER po
            LEFT OUTER JOIN (SELECT x.PO_NO, x.LINE_NO, SUM(x.QTY) AS QTY FROM RECEIPT x GROUP BY x.PO_NO, x.LINE_NO) r
              ON po.PO_NO = r.PO_NO
             AND po.LINE_NO = r.LINE_NO
           WHERE po.QTY > COALESCE(r.QTY, 0)
           ORDER BY
                 po.DUE_DT"""),
      new Example("영업 · 구매 · 회계", "그날 적용 단가 (단가 이력에서)", "발주일에 유효했던 단가를 붙여 발주 단가와 다른 것을 찾습니다.",
          """
          SELECT
                 po.PO_NO
               , po.PART_NO
               , po.PO_DT
               , po.PRICE AS PO_PRICE
               , h.PRICE AS MASTER_PRICE
            FROM PURCHASE_ORDER po, PART_PRICE_HIST h
           WHERE po.PART_NO = h.PART_NO
             AND po.VENDOR_ID = h.VENDOR_ID
             AND po.PO_DT BETWEEN h.VALID_FROM AND h.VALID_TO
             AND po.PRICE <> h.PRICE""",
          """
          SELECT
                 po.PO_NO
               , po.PART_NO
               , po.PO_DT
               , po.PRICE AS PO_PRICE
               , h.PRICE AS MASTER_PRICE
            FROM PURCHASE_ORDER po, PART_PRICE_HIST h
           WHERE po.PART_NO = h.PART_NO
             AND po.VENDOR_ID = h.VENDOR_ID
             AND po.PO_DT BETWEEN h.VALID_FROM AND h.VALID_TO
             AND po.PRICE <> h.PRICE"""),
      new Example("영업 · 구매 · 회계", "환율 적용 금액 (그날 환율, 없으면 직전 영업일)", "외화 발주 금액을 원화로. 발주일 환율이 없으면(주말) 그 전 가장 가까운 날 환율을 씁니다.",
          """
          SELECT
                 po.PO_NO
               , po.CURRENCY
               , po.QTY * po.PRICE AS AMT
               , (SELECT MAX(e.RATE) KEEP (DENSE_RANK LAST ORDER BY e.RATE_DT)
                    FROM EXCH_RATE e
                   WHERE e.CURRENCY = po.CURRENCY
                     AND e.RATE_DT <= po.PO_DT) AS RATE
               , po.QTY * po.PRICE * (SELECT MAX(e.RATE) KEEP (DENSE_RANK LAST ORDER BY e.RATE_DT)
                                        FROM EXCH_RATE e
                                       WHERE e.CURRENCY = po.CURRENCY
                                         AND e.RATE_DT <= po.PO_DT) AS AMT_KRW
            FROM PURCHASE_ORDER po
           WHERE po.CURRENCY <> 'KRW'""",
          """
          SELECT
                 po.PO_NO
               , po.CURRENCY
               , po.QTY * po.PRICE AS AMT
               , e.RATE
               , po.QTY * po.PRICE * e.RATE AS AMT_KRW
            FROM PURCHASE_ORDER po
            LEFT JOIN LATERAL (
                  SELECT
                         x.RATE
                    FROM EXCH_RATE x
                   WHERE x.CURRENCY = po.CURRENCY
                     AND x.RATE_DT <= po.PO_DT
                   ORDER BY
                         x.RATE_DT DESC
                   LIMIT 1
                 ) e
              ON TRUE
           WHERE po.CURRENCY <> 'KRW'"""),
      new Example("영업 · 구매 · 회계", "거래처별 미수금 나이 (30 · 60 · 90일 넘음)", "만기일이 지난 날 수로 칸을 나눠 남은 금액을 합칩니다.",
          """
          SELECT
                 a.CUST_ID
               , SUM(CASE WHEN TRUNC(SYSDATE) - a.DUE_DT <= 0 THEN a.AMT - a.PAID_AMT ELSE 0 END) AS NOT_DUE
               , SUM(CASE WHEN TRUNC(SYSDATE) - a.DUE_DT BETWEEN 1 AND 30 THEN a.AMT - a.PAID_AMT ELSE 0 END) AS D1_30
               , SUM(CASE WHEN TRUNC(SYSDATE) - a.DUE_DT BETWEEN 31 AND 60 THEN a.AMT - a.PAID_AMT ELSE 0 END) AS D31_60
               , SUM(CASE WHEN TRUNC(SYSDATE) - a.DUE_DT BETWEEN 61 AND 90 THEN a.AMT - a.PAID_AMT ELSE 0 END) AS D61_90
               , SUM(CASE WHEN TRUNC(SYSDATE) - a.DUE_DT > 90 THEN a.AMT - a.PAID_AMT ELSE 0 END) AS OVER_90
               , SUM(a.AMT - a.PAID_AMT) AS TOTAL
            FROM AR a
           WHERE a.AMT > a.PAID_AMT
           GROUP BY
                 a.CUST_ID
           ORDER BY
                 TOTAL DESC""",
          """
          SELECT
                 a.CUST_ID
               , SUM(CASE WHEN CURRENT_DATE - a.DUE_DT <= 0 THEN a.AMT - a.PAID_AMT ELSE 0 END) AS NOT_DUE
               , SUM(CASE WHEN CURRENT_DATE - a.DUE_DT BETWEEN 1 AND 30 THEN a.AMT - a.PAID_AMT ELSE 0 END) AS D1_30
               , SUM(CASE WHEN CURRENT_DATE - a.DUE_DT BETWEEN 31 AND 60 THEN a.AMT - a.PAID_AMT ELSE 0 END) AS D31_60
               , SUM(CASE WHEN CURRENT_DATE - a.DUE_DT BETWEEN 61 AND 90 THEN a.AMT - a.PAID_AMT ELSE 0 END) AS D61_90
               , SUM(CASE WHEN CURRENT_DATE - a.DUE_DT > 90 THEN a.AMT - a.PAID_AMT ELSE 0 END) AS OVER_90
               , SUM(a.AMT - a.PAID_AMT) AS TOTAL
            FROM AR a
           WHERE a.AMT > a.PAID_AMT
           GROUP BY
                 a.CUST_ID
           ORDER BY
                 TOTAL DESC"""),
      new Example("영업 · 구매 · 회계", "전표 차변 · 대변 합이 안 맞는 것 (점검)", "전표마다 차변 합과 대변 합이 같아야 합니다. 다른 전표만.",
          """
          SELECT
                 g.VOUCHER_NO
               , SUM(g.DR_AMT) AS DR
               , SUM(g.CR_AMT) AS CR
               , SUM(g.DR_AMT) - SUM(g.CR_AMT) AS DIFF
            FROM GL_ENTRY g
           GROUP BY
                 g.VOUCHER_NO
          HAVING SUM(g.DR_AMT) <> SUM(g.CR_AMT)""",
          """
          SELECT
                 g.VOUCHER_NO
               , SUM(g.DR_AMT) AS DR
               , SUM(g.CR_AMT) AS CR
               , SUM(g.DR_AMT) - SUM(g.CR_AMT) AS DIFF
            FROM GL_ENTRY g
           GROUP BY
                 g.VOUCHER_NO
          HAVING SUM(g.DR_AMT) <> SUM(g.CR_AMT)"""),
      new Example("영업 · 구매 · 회계", "계정별 월 합계 (마감 집계)", "월 · 계정마다 차변 · 대변 · 잔액.",
          """
          SELECT
                 TO_CHAR(g.ENTRY_DT, 'YYYYMM') AS YM
               , g.ACCT_CD
               , SUM(g.DR_AMT) AS DR
               , SUM(g.CR_AMT) AS CR
               , SUM(g.DR_AMT - g.CR_AMT) AS BAL
            FROM GL_ENTRY g
           GROUP BY
                 TO_CHAR(g.ENTRY_DT, 'YYYYMM')
               , g.ACCT_CD
           ORDER BY
                 1, 2""",
          """
          SELECT
                 TO_CHAR(g.ENTRY_DT, 'YYYYMM') AS YM
               , g.ACCT_CD
               , SUM(g.DR_AMT) AS DR
               , SUM(g.CR_AMT) AS CR
               , SUM(g.DR_AMT - g.CR_AMT) AS BAL
            FROM GL_ENTRY g
           GROUP BY
                 TO_CHAR(g.ENTRY_DT, 'YYYYMM')
               , g.ACCT_CD
           ORDER BY
                 1, 2"""),
      new Example("영업 · 구매 · 회계", "예산 대비 실적 (부서 · 계정)", "예산이 있는데 실적이 없어도, 실적이 있는데 예산이 없어도 다 보이게 양쪽을 모아 묶습니다.",
          """
          SELECT
                 x.DEPT_ID
               , x.ACCT_CD
               , SUM(x.BUDGET) AS BUDGET
               , SUM(x.ACTUAL) AS ACTUAL
               , ROUND(SUM(x.ACTUAL) * 100 / NULLIF(SUM(x.BUDGET), 0), 1) AS USE_PCT
            FROM (
                  SELECT b.DEPT_ID, b.ACCT_CD, b.BUDGET_AMT AS BUDGET, 0 AS ACTUAL
                    FROM BUDGET b
                   WHERE b.YM = :ym
                   UNION ALL
                  SELECT g.DEPT_ID, g.ACCT_CD, 0, g.DR_AMT - g.CR_AMT
                    FROM GL_ENTRY g
                   WHERE TO_CHAR(g.ENTRY_DT, 'YYYYMM') = :ym
                     AND g.ACCT_CD LIKE '5%'
                 ) x
           GROUP BY
                 x.DEPT_ID
               , x.ACCT_CD
           ORDER BY
                 1, 2""",
          """
          SELECT
                 x.DEPT_ID
               , x.ACCT_CD
               , SUM(x.BUDGET) AS BUDGET
               , SUM(x.ACTUAL) AS ACTUAL
               , ROUND(SUM(x.ACTUAL) * 100 / NULLIF(SUM(x.BUDGET), 0), 1) AS USE_PCT
            FROM (
                  SELECT b.DEPT_ID, b.ACCT_CD, b.BUDGET_AMT AS BUDGET, 0 AS ACTUAL
                    FROM BUDGET b
                   WHERE b.YM = :ym
                   UNION ALL
                  SELECT g.DEPT_ID, g.ACCT_CD, 0, g.DR_AMT - g.CR_AMT
                    FROM GL_ENTRY g
                   WHERE TO_CHAR(g.ENTRY_DT, 'YYYYMM') = :ym
                     AND g.ACCT_CD LIKE '5%'
                 ) x
           GROUP BY
                 x.DEPT_ID
               , x.ACCT_CD
           ORDER BY
                 1, 2"""),
      new Example("영업 · 구매 · 회계", "공통 비용을 인원 비율로 부서에 나누기 (원가 배부)", "공통 비용 합계를 부서 인원 비율대로 나눕니다. 반올림 끝전 때문에 합이 1~2원 다를 수 있습니다.",
          """
          SELECT
                 d.DEPT_ID
               , d.HEADCOUNT
               , ROUND((SELECT SUM(p.AMT) FROM COST_POOL p WHERE p.YM = :ym) * d.HEADCOUNT / SUM(d.HEADCOUNT) OVER ()) AS ALLOC
            FROM DEPT d
           WHERE d.HEADCOUNT > 0
           ORDER BY
                 d.DEPT_ID""",
          """
          SELECT
                 d.DEPT_ID
               , d.HEADCOUNT
               , ROUND((SELECT SUM(p.AMT) FROM COST_POOL p WHERE p.YM = :ym) * d.HEADCOUNT / SUM(d.HEADCOUNT) OVER ()) AS ALLOC
            FROM DEPT d
           WHERE d.HEADCOUNT > 0
           ORDER BY
                 d.DEPT_ID"""),
      new Example("영업 · 구매 · 회계", "목표 대비 달성률 (월 · 부서)", "부서별 월 매출(수주 금액)을 목표와 나란히.",
          """
          SELECT
                 t.YM
               , t.DEPT_ID
               , t.TARGET_AMT
               , NVL(s.AMT, 0) AS ACTUAL
               , ROUND(NVL(s.AMT, 0) * 100 / NULLIF(t.TARGET_AMT, 0), 1) AS ACHIEVE_PCT
            FROM SALES_TARGET t
               , (SELECT TO_CHAR(o.SO_DT, 'YYYYMM') AS YM, o.DEPT_ID, SUM(o.QTY * o.PRICE) AS AMT FROM SALES_ORDER o GROUP BY TO_CHAR(o.SO_DT, 'YYYYMM'), o.DEPT_ID) s
           WHERE t.YM = s.YM(+)
             AND t.DEPT_ID = s.DEPT_ID(+)
           ORDER BY
                 t.YM
               , t.DEPT_ID""",
          """
          SELECT
                 t.YM
               , t.DEPT_ID
               , t.TARGET_AMT
               , COALESCE(s.AMT, 0) AS ACTUAL
               , ROUND(COALESCE(s.AMT, 0) * 100 / NULLIF(t.TARGET_AMT, 0), 1) AS ACHIEVE_PCT
            FROM SALES_TARGET t
            LEFT OUTER JOIN (SELECT TO_CHAR(o.SO_DT, 'YYYYMM') AS YM, o.DEPT_ID, SUM(o.QTY * o.PRICE) AS AMT FROM SALES_ORDER o GROUP BY TO_CHAR(o.SO_DT, 'YYYYMM'), o.DEPT_ID) s
              ON t.YM = s.YM
             AND t.DEPT_ID = s.DEPT_ID
           ORDER BY
                 t.YM
               , t.DEPT_ID"""),
      new Example("영업 · 구매 · 회계", "고객별 첫 주문 · 마지막 주문 · 주문 횟수", "고객 생애 요약. 오래 주문이 없는 고객도 보입니다.",
          """
          SELECT
                 c.CUST_ID
               , c.CUST_NAME
               , MIN(o.ORDER_DT) AS FIRST_DT
               , MAX(o.ORDER_DT) AS LAST_DT
               , COUNT(o.ORDER_ID) AS ORDER_CNT
               , TRUNC(SYSDATE) - MAX(o.ORDER_DT) AS DAYS_SINCE
            FROM CUSTOMER c
               , ORDERS o
           WHERE c.CUST_ID = o.CUST_ID(+)
           GROUP BY
                 c.CUST_ID
               , c.CUST_NAME""",
          """
          SELECT
                 c.CUST_ID
               , c.CUST_NAME
               , MIN(o.ORDER_DT) AS FIRST_DT
               , MAX(o.ORDER_DT) AS LAST_DT
               , COUNT(o.ORDER_ID) AS ORDER_CNT
               , CURRENT_DATE - MAX(o.ORDER_DT) AS DAYS_SINCE
            FROM CUSTOMER c
            LEFT OUTER JOIN ORDERS o
              ON c.CUST_ID = o.CUST_ID
           GROUP BY
                 c.CUST_ID
               , c.CUST_NAME"""),
      new Example("영업 · 구매 · 회계", "ABC 분석 (매출 누적 비율로 A · B · C)", "매출 큰 순으로 누적 70% 까지 A, 90% 까지 B, 나머지 C.",
          """
          SELECT
                 x.PART_NO
               , x.AMT
               , ROUND(x.CUM_PCT, 1) AS CUM_PCT
               , CASE WHEN x.CUM_PCT <= 70 THEN 'A' WHEN x.CUM_PCT <= 90 THEN 'B' ELSE 'C' END AS GRADE
            FROM (
                  SELECT
                         s.PART_NO
                       , SUM(s.QTY * s.PRICE) AS AMT
                       , SUM(SUM(s.QTY * s.PRICE)) OVER (ORDER BY SUM(s.QTY * s.PRICE) DESC) * 100 / SUM(SUM(s.QTY * s.PRICE)) OVER () AS CUM_PCT
                    FROM SALES_ORDER s
                   GROUP BY
                         s.PART_NO
                 ) x
           ORDER BY
                 x.AMT DESC""",
          """
          SELECT
                 x.PART_NO
               , x.AMT
               , ROUND(x.CUM_PCT, 1) AS CUM_PCT
               , CASE WHEN x.CUM_PCT <= 70 THEN 'A' WHEN x.CUM_PCT <= 90 THEN 'B' ELSE 'C' END AS GRADE
            FROM (
                  SELECT
                         s.PART_NO
                       , SUM(s.QTY * s.PRICE) AS AMT
                       , SUM(SUM(s.QTY * s.PRICE)) OVER (ORDER BY SUM(s.QTY * s.PRICE) DESC) * 100 / SUM(SUM(s.QTY * s.PRICE)) OVER () AS CUM_PCT
                    FROM SALES_ORDER s
                   GROUP BY
                         s.PART_NO
                 ) x
           ORDER BY
                 x.AMT DESC"""),
      new Example("영업 · 구매 · 회계", "부품별 최근 구매 단가와 직전 단가 차이", "같은 부품의 마지막 두 발주 단가를 비교합니다.",
          """
          SELECT
                 x.PART_NO
               , x.PO_DT
               , x.PRICE
               , x.PREV_PRICE
               , x.PRICE - x.PREV_PRICE AS DIFF
            FROM (
                  SELECT
                         p.PART_NO
                       , p.PO_DT
                       , p.PRICE
                       , LAG(p.PRICE) OVER (PARTITION BY p.PART_NO ORDER BY p.PO_DT, p.PO_NO) AS PREV_PRICE
                       , ROW_NUMBER() OVER (PARTITION BY p.PART_NO ORDER BY p.PO_DT DESC, p.PO_NO DESC) AS RN
                    FROM PURCHASE_ORDER p
                 ) x
           WHERE x.RN = 1""",
          """
          SELECT
                 x.PART_NO
               , x.PO_DT
               , x.PRICE
               , x.PREV_PRICE
               , x.PRICE - x.PREV_PRICE AS DIFF
            FROM (
                  SELECT
                         p.PART_NO
                       , p.PO_DT
                       , p.PRICE
                       , LAG(p.PRICE) OVER (PARTITION BY p.PART_NO ORDER BY p.PO_DT, p.PO_NO) AS PREV_PRICE
                       , ROW_NUMBER() OVER (PARTITION BY p.PART_NO ORDER BY p.PO_DT DESC, p.PO_NO DESC) AS RN
                    FROM PURCHASE_ORDER p
                 ) x
           WHERE x.RN = 1"""),
      new Example("영업 · 구매 · 회계", "월 마감 잔액 (전월 이월 + 이번 달 증감)", "계정마다 달별 차변 − 대변 합을 누적해 월말 잔액을 만듭니다.",
          """
          SELECT
                 x.ACCT_CD
               , x.YM
               , x.NET_AMT
               , SUM(x.NET_AMT) OVER (PARTITION BY x.ACCT_CD ORDER BY x.YM) AS BALANCE
            FROM (
                  SELECT
                         g.ACCT_CD
                       , TO_CHAR(g.ENTRY_DT, 'YYYY-MM') AS YM
                       , SUM(NVL(g.DR_AMT, 0) - NVL(g.CR_AMT, 0)) AS NET_AMT
                    FROM GL_ENTRY g
                   GROUP BY
                         g.ACCT_CD
                       , TO_CHAR(g.ENTRY_DT, 'YYYY-MM')
                 ) x""",
          """
          SELECT
                 x.ACCT_CD
               , x.YM
               , x.NET_AMT
               , SUM(x.NET_AMT) OVER (PARTITION BY x.ACCT_CD ORDER BY x.YM) AS BALANCE
            FROM (
                  SELECT
                         g.ACCT_CD
                       , TO_CHAR(g.ENTRY_DT, 'YYYY-MM') AS YM
                       , SUM(COALESCE(g.DR_AMT, 0) - COALESCE(g.CR_AMT, 0)) AS NET_AMT
                    FROM GL_ENTRY g
                   GROUP BY
                         g.ACCT_CD
                       , TO_CHAR(g.ENTRY_DT, 'YYYY-MM')
                 ) x"""),
      new Example("영업 · 구매 · 회계", "월별 매출 · 매입 · 차이", "수주 금액과 발주 금액을 달마다 나란히 놓습니다. 두 표를 붙인 뒤 묶습니다.",
          """
          SELECT
                 u.YM
               , SUM(u.SALES) AS SALES
               , SUM(u.PURCHASE) AS PURCHASE
               , SUM(u.SALES) - SUM(u.PURCHASE) AS GAP
            FROM (
                  SELECT TO_CHAR(s.SO_DT, 'YYYY-MM') AS YM, s.QTY * s.PRICE AS SALES, 0 AS PURCHASE FROM SALES_ORDER s
                  UNION ALL
                  SELECT TO_CHAR(p.PO_DT, 'YYYY-MM'), 0, p.QTY * p.PRICE FROM PURCHASE_ORDER p
                 ) u
           GROUP BY
                 u.YM
           ORDER BY
                 u.YM""",
          """
          SELECT
                 u.YM
               , SUM(u.SALES) AS SALES
               , SUM(u.PURCHASE) AS PURCHASE
               , SUM(u.SALES) - SUM(u.PURCHASE) AS GAP
            FROM (
                  SELECT TO_CHAR(s.SO_DT, 'YYYY-MM') AS YM, s.QTY * s.PRICE AS SALES, 0 AS PURCHASE FROM SALES_ORDER s
                  UNION ALL
                  SELECT TO_CHAR(p.PO_DT, 'YYYY-MM'), 0, p.QTY * p.PRICE FROM PURCHASE_ORDER p
                 ) u
           GROUP BY
                 u.YM
           ORDER BY
                 u.YM"""),
      new Example("영업 · 구매 · 회계", "거래처별 매출 순위와 비중 · 누적 비중", "매출 큰 순으로 순위, 전체 중 비중, 누적 비중(상위 몇 곳이 80% 인지).",
          """
          SELECT
                 x.CUST_ID
               , x.AMT
               , RANK() OVER (ORDER BY x.AMT DESC) AS RNK
               , ROUND(x.AMT * 100 / SUM(x.AMT) OVER (), 1) AS PCT
               , ROUND(SUM(x.AMT) OVER (ORDER BY x.AMT DESC ROWS UNBOUNDED PRECEDING) * 100 / SUM(x.AMT) OVER (), 1) AS CUM_PCT
            FROM (
                  SELECT
                         s.CUST_ID
                       , SUM(s.QTY * s.PRICE) AS AMT
                    FROM SALES_ORDER s
                   GROUP BY
                         s.CUST_ID
                 ) x
           ORDER BY
                 RNK""",
          """
          SELECT
                 x.CUST_ID
               , x.AMT
               , RANK() OVER (ORDER BY x.AMT DESC) AS RNK
               , ROUND(x.AMT * 100 / SUM(x.AMT) OVER (), 1) AS PCT
               , ROUND(SUM(x.AMT) OVER (ORDER BY x.AMT DESC ROWS UNBOUNDED PRECEDING) * 100 / SUM(x.AMT) OVER (), 1) AS CUM_PCT
            FROM (
                  SELECT
                         s.CUST_ID
                       , SUM(s.QTY * s.PRICE) AS AMT
                    FROM SALES_ORDER s
                   GROUP BY
                         s.CUST_ID
                 ) x
           ORDER BY
                 RNK"""),
      new Example("영업 · 구매 · 회계", "납기 임박 미출하 수주 (7일 안)", "납기가 7일 안인데 아직 다 나가지 않은 수주 줄.",
          """
          SELECT
                 s.SO_NO
               , s.LINE_NO
               , s.PART_NO
               , s.DUE_DT
               , s.QTY - NVL((SELECT SUM(h.QTY) FROM SHIPMENT h WHERE h.SO_NO = s.SO_NO AND h.LINE_NO = s.LINE_NO), 0) AS REMAIN_QTY
            FROM SALES_ORDER s
           WHERE s.DUE_DT BETWEEN TRUNC(SYSDATE) AND TRUNC(SYSDATE) + 7
             AND s.QTY > NVL((SELECT SUM(h.QTY) FROM SHIPMENT h WHERE h.SO_NO = s.SO_NO AND h.LINE_NO = s.LINE_NO), 0)
           ORDER BY
                 s.DUE_DT""",
          """
          SELECT
                 s.SO_NO
               , s.LINE_NO
               , s.PART_NO
               , s.DUE_DT
               , s.QTY - COALESCE((SELECT SUM(h.QTY) FROM SHIPMENT h WHERE h.SO_NO = s.SO_NO AND h.LINE_NO = s.LINE_NO), 0) AS REMAIN_QTY
            FROM SALES_ORDER s
           WHERE s.DUE_DT BETWEEN CURRENT_DATE AND CURRENT_DATE + 7
             AND s.QTY > COALESCE((SELECT SUM(h.QTY) FROM SHIPMENT h WHERE h.SO_NO = s.SO_NO AND h.LINE_NO = s.LINE_NO), 0)
           ORDER BY
                 s.DUE_DT"""),
      new Example("영업 · 구매 · 회계", "출하했는데 청구 안 된 것", "출하 수량은 있는데 같은 수주 줄의 청구가 없는 것.",
          """
          SELECT
                 h.SO_NO
               , h.LINE_NO
               , SUM(h.QTY) AS SHIP_QTY
               , MAX(h.SHIP_DT) AS LAST_SHIP
            FROM SHIPMENT h
           WHERE NOT EXISTS (SELECT 1 FROM INVOICE v WHERE v.SO_NO = h.SO_NO AND v.LINE_NO = h.LINE_NO)
           GROUP BY
                 h.SO_NO
               , h.LINE_NO""",
          """
          SELECT
                 h.SO_NO
               , h.LINE_NO
               , SUM(h.QTY) AS SHIP_QTY
               , MAX(h.SHIP_DT) AS LAST_SHIP
            FROM SHIPMENT h
           WHERE NOT EXISTS (SELECT 1 FROM INVOICE v WHERE v.SO_NO = h.SO_NO AND v.LINE_NO = h.LINE_NO)
           GROUP BY
                 h.SO_NO
               , h.LINE_NO"""),
      new Example("영업 · 구매 · 회계", "통화별 발주 금액과 원화 환산 합계", "발주일 환율로 원화로 바꿔 통화마다 합칩니다. 환율이 없으면 그 줄은 비어 있습니다.",
          """
          SELECT
                 p.CURRENCY
               , SUM(p.QTY * p.PRICE) AS AMT
               , SUM(p.QTY * p.PRICE * NVL(r.RATE, CASE WHEN p.CURRENCY = 'KRW' THEN 1 END)) AS AMT_KRW
            FROM PURCHASE_ORDER p
               , EXCH_RATE r
           WHERE p.CURRENCY = r.CURRENCY(+)
             AND p.PO_DT = r.RATE_DT(+)
           GROUP BY
                 p.CURRENCY""",
          """
          SELECT
                 p.CURRENCY
               , SUM(p.QTY * p.PRICE) AS AMT
               , SUM(p.QTY * p.PRICE * COALESCE(r.RATE, CASE WHEN p.CURRENCY = 'KRW' THEN 1 END)) AS AMT_KRW
            FROM PURCHASE_ORDER p
            LEFT OUTER JOIN EXCH_RATE r
              ON p.CURRENCY = r.CURRENCY
             AND p.PO_DT = r.RATE_DT
           GROUP BY
                 p.CURRENCY"""),
      new Example("영업 · 구매 · 회계", "예산 소진율 (부서 · 연 누계)", "올해 예산 합과 전표 실적 합. 80% 넘은 부서를 먼저.",
          """
          SELECT
                 b.DEPT_ID
               , SUM(b.BUDGET_AMT) AS BUDGET
               , NVL(MAX(g.SPENT), 0) AS SPENT
               , ROUND(NVL(MAX(g.SPENT), 0) * 100 / NULLIF(SUM(b.BUDGET_AMT), 0), 1) AS USED_PCT
            FROM BUDGET b
               , (SELECT e.DEPT_ID, SUM(e.DR_AMT) AS SPENT FROM GL_ENTRY e WHERE TO_CHAR(e.ENTRY_DT, 'YYYY') = TO_CHAR(SYSDATE, 'YYYY') GROUP BY e.DEPT_ID) g
           WHERE b.DEPT_ID = g.DEPT_ID(+)
             AND SUBSTR(b.YM, 1, 4) = TO_CHAR(SYSDATE, 'YYYY')
           GROUP BY
                 b.DEPT_ID
           ORDER BY
                 USED_PCT DESC NULLS LAST""",
          """
          SELECT
                 b.DEPT_ID
               , SUM(b.BUDGET_AMT) AS BUDGET
               , COALESCE(MAX(g.SPENT), 0) AS SPENT
               , ROUND(COALESCE(MAX(g.SPENT), 0) * 100 / NULLIF(SUM(b.BUDGET_AMT), 0), 1) AS USED_PCT
            FROM BUDGET b
            LEFT OUTER JOIN (SELECT e.DEPT_ID, SUM(e.DR_AMT) AS SPENT FROM GL_ENTRY e WHERE TO_CHAR(e.ENTRY_DT, 'YYYY') = TO_CHAR(CURRENT_DATE, 'YYYY') GROUP BY e.DEPT_ID) g
              ON b.DEPT_ID = g.DEPT_ID
           WHERE SUBSTR(b.YM, 1, 4) = TO_CHAR(CURRENT_DATE, 'YYYY')
           GROUP BY
                 b.DEPT_ID
           ORDER BY
                 USED_PCT DESC NULLS LAST"""),
      new Example("영업 · 구매 · 회계", "전표 번호 빠진 곳 (끊긴 번호)", "전표 번호가 숫자일 때 앞 번호 + 1 이 아닌 곳.",
          """
          SELECT
                 x.PREV_NO
               , x.VOUCHER_NO
            FROM (
                  SELECT DISTINCT
                         g.VOUCHER_NO
                       , LAG(g.VOUCHER_NO) OVER (ORDER BY TO_NUMBER(g.VOUCHER_NO)) AS PREV_NO
                    FROM (SELECT DISTINCT e.VOUCHER_NO FROM GL_ENTRY e WHERE REGEXP_LIKE(e.VOUCHER_NO, '^[0-9]+$')) g
                 ) x
           WHERE TO_NUMBER(x.VOUCHER_NO) - TO_NUMBER(x.PREV_NO) > 1""",
          """
          SELECT
                 x.PREV_NO
               , x.VOUCHER_NO
            FROM (
                  SELECT
                         g.VOUCHER_NO
                       , LAG(g.VOUCHER_NO) OVER (ORDER BY CAST(g.VOUCHER_NO AS BIGINT)) AS PREV_NO
                    FROM (SELECT DISTINCT e.VOUCHER_NO FROM GL_ENTRY e WHERE e.VOUCHER_NO ~ '^[0-9]+$') g
                 ) x
           WHERE CAST(x.VOUCHER_NO AS BIGINT) - CAST(x.PREV_NO AS BIGINT) > 1"""),
      new Example("영업 · 구매 · 회계", "고객 코호트 (첫 주문 달 × 몇 달 뒤 재주문 수)", "첫 주문 달로 고객을 묶고, 그 뒤 0 · 1 · 2 · 3 달째에 주문한 고객 수.",
          """
          SELECT
                 f.FIRST_YM
               , COUNT(DISTINCT f.CUST_ID) AS CUST_CNT
               , COUNT(DISTINCT CASE WHEN MONTHS_BETWEEN(TRUNC(o.ORDER_DT, 'MM'), TO_DATE(f.FIRST_YM, 'YYYY-MM')) = 1 THEN o.CUST_ID END) AS M1
               , COUNT(DISTINCT CASE WHEN MONTHS_BETWEEN(TRUNC(o.ORDER_DT, 'MM'), TO_DATE(f.FIRST_YM, 'YYYY-MM')) = 2 THEN o.CUST_ID END) AS M2
               , COUNT(DISTINCT CASE WHEN MONTHS_BETWEEN(TRUNC(o.ORDER_DT, 'MM'), TO_DATE(f.FIRST_YM, 'YYYY-MM')) = 3 THEN o.CUST_ID END) AS M3
            FROM (SELECT x.CUST_ID, TO_CHAR(MIN(x.ORDER_DT), 'YYYY-MM') AS FIRST_YM FROM ORDERS x GROUP BY x.CUST_ID) f
               , ORDERS o
           WHERE f.CUST_ID = o.CUST_ID
           GROUP BY
                 f.FIRST_YM
           ORDER BY
                 f.FIRST_YM""",
          """
          SELECT
                 f.FIRST_YM
               , COUNT(DISTINCT f.CUST_ID) AS CUST_CNT
               , COUNT(DISTINCT CASE WHEN (EXTRACT(YEAR FROM o.ORDER_DT) * 12 + EXTRACT(MONTH FROM o.ORDER_DT)) - (CAST(LEFT(f.FIRST_YM, 4) AS INTEGER) * 12 + CAST(RIGHT(f.FIRST_YM, 2) AS INTEGER)) = 1 THEN o.CUST_ID END) AS M1
               , COUNT(DISTINCT CASE WHEN (EXTRACT(YEAR FROM o.ORDER_DT) * 12 + EXTRACT(MONTH FROM o.ORDER_DT)) - (CAST(LEFT(f.FIRST_YM, 4) AS INTEGER) * 12 + CAST(RIGHT(f.FIRST_YM, 2) AS INTEGER)) = 2 THEN o.CUST_ID END) AS M2
               , COUNT(DISTINCT CASE WHEN (EXTRACT(YEAR FROM o.ORDER_DT) * 12 + EXTRACT(MONTH FROM o.ORDER_DT)) - (CAST(LEFT(f.FIRST_YM, 4) AS INTEGER) * 12 + CAST(RIGHT(f.FIRST_YM, 2) AS INTEGER)) = 3 THEN o.CUST_ID END) AS M3
            FROM (SELECT x.CUST_ID, TO_CHAR(MIN(x.ORDER_DT), 'YYYY-MM') AS FIRST_YM FROM ORDERS x GROUP BY x.CUST_ID) f
               , ORDERS o
           WHERE f.CUST_ID = o.CUST_ID
           GROUP BY
                 f.FIRST_YM
           ORDER BY
                 f.FIRST_YM"""),
      new Example("영업 · 구매 · 회계", "지난 분기엔 샀는데 이번 분기엔 안 산 고객 (이탈 후보)", "두 기간을 EXISTS 두 개로 비교합니다.",
          """
          SELECT
                 c.CUST_ID
               , c.CUST_NAME
            FROM CUSTOMER c
           WHERE EXISTS (
                  SELECT 1 FROM ORDERS o
                   WHERE o.CUST_ID = c.CUST_ID
                     AND o.ORDER_DT >= ADD_MONTHS(TRUNC(SYSDATE, 'Q'), -3)
                     AND o.ORDER_DT < TRUNC(SYSDATE, 'Q')
                 )
             AND NOT EXISTS (
                  SELECT 1 FROM ORDERS o
                   WHERE o.CUST_ID = c.CUST_ID
                     AND o.ORDER_DT >= TRUNC(SYSDATE, 'Q')
                 )""",
          """
          SELECT
                 c.CUST_ID
               , c.CUST_NAME
            FROM CUSTOMER c
           WHERE EXISTS (
                  SELECT 1 FROM ORDERS o
                   WHERE o.CUST_ID = c.CUST_ID
                     AND o.ORDER_DT >= DATE_TRUNC('QUARTER', CURRENT_DATE) - INTERVAL '3 MONTH'
                     AND o.ORDER_DT < DATE_TRUNC('QUARTER', CURRENT_DATE)
                 )
             AND NOT EXISTS (
                  SELECT 1 FROM ORDERS o
                   WHERE o.CUST_ID = c.CUST_ID
                     AND o.ORDER_DT >= DATE_TRUNC('QUARTER', CURRENT_DATE)
                 )"""),
      new Example("영업 · 구매 · 회계", "월별 신규 고객 수 (첫 주문 기준)", "고객마다 첫 주문한 달을 구해 달마다 셉니다.",
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
      new Example("영업 · 구매 · 회계", "월별 재구매 고객 (그달 2번 이상)", "한 달에 두 번 이상 주문한 고객 수.",
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
      new Example("영업 · 구매 · 회계", "월별 3개월 이동 평균 매출", "이번 달 포함 앞 3달 평균. 들쭉날쭉한 달 매출을 부드럽게 봅니다.",
          """
          SELECT
                 x.YM
               , x.AMT
               , ROUND(AVG(x.AMT) OVER (ORDER BY x.YM ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)) AS MA3
            FROM (
                  SELECT
                         TO_CHAR(s.SO_DT, 'YYYY-MM') AS YM
                       , SUM(s.QTY * s.PRICE) AS AMT
                    FROM SALES_ORDER s
                   GROUP BY
                         TO_CHAR(s.SO_DT, 'YYYY-MM')
                 ) x
           ORDER BY
                 x.YM""",
          """
          SELECT
                 x.YM
               , x.AMT
               , ROUND(AVG(x.AMT) OVER (ORDER BY x.YM ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)) AS MA3
            FROM (
                  SELECT
                         TO_CHAR(s.SO_DT, 'YYYY-MM') AS YM
                       , SUM(s.QTY * s.PRICE) AS AMT
                    FROM SALES_ORDER s
                   GROUP BY
                         TO_CHAR(s.SO_DT, 'YYYY-MM')
                 ) x
           ORDER BY
                 x.YM"""),
      new Example("영업 · 구매 · 회계", "상위 20% 고객이 차지하는 매출 비중 (파레토)", "고객을 매출 순으로 다섯 등분해 맨 위 묶음의 매출 비중을 봅니다.",
          """
          SELECT
                 x.BUCKET
               , COUNT(*) AS CUST_CNT
               , SUM(x.AMT) AS AMT
               , ROUND(SUM(x.AMT) * 100 / SUM(SUM(x.AMT)) OVER (), 1) AS PCT
            FROM (
                  SELECT
                         o.CUST_ID
                       , SUM(o.AMOUNT) AS AMT
                       , NTILE(5) OVER (ORDER BY SUM(o.AMOUNT) DESC) AS BUCKET
                    FROM ORDERS o
                   GROUP BY
                         o.CUST_ID
                 ) x
           GROUP BY
                 x.BUCKET
           ORDER BY
                 x.BUCKET""",
          """
          SELECT
                 x.BUCKET
               , COUNT(*) AS CUST_CNT
               , SUM(x.AMT) AS AMT
               , ROUND(SUM(x.AMT) * 100 / SUM(SUM(x.AMT)) OVER (), 1) AS PCT
            FROM (
                  SELECT
                         o.CUST_ID
                       , SUM(o.AMOUNT) AS AMT
                       , NTILE(5) OVER (ORDER BY SUM(o.AMOUNT) DESC) AS BUCKET
                    FROM ORDERS o
                   GROUP BY
                         o.CUST_ID
                 ) x
           GROUP BY
                 x.BUCKET
           ORDER BY
                 x.BUCKET"""),
      new Example("생산 · 품질", "소요량 계산 (MRP: 생산 계획 × BOM − 재고 − 발주 잔량)", "계획 수량으로 BOM 을 끝까지 펼쳐 부품별 총 필요량을 구하고, 지금 재고와 들어올 발주 잔량을 빼 더 사야 할 양을 냅니다.",
          """
          WITH NEED (PART_NO, QTY, LVL) AS (
          SELECT
                 b.CHILD_PART
               , b.QTY * p.QTY
               , 1
            FROM PROD_PLAN p, BOM b
           WHERE p.PART_NO = b.PARENT_PART
             AND p.PLAN_DT BETWEEN TO_DATE(:fromDt, 'YYYY-MM-DD') AND TO_DATE(:toDt, 'YYYY-MM-DD')
             AND b.REV = :rev
           UNION ALL
          SELECT
                 b.CHILD_PART
               , b.QTY * n.QTY
               , n.LVL + 1
            FROM BOM b, NEED n
           WHERE b.PARENT_PART = n.PART_NO
             AND b.REV = :rev
             AND n.LVL < 20
          )
          SELECT
                 n.PART_NO
               , SUM(n.QTY) AS GROSS
               , NVL(MAX(st.QTY), 0) AS STOCK
               , NVL(MAX(po.QTY), 0) AS ON_ORDER
               , GREATEST(SUM(n.QTY) - NVL(MAX(st.QTY), 0) - NVL(MAX(po.QTY), 0), 0) AS TO_BUY
            FROM NEED n
               , (SELECT t.PART_NO, SUM(DECODE(t.TXN_TYPE, 'IN', t.QTY, -t.QTY)) AS QTY FROM STOCK_TXN t GROUP BY t.PART_NO) st
               , (SELECT o.PART_NO, SUM(o.QTY) - NVL(SUM(r.QTY), 0) AS QTY FROM PURCHASE_ORDER o, RECEIPT r WHERE o.PO_NO = r.PO_NO(+) AND o.LINE_NO = r.LINE_NO(+) GROUP BY o.PART_NO) po
           WHERE n.PART_NO = st.PART_NO(+)
             AND n.PART_NO = po.PART_NO(+)
             AND NOT EXISTS (SELECT 1 FROM BOM c WHERE c.PARENT_PART = n.PART_NO)
           GROUP BY
                 n.PART_NO
           ORDER BY
                 TO_BUY DESC""",
          """
          WITH RECURSIVE NEED AS (
          SELECT
                 b.CHILD_PART AS PART_NO
               , b.QTY * p.QTY AS QTY
               , 1 AS LVL
            FROM PROD_PLAN p, BOM b
           WHERE p.PART_NO = b.PARENT_PART
             AND p.PLAN_DT BETWEEN TO_DATE(:fromDt, 'YYYY-MM-DD') AND TO_DATE(:toDt, 'YYYY-MM-DD')
             AND b.REV = :rev
           UNION ALL
          SELECT
                 b.CHILD_PART
               , b.QTY * n.QTY
               , n.LVL + 1
            FROM BOM b, NEED n
           WHERE b.PARENT_PART = n.PART_NO
             AND b.REV = :rev
             AND n.LVL < 20
          )
          SELECT
                 n.PART_NO
               , SUM(n.QTY) AS GROSS
               , COALESCE(MAX(st.QTY), 0) AS STOCK
               , COALESCE(MAX(po.QTY), 0) AS ON_ORDER
               , GREATEST(SUM(n.QTY) - COALESCE(MAX(st.QTY), 0) - COALESCE(MAX(po.QTY), 0), 0) AS TO_BUY
            FROM NEED n
            LEFT OUTER JOIN (SELECT t.PART_NO, SUM(CASE t.TXN_TYPE WHEN 'IN' THEN t.QTY ELSE -t.QTY END) AS QTY FROM STOCK_TXN t GROUP BY t.PART_NO) st
              ON n.PART_NO = st.PART_NO
            LEFT OUTER JOIN (SELECT o.PART_NO, SUM(o.QTY) - COALESCE(SUM(r.QTY), 0) AS QTY FROM PURCHASE_ORDER o LEFT OUTER JOIN RECEIPT r ON o.PO_NO = r.PO_NO AND o.LINE_NO = r.LINE_NO GROUP BY o.PART_NO) po
              ON n.PART_NO = po.PART_NO
           WHERE NOT EXISTS (SELECT 1 FROM BOM c WHERE c.PARENT_PART = n.PART_NO)
           GROUP BY
                 n.PART_NO
           ORDER BY
                 TO_BUY DESC"""),
      new Example("생산 · 품질", "작업 지시 대비 실적 · 달성률", "지시 수량과 양품 · 불량 합, 달성률.",
          """
          SELECT
                 w.WO_NO
               , w.PART_NO
               , w.PLAN_QTY
               , NVL(SUM(r.GOOD_QTY), 0) AS GOOD
               , NVL(SUM(r.NG_QTY), 0) AS NG
               , ROUND(NVL(SUM(r.GOOD_QTY), 0) * 100 / NULLIF(w.PLAN_QTY, 0), 1) AS ACHIEVE_PCT
            FROM WORK_ORDER w, WORK_RESULT r
           WHERE w.WO_NO = r.WO_NO(+)
           GROUP BY
                 w.WO_NO
               , w.PART_NO
               , w.PLAN_QTY
           ORDER BY
                 w.WO_NO""",
          """
          SELECT
                 w.WO_NO
               , w.PART_NO
               , w.PLAN_QTY
               , COALESCE(SUM(r.GOOD_QTY), 0) AS GOOD
               , COALESCE(SUM(r.NG_QTY), 0) AS NG
               , ROUND(COALESCE(SUM(r.GOOD_QTY), 0) * 100 / NULLIF(w.PLAN_QTY, 0), 1) AS ACHIEVE_PCT
            FROM WORK_ORDER w
            LEFT OUTER JOIN WORK_RESULT r
              ON w.WO_NO = r.WO_NO
           GROUP BY
                 w.WO_NO
               , w.PART_NO
               , w.PLAN_QTY
           ORDER BY
                 w.WO_NO"""),
      new Example("생산 · 품질", "공정별 불량률 (ppm 포함)", "공정마다 생산 · 불량 수와 불량률(%) · ppm.",
          """
          SELECT
                 r.PROC_CD
               , SUM(r.GOOD_QTY + r.NG_QTY) AS TOTAL
               , SUM(r.NG_QTY) AS NG
               , ROUND(SUM(r.NG_QTY) * 100 / NULLIF(SUM(r.GOOD_QTY + r.NG_QTY), 0), 2) AS NG_PCT
               , ROUND(SUM(r.NG_QTY) * 1000000 / NULLIF(SUM(r.GOOD_QTY + r.NG_QTY), 0)) AS PPM
            FROM WORK_RESULT r
           GROUP BY
                 r.PROC_CD
           ORDER BY
                 NG_PCT DESC""",
          """
          SELECT
                 r.PROC_CD
               , SUM(r.GOOD_QTY + r.NG_QTY) AS TOTAL
               , SUM(r.NG_QTY) AS NG
               , ROUND(SUM(r.NG_QTY) * 100 / NULLIF(SUM(r.GOOD_QTY + r.NG_QTY), 0), 2) AS NG_PCT
               , ROUND(SUM(r.NG_QTY) * 1000000 / NULLIF(SUM(r.GOOD_QTY + r.NG_QTY), 0)) AS PPM
            FROM WORK_RESULT r
           GROUP BY
                 r.PROC_CD
           ORDER BY
                 NG_PCT DESC"""),
      new Example("생산 · 품질", "불량 유형 파레토 (누적 80%)", "불량 유형을 많은 순으로 세우고 누적 비율을 붙입니다. 누적 80% 안의 유형이 먼저 잡을 것.",
          """
          SELECT
                 x.NG_TYPE
               , x.NG
               , ROUND(x.NG * 100 / SUM(x.NG) OVER (), 1) AS PCT
               , ROUND(SUM(x.NG) OVER (ORDER BY x.NG DESC, x.NG_TYPE) * 100 / SUM(x.NG) OVER (), 1) AS CUM_PCT
            FROM (
                  SELECT
                         r.NG_TYPE
                       , SUM(r.NG_QTY) AS NG
                    FROM WORK_RESULT r
                   WHERE r.NG_QTY > 0
                   GROUP BY
                         r.NG_TYPE
                 ) x
           ORDER BY
                 x.NG DESC""",
          """
          SELECT
                 x.NG_TYPE
               , x.NG
               , ROUND(x.NG * 100 / SUM(x.NG) OVER (), 1) AS PCT
               , ROUND(SUM(x.NG) OVER (ORDER BY x.NG DESC, x.NG_TYPE) * 100 / SUM(x.NG) OVER (), 1) AS CUM_PCT
            FROM (
                  SELECT
                         r.NG_TYPE
                       , SUM(r.NG_QTY) AS NG
                    FROM WORK_RESULT r
                   WHERE r.NG_QTY > 0
                   GROUP BY
                         r.NG_TYPE
                 ) x
           ORDER BY
                 x.NG DESC"""),
      new Example("생산 · 품질", "설비 가동률 (가동 시간 / 전체 시간)", "설비 로그의 상태별 시간을 더해 가동률을 냅니다. 비가동 사유별 시간도 함께.",
          """
          SELECT
                 e.EQUIP_ID
               , ROUND(SUM(CASE WHEN e.STATUS = 'RUN' THEN (e.END_TS - e.START_TS) * 24 ELSE 0 END), 1) AS RUN_H
               , ROUND(SUM(CASE WHEN e.STATUS = 'STOP' THEN (e.END_TS - e.START_TS) * 24 ELSE 0 END), 1) AS STOP_H
               , ROUND(SUM(CASE WHEN e.STATUS = 'RUN' THEN e.END_TS - e.START_TS ELSE 0 END) * 100 / SUM(e.END_TS - e.START_TS), 1) AS RUN_PCT
            FROM EQUIP_LOG e
           WHERE e.START_TS >= TO_DATE(:fromDt, 'YYYY-MM-DD')
             AND e.START_TS < TO_DATE(:toDt, 'YYYY-MM-DD') + 1
           GROUP BY
                 e.EQUIP_ID""",
          """
          SELECT
                 e.EQUIP_ID
               , ROUND(SUM(CASE WHEN e.STATUS = 'RUN' THEN EXTRACT(EPOCH FROM e.END_TS - e.START_TS) / 3600 ELSE 0 END), 1) AS RUN_H
               , ROUND(SUM(CASE WHEN e.STATUS = 'STOP' THEN EXTRACT(EPOCH FROM e.END_TS - e.START_TS) / 3600 ELSE 0 END), 1) AS STOP_H
               , ROUND(SUM(CASE WHEN e.STATUS = 'RUN' THEN EXTRACT(EPOCH FROM e.END_TS - e.START_TS) ELSE 0 END) * 100 / SUM(EXTRACT(EPOCH FROM e.END_TS - e.START_TS)), 1) AS RUN_PCT
            FROM EQUIP_LOG e
           WHERE e.START_TS >= TO_DATE(:fromDt, 'YYYY-MM-DD')
             AND e.START_TS < TO_DATE(:toDt, 'YYYY-MM-DD') + 1
           GROUP BY
                 e.EQUIP_ID"""),
      new Example("생산 · 품질", "비가동 사유별 시간 순위", "멈춘 시간을 사유별로 합쳐 큰 순으로.",
          """
          SELECT
                 e.REASON
               , COUNT(*) AS TIMES
               , ROUND(SUM(e.END_TS - e.START_TS) * 24 * 60) AS STOP_MIN
            FROM EQUIP_LOG e
           WHERE e.STATUS = 'STOP'
           GROUP BY
                 e.REASON
           ORDER BY
                 STOP_MIN DESC""",
          """
          SELECT
                 e.REASON
               , COUNT(*) AS TIMES
               , ROUND(SUM(EXTRACT(EPOCH FROM e.END_TS - e.START_TS)) / 60) AS STOP_MIN
            FROM EQUIP_LOG e
           WHERE e.STATUS = 'STOP'
           GROUP BY
                 e.REASON
           ORDER BY
                 STOP_MIN DESC"""),
      new Example("생산 · 품질", "로트 추적 정방향 (이 로트가 들어간 완제품)", "부품 로트에서 위로 올라가 어느 완제품 로트에 들어갔는지. 리콜 범위를 정할 때 씁니다.",
          """
          SELECT
                 LEVEL AS UP
               , l.PARENT_LOT
               , l.CHILD_LOT
               , l.PART_NO
            FROM LOT_LINK l
           START WITH l.CHILD_LOT = :lotNo
          CONNECT BY NOCYCLE l.CHILD_LOT = PRIOR l.PARENT_LOT""",
          """
          WITH RECURSIVE up AS (
          SELECT
                 1 AS UP
               , l.PARENT_LOT
               , l.CHILD_LOT
               , l.PART_NO
            FROM LOT_LINK l
           WHERE l.CHILD_LOT = :lotNo
           UNION
          SELECT
                 up.UP + 1
               , l.PARENT_LOT
               , l.CHILD_LOT
               , l.PART_NO
            FROM LOT_LINK l, up
           WHERE l.CHILD_LOT = up.PARENT_LOT
             AND up.UP < 20
          )
          SELECT
                 up.*
            FROM up"""),
      new Example("생산 · 품질", "로트 추적 역방향 (이 완제품에 들어간 부품 로트)", "완제품 로트에서 아래로 내려가 쓰인 부품 로트 전부.",
          """
          SELECT
                 LEVEL AS DOWN
               , LPAD(' ', (LEVEL - 1) * 2) || l.CHILD_LOT AS LOT_NO
               , l.PART_NO
            FROM LOT_LINK l
           START WITH l.PARENT_LOT = :lotNo
          CONNECT BY NOCYCLE PRIOR l.CHILD_LOT = l.PARENT_LOT""",
          """
          WITH RECURSIVE down AS (
          SELECT
                 1 AS DOWN
               , l.CHILD_LOT
               , l.PART_NO
            FROM LOT_LINK l
           WHERE l.PARENT_LOT = :lotNo
           UNION
          SELECT
                 down.DOWN + 1
               , l.CHILD_LOT
               , l.PART_NO
            FROM LOT_LINK l, down
           WHERE l.PARENT_LOT = down.CHILD_LOT
             AND down.DOWN < 20
          )
          SELECT
                 down.DOWN
               , REPEAT(' ', (down.DOWN - 1) * 2) || down.CHILD_LOT AS LOT_NO
               , down.PART_NO
            FROM down"""),
      new Example("생산 · 품질", "검사 규격 벗어난 측정값", "측정값이 하한(LSL) ~ 상한(USL) 밖인 것과 얼마나 벗어났는지.",
          """
          SELECT
                 i.PART_NO
               , i.LOT_NO
               , i.ITEM_CD
               , i.MEASURED
               , i.LSL
               , i.USL
               , CASE WHEN i.MEASURED < i.LSL THEN i.MEASURED - i.LSL ELSE i.MEASURED - i.USL END AS OUT_BY
            FROM INSPECTION i
           WHERE i.MEASURED < i.LSL
              OR i.MEASURED > i.USL
           ORDER BY
                 i.INSP_DT DESC""",
          """
          SELECT
                 i.PART_NO
               , i.LOT_NO
               , i.ITEM_CD
               , i.MEASURED
               , i.LSL
               , i.USL
               , CASE WHEN i.MEASURED < i.LSL THEN i.MEASURED - i.LSL ELSE i.MEASURED - i.USL END AS OUT_BY
            FROM INSPECTION i
           WHERE i.MEASURED < i.LSL
              OR i.MEASURED > i.USL
           ORDER BY
                 i.INSP_DT DESC"""),
      new Example("생산 · 품질", "공정 능력 (평균 · 표준편차 · Cpk)", "검사 항목마다 평균, 표준편차, Cpk = min(USL − 평균, 평균 − LSL) / (3σ). 1.33 이상이면 보통 충분하다고 봅니다.",
          """
          SELECT
                 i.PART_NO
               , i.ITEM_CD
               , COUNT(*) AS N
               , ROUND(AVG(i.MEASURED), 4) AS MEAN
               , ROUND(STDDEV(i.MEASURED), 4) AS SIGMA
               , ROUND(LEAST(MAX(i.USL) - AVG(i.MEASURED), AVG(i.MEASURED) - MAX(i.LSL)) / NULLIF(3 * STDDEV(i.MEASURED), 0), 2) AS CPK
            FROM INSPECTION i
           GROUP BY
                 i.PART_NO
               , i.ITEM_CD""",
          """
          SELECT
                 i.PART_NO
               , i.ITEM_CD
               , COUNT(*) AS N
               , ROUND(AVG(i.MEASURED), 4) AS MEAN
               , ROUND(STDDEV(i.MEASURED), 4) AS SIGMA
               , ROUND(LEAST(MAX(i.USL) - AVG(i.MEASURED), AVG(i.MEASURED) - MAX(i.LSL)) / NULLIF(3 * STDDEV(i.MEASURED), 0), 2) AS CPK
            FROM INSPECTION i
           GROUP BY
                 i.PART_NO
               , i.ITEM_CD"""),
      new Example("생산 · 품질", "시정 조치(CAPA) 미결 건과 경과일", "열린 시정 조치, 기한이 지났는지, 연 지 며칠인지.",
          """
          SELECT
                 c.CAPA_NO
               , c.ISSUE_TYPE
               , c.PART_NO
               , c.OWNER_ID
               , c.OPEN_DT
               , TRUNC(SYSDATE) - c.OPEN_DT AS OPEN_DAYS
               , CASE WHEN c.DUE_DT < TRUNC(SYSDATE) THEN '기한 지남' END AS OVERDUE
            FROM CAPA c
           WHERE c.STATUS = 'OPEN'
           ORDER BY
                 c.DUE_DT""",
          """
          SELECT
                 c.CAPA_NO
               , c.ISSUE_TYPE
               , c.PART_NO
               , c.OWNER_ID
               , c.OPEN_DT
               , CURRENT_DATE - c.OPEN_DT AS OPEN_DAYS
               , CASE WHEN c.DUE_DT < CURRENT_DATE THEN '기한 지남' END AS OVERDUE
            FROM CAPA c
           WHERE c.STATUS = 'OPEN'
           ORDER BY
                 c.DUE_DT"""),
      new Example("생산 · 품질", "라인별 일 생산량과 목표 대비", "라인마다 날짜별 양품 수와 계획 수량 대비 달성률.",
          """
          SELECT
                 w.LINE_CD
               , r.WORK_DT
               , SUM(r.GOOD_QTY) AS GOOD
               , SUM(w.PLAN_QTY) AS PLAN
               , ROUND(SUM(r.GOOD_QTY) * 100 / NULLIF(SUM(w.PLAN_QTY), 0), 1) AS RATE
            FROM WORK_ORDER w
               , WORK_RESULT r
           WHERE w.WO_NO = r.WO_NO
           GROUP BY
                 w.LINE_CD
               , r.WORK_DT
           ORDER BY
                 w.LINE_CD
               , r.WORK_DT""",
          """
          SELECT
                 w.LINE_CD
               , r.WORK_DT
               , SUM(r.GOOD_QTY) AS GOOD
               , SUM(w.PLAN_QTY) AS PLAN
               , ROUND(SUM(r.GOOD_QTY) * 100 / NULLIF(SUM(w.PLAN_QTY), 0), 1) AS RATE
            FROM WORK_ORDER w
               , WORK_RESULT r
           WHERE w.WO_NO = r.WO_NO
           GROUP BY
                 w.LINE_CD
               , r.WORK_DT
           ORDER BY
                 w.LINE_CD
               , r.WORK_DT"""),
      new Example("생산 · 품질", "설비별 고장 사이 평균 시간 (MTBF) · 평균 수리 시간 (MTTR)", "고장(DOWN) 구간의 평균 길이와 고장 사이 간격. 설비 보전 지표입니다.",
          """
          SELECT
                 x.EQUIP_ID
               , COUNT(*) AS DOWN_CNT
               , ROUND(AVG((CAST(x.END_TS AS DATE) - CAST(x.START_TS AS DATE)) * 24), 2) AS MTTR_HOURS
               , ROUND(AVG((CAST(x.START_TS AS DATE) - CAST(x.PREV_END AS DATE)) * 24), 2) AS MTBF_HOURS
            FROM (
                  SELECT
                         l.EQUIP_ID
                       , l.START_TS
                       , l.END_TS
                       , LAG(l.END_TS) OVER (PARTITION BY l.EQUIP_ID ORDER BY l.START_TS) AS PREV_END
                    FROM EQUIP_LOG l
                   WHERE l.STATUS = 'DOWN'
                 ) x
           GROUP BY
                 x.EQUIP_ID""",
          """
          SELECT
                 x.EQUIP_ID
               , COUNT(*) AS DOWN_CNT
               , ROUND(CAST(AVG(EXTRACT(EPOCH FROM (x.END_TS - x.START_TS))) / 3600 AS NUMERIC), 2) AS MTTR_HOURS
               , ROUND(CAST(AVG(EXTRACT(EPOCH FROM (x.START_TS - x.PREV_END))) / 3600 AS NUMERIC), 2) AS MTBF_HOURS
            FROM (
                  SELECT
                         l.EQUIP_ID
                       , l.START_TS
                       , l.END_TS
                       , LAG(l.END_TS) OVER (PARTITION BY l.EQUIP_ID ORDER BY l.START_TS) AS PREV_END
                    FROM EQUIP_LOG l
                   WHERE l.STATUS = 'DOWN'
                 ) x
           GROUP BY
                 x.EQUIP_ID"""),
      new Example("생산 · 품질", "협력사 · 부품별 입고 불량률 추이 (월)", "입고 검사 불량 수량을 월마다 나눠 봅니다.",
          """
          SELECT
                 TO_CHAR(r.RCV_DT, 'YYYY-MM') AS YM
               , p.VENDOR_ID
               , SUM(r.QTY) AS RCV_QTY
               , SUM(r.NG_QTY) AS NG_QTY
               , ROUND(SUM(r.NG_QTY) * 100 / NULLIF(SUM(r.QTY), 0), 2) AS NG_PCT
            FROM RECEIPT r
               , PURCHASE_ORDER p
           WHERE r.PO_NO = p.PO_NO
             AND r.LINE_NO = p.LINE_NO
           GROUP BY
                 TO_CHAR(r.RCV_DT, 'YYYY-MM')
               , p.VENDOR_ID
           ORDER BY
                 YM
               , p.VENDOR_ID""",
          """
          SELECT
                 TO_CHAR(r.RCV_DT, 'YYYY-MM') AS YM
               , p.VENDOR_ID
               , SUM(r.QTY) AS RCV_QTY
               , SUM(r.NG_QTY) AS NG_QTY
               , ROUND(SUM(r.NG_QTY) * 100 / NULLIF(SUM(r.QTY), 0), 2) AS NG_PCT
            FROM RECEIPT r
               , PURCHASE_ORDER p
           WHERE r.PO_NO = p.PO_NO
             AND r.LINE_NO = p.LINE_NO
           GROUP BY
                 TO_CHAR(r.RCV_DT, 'YYYY-MM')
               , p.VENDOR_ID
           ORDER BY
                 YM
               , p.VENDOR_ID"""),
      new Example("생산 · 품질", "같은 불량이 반복되는 부품 (최근 30일 3번 넘게)", "불량 유형별로 같은 부품에서 자꾸 나오는 것. 시정 조치 후보입니다.",
          """
          SELECT
                 w.PART_NO
               , r.NG_TYPE
               , COUNT(*) AS TIMES
               , SUM(r.NG_QTY) AS NG_QTY
            FROM WORK_RESULT r
               , WORK_ORDER w
           WHERE r.WO_NO = w.WO_NO
             AND r.NG_QTY > 0
             AND r.WORK_DT >= TRUNC(SYSDATE) - 30
           GROUP BY
                 w.PART_NO
               , r.NG_TYPE
          HAVING COUNT(*) > 3
           ORDER BY
                 TIMES DESC""",
          """
          SELECT
                 w.PART_NO
               , r.NG_TYPE
               , COUNT(*) AS TIMES
               , SUM(r.NG_QTY) AS NG_QTY
            FROM WORK_RESULT r
               , WORK_ORDER w
           WHERE r.WO_NO = w.WO_NO
             AND r.NG_QTY > 0
             AND r.WORK_DT >= CURRENT_DATE - 30
           GROUP BY
                 w.PART_NO
               , r.NG_TYPE
          HAVING COUNT(*) > 3
           ORDER BY
                 TIMES DESC"""),
      new Example("생산 · 품질", "시간당 생산량 (UPH, 설비별)", "가동(RUN) 시간 합으로 양품 수를 나눕니다.",
          """
          SELECT
                 r.EQUIP_ID
               , SUM(r.GOOD_QTY) AS GOOD
               , ROUND(MAX(t.RUN_HOURS), 1) AS RUN_HOURS
               , ROUND(SUM(r.GOOD_QTY) / NULLIF(MAX(t.RUN_HOURS), 0), 1) AS UPH
            FROM WORK_RESULT r
               , (SELECT l.EQUIP_ID, SUM((CAST(l.END_TS AS DATE) - CAST(l.START_TS AS DATE)) * 24) AS RUN_HOURS FROM EQUIP_LOG l WHERE l.STATUS = 'RUN' GROUP BY l.EQUIP_ID) t
           WHERE r.EQUIP_ID = t.EQUIP_ID
           GROUP BY
                 r.EQUIP_ID""",
          """
          SELECT
                 r.EQUIP_ID
               , SUM(r.GOOD_QTY) AS GOOD
               , ROUND(MAX(t.RUN_HOURS), 1) AS RUN_HOURS
               , ROUND(SUM(r.GOOD_QTY) / NULLIF(MAX(t.RUN_HOURS), 0), 1) AS UPH
            FROM WORK_RESULT r
               , (SELECT l.EQUIP_ID, CAST(SUM(EXTRACT(EPOCH FROM (l.END_TS - l.START_TS))) / 3600 AS NUMERIC) AS RUN_HOURS FROM EQUIP_LOG l WHERE l.STATUS = 'RUN' GROUP BY l.EQUIP_ID) t
           WHERE r.EQUIP_ID = t.EQUIP_ID
           GROUP BY
                 r.EQUIP_ID"""),
      new Example("생산 · 품질", "설비 지금 상태 (마지막 기록 한 줄)", "설비마다 가장 최근 상태와 그 상태가 된 지 몇 분째인지.",
          """
          SELECT
                 x.EQUIP_ID
               , x.STATUS
               , x.START_TS
               , ROUND((SYSDATE - CAST(x.START_TS AS DATE)) * 1440) AS MINUTES
            FROM (
                  SELECT
                         l.*
                       , ROW_NUMBER() OVER (PARTITION BY l.EQUIP_ID ORDER BY l.START_TS DESC) AS RN
                    FROM EQUIP_LOG l
                 ) x
           WHERE x.RN = 1""",
          """
          SELECT
                 x.EQUIP_ID
               , x.STATUS
               , x.START_TS
               , ROUND(CAST(EXTRACT(EPOCH FROM (NOW() - x.START_TS)) / 60 AS NUMERIC)) AS MINUTES
            FROM (
                  SELECT
                         l.*
                       , ROW_NUMBER() OVER (PARTITION BY l.EQUIP_ID ORDER BY l.START_TS DESC) AS RN
                    FROM EQUIP_LOG l
                 ) x
           WHERE x.RN = 1"""),
      new Example("생산 · 품질", "교대별 생산 (주간 08~20시 · 야간)", "시작 시각으로 주간 · 야간을 나눠 설비마다 셉니다. 야간이 날을 넘으면 앞날로 붙입니다.",
          """
          SELECT
                 l.EQUIP_ID
               , TRUNC(CAST(l.START_TS AS DATE) - 8 / 24) AS WORK_DAY
               , CASE WHEN TO_NUMBER(TO_CHAR(l.START_TS, 'HH24')) BETWEEN 8 AND 19 THEN '주간' ELSE '야간' END AS SHIFT
               , COUNT(*) AS LOG_CNT
            FROM EQUIP_LOG l
           GROUP BY
                 l.EQUIP_ID
               , TRUNC(CAST(l.START_TS AS DATE) - 8 / 24)
               , CASE WHEN TO_NUMBER(TO_CHAR(l.START_TS, 'HH24')) BETWEEN 8 AND 19 THEN '주간' ELSE '야간' END""",
          """
          SELECT
                 l.EQUIP_ID
               , CAST(l.START_TS - INTERVAL '8 HOUR' AS DATE) AS WORK_DAY
               , CASE WHEN EXTRACT(HOUR FROM l.START_TS) BETWEEN 8 AND 19 THEN '주간' ELSE '야간' END AS SHIFT
               , COUNT(*) AS LOG_CNT
            FROM EQUIP_LOG l
           GROUP BY
                 l.EQUIP_ID
               , CAST(l.START_TS - INTERVAL '8 HOUR' AS DATE)
               , CASE WHEN EXTRACT(HOUR FROM l.START_TS) BETWEEN 8 AND 19 THEN '주간' ELSE '야간' END"""),
      new Example("생산 · 품질", "로트별 양품률 · 나쁜 로트 순", "로트마다 양품 / (양품 + 불량). 낮은 순.",
          """
          SELECT
                 r.LOT_NO
               , SUM(r.GOOD_QTY) AS GOOD
               , SUM(r.NG_QTY) AS NG
               , ROUND(SUM(r.GOOD_QTY) * 100 / NULLIF(SUM(r.GOOD_QTY) + SUM(r.NG_QTY), 0), 2) AS YIELD_PCT
            FROM WORK_RESULT r
           GROUP BY
                 r.LOT_NO
           ORDER BY
                 YIELD_PCT""",
          """
          SELECT
                 r.LOT_NO
               , SUM(r.GOOD_QTY) AS GOOD
               , SUM(r.NG_QTY) AS NG
               , ROUND(SUM(r.GOOD_QTY) * 100 / NULLIF(SUM(r.GOOD_QTY) + SUM(r.NG_QTY), 0), 2) AS YIELD_PCT
            FROM WORK_RESULT r
           GROUP BY
                 r.LOT_NO
           ORDER BY
                 YIELD_PCT"""),
      new Example("생산 · 품질", "공정 실적 빠짐 (지시는 있는데 실적이 없는 공정)", "작업 지시마다 실적이 하나도 없거나 오늘까지 실적이 없는 것.",
          """
          SELECT
                 w.WO_NO
               , w.PART_NO
               , w.WO_DT
               , w.STATUS
            FROM WORK_ORDER w
           WHERE w.WO_DT < TRUNC(SYSDATE)
             AND NOT EXISTS (SELECT 1 FROM WORK_RESULT r WHERE r.WO_NO = w.WO_NO)""",
          """
          SELECT
                 w.WO_NO
               , w.PART_NO
               , w.WO_DT
               , w.STATUS
            FROM WORK_ORDER w
           WHERE w.WO_DT < CURRENT_DATE
             AND NOT EXISTS (SELECT 1 FROM WORK_RESULT r WHERE r.WO_NO = w.WO_NO)"""),
      new Example("생산 · 품질", "측정값 연속 3번 관리선 밖 (연속 이탈)", "같은 부품 · 검사 항목에서 규격 밖이 3번 이어진 곳. 앞 두 줄을 LAG 로 봅니다.",
          """
          SELECT
                 x.PART_NO
               , x.ITEM_CD
               , x.INSP_DT
               , x.MEASURED
            FROM (
                  SELECT
                         i.PART_NO
                       , i.ITEM_CD
                       , i.INSP_DT
                       , i.MEASURED
                       , CASE WHEN i.MEASURED NOT BETWEEN i.LSL AND i.USL THEN 1 ELSE 0 END AS OUT1
                       , LAG(CASE WHEN i.MEASURED NOT BETWEEN i.LSL AND i.USL THEN 1 ELSE 0 END, 1) OVER (PARTITION BY i.PART_NO, i.ITEM_CD ORDER BY i.INSP_DT, i.INSP_ID) AS OUT2
                       , LAG(CASE WHEN i.MEASURED NOT BETWEEN i.LSL AND i.USL THEN 1 ELSE 0 END, 2) OVER (PARTITION BY i.PART_NO, i.ITEM_CD ORDER BY i.INSP_DT, i.INSP_ID) AS OUT3
                    FROM INSPECTION i
                 ) x
           WHERE x.OUT1 + x.OUT2 + x.OUT3 = 3""",
          """
          SELECT
                 x.PART_NO
               , x.ITEM_CD
               , x.INSP_DT
               , x.MEASURED
            FROM (
                  SELECT
                         i.PART_NO
                       , i.ITEM_CD
                       , i.INSP_DT
                       , i.MEASURED
                       , CASE WHEN i.MEASURED NOT BETWEEN i.LSL AND i.USL THEN 1 ELSE 0 END AS OUT1
                       , LAG(CASE WHEN i.MEASURED NOT BETWEEN i.LSL AND i.USL THEN 1 ELSE 0 END, 1) OVER (PARTITION BY i.PART_NO, i.ITEM_CD ORDER BY i.INSP_DT, i.INSP_ID) AS OUT2
                       , LAG(CASE WHEN i.MEASURED NOT BETWEEN i.LSL AND i.USL THEN 1 ELSE 0 END, 2) OVER (PARTITION BY i.PART_NO, i.ITEM_CD ORDER BY i.INSP_DT, i.INSP_ID) AS OUT3
                    FROM INSPECTION i
                 ) x
           WHERE x.OUT1 + x.OUT2 + x.OUT3 = 3"""),
      new Example("생산 · 품질", "불량 유형 월별 가로표", "불량 유형을 줄로, 달을 칸으로. 최근 3달.",
          """
          SELECT
                 r.NG_TYPE
               , SUM(CASE WHEN r.WORK_DT >= ADD_MONTHS(TRUNC(SYSDATE, 'MM'), -2) AND r.WORK_DT < ADD_MONTHS(TRUNC(SYSDATE, 'MM'), -1) THEN r.NG_QTY ELSE 0 END) AS M_2
               , SUM(CASE WHEN r.WORK_DT >= ADD_MONTHS(TRUNC(SYSDATE, 'MM'), -1) AND r.WORK_DT < TRUNC(SYSDATE, 'MM') THEN r.NG_QTY ELSE 0 END) AS M_1
               , SUM(CASE WHEN r.WORK_DT >= TRUNC(SYSDATE, 'MM') THEN r.NG_QTY ELSE 0 END) AS THIS_M
            FROM WORK_RESULT r
           WHERE r.NG_QTY > 0
           GROUP BY
                 r.NG_TYPE""",
          """
          SELECT
                 r.NG_TYPE
               , SUM(CASE WHEN r.WORK_DT >= DATE_TRUNC('MONTH', CURRENT_DATE) - INTERVAL '2 MONTH' AND r.WORK_DT < DATE_TRUNC('MONTH', CURRENT_DATE) - INTERVAL '1 MONTH' THEN r.NG_QTY ELSE 0 END) AS M_2
               , SUM(CASE WHEN r.WORK_DT >= DATE_TRUNC('MONTH', CURRENT_DATE) - INTERVAL '1 MONTH' AND r.WORK_DT < DATE_TRUNC('MONTH', CURRENT_DATE) THEN r.NG_QTY ELSE 0 END) AS M_1
               , SUM(CASE WHEN r.WORK_DT >= DATE_TRUNC('MONTH', CURRENT_DATE) THEN r.NG_QTY ELSE 0 END) AS THIS_M
            FROM WORK_RESULT r
           WHERE r.NG_QTY > 0
           GROUP BY
                 r.NG_TYPE"""),
      new Example("생산 · 품질", "협력사별 검사 합격률", "검사 줄 중 규격 안 비율을 협력사마다.",
          """
          SELECT
                 i.VENDOR_ID
               , COUNT(*) AS INSP_CNT
               , SUM(CASE WHEN i.MEASURED BETWEEN i.LSL AND i.USL THEN 1 ELSE 0 END) AS PASS_CNT
               , ROUND(SUM(CASE WHEN i.MEASURED BETWEEN i.LSL AND i.USL THEN 1 ELSE 0 END) * 100 / COUNT(*), 1) AS PASS_PCT
            FROM INSPECTION i
           GROUP BY
                 i.VENDOR_ID
           ORDER BY
                 PASS_PCT""",
          """
          SELECT
                 i.VENDOR_ID
               , COUNT(*) AS INSP_CNT
               , SUM(CASE WHEN i.MEASURED BETWEEN i.LSL AND i.USL THEN 1 ELSE 0 END) AS PASS_CNT
               , ROUND(SUM(CASE WHEN i.MEASURED BETWEEN i.LSL AND i.USL THEN 1 ELSE 0 END) * 100 / COUNT(*), 1) AS PASS_PCT
            FROM INSPECTION i
           GROUP BY
                 i.VENDOR_ID
           ORDER BY
                 PASS_PCT"""),
      new Example("생산 · 품질", "CAPA 기한 지남 · 담당자별", "열린 시정 조치 중 기한이 지난 것을 담당자마다 세고 가장 오래 밀린 날수.",
          """
          SELECT
                 c.OWNER_ID
               , COUNT(*) AS OVERDUE_CNT
               , MAX(TRUNC(SYSDATE) - c.DUE_DT) AS MAX_DAYS
            FROM CAPA c
           WHERE c.CLOSE_DT IS NULL
             AND c.DUE_DT < TRUNC(SYSDATE)
           GROUP BY
                 c.OWNER_ID
           ORDER BY
                 OVERDUE_CNT DESC""",
          """
          SELECT
                 c.OWNER_ID
               , COUNT(*) AS OVERDUE_CNT
               , MAX(CURRENT_DATE - c.DUE_DT) AS MAX_DAYS
            FROM CAPA c
           WHERE c.CLOSE_DT IS NULL
             AND c.DUE_DT < CURRENT_DATE
           GROUP BY
                 c.OWNER_ID
           ORDER BY
                 OVERDUE_CNT DESC"""),
      new Example("생산 · 품질", "CAPA 처리 기간 (유형별 평균 · 최장)", "닫힌 시정 조치의 열림 ~ 닫힘 일수.",
          """
          SELECT
                 c.ISSUE_TYPE
               , COUNT(*) AS CNT
               , ROUND(AVG(c.CLOSE_DT - c.OPEN_DT), 1) AS AVG_DAYS
               , MAX(c.CLOSE_DT - c.OPEN_DT) AS MAX_DAYS
            FROM CAPA c
           WHERE c.CLOSE_DT IS NOT NULL
           GROUP BY
                 c.ISSUE_TYPE""",
          """
          SELECT
                 c.ISSUE_TYPE
               , COUNT(*) AS CNT
               , ROUND(AVG(c.CLOSE_DT - c.OPEN_DT), 1) AS AVG_DAYS
               , MAX(c.CLOSE_DT - c.OPEN_DT) AS MAX_DAYS
            FROM CAPA c
           WHERE c.CLOSE_DT IS NOT NULL
           GROUP BY
                 c.ISSUE_TYPE"""),
      new Example("인사 · 근태", "기간별 소속 (그날 어느 부서였나)", "부서 이동 이력에서 기준일에 걸리는 부서와 그때 부서 이름.",
          """
          SELECT
                 h.EMP_ID
               , h.DEPT_ID
               , d.DEPT_NAME
            FROM EMP_DEPT_HIST h, DEPT_HIST d
           WHERE h.DEPT_ID = d.DEPT_ID
             AND TO_DATE(:baseDt, 'YYYY-MM-DD') BETWEEN h.VALID_FROM AND h.VALID_TO
             AND TO_DATE(:baseDt, 'YYYY-MM-DD') BETWEEN d.VALID_FROM AND d.VALID_TO""",
          """
          SELECT
                 h.EMP_ID
               , h.DEPT_ID
               , d.DEPT_NAME
            FROM EMP_DEPT_HIST h, DEPT_HIST d
           WHERE h.DEPT_ID = d.DEPT_ID
             AND TO_DATE(:baseDt, 'YYYY-MM-DD') BETWEEN h.VALID_FROM AND h.VALID_TO
             AND TO_DATE(:baseDt, 'YYYY-MM-DD') BETWEEN d.VALID_FROM AND d.VALID_TO"""),
      new Example("인사 · 근태", "조직 개편 전후 부서 이름 비교", "두 기준일의 부서 이름을 나란히 놓고 바뀐 것만.",
          """
          SELECT
                 a.DEPT_ID
               , a.DEPT_NAME AS BEFORE_NAME
               , b.DEPT_NAME AS AFTER_NAME
            FROM DEPT_HIST a, DEPT_HIST b
           WHERE a.DEPT_ID = b.DEPT_ID
             AND TO_DATE(:beforeDt, 'YYYY-MM-DD') BETWEEN a.VALID_FROM AND a.VALID_TO
             AND TO_DATE(:afterDt, 'YYYY-MM-DD') BETWEEN b.VALID_FROM AND b.VALID_TO
             AND a.DEPT_NAME <> b.DEPT_NAME""",
          """
          SELECT
                 a.DEPT_ID
               , a.DEPT_NAME AS BEFORE_NAME
               , b.DEPT_NAME AS AFTER_NAME
            FROM DEPT_HIST a, DEPT_HIST b
           WHERE a.DEPT_ID = b.DEPT_ID
             AND TO_DATE(:beforeDt, 'YYYY-MM-DD') BETWEEN a.VALID_FROM AND a.VALID_TO
             AND TO_DATE(:afterDt, 'YYYY-MM-DD') BETWEEN b.VALID_FROM AND b.VALID_TO
             AND a.DEPT_NAME <> b.DEPT_NAME"""),
      new Example("인사 · 근태", "지각 · 연장 근무 집계 (월)", "9시 넘어 들어온 날 수, 18시 넘어 일한 시간 합.",
          """
          SELECT
                 a.EMP_ID
               , SUM(CASE WHEN TO_CHAR(a.IN_TS, 'HH24MI') > '0900' THEN 1 ELSE 0 END) AS LATE_DAYS
               , ROUND(SUM(GREATEST(a.OUT_TS - (TRUNC(a.OUT_TS) + 18 / 24), 0)) * 24, 1) AS OVERTIME_H
            FROM ATTEND a
           WHERE a.WORK_DT >= TO_DATE(:ym, 'YYYYMM')
             AND a.WORK_DT < ADD_MONTHS(TO_DATE(:ym, 'YYYYMM'), 1)
           GROUP BY
                 a.EMP_ID""",
          """
          SELECT
                 a.EMP_ID
               , SUM(CASE WHEN TO_CHAR(a.IN_TS, 'HH24MI') > '0900' THEN 1 ELSE 0 END) AS LATE_DAYS
               , ROUND(CAST(SUM(GREATEST(EXTRACT(EPOCH FROM a.OUT_TS - (DATE_TRUNC('DAY', a.OUT_TS) + INTERVAL '18 HOUR')), 0)) / 3600 AS NUMERIC), 1) AS OVERTIME_H
            FROM ATTEND a
           WHERE a.WORK_DT >= TO_DATE(:ym, 'YYYYMM')
             AND a.WORK_DT < TO_DATE(:ym, 'YYYYMM') + INTERVAL '1 MONTH'
           GROUP BY
                 a.EMP_ID"""),
      new Example("인사 · 근태", "휴가 잔여일", "올해 받은 날 − 쓴 날. 안 쓴 사람도 나오게 아우터.",
          """
          SELECT
                 g.EMP_ID
               , g.GRANTED_DAYS
               , NVL(SUM(u.DAYS), 0) AS USED
               , g.GRANTED_DAYS - NVL(SUM(u.DAYS), 0) AS REMAIN
            FROM LEAVE_GRANT g, LEAVE_USE u
           WHERE g.EMP_ID = u.EMP_ID(+)
             AND g.YEAR = :year
             AND TO_CHAR(u.USE_DT(+), 'YYYY') = :year
           GROUP BY
                 g.EMP_ID
               , g.GRANTED_DAYS""",
          """
          SELECT
                 g.EMP_ID
               , g.GRANTED_DAYS
               , COALESCE(SUM(u.DAYS), 0) AS USED
               , g.GRANTED_DAYS - COALESCE(SUM(u.DAYS), 0) AS REMAIN
            FROM LEAVE_GRANT g
            LEFT OUTER JOIN LEAVE_USE u
              ON g.EMP_ID = u.EMP_ID
             AND TO_CHAR(u.USE_DT, 'YYYY') = :year
           WHERE g.YEAR = :year
           GROUP BY
                 g.EMP_ID
               , g.GRANTED_DAYS"""),
      new Example("인사 · 근태", "부서별 인원 · 평균 급여 · 최고 급여자", "부서마다 인원과 평균, 그리고 가장 많이 받는 사람 이름을 한 줄에.",
          """
          SELECT
                 d.DEPT_NAME
               , COUNT(e.EMP_ID) AS EMP_CNT
               , ROUND(AVG(e.SAL)) AS AVG_SAL
               , MAX(e.EMP_NAME) KEEP (DENSE_RANK FIRST ORDER BY e.SAL DESC) AS TOP_EMP
            FROM DEPT d
               , EMP e
           WHERE d.DEPT_ID = e.DEPT_ID(+)
           GROUP BY
                 d.DEPT_NAME""",
          """
          SELECT
                 d.DEPT_NAME
               , COUNT(e.EMP_ID) AS EMP_CNT
               , ROUND(AVG(e.SAL)) AS AVG_SAL
               , (ARRAY_AGG(e.EMP_NAME ORDER BY e.SAL DESC NULLS LAST))[1] AS TOP_EMP
            FROM DEPT d
            LEFT OUTER JOIN EMP e
              ON d.DEPT_ID = e.DEPT_ID
           GROUP BY
                 d.DEPT_NAME"""),
      new Example("인사 · 근태", "내 상사 이름 붙이기 (자기 조인)", "직원 표를 두 번 써서 상사 이름을 붙입니다. 상사가 없는 맨 위 사람도 나오게 아우터로.",
          """
          SELECT
                 e.EMP_ID
               , e.EMP_NAME
               , m.EMP_NAME AS MGR_NAME
            FROM EMP e
               , EMP m
           WHERE e.MGR_ID = m.EMP_ID(+)""",
          """
          SELECT
                 e.EMP_ID
               , e.EMP_NAME
               , m.EMP_NAME AS MGR_NAME
            FROM EMP e
            LEFT OUTER JOIN EMP m
              ON e.MGR_ID = m.EMP_ID"""),
      new Example("인사 · 근태", "근무 시간 계산 (출근 ~ 퇴근, 시간 단위)", "하루 근무 시간과 8시간을 넘은 연장 시간. 퇴근 기록이 없으면 비웁니다.",
          """
          SELECT
                 a.EMP_ID
               , a.WORK_DT
               , ROUND((CAST(a.OUT_TS AS DATE) - CAST(a.IN_TS AS DATE)) * 24, 2) AS WORK_HOURS
               , GREATEST(ROUND((CAST(a.OUT_TS AS DATE) - CAST(a.IN_TS AS DATE)) * 24, 2) - 8, 0) AS OVER_HOURS
            FROM ATTEND a""",
          """
          SELECT
                 a.EMP_ID
               , a.WORK_DT
               , ROUND(CAST(EXTRACT(EPOCH FROM (a.OUT_TS - a.IN_TS)) / 3600 AS NUMERIC), 2) AS WORK_HOURS
               , GREATEST(ROUND(CAST(EXTRACT(EPOCH FROM (a.OUT_TS - a.IN_TS)) / 3600 AS NUMERIC), 2) - 8, 0) AS OVER_HOURS
            FROM ATTEND a"""),
      new Example("인사 · 근태", "출근 기록이 없는 날 (결근 후보)", "평일인데 출근 기록이 없는 직원 · 날짜. 이번 달 평일 × 직원에서 기록을 뺍니다.",
          """
          SELECT
                 e.EMP_ID
               , d.DT
            FROM EMP e
           CROSS JOIN (SELECT TRUNC(SYSDATE, 'MM') + LEVEL - 1 AS DT FROM DUAL CONNECT BY LEVEL <= TRUNC(SYSDATE) - TRUNC(SYSDATE, 'MM') + 1) d
           WHERE TO_CHAR(d.DT, 'DY', 'NLS_DATE_LANGUAGE=AMERICAN') NOT IN ('SAT', 'SUN')
             AND NOT EXISTS (
                  SELECT 1
                    FROM ATTEND a
                   WHERE a.EMP_ID = e.EMP_ID
                     AND a.WORK_DT = d.DT
                 )""",
          """
          SELECT
                 e.EMP_ID
               , d.DT
            FROM EMP e
           CROSS JOIN (SELECT CAST(g AS DATE) AS DT FROM GENERATE_SERIES(DATE_TRUNC('MONTH', CURRENT_DATE), CURRENT_DATE, INTERVAL '1 DAY') g) d
           WHERE EXTRACT(ISODOW FROM d.DT) < 6
             AND NOT EXISTS (
                  SELECT 1
                    FROM ATTEND a
                   WHERE a.EMP_ID = e.EMP_ID
                     AND a.WORK_DT = d.DT
                 )"""),
      new Example("인사 · 근태", "부서 이동 이력 (전 부서 → 새 부서)", "소속 이력에서 바로 앞 부서를 붙여 이동만 봅니다.",
          """
          SELECT
                 x.EMP_ID
               , x.PREV_DEPT
               , x.DEPT_ID
               , x.VALID_FROM
            FROM (
                  SELECT
                         h.EMP_ID
                       , h.DEPT_ID
                       , h.VALID_FROM
                       , LAG(h.DEPT_ID) OVER (PARTITION BY h.EMP_ID ORDER BY h.VALID_FROM) AS PREV_DEPT
                    FROM EMP_DEPT_HIST h
                 ) x
           WHERE x.PREV_DEPT IS NOT NULL
             AND x.PREV_DEPT <> x.DEPT_ID""",
          """
          SELECT
                 x.EMP_ID
               , x.PREV_DEPT
               , x.DEPT_ID
               , x.VALID_FROM
            FROM (
                  SELECT
                         h.EMP_ID
                       , h.DEPT_ID
                       , h.VALID_FROM
                       , LAG(h.DEPT_ID) OVER (PARTITION BY h.EMP_ID ORDER BY h.VALID_FROM) AS PREV_DEPT
                    FROM EMP_DEPT_HIST h
                 ) x
           WHERE x.PREV_DEPT IS NOT NULL
             AND x.PREV_DEPT <> x.DEPT_ID"""),
      new Example("인사 · 근태", "급여 등급 구간별 인원", "급여를 구간으로 나눠 몇 명씩인지. 구간은 CASE 로 정합니다.",
          """
          SELECT
                 CASE
                   WHEN e.SAL < 3000 THEN '1. 3천 미만'
                   WHEN e.SAL < 5000 THEN '2. 3천~5천'
                   WHEN e.SAL < 8000 THEN '3. 5천~8천'
                   ELSE '4. 8천 이상'
                 END AS SAL_BAND
               , COUNT(*) AS CNT
            FROM EMP e
           GROUP BY
                 CASE
                   WHEN e.SAL < 3000 THEN '1. 3천 미만'
                   WHEN e.SAL < 5000 THEN '2. 3천~5천'
                   WHEN e.SAL < 8000 THEN '3. 5천~8천'
                   ELSE '4. 8천 이상'
                 END
           ORDER BY
                 SAL_BAND""",
          """
          SELECT
                 CASE
                   WHEN e.SAL < 3000 THEN '1. 3천 미만'
                   WHEN e.SAL < 5000 THEN '2. 3천~5천'
                   WHEN e.SAL < 8000 THEN '3. 5천~8천'
                   ELSE '4. 8천 이상'
                 END AS SAL_BAND
               , COUNT(*) AS CNT
            FROM EMP e
           GROUP BY
                 CASE
                   WHEN e.SAL < 3000 THEN '1. 3천 미만'
                   WHEN e.SAL < 5000 THEN '2. 3천~5천'
                   WHEN e.SAL < 8000 THEN '3. 5천~8천'
                   ELSE '4. 8천 이상'
                 END
           ORDER BY
                 SAL_BAND"""),
      new Example("인사 · 근태", "관리자가 없는 직원 · 없는 관리자를 가리키는 직원", "상사 번호가 비었거나 직원 표에 없는 사람을 가리키는 줄(조직도 점검).",
          """
          SELECT
                 e.EMP_ID
               , e.EMP_NAME
               , e.MGR_ID
               , CASE WHEN e.MGR_ID IS NULL THEN '상사 없음' ELSE '없는 상사' END AS PROBLEM
            FROM EMP e
           WHERE e.MGR_ID IS NULL
              OR NOT EXISTS (SELECT 1 FROM EMP m WHERE m.EMP_ID = e.MGR_ID)""",
          """
          SELECT
                 e.EMP_ID
               , e.EMP_NAME
               , e.MGR_ID
               , CASE WHEN e.MGR_ID IS NULL THEN '상사 없음' ELSE '없는 상사' END AS PROBLEM
            FROM EMP e
           WHERE e.MGR_ID IS NULL
              OR NOT EXISTS (SELECT 1 FROM EMP m WHERE m.EMP_ID = e.MGR_ID)"""),
      new Example("인사 · 근태", "직속 부하 수 · 전체 부하 수", "관리자마다 바로 아래 인원과 아래로 끝까지 센 인원.",
          """
          SELECT
                 m.EMP_ID
               , m.EMP_NAME
               , (SELECT COUNT(*) FROM EMP e WHERE e.MGR_ID = m.EMP_ID) AS DIRECT_CNT
               , (SELECT COUNT(*) - 1 FROM EMP e START WITH e.EMP_ID = m.EMP_ID CONNECT BY PRIOR e.EMP_ID = e.MGR_ID) AS ALL_CNT
            FROM EMP m
           WHERE EXISTS (SELECT 1 FROM EMP e WHERE e.MGR_ID = m.EMP_ID)""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 e.EMP_ID AS TOP_ID
               , e.EMP_ID
               , 0 AS LVL
            FROM EMP e
           UNION ALL
          SELECT
                 t.TOP_ID
               , e.EMP_ID
               , t.LVL + 1
            FROM EMP e
               , t
           WHERE e.MGR_ID = t.EMP_ID
             AND t.LVL < 20
          )
          SELECT
                 m.EMP_ID
               , m.EMP_NAME
               , (SELECT COUNT(*) FROM EMP e WHERE e.MGR_ID = m.EMP_ID) AS DIRECT_CNT
               , (SELECT COUNT(*) FROM t WHERE t.TOP_ID = m.EMP_ID AND t.LVL > 0) AS ALL_CNT
            FROM EMP m
           WHERE EXISTS (SELECT 1 FROM EMP e WHERE e.MGR_ID = m.EMP_ID)"""),
      new Example("인사 · 근태", "연차 사용률 (부여 대비 사용)", "올해 부여 일수와 사용 일수, 사용률.",
          """
          SELECT
                 g.EMP_ID
               , g.GRANTED_DAYS
               , NVL(SUM(u.DAYS), 0) AS USED_DAYS
               , ROUND(NVL(SUM(u.DAYS), 0) * 100 / NULLIF(g.GRANTED_DAYS, 0), 1) AS USED_PCT
            FROM LEAVE_GRANT g
               , LEAVE_USE u
           WHERE g.EMP_ID = u.EMP_ID(+)
             AND g.YEAR = TO_CHAR(SYSDATE, 'YYYY')
             AND TO_CHAR(u.USE_DT(+), 'YYYY') = g.YEAR
           GROUP BY
                 g.EMP_ID
               , g.GRANTED_DAYS""",
          """
          SELECT
                 g.EMP_ID
               , g.GRANTED_DAYS
               , COALESCE(SUM(u.DAYS), 0) AS USED_DAYS
               , ROUND(COALESCE(SUM(u.DAYS), 0) * 100 / NULLIF(g.GRANTED_DAYS, 0), 1) AS USED_PCT
            FROM LEAVE_GRANT g
            LEFT OUTER JOIN LEAVE_USE u
              ON g.EMP_ID = u.EMP_ID
             AND TO_CHAR(u.USE_DT, 'YYYY') = g.YEAR
           WHERE g.YEAR = TO_CHAR(CURRENT_DATE, 'YYYY')
           GROUP BY
                 g.EMP_ID
               , g.GRANTED_DAYS"""),
      new Example("인사 · 근태", "조직도 위에서 아래로 (CONNECT BY 기본)", "대표부터 아래로 펼칩니다. START WITH 는 시작할 줄, CONNECT BY PRIOR 자식칼럼 쪽이 아래로 내려가는 방향입니다.",
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
      new Example("인사 · 근태", "아래에서 위로 (내 상사 줄줄이)", "한 사람에서 시작해 대표까지 올라갑니다. PRIOR 를 부모 칼럼 쪽에 붙이면 위로 갑니다.",
          """
          SELECT
                 LEVEL AS UP
               , e.EMP_ID
               , e.EMP_NAME
            FROM EMP e
           START WITH e.EMP_ID = :empId
          CONNECT BY e.EMP_ID = PRIOR e.MGR_ID""",
          """
          WITH RECURSIVE UP AS (
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
            JOIN UP u
              ON e.EMP_ID = u.MGR_ID
           WHERE u.UP < 100
          )
          SELECT
                 u.UP
               , u.EMP_ID
               , u.EMP_NAME
            FROM UP u"""),
      new Example("인사 · 근태", "부서 아래 직원 모두 (하위 부서 포함)", "고른 부서와 그 아래 모든 부서에 속한 직원.",
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
          WITH RECURSIVE SUB AS (
          SELECT
                 d.DEPT_ID
            FROM DEPT d
           WHERE d.DEPT_ID = CAST(:deptId AS INTEGER)
           UNION
          SELECT
                 d.DEPT_ID
            FROM DEPT d
            JOIN SUB s
              ON d.PARENT_DEPT_ID = s.DEPT_ID
          )
          SELECT
                 e.*
            FROM EMP e
           WHERE e.DEPT_ID IN (SELECT s.DEPT_ID FROM SUB s)"""),
      new Example("인사 · 근태", "부서별 인원 · 급여 합 (하위 부서까지 합침)", "부서마다 그 아래 부서까지 합친 인원과 급여 합.",
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
          WITH RECURSIVE PAIR AS (
          SELECT
                 d.DEPT_ID AS ROOT_ID
               , d.DEPT_ID
            FROM DEPT d
           UNION
          SELECT
                 p.ROOT_ID
               , d.DEPT_ID
            FROM DEPT d
            JOIN PAIR p
              ON d.PARENT_DEPT_ID = p.DEPT_ID
          )
          SELECT
                 d.DEPT_ID
               , d.DEPT_NAME
               , COUNT(e.EMP_ID) AS EMP_CNT
               , SUM(e.SAL) AS SAL_SUM
            FROM DEPT d
            JOIN PAIR p
              ON p.ROOT_ID = d.DEPT_ID
            LEFT JOIN EMP e
              ON e.DEPT_ID = p.DEPT_ID
           GROUP BY
                 d.DEPT_ID
               , d.DEPT_NAME"""),
      new Example("설계 변경 · 개발 프로젝트", "설계 변경(ECO) 영향 부품과 그 부품을 쓰는 상위 부품", "ECO 에 걸린 부품마다 바로 위 모부품까지 붙여 영향 범위를 봅니다.",
          """
          SELECT
                 e.ECO_NO
               , e.TITLE
               , ep.PART_NO
               , ep.CHG_TYPE
               , ep.OLD_REV || ' → ' || ep.NEW_REV AS REV_CHANGE
               , b.PARENT_PART AS USED_IN
            FROM ECO e, ECO_PART ep, BOM b
           WHERE e.ECO_NO = ep.ECO_NO
             AND ep.PART_NO = b.CHILD_PART(+)
             AND e.ECO_NO = :ecoNo
           ORDER BY
                 ep.PART_NO""",
          """
          SELECT
                 e.ECO_NO
               , e.TITLE
               , ep.PART_NO
               , ep.CHG_TYPE
               , ep.OLD_REV || ' → ' || ep.NEW_REV AS REV_CHANGE
               , b.PARENT_PART AS USED_IN
            FROM ECO e
               , ECO_PART ep
            LEFT OUTER JOIN BOM b
              ON ep.PART_NO = b.CHILD_PART
           WHERE e.ECO_NO = ep.ECO_NO
             AND e.ECO_NO = :ecoNo
           ORDER BY
                 ep.PART_NO"""),
      new Example("설계 변경 · 개발 프로젝트", "ECO 영향 도면 · 문서", "변경 부품에 걸린 도면과 승인된 문서. 같이 고쳐야 할 것 목록입니다.",
          """
          SELECT
                 ep.ECO_NO
               , ep.PART_NO
               , d.DRAWING_NO
               , d.REV AS DRAWING_REV
               , c.DOC_NO
               , c.REV AS DOC_REV
            FROM ECO_PART ep, DRAWING d, DOC c
           WHERE ep.PART_NO = d.PART_NO(+)
             AND ep.PART_NO = c.PART_NO(+)
             AND c.STATUS(+) = 'REL'
             AND ep.ECO_NO = :ecoNo""",
          """
          SELECT
                 ep.ECO_NO
               , ep.PART_NO
               , d.DRAWING_NO
               , d.REV AS DRAWING_REV
               , c.DOC_NO
               , c.REV AS DOC_REV
            FROM ECO_PART ep
            LEFT OUTER JOIN DRAWING d
              ON ep.PART_NO = d.PART_NO
            LEFT OUTER JOIN DOC c
              ON ep.PART_NO = c.PART_NO
             AND c.STATUS = 'REL'
           WHERE ep.ECO_NO = :ecoNo"""),
      new Example("설계 변경 · 개발 프로젝트", "ECO 진행 현황 (상태별 건수 · 평균 처리일)", "상태마다 건수와 요청에서 적용까지 걸린 평균 날 수.",
          """
          SELECT
                 e.STATUS
               , COUNT(*) AS CNT
               , ROUND(AVG(e.APPLY_DT - e.REQ_DT), 1) AS AVG_DAYS
            FROM ECO e
           GROUP BY
                 e.STATUS""",
          """
          SELECT
                 e.STATUS
               , COUNT(*) AS CNT
               , ROUND(AVG(e.APPLY_DT - e.REQ_DT), 1) AS AVG_DAYS
            FROM ECO e
           GROUP BY
                 e.STATUS"""),
      new Example("설계 변경 · 개발 프로젝트", "APQP 단계별 산출물 제출 · 미제출", "프로젝트 · 단계마다 산출물 몇 개 중 몇 개를 냈고 몇 개가 승인됐는지.",
          """
          SELECT
                 d.PROJ_ID
               , s.STEP_NAME
               , COUNT(*) AS TOTAL
               , COUNT(d.SUBMIT_DT) AS SUBMITTED
               , SUM(CASE WHEN d.APPROVED_YN = 'Y' THEN 1 ELSE 0 END) AS APPROVED
               , COUNT(*) - COUNT(d.SUBMIT_DT) AS MISSING
            FROM APQP_DELIV d, APQP_STEP s
           WHERE d.PROJ_ID = s.PROJ_ID
             AND d.STEP_SEQ = s.STEP_SEQ
           GROUP BY
                 d.PROJ_ID
               , s.STEP_SEQ
               , s.STEP_NAME
           ORDER BY
                 d.PROJ_ID
               , s.STEP_SEQ""",
          """
          SELECT
                 d.PROJ_ID
               , s.STEP_NAME
               , COUNT(*) AS TOTAL
               , COUNT(d.SUBMIT_DT) AS SUBMITTED
               , SUM(CASE WHEN d.APPROVED_YN = 'Y' THEN 1 ELSE 0 END) AS APPROVED
               , COUNT(*) - COUNT(d.SUBMIT_DT) AS MISSING
            FROM APQP_DELIV d, APQP_STEP s
           WHERE d.PROJ_ID = s.PROJ_ID
             AND d.STEP_SEQ = s.STEP_SEQ
           GROUP BY
                 d.PROJ_ID
               , s.STEP_SEQ
               , s.STEP_NAME
           ORDER BY
                 d.PROJ_ID
               , s.STEP_SEQ"""),
      new Example("설계 변경 · 개발 프로젝트", "APQP 게이트 통과 여부", "게이트 단계에서 산출물이 모두 승인돼야 통과. 하나라도 아니면 미통과와 남은 개수.",
          """
          SELECT
                 s.PROJ_ID
               , s.STEP_SEQ
               , s.STEP_NAME
               , CASE
                   WHEN NOT EXISTS (SELECT 1 FROM APQP_DELIV d WHERE d.PROJ_ID = s.PROJ_ID AND d.STEP_SEQ = s.STEP_SEQ AND NVL(d.APPROVED_YN, 'N') <> 'Y')
                   THEN '통과'
                   ELSE '미통과'
                 END AS GATE
               , (SELECT COUNT(*) FROM APQP_DELIV d WHERE d.PROJ_ID = s.PROJ_ID AND d.STEP_SEQ = s.STEP_SEQ AND NVL(d.APPROVED_YN, 'N') <> 'Y') AS LEFT_CNT
            FROM APQP_STEP s
           WHERE s.GATE_YN = 'Y'
           ORDER BY
                 s.PROJ_ID
               , s.STEP_SEQ""",
          """
          SELECT
                 s.PROJ_ID
               , s.STEP_SEQ
               , s.STEP_NAME
               , CASE
                   WHEN NOT EXISTS (SELECT 1 FROM APQP_DELIV d WHERE d.PROJ_ID = s.PROJ_ID AND d.STEP_SEQ = s.STEP_SEQ AND COALESCE(d.APPROVED_YN, 'N') <> 'Y')
                   THEN '통과'
                   ELSE '미통과'
                 END AS GATE
               , (SELECT COUNT(*) FROM APQP_DELIV d WHERE d.PROJ_ID = s.PROJ_ID AND d.STEP_SEQ = s.STEP_SEQ AND COALESCE(d.APPROVED_YN, 'N') <> 'Y') AS LEFT_CNT
            FROM APQP_STEP s
           WHERE s.GATE_YN = 'Y'
           ORDER BY
                 s.PROJ_ID
               , s.STEP_SEQ"""),
      new Example("설계 변경 · 개발 프로젝트", "프로젝트 이슈 미결 건 · 담당자 · 경과일", "열린 이슈를 오래된 순으로. 등급(A 가 높음)도 같이.",
          """
          SELECT
                 i.PROJ_ID
               , i.ISSUE_ID
               , i.TITLE
               , i.SEVERITY
               , u.USER_NAME
               , i.REG_DT
               , TRUNC(SYSDATE) - i.REG_DT AS OPEN_DAYS
            FROM ISSUE i, USERS u
           WHERE i.OWNER_ID = u.USER_ID(+)
             AND i.STATUS = 'OPEN'
           ORDER BY
                 i.SEVERITY
               , OPEN_DAYS DESC""",
          """
          SELECT
                 i.PROJ_ID
               , i.ISSUE_ID
               , i.TITLE
               , i.SEVERITY
               , u.USER_NAME
               , i.REG_DT
               , CURRENT_DATE - i.REG_DT AS OPEN_DAYS
            FROM ISSUE i
            LEFT OUTER JOIN USERS u
              ON i.OWNER_ID = u.USER_ID
           WHERE i.STATUS = 'OPEN'
           ORDER BY
                 i.SEVERITY
               , OPEN_DAYS DESC"""),
      new Example("설계 변경 · 개발 프로젝트", "담당자별 할 일 (이슈 · 일정 · 결재 합쳐 세기)", "한 사람에게 걸린 열린 이슈, 안 끝난 일정, 결재 대기를 한 줄로.",
          """
          SELECT
                 u.USER_ID
               , u.USER_NAME
               , (SELECT COUNT(*) FROM ISSUE i WHERE i.OWNER_ID = u.USER_ID AND i.STATUS = 'OPEN') AS OPEN_ISSUES
               , (SELECT COUNT(*) FROM SCHEDULE s WHERE s.OWNER_ID = u.USER_ID AND s.ACT_END IS NULL) AS OPEN_STEPS
               , (SELECT COUNT(*) FROM APPROVAL a WHERE a.APPROVER_ID = u.USER_ID AND a.STATUS = 'WAIT') AS WAIT_APPROVALS
            FROM USERS u
           WHERE u.USE_YN = 'Y'
           ORDER BY
                 3 DESC""",
          """
          SELECT
                 u.USER_ID
               , u.USER_NAME
               , (SELECT COUNT(*) FROM ISSUE i WHERE i.OWNER_ID = u.USER_ID AND i.STATUS = 'OPEN') AS OPEN_ISSUES
               , (SELECT COUNT(*) FROM SCHEDULE s WHERE s.OWNER_ID = u.USER_ID AND s.ACT_END IS NULL) AS OPEN_STEPS
               , (SELECT COUNT(*) FROM APPROVAL a WHERE a.APPROVER_ID = u.USER_ID AND a.STATUS = 'WAIT') AS WAIT_APPROVALS
            FROM USERS u
           WHERE u.USE_YN = 'Y'
           ORDER BY
                 3 DESC"""),
      new Example("문서 · 도면", "문서 최신 판만 · 승인 안 된 판", "문서 번호마다 가장 높은 판, 그 판이 승인(REL)인지.",
          """
          SELECT
                 d.DOC_NO
               , d.REV
               , d.STATUS
               , CASE WHEN d.STATUS <> 'REL' THEN '승인 전' END AS NOTE
            FROM DOC d
           WHERE d.REV = (SELECT MAX(x.REV) FROM DOC x WHERE x.DOC_NO = d.DOC_NO)""",
          """
          SELECT
                 d.DOC_NO
               , d.REV
               , d.STATUS
               , CASE WHEN d.STATUS <> 'REL' THEN '승인 전' END AS NOTE
            FROM DOC d
           WHERE d.REV = (SELECT MAX(x.REV) FROM DOC x WHERE x.DOC_NO = d.DOC_NO)"""),
      new Example("문서 · 도면", "배포처별 수신 확인 안 한 곳", "보낸 지 오래됐는데 받았다고 안 한 부서.",
          """
          SELECT
                 d.DOC_NO
               , d.REV
               , x.DEPT_ID
               , x.SENT_DT
               , TRUNC(SYSDATE) - x.SENT_DT AS WAIT_DAYS
            FROM DOC_DIST x, DOC d
           WHERE x.DOC_ID = d.DOC_ID
             AND x.RECV_DT IS NULL
           ORDER BY
                 x.SENT_DT""",
          """
          SELECT
                 d.DOC_NO
               , d.REV
               , x.DEPT_ID
               , x.SENT_DT
               , CURRENT_DATE - x.SENT_DT AS WAIT_DAYS
            FROM DOC_DIST x, DOC d
           WHERE x.DOC_ID = d.DOC_ID
             AND x.RECV_DT IS NULL
           ORDER BY
                 x.SENT_DT"""),
      new Example("문서 · 도면", "부품 ↔ 도면 ↔ 문서 연결 빠진 것", "도면이 없는 부품, 문서가 없는 부품을 한 번에.",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
               , CASE WHEN NOT EXISTS (SELECT 1 FROM DRAWING d WHERE d.PART_NO = p.PART_NO) THEN '도면 없음' END AS DRAWING
               , CASE WHEN NOT EXISTS (SELECT 1 FROM DOC c WHERE c.PART_NO = p.PART_NO) THEN '문서 없음' END AS DOC
            FROM PART p
           WHERE NOT EXISTS (SELECT 1 FROM DRAWING d WHERE d.PART_NO = p.PART_NO)
              OR NOT EXISTS (SELECT 1 FROM DOC c WHERE c.PART_NO = p.PART_NO)""",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
               , CASE WHEN NOT EXISTS (SELECT 1 FROM DRAWING d WHERE d.PART_NO = p.PART_NO) THEN '도면 없음' END AS DRAWING
               , CASE WHEN NOT EXISTS (SELECT 1 FROM DOC c WHERE c.PART_NO = p.PART_NO) THEN '문서 없음' END AS DOC
            FROM PART p
           WHERE NOT EXISTS (SELECT 1 FROM DRAWING d WHERE d.PART_NO = p.PART_NO)
              OR NOT EXISTS (SELECT 1 FROM DOC c WHERE c.PART_NO = p.PART_NO)"""),
      new Example("문서 · 도면", "도면 판과 부품 최신 리비전이 다른 것", "부품은 B 판인데 도면은 아직 A 판인 것처럼 어긋난 곳.",
          """
          SELECT
                 d.PART_NO
               , d.DRAWING_NO
               , d.REV AS DRAWING_REV
               , r.REV AS PART_REV
            FROM DRAWING d
               , (SELECT x.PART_NO, MAX(x.REV) AS REV FROM PART_REV x WHERE x.STATUS = 'REL' GROUP BY x.PART_NO) r
           WHERE d.PART_NO = r.PART_NO
             AND d.REV <> r.REV""",
          """
          SELECT
                 d.PART_NO
               , d.DRAWING_NO
               , d.REV AS DRAWING_REV
               , r.REV AS PART_REV
            FROM DRAWING d
               , (SELECT x.PART_NO, MAX(x.REV) AS REV FROM PART_REV x WHERE x.STATUS = 'REL' GROUP BY x.PART_NO) r
           WHERE d.PART_NO = r.PART_NO
             AND d.REV <> r.REV"""),
      new Example("문서 · 도면", "부품마다 문서 수 · 최신 등록일", "부품별로 연결된 문서 수와 가장 최근 문서 날짜. 문서가 없는 부품도 0 으로.",
          """
          SELECT
                 p.PART_NO
               , COUNT(d.DOC_ID) AS DOC_CNT
               , MAX(d.REG_DT) AS LAST_DOC_DT
            FROM PART p
               , DOC d
           WHERE p.PART_NO = d.PART_NO(+)
           GROUP BY
                 p.PART_NO""",
          """
          SELECT
                 p.PART_NO
               , COUNT(d.DOC_ID) AS DOC_CNT
               , MAX(d.REG_DT) AS LAST_DOC_DT
            FROM PART p
            LEFT OUTER JOIN DOC d
              ON p.PART_NO = d.PART_NO
           GROUP BY
                 p.PART_NO"""),
      new Example("문서 · 도면", "배포 뒤 수신까지 걸린 날 (부서별 평균)", "문서를 보낸 날부터 받은 날까지 며칠 걸렸는지 부서별로.",
          """
          SELECT
                 x.DEPT_ID
               , COUNT(*) AS SENT_CNT
               , ROUND(AVG(x.RECV_DT - x.SENT_DT), 1) AS AVG_DAYS
               , SUM(CASE WHEN x.RECV_DT IS NULL THEN 1 ELSE 0 END) AS NOT_RECV
            FROM DOC_DIST x
           GROUP BY
                 x.DEPT_ID""",
          """
          SELECT
                 x.DEPT_ID
               , COUNT(*) AS SENT_CNT
               , ROUND(AVG(x.RECV_DT - x.SENT_DT), 1) AS AVG_DAYS
               , SUM(CASE WHEN x.RECV_DT IS NULL THEN 1 ELSE 0 END) AS NOT_RECV
            FROM DOC_DIST x
           GROUP BY
                 x.DEPT_ID"""),
      new Example("문서 · 도면", "한 문서의 판 이력 (판마다 한 줄)", "문서 번호 하나의 모든 판을 최신부터. 앞 판과 상태가 어떻게 바뀌었는지.",
          """
          SELECT
                 d.DOC_NO
               , d.REV
               , d.STATUS
               , d.REG_DT
               , LEAD(d.REV) OVER (PARTITION BY d.DOC_NO ORDER BY d.REG_DT DESC) AS PREV_REV
            FROM DOC d
           WHERE d.DOC_NO = :docNo
           ORDER BY
                 d.REG_DT DESC""",
          """
          SELECT
                 d.DOC_NO
               , d.REV
               , d.STATUS
               , d.REG_DT
               , LEAD(d.REV) OVER (PARTITION BY d.DOC_NO ORDER BY d.REG_DT DESC) AS PREV_REV
            FROM DOC d
           WHERE d.DOC_NO = :docNo
           ORDER BY
                 d.REG_DT DESC"""),
      new Example("구매 · 협력사", "협력사별 납기 준수율", "입고일이 납기일 안인 입고 건 비율.",
          """
          SELECT
                 v.VENDOR_NAME
               , COUNT(*) AS RCV_CNT
               , SUM(CASE WHEN r.RCV_DT <= po.DUE_DT THEN 1 ELSE 0 END) AS ON_TIME
               , ROUND(SUM(CASE WHEN r.RCV_DT <= po.DUE_DT THEN 1 ELSE 0 END) * 100 / COUNT(*), 1) AS ON_TIME_PCT
            FROM RECEIPT r, PURCHASE_ORDER po, VENDOR v
           WHERE r.PO_NO = po.PO_NO
             AND r.LINE_NO = po.LINE_NO
             AND po.VENDOR_ID = v.VENDOR_ID
           GROUP BY
                 v.VENDOR_NAME
           ORDER BY
                 ON_TIME_PCT DESC""",
          """
          SELECT
                 v.VENDOR_NAME
               , COUNT(*) AS RCV_CNT
               , SUM(CASE WHEN r.RCV_DT <= po.DUE_DT THEN 1 ELSE 0 END) AS ON_TIME
               , ROUND(SUM(CASE WHEN r.RCV_DT <= po.DUE_DT THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 1) AS ON_TIME_PCT
            FROM RECEIPT r, PURCHASE_ORDER po, VENDOR v
           WHERE r.PO_NO = po.PO_NO
             AND r.LINE_NO = po.LINE_NO
             AND po.VENDOR_ID = v.VENDOR_ID
           GROUP BY
                 v.VENDOR_NAME
           ORDER BY
                 ON_TIME_PCT DESC"""),
      new Example("구매 · 협력사", "협력사 품질 점수 순위 (입고 불량률)", "입고 수량 대비 불량 수량으로 순위.",
          """
          SELECT
                 v.VENDOR_NAME
               , SUM(r.QTY) AS RCV_QTY
               , SUM(r.NG_QTY) AS NG_QTY
               , ROUND(SUM(r.NG_QTY) * 1000000 / NULLIF(SUM(r.QTY), 0)) AS PPM
               , RANK() OVER (ORDER BY SUM(r.NG_QTY) / NULLIF(SUM(r.QTY), 0)) AS RNK
            FROM RECEIPT r, PURCHASE_ORDER po, VENDOR v
           WHERE r.PO_NO = po.PO_NO
             AND r.LINE_NO = po.LINE_NO
             AND po.VENDOR_ID = v.VENDOR_ID
           GROUP BY
                 v.VENDOR_NAME
           ORDER BY
                 RNK""",
          """
          SELECT
                 v.VENDOR_NAME
               , SUM(r.QTY) AS RCV_QTY
               , SUM(r.NG_QTY) AS NG_QTY
               , ROUND(SUM(r.NG_QTY) * 1000000 / NULLIF(SUM(r.QTY), 0)) AS PPM
               , RANK() OVER (ORDER BY SUM(r.NG_QTY) / NULLIF(SUM(r.QTY), 0)) AS RNK
            FROM RECEIPT r, PURCHASE_ORDER po, VENDOR v
           WHERE r.PO_NO = po.PO_NO
             AND r.LINE_NO = po.LINE_NO
             AND po.VENDOR_ID = v.VENDOR_ID
           GROUP BY
                 v.VENDOR_NAME
           ORDER BY
                 RNK"""),
      new Example("구매 · 협력사", "협력사가 하나뿐인 부품", "공급이 끊기면 대신할 곳이 없는 부품.",
          """
          SELECT
                 pv.PART_NO
               , MAX(pv.VENDOR_ID) AS ONLY_VENDOR
            FROM PART_VENDOR pv
           GROUP BY
                 pv.PART_NO
          HAVING COUNT(*) = 1""",
          """
          SELECT
                 pv.PART_NO
               , MAX(pv.VENDOR_ID) AS ONLY_VENDOR
            FROM PART_VENDOR pv
           GROUP BY
                 pv.PART_NO
          HAVING COUNT(*) = 1"""),
      new Example("구매 · 협력사", "부품별 주 · 부 협력사 비율", "부품 한 줄에 주 협력사와 나머지 비율.",
          """
          SELECT
                 pv.PART_NO
               , MAX(CASE WHEN pv.MAIN_YN = 'Y' THEN v.VENDOR_NAME END) AS MAIN_VENDOR
               , MAX(CASE WHEN pv.MAIN_YN = 'Y' THEN pv.SHARE_PCT END) AS MAIN_PCT
               , SUM(CASE WHEN pv.MAIN_YN = 'N' THEN pv.SHARE_PCT ELSE 0 END) AS SUB_PCT
               , SUM(pv.SHARE_PCT) AS TOTAL_PCT
            FROM PART_VENDOR pv, VENDOR v
           WHERE pv.VENDOR_ID = v.VENDOR_ID
           GROUP BY
                 pv.PART_NO""",
          """
          SELECT
                 pv.PART_NO
               , MAX(CASE WHEN pv.MAIN_YN = 'Y' THEN v.VENDOR_NAME END) AS MAIN_VENDOR
               , MAX(CASE WHEN pv.MAIN_YN = 'Y' THEN pv.SHARE_PCT END) AS MAIN_PCT
               , SUM(CASE WHEN pv.MAIN_YN = 'N' THEN pv.SHARE_PCT ELSE 0 END) AS SUB_PCT
               , SUM(pv.SHARE_PCT) AS TOTAL_PCT
            FROM PART_VENDOR pv, VENDOR v
           WHERE pv.VENDOR_ID = v.VENDOR_ID
           GROUP BY
                 pv.PART_NO"""),
      new Example("구매 · 협력사", "견적 비교 (부품별 최저가 협력사)", "같은 부품 견적 중 가장 싼 곳과 두 번째와의 차이.",
          """
          SELECT
                 x.PART_NO
               , x.VENDOR_NAME
               , x.PRICE
               , x.NEXT_PRICE - x.PRICE AS GAP_TO_2ND
            FROM (
                  SELECT
                         q.PART_NO
                       , v.VENDOR_NAME
                       , q.PRICE
                       , ROW_NUMBER() OVER (PARTITION BY q.PART_NO ORDER BY q.PRICE) AS RN
                       , LEAD(q.PRICE) OVER (PARTITION BY q.PART_NO ORDER BY q.PRICE) AS NEXT_PRICE
                    FROM QUOTE q, VENDOR v
                   WHERE q.VENDOR_ID = v.VENDOR_ID
                 ) x
           WHERE x.RN = 1""",
          """
          SELECT
                 x.PART_NO
               , x.VENDOR_NAME
               , x.PRICE
               , x.NEXT_PRICE - x.PRICE AS GAP_TO_2ND
            FROM (
                  SELECT
                         q.PART_NO
                       , v.VENDOR_NAME
                       , q.PRICE
                       , ROW_NUMBER() OVER (PARTITION BY q.PART_NO ORDER BY q.PRICE) AS RN
                       , LEAD(q.PRICE) OVER (PARTITION BY q.PART_NO ORDER BY q.PRICE) AS NEXT_PRICE
                    FROM QUOTE q, VENDOR v
                   WHERE q.VENDOR_ID = v.VENDOR_ID
                 ) x
           WHERE x.RN = 1"""),
      new Example("구매 · 협력사", "부품별 견적 가로표 (협력사가 칸)", "부품 한 줄에 협력사별 견적가를 나란히.",
          """
          SELECT
                 q.PART_NO
               , MAX(CASE WHEN q.VENDOR_ID = 1 THEN q.PRICE END) AS V1
               , MAX(CASE WHEN q.VENDOR_ID = 2 THEN q.PRICE END) AS V2
               , MAX(CASE WHEN q.VENDOR_ID = 3 THEN q.PRICE END) AS V3
               , MIN(q.PRICE) AS BEST
            FROM QUOTE q
           GROUP BY
                 q.PART_NO""",
          """
          SELECT
                 q.PART_NO
               , MAX(CASE WHEN q.VENDOR_ID = 1 THEN q.PRICE END) AS V1
               , MAX(CASE WHEN q.VENDOR_ID = 2 THEN q.PRICE END) AS V2
               , MAX(CASE WHEN q.VENDOR_ID = 3 THEN q.PRICE END) AS V3
               , MIN(q.PRICE) AS BEST
            FROM QUOTE q
           GROUP BY
                 q.PART_NO"""),
      new Example("구매 · 협력사", "협력사별 입고 리드타임 (발주 ~ 입고 평균 일수)", "발주일부터 첫 입고일까지 걸린 날.",
          """
          SELECT
                 p.VENDOR_ID
               , COUNT(*) AS PO_LINES
               , ROUND(AVG(r.FIRST_RCV - p.PO_DT), 1) AS AVG_DAYS
               , MAX(r.FIRST_RCV - p.PO_DT) AS MAX_DAYS
            FROM PURCHASE_ORDER p
               , (SELECT x.PO_NO, x.LINE_NO, MIN(x.RCV_DT) AS FIRST_RCV FROM RECEIPT x GROUP BY x.PO_NO, x.LINE_NO) r
           WHERE p.PO_NO = r.PO_NO
             AND p.LINE_NO = r.LINE_NO
           GROUP BY
                 p.VENDOR_ID""",
          """
          SELECT
                 p.VENDOR_ID
               , COUNT(*) AS PO_LINES
               , ROUND(AVG(r.FIRST_RCV - p.PO_DT), 1) AS AVG_DAYS
               , MAX(r.FIRST_RCV - p.PO_DT) AS MAX_DAYS
            FROM PURCHASE_ORDER p
               , (SELECT x.PO_NO, x.LINE_NO, MIN(x.RCV_DT) AS FIRST_RCV FROM RECEIPT x GROUP BY x.PO_NO, x.LINE_NO) r
           WHERE p.PO_NO = r.PO_NO
             AND p.LINE_NO = r.LINE_NO
           GROUP BY
                 p.VENDOR_ID"""),
      new Example("구매 · 협력사", "납기 지연 일수 분포 (0 · 1~3 · 4~7 · 8일 넘음)", "입고가 납기보다 며칠 늦었는지 구간으로.",
          """
          SELECT
                 CASE
                   WHEN r.RCV_DT <= p.DUE_DT THEN '0. 납기 안'
                   WHEN r.RCV_DT - p.DUE_DT <= 3 THEN '1. 1~3일'
                   WHEN r.RCV_DT - p.DUE_DT <= 7 THEN '2. 4~7일'
                   ELSE '3. 8일 넘음'
                 END AS DELAY_BAND
               , COUNT(*) AS CNT
            FROM PURCHASE_ORDER p
               , RECEIPT r
           WHERE p.PO_NO = r.PO_NO
             AND p.LINE_NO = r.LINE_NO
           GROUP BY
                 CASE
                   WHEN r.RCV_DT <= p.DUE_DT THEN '0. 납기 안'
                   WHEN r.RCV_DT - p.DUE_DT <= 3 THEN '1. 1~3일'
                   WHEN r.RCV_DT - p.DUE_DT <= 7 THEN '2. 4~7일'
                   ELSE '3. 8일 넘음'
                 END
           ORDER BY
                 DELAY_BAND""",
          """
          SELECT
                 CASE
                   WHEN r.RCV_DT <= p.DUE_DT THEN '0. 납기 안'
                   WHEN r.RCV_DT - p.DUE_DT <= 3 THEN '1. 1~3일'
                   WHEN r.RCV_DT - p.DUE_DT <= 7 THEN '2. 4~7일'
                   ELSE '3. 8일 넘음'
                 END AS DELAY_BAND
               , COUNT(*) AS CNT
            FROM PURCHASE_ORDER p
               , RECEIPT r
           WHERE p.PO_NO = r.PO_NO
             AND p.LINE_NO = r.LINE_NO
           GROUP BY
                 CASE
                   WHEN r.RCV_DT <= p.DUE_DT THEN '0. 납기 안'
                   WHEN r.RCV_DT - p.DUE_DT <= 3 THEN '1. 1~3일'
                   WHEN r.RCV_DT - p.DUE_DT <= 7 THEN '2. 4~7일'
                   ELSE '3. 8일 넘음'
                 END
           ORDER BY
                 DELAY_BAND"""),
      new Example("구매 · 협력사", "견적가와 실제 발주가 차이", "부품 · 협력사마다 마지막 견적가와 마지막 발주 단가.",
          """
          SELECT
                 q.PART_NO
               , q.VENDOR_ID
               , q.PRICE AS QUOTE_PRICE
               , o.PRICE AS PO_PRICE
               , o.PRICE - q.PRICE AS DIFF
            FROM (SELECT x.*, ROW_NUMBER() OVER (PARTITION BY x.PART_NO, x.VENDOR_ID ORDER BY x.QUOTE_DT DESC) AS RN FROM QUOTE x) q
               , (SELECT y.*, ROW_NUMBER() OVER (PARTITION BY y.PART_NO, y.VENDOR_ID ORDER BY y.PO_DT DESC) AS RN FROM PURCHASE_ORDER y) o
           WHERE q.PART_NO = o.PART_NO
             AND q.VENDOR_ID = o.VENDOR_ID
             AND q.RN = 1
             AND o.RN = 1""",
          """
          SELECT
                 q.PART_NO
               , q.VENDOR_ID
               , q.PRICE AS QUOTE_PRICE
               , o.PRICE AS PO_PRICE
               , o.PRICE - q.PRICE AS DIFF
            FROM (SELECT x.*, ROW_NUMBER() OVER (PARTITION BY x.PART_NO, x.VENDOR_ID ORDER BY x.QUOTE_DT DESC) AS RN FROM QUOTE x) q
               , (SELECT y.*, ROW_NUMBER() OVER (PARTITION BY y.PART_NO, y.VENDOR_ID ORDER BY y.PO_DT DESC) AS RN FROM PURCHASE_ORDER y) o
           WHERE q.PART_NO = o.PART_NO
             AND q.VENDOR_ID = o.VENDOR_ID
             AND q.RN = 1
             AND o.RN = 1"""),
      new Example("인터페이스 · 배치 · 이관", "인터페이스 실패 · 미처리 건 (재처리 대상)", "실패했거나 한 시간 넘게 대기인 건. 재시도 횟수가 3 미만인 것만 다시 보냅니다.",
          """
          SELECT
                 f.IF_ID
               , f.PART_NO
               , f.STATUS
               , f.ERR_MSG
               , f.RETRY_CNT
               , f.REG_DT
            FROM IF_PART f
           WHERE (f.STATUS = 'FAIL' OR (f.STATUS = 'READY' AND f.REG_DT < SYSDATE - 1 / 24))
             AND f.RETRY_CNT < 3
           ORDER BY
                 f.REG_DT""",
          """
          SELECT
                 f.IF_ID
               , f.PART_NO
               , f.STATUS
               , f.ERR_MSG
               , f.RETRY_CNT
               , f.REG_DT
            FROM IF_PART f
           WHERE (f.STATUS = 'FAIL' OR (f.STATUS = 'READY' AND f.REG_DT < NOW() - INTERVAL '1 HOUR'))
             AND f.RETRY_CNT < 3
           ORDER BY
                 f.REG_DT"""),
      new Example("인터페이스 · 배치 · 이관", "재처리 표시 (상태를 READY 로, 횟수 + 1)", "위에서 고른 건을 다시 보내도록 되돌립니다.",
          """
          UPDATE IF_PART f
             SET f.STATUS = 'READY'
               , f.RETRY_CNT = f.RETRY_CNT + 1
               , f.ERR_MSG = NULL
           WHERE f.STATUS = 'FAIL'
             AND f.RETRY_CNT < 3""",
          """
          UPDATE IF_PART f
             SET STATUS = 'READY'
               , RETRY_CNT = f.RETRY_CNT + 1
               , ERR_MSG = NULL
           WHERE f.STATUS = 'FAIL'
             AND f.RETRY_CNT < 3"""),
      new Example("인터페이스 · 배치 · 이관", "두 시스템 부품 마스터 대조 (한쪽만 있음 · 값 다름)", "PLM 과 ERP 부품을 양쪽 다 보이게 붙여 차이만.",
          """
          SELECT
                 NVL(p.PART_NO, e.PART_NO) AS PART_NO
               , p.PART_NAME AS PLM_NAME
               , e.PART_NAME AS ERP_NAME
               , p.UNIT_COST AS PLM_COST
               , e.UNIT_COST AS ERP_COST
               , CASE
                   WHEN p.PART_NO IS NULL THEN 'ERP 에만'
                   WHEN e.PART_NO IS NULL THEN 'PLM 에만'
                   WHEN p.PART_NAME <> e.PART_NAME THEN '이름 다름'
                   ELSE '단가 다름'
                 END AS DIFF
            FROM PART p
            FULL OUTER JOIN ERP_PART e
              ON p.PART_NO = e.PART_NO
           WHERE p.PART_NO IS NULL
              OR e.PART_NO IS NULL
              OR p.PART_NAME <> e.PART_NAME
              OR p.UNIT_COST <> e.UNIT_COST""",
          """
          SELECT
                 COALESCE(p.PART_NO, e.PART_NO) AS PART_NO
               , p.PART_NAME AS PLM_NAME
               , e.PART_NAME AS ERP_NAME
               , p.UNIT_COST AS PLM_COST
               , e.UNIT_COST AS ERP_COST
               , CASE
                   WHEN p.PART_NO IS NULL THEN 'ERP 에만'
                   WHEN e.PART_NO IS NULL THEN 'PLM 에만'
                   WHEN p.PART_NAME <> e.PART_NAME THEN '이름 다름'
                   ELSE '단가 다름'
                 END AS DIFF
            FROM PART p
            FULL OUTER JOIN ERP_PART e
              ON p.PART_NO = e.PART_NO
           WHERE p.PART_NO IS NULL
              OR e.PART_NO IS NULL
              OR p.PART_NAME <> e.PART_NAME
              OR p.UNIT_COST <> e.UNIT_COST"""),
      new Example("인터페이스 · 배치 · 이관", "두 표 통째로 같은지 (MINUS / EXCEPT 양쪽)", "칼럼이 같은 두 표에서 한쪽에만 있는 줄. 이관 뒤 대조에 씁니다.",
          """
          SELECT 'OLD 에만' AS SIDE, a.* FROM (SELECT o.ORDER_ID, o.AMOUNT, o.STATUS FROM ORDERS_BAK o MINUS SELECT n.ORDER_ID, n.AMOUNT, n.STATUS FROM ORDERS n) a
           UNION ALL
          SELECT 'NEW 에만', b.* FROM (SELECT n.ORDER_ID, n.AMOUNT, n.STATUS FROM ORDERS n MINUS SELECT o.ORDER_ID, o.AMOUNT, o.STATUS FROM ORDERS_BAK o) b""",
          """
          SELECT 'OLD 에만' AS SIDE, a.* FROM (SELECT o.ORDER_ID, o.AMOUNT, o.STATUS FROM ORDERS_BAK o EXCEPT SELECT n.ORDER_ID, n.AMOUNT, n.STATUS FROM ORDERS n) a
           UNION ALL
          SELECT 'NEW 에만', b.* FROM (SELECT n.ORDER_ID, n.AMOUNT, n.STATUS FROM ORDERS n EXCEPT SELECT o.ORDER_ID, o.AMOUNT, o.STATUS FROM ORDERS_BAK o) b"""),
      new Example("인터페이스 · 배치 · 이관", "이관 점검 요약 (건수 · 합계 · 키 범위 비교)", "옛 표와 새 표의 건수, 금액 합, 최소 · 최대 키를 한 줄씩.",
          """
          SELECT
                 'OLD' AS SIDE
               , COUNT(*) AS CNT
               , SUM(o.AMOUNT) AS AMT
               , MIN(o.ORDER_ID) AS MIN_ID
               , MAX(o.ORDER_ID) AS MAX_ID
            FROM ORDERS_BAK o
           UNION ALL
          SELECT
                 'NEW'
               , COUNT(*)
               , SUM(n.AMOUNT)
               , MIN(n.ORDER_ID)
               , MAX(n.ORDER_ID)
            FROM ORDERS n""",
          """
          SELECT
                 'OLD' AS SIDE
               , COUNT(*) AS CNT
               , SUM(o.AMOUNT) AS AMT
               , MIN(o.ORDER_ID) AS MIN_ID
               , MAX(o.ORDER_ID) AS MAX_ID
            FROM ORDERS_BAK o
           UNION ALL
          SELECT
                 'NEW'
               , COUNT(*)
               , SUM(n.AMOUNT)
               , MIN(n.ORDER_ID)
               , MAX(n.ORDER_ID)
            FROM ORDERS n"""),
      new Example("인터페이스 · 배치 · 이관", "배치 실행 이력: 오래 걸린 날 · 실패한 날 · 안 돈 날", "평소(평균)보다 두 배 넘게 걸린 날과 실패, 그리고 달력에 있는데 기록이 없는 날.",
          """
          WITH d AS (
          SELECT
                 TO_DATE(:fromDt, 'YYYY-MM-DD') + LEVEL - 1 AS DT
            FROM DUAL
          CONNECT BY LEVEL <= TO_DATE(:toDt, 'YYYY-MM-DD') - TO_DATE(:fromDt, 'YYYY-MM-DD') + 1
          ), b AS (
          SELECT
                 TRUNC(l.START_TS) AS DT
               , l.STATUS
               , (l.END_TS - l.START_TS) * 24 * 60 AS MIN
               , AVG((l.END_TS - l.START_TS) * 24 * 60) OVER () AS AVG_MIN
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
            FROM d, b
           WHERE d.DT = b.DT(+)
           ORDER BY
                 d.DT""",
          """
          WITH d AS (
          SELECT
                 CAST(g AS DATE) AS DT
            FROM GENERATE_SERIES(TO_DATE(:fromDt, 'YYYY-MM-DD'), TO_DATE(:toDt, 'YYYY-MM-DD'), INTERVAL '1 DAY') g
          ), b AS (
          SELECT
                 CAST(l.START_TS AS DATE) AS DT
               , l.STATUS
               , EXTRACT(EPOCH FROM l.END_TS - l.START_TS) / 60 AS MIN
               , AVG(EXTRACT(EPOCH FROM l.END_TS - l.START_TS) / 60) OVER () AS AVG_MIN
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
      new Example("인터페이스 · 배치 · 이관", "오늘 안 돈 배치 (매일 도는 작업 중)", "최근 7일 중 하루라도 돈 작업인데 오늘은 기록이 없는 것.",
          """
          SELECT DISTINCT
                 b.JOB_NAME
            FROM BATCH_LOG b
           WHERE b.START_TS >= TRUNC(SYSDATE) - 7
             AND NOT EXISTS (
                  SELECT 1
                    FROM BATCH_LOG x
                   WHERE x.JOB_NAME = b.JOB_NAME
                     AND x.START_TS >= TRUNC(SYSDATE)
                 )""",
          """
          SELECT DISTINCT
                 b.JOB_NAME
            FROM BATCH_LOG b
           WHERE b.START_TS >= CURRENT_DATE - 7
             AND NOT EXISTS (
                  SELECT 1
                    FROM BATCH_LOG x
                   WHERE x.JOB_NAME = b.JOB_NAME
                     AND x.START_TS >= CURRENT_DATE
                 )"""),
      new Example("인터페이스 · 배치 · 이관", "평소보다 오래 걸린 배치 (평균의 2배 넘음)", "작업마다 평균 시간과 비교해 2배 넘게 걸린 날.",
          """
          SELECT
                 x.JOB_NAME
               , x.START_TS
               , ROUND(x.MINUTES, 1) AS MINUTES
               , ROUND(x.AVG_MIN, 1) AS AVG_MIN
            FROM (
                  SELECT
                         b.JOB_NAME
                       , b.START_TS
                       , (CAST(b.END_TS AS DATE) - CAST(b.START_TS AS DATE)) * 1440 AS MINUTES
                       , AVG((CAST(b.END_TS AS DATE) - CAST(b.START_TS AS DATE)) * 1440) OVER (PARTITION BY b.JOB_NAME) AS AVG_MIN
                    FROM BATCH_LOG b
                   WHERE b.END_TS IS NOT NULL
                 ) x
           WHERE x.MINUTES > x.AVG_MIN * 2""",
          """
          SELECT
                 x.JOB_NAME
               , x.START_TS
               , ROUND(x.MINUTES, 1) AS MINUTES
               , ROUND(x.AVG_MIN, 1) AS AVG_MIN
            FROM (
                  SELECT
                         b.JOB_NAME
                       , b.START_TS
                       , CAST(EXTRACT(EPOCH FROM (b.END_TS - b.START_TS)) / 60 AS NUMERIC) AS MINUTES
                       , CAST(AVG(EXTRACT(EPOCH FROM (b.END_TS - b.START_TS)) / 60) OVER (PARTITION BY b.JOB_NAME) AS NUMERIC) AS AVG_MIN
                    FROM BATCH_LOG b
                   WHERE b.END_TS IS NOT NULL
                 ) x
           WHERE x.MINUTES > x.AVG_MIN * 2"""),
      new Example("인터페이스 · 배치 · 이관", "인터페이스 일별 성공 · 실패 · 대기", "인터페이스 표의 상태를 날짜마다 칸으로.",
          """
          SELECT
                 TRUNC(f.REG_DT) AS DT
               , SUM(CASE WHEN f.STATUS = 'DONE' THEN 1 ELSE 0 END) AS DONE_CNT
               , SUM(CASE WHEN f.STATUS = 'ERROR' THEN 1 ELSE 0 END) AS ERROR_CNT
               , SUM(CASE WHEN f.STATUS = 'READY' THEN 1 ELSE 0 END) AS READY_CNT
            FROM IF_PART f
           GROUP BY
                 TRUNC(f.REG_DT)
           ORDER BY
                 DT DESC""",
          """
          SELECT
                 CAST(f.REG_DT AS DATE) AS DT
               , SUM(CASE WHEN f.STATUS = 'DONE' THEN 1 ELSE 0 END) AS DONE_CNT
               , SUM(CASE WHEN f.STATUS = 'ERROR' THEN 1 ELSE 0 END) AS ERROR_CNT
               , SUM(CASE WHEN f.STATUS = 'READY' THEN 1 ELSE 0 END) AS READY_CNT
            FROM IF_PART f
           GROUP BY
                 CAST(f.REG_DT AS DATE)
           ORDER BY
                 DT DESC"""),
      new Example("인터페이스 · 배치 · 이관", "오류 메시지별 건수 (무엇 때문에 실패하나)", "실패 줄을 오류 글 앞부분으로 묶어 많은 순.",
          """
          SELECT
                 SUBSTR(f.ERR_MSG, 1, 60) AS ERR_HEAD
               , COUNT(*) AS CNT
               , MAX(f.REG_DT) AS LAST_DT
            FROM IF_PART f
           WHERE f.STATUS = 'ERROR'
           GROUP BY
                 SUBSTR(f.ERR_MSG, 1, 60)
           ORDER BY
                 CNT DESC""",
          """
          SELECT
                 SUBSTR(f.ERR_MSG, 1, 60) AS ERR_HEAD
               , COUNT(*) AS CNT
               , MAX(f.REG_DT) AS LAST_DT
            FROM IF_PART f
           WHERE f.STATUS = 'ERROR'
           GROUP BY
                 SUBSTR(f.ERR_MSG, 1, 60)
           ORDER BY
                 CNT DESC"""),
      new Example("감사 · 로그", "누가 언제 무엇을 바꿨나 (감사 로그)", "표 · 키를 골라 바뀐 칼럼과 전후 값, 바꾼 사람.",
          """
          SELECT
                 l.LOG_TS
               , u.USER_NAME
               , l.ACTION
               , l.COL_NAME
               , l.OLD_VAL
               , l.NEW_VAL
            FROM AUDIT_LOG l, USERS u
           WHERE l.USER_ID = u.USER_ID(+)
             AND l.TABLE_NAME = :tableName
             AND l.PK_VAL = :pkVal
           ORDER BY
                 l.LOG_TS DESC""",
          """
          SELECT
                 l.LOG_TS
               , u.USER_NAME
               , l.ACTION
               , l.COL_NAME
               , l.OLD_VAL
               , l.NEW_VAL
            FROM AUDIT_LOG l
            LEFT OUTER JOIN USERS u
              ON l.USER_ID = u.USER_ID
           WHERE l.TABLE_NAME = :tableName
             AND l.PK_VAL = :pkVal
           ORDER BY
                 l.LOG_TS DESC"""),
      new Example("감사 · 로그", "사람별 변경 건수 (최근 30일)", "누가 얼마나 고쳤는지 표 · 동작별로.",
          """
          SELECT
                 l.USER_ID
               , l.TABLE_NAME
               , l.ACTION
               , COUNT(*) AS CNT
            FROM AUDIT_LOG l
           WHERE l.LOG_TS >= SYSDATE - 30
           GROUP BY
                 l.USER_ID
               , l.TABLE_NAME
               , l.ACTION
           ORDER BY
                 CNT DESC""",
          """
          SELECT
                 l.USER_ID
               , l.TABLE_NAME
               , l.ACTION
               , COUNT(*) AS CNT
            FROM AUDIT_LOG l
           WHERE l.LOG_TS >= NOW() - INTERVAL '30 DAY'
           GROUP BY
                 l.USER_ID
               , l.TABLE_NAME
               , l.ACTION
           ORDER BY
                 CNT DESC"""),
      new Example("감사 · 로그", "오래 안 쓴 계정 (90일 넘게 로그인 없음)", "마지막 로그인이 90일 전이거나 한 번도 없는 사용 중 계정.",
          """
          SELECT
                 u.USER_ID
               , u.USER_NAME
               , MAX(l.LOGIN_TS) AS LAST_LOGIN
            FROM USERS u, LOGIN_LOG l
           WHERE u.USER_ID = l.USER_ID(+)
             AND u.USE_YN = 'Y'
           GROUP BY
                 u.USER_ID
               , u.USER_NAME
          HAVING MAX(l.LOGIN_TS) IS NULL
              OR MAX(l.LOGIN_TS) < SYSDATE - 90""",
          """
          SELECT
                 u.USER_ID
               , u.USER_NAME
               , MAX(l.LOGIN_TS) AS LAST_LOGIN
            FROM USERS u
            LEFT OUTER JOIN LOGIN_LOG l
              ON u.USER_ID = l.USER_ID
           WHERE u.USE_YN = 'Y'
           GROUP BY
                 u.USER_ID
               , u.USER_NAME
          HAVING MAX(l.LOGIN_TS) IS NULL
              OR MAX(l.LOGIN_TS) < NOW() - INTERVAL '90 DAY'"""),
      new Example("감사 · 로그", "시간대별 로그인 수 · 한 사람 여러 IP", "몇 시에 많이 들어오는지, 하루에 IP 가 여럿인 계정.",
          """
          SELECT
                 l.USER_ID
               , TRUNC(l.LOGIN_TS) AS DT
               , COUNT(DISTINCT l.IP) AS IPS
            FROM LOGIN_LOG l
           GROUP BY
                 l.USER_ID
               , TRUNC(l.LOGIN_TS)
          HAVING COUNT(DISTINCT l.IP) > 1""",
          """
          SELECT
                 l.USER_ID
               , CAST(l.LOGIN_TS AS DATE) AS DT
               , COUNT(DISTINCT l.IP) AS IPS
            FROM LOGIN_LOG l
           GROUP BY
                 l.USER_ID
               , CAST(l.LOGIN_TS AS DATE)
          HAVING COUNT(DISTINCT l.IP) > 1"""),
      new Example("감사 · 로그", "근무 시간 밖에 바뀐 데이터 (밤 · 주말)", "감사 로그 중 평일 08~20시 밖에 일어난 변경.",
          """
          SELECT
                 a.LOG_TS
               , a.USER_ID
               , a.TABLE_NAME
               , a.ACTION
            FROM AUDIT_LOG a
           WHERE TO_CHAR(a.LOG_TS, 'DY', 'NLS_DATE_LANGUAGE=AMERICAN') IN ('SAT', 'SUN')
              OR TO_NUMBER(TO_CHAR(a.LOG_TS, 'HH24')) NOT BETWEEN 8 AND 19""",
          """
          SELECT
                 a.LOG_TS
               , a.USER_ID
               , a.TABLE_NAME
               , a.ACTION
            FROM AUDIT_LOG a
           WHERE EXTRACT(ISODOW FROM a.LOG_TS) IN (6, 7)
              OR EXTRACT(HOUR FROM a.LOG_TS) NOT BETWEEN 8 AND 19"""),
      new Example("감사 · 로그", "같은 줄을 여러 번 고친 것 (하루 3번 넘게)", "한 줄이 하루에 자꾸 바뀌면 화면 버그나 중복 저장일 수 있습니다.",
          """
          SELECT
                 a.TABLE_NAME
               , a.PK_VAL
               , TRUNC(a.LOG_TS) AS DT
               , COUNT(*) AS CHG_CNT
            FROM AUDIT_LOG a
           WHERE a.ACTION = 'UPDATE'
           GROUP BY
                 a.TABLE_NAME
               , a.PK_VAL
               , TRUNC(a.LOG_TS)
          HAVING COUNT(*) > 3""",
          """
          SELECT
                 a.TABLE_NAME
               , a.PK_VAL
               , CAST(a.LOG_TS AS DATE) AS DT
               , COUNT(*) AS CHG_CNT
            FROM AUDIT_LOG a
           WHERE a.ACTION = 'UPDATE'
           GROUP BY
                 a.TABLE_NAME
               , a.PK_VAL
               , CAST(a.LOG_TS AS DATE)
          HAVING COUNT(*) > 3"""),
      new Example("감사 · 로그", "지운 데이터 이력 (누가 무엇을 지웠나)", "감사 로그에서 DELETE 만, 표 · 사람별로.",
          """
          SELECT
                 a.TABLE_NAME
               , a.USER_ID
               , COUNT(*) AS DEL_CNT
               , MAX(a.LOG_TS) AS LAST_DEL
            FROM AUDIT_LOG a
           WHERE a.ACTION = 'DELETE'
           GROUP BY
                 a.TABLE_NAME
               , a.USER_ID
           ORDER BY
                 DEL_CNT DESC""",
          """
          SELECT
                 a.TABLE_NAME
               , a.USER_ID
               , COUNT(*) AS DEL_CNT
               , MAX(a.LOG_TS) AS LAST_DEL
            FROM AUDIT_LOG a
           WHERE a.ACTION = 'DELETE'
           GROUP BY
                 a.TABLE_NAME
               , a.USER_ID
           ORDER BY
                 DEL_CNT DESC"""),
      new Example("대시보드 통계", "건수 (전체 · 그룹별)", "상태 값마다 건수를 많은 순으로. GROUP BY 를 빼면 전체 건수입니다.",
          """
          -- STATUS 값마다 건수 (많은 순)
          SELECT
                 o.STATUS
               , COUNT(*) AS CNT
            FROM ORDERS o
           WHERE 1 = 1
           GROUP BY
                 o.STATUS
           ORDER BY
                 CNT DESC""",
          """
          -- STATUS 값마다 건수 (많은 순)
          SELECT
                 o.STATUS
               , COUNT(*) AS CNT
            FROM ORDERS o
           WHERE 1 = 1
           GROUP BY
                 o.STATUS
           ORDER BY
                 CNT DESC"""),
      new Example("대시보드 통계", "합계 · 평균 · 최대 · 최소", "금액의 건수 · 합계 · 평균 · 최대 · 최소를 상태마다 한 번에. 합계 · 평균은 NULL 행을 빼고 셉니다.",
          """
          -- AMOUNT 의 건수 · 합계 · 평균 · 최대 · 최소 (STATUS 마다). 합계 · 평균은 NULL 행을 빼고 셉니다
          SELECT
                 o.STATUS
               , COUNT(*) AS CNT
               , SUM(o.AMOUNT) AS SUM_AMOUNT
               , ROUND(AVG(o.AMOUNT), 2) AS AVG_AMOUNT
               , MAX(o.AMOUNT) AS MAX_AMOUNT
               , MIN(o.AMOUNT) AS MIN_AMOUNT
            FROM ORDERS o
           WHERE 1 = 1
           GROUP BY
                 o.STATUS
           ORDER BY
                 o.STATUS""",
          """
          -- AMOUNT 의 건수 · 합계 · 평균 · 최대 · 최소 (STATUS 마다). 합계 · 평균은 NULL 행을 빼고 셉니다
          SELECT
                 o.STATUS
               , COUNT(*) AS CNT
               , SUM(o.AMOUNT) AS SUM_AMOUNT
               , ROUND(AVG(o.AMOUNT), 2) AS AVG_AMOUNT
               , MAX(o.AMOUNT) AS MAX_AMOUNT
               , MIN(o.AMOUNT) AS MIN_AMOUNT
            FROM ORDERS o
           WHERE 1 = 1
           GROUP BY
                 o.STATUS
           ORDER BY
                 o.STATUS"""),
      new Example("대시보드 통계", "기간별 현황", "월마다 건수와 금액 합계. 'MM' 을 'DD' · 'IW' · 'YYYY'(PostgreSQL 은 day · week · year)로 바꾸면 일 · 주 · 연도별입니다.",
          """
          -- 월별 현황: 시작일 ~ 끝일(끝일 하루 전체 포함)
          SELECT
                 TO_CHAR(TRUNC(o.ORDER_DT, 'MM'), 'YYYY-MM') AS PERIOD
               , COUNT(*) AS CNT
               , SUM(o.AMOUNT) AS SUM_AMOUNT
            FROM ORDERS o
           WHERE o.ORDER_DT >= TO_DATE(:fromDt, 'YYYY-MM-DD')
             AND o.ORDER_DT < TO_DATE(:toDt, 'YYYY-MM-DD') + 1
           GROUP BY
                 TO_CHAR(TRUNC(o.ORDER_DT, 'MM'), 'YYYY-MM')
           ORDER BY
                 PERIOD""",
          """
          -- 월별 현황: 시작일 ~ 끝일(끝일 하루 전체 포함)
          SELECT
                 TO_CHAR(DATE_TRUNC('MONTH', o.ORDER_DT), 'YYYY-MM') AS PERIOD
               , COUNT(*) AS CNT
               , SUM(o.AMOUNT) AS SUM_AMOUNT
            FROM ORDERS o
           WHERE o.ORDER_DT >= CAST(:fromDt AS DATE)
             AND o.ORDER_DT < CAST(:toDt AS DATE) + 1
           GROUP BY
                 TO_CHAR(DATE_TRUNC('MONTH', o.ORDER_DT), 'YYYY-MM')
           ORDER BY
                 PERIOD"""),
      new Example("대시보드 통계", "전 기간 대비 증감", "월마다 건수와 바로 앞 달 대비 증감 · 증감률(%). 앞 달이 0 건이면 증감률은 비웁니다.",
          """
          -- 월별 건수와 앞 기간 대비 증감 · 증감률(%). 앞 기간이 0 건이면 증감률은 비웁니다
          WITH COUNTED AS (
              SELECT
                     TO_CHAR(TRUNC(o.ORDER_DT, 'MM'), 'YYYY-MM') AS PERIOD
                   , COUNT(*) AS CNT
                FROM ORDERS o
               WHERE o.ORDER_DT >= TO_DATE(:fromDt, 'YYYY-MM-DD')
                 AND o.ORDER_DT < TO_DATE(:toDt, 'YYYY-MM-DD') + 1
               GROUP BY
                     TO_CHAR(TRUNC(o.ORDER_DT, 'MM'), 'YYYY-MM')
          )
          SELECT
                 c.PERIOD
               , c.CNT
               , LAG(c.CNT) OVER (ORDER BY c.PERIOD) AS PREV_CNT
               , c.CNT - LAG(c.CNT) OVER (ORDER BY c.PERIOD) AS DIFF
               , ROUND((c.CNT - LAG(c.CNT) OVER (ORDER BY c.PERIOD)) * 100.0 / NULLIF(LAG(c.CNT) OVER (ORDER BY c.PERIOD), 0), 1) AS RATE
            FROM COUNTED c
           ORDER BY
                 c.PERIOD""",
          """
          -- 월별 건수와 앞 기간 대비 증감 · 증감률(%). 앞 기간이 0 건이면 증감률은 비웁니다
          WITH COUNTED AS (
              SELECT
                     TO_CHAR(DATE_TRUNC('MONTH', o.ORDER_DT), 'YYYY-MM') AS PERIOD
                   , COUNT(*) AS CNT
                FROM ORDERS o
               WHERE o.ORDER_DT >= CAST(:fromDt AS DATE)
                 AND o.ORDER_DT < CAST(:toDt AS DATE) + 1
               GROUP BY
                     TO_CHAR(DATE_TRUNC('MONTH', o.ORDER_DT), 'YYYY-MM')
          )
          SELECT
                 c.PERIOD
               , c.CNT
               , LAG(c.CNT) OVER (ORDER BY c.PERIOD) AS PREV_CNT
               , c.CNT - LAG(c.CNT) OVER (ORDER BY c.PERIOD) AS DIFF
               , ROUND((c.CNT - LAG(c.CNT) OVER (ORDER BY c.PERIOD)) * 100.0 / NULLIF(LAG(c.CNT) OVER (ORDER BY c.PERIOD), 0), 1) AS RATE
            FROM COUNTED c
           ORDER BY
                 c.PERIOD"""),
      new Example("대시보드 통계", "비율 (전체 중 몇 %)", "상태 값마다 건수와 전체 중 비율(%).",
          """
          -- STATUS 값마다 건수와 전체 중 비율(%)
          SELECT
                 o.STATUS
               , COUNT(*) AS CNT
               , ROUND(COUNT(*) * 100.0 / NULLIF(SUM(COUNT(*)) OVER (), 0), 1) AS PCT
            FROM ORDERS o
           WHERE 1 = 1
           GROUP BY
                 o.STATUS
           ORDER BY
                 CNT DESC""",
          """
          -- STATUS 값마다 건수와 전체 중 비율(%)
          SELECT
                 o.STATUS
               , COUNT(*) AS CNT
               , ROUND(COUNT(*) * 100.0 / NULLIF(SUM(COUNT(*)) OVER (), 0), 1) AS PCT
            FROM ORDERS o
           WHERE 1 = 1
           GROUP BY
                 o.STATUS
           ORDER BY
                 CNT DESC"""),
      new Example("대시보드 통계", "기간별 누적", "월마다 건수와 처음부터 쌓인 누적 건수.",
          """
          -- 월별 건수와 누적 건수
          SELECT
                 TO_CHAR(TRUNC(o.ORDER_DT, 'MM'), 'YYYY-MM') AS PERIOD
               , COUNT(*) AS CNT
               , SUM(COUNT(*)) OVER (ORDER BY TO_CHAR(TRUNC(o.ORDER_DT, 'MM'), 'YYYY-MM')) AS CUM_CNT
            FROM ORDERS o
           WHERE o.ORDER_DT >= TO_DATE(:fromDt, 'YYYY-MM-DD')
             AND o.ORDER_DT < TO_DATE(:toDt, 'YYYY-MM-DD') + 1
           GROUP BY
                 TO_CHAR(TRUNC(o.ORDER_DT, 'MM'), 'YYYY-MM')
           ORDER BY
                 PERIOD""",
          """
          -- 월별 건수와 누적 건수
          SELECT
                 TO_CHAR(DATE_TRUNC('MONTH', o.ORDER_DT), 'YYYY-MM') AS PERIOD
               , COUNT(*) AS CNT
               , SUM(COUNT(*)) OVER (ORDER BY TO_CHAR(DATE_TRUNC('MONTH', o.ORDER_DT), 'YYYY-MM')) AS CUM_CNT
            FROM ORDERS o
           WHERE o.ORDER_DT >= CAST(:fromDt AS DATE)
             AND o.ORDER_DT < CAST(:toDt AS DATE) + 1
           GROUP BY
                 TO_CHAR(DATE_TRUNC('MONTH', o.ORDER_DT), 'YYYY-MM')
           ORDER BY
                 PERIOD"""),
      new Example("대시보드 통계", "순위 · 상위 N 개", "상태마다 금액 합계로 순위를 매겨 상위 10 개만.",
          """
          -- STATUS 마다 AMOUNT 합계 순위, 상위 10 개 (같은 값은 같은 순위)
          WITH RANKED AS (
              SELECT
                     o.STATUS
                   , SUM(o.AMOUNT) AS SUM_AMOUNT
                   , RANK() OVER (ORDER BY SUM(o.AMOUNT) DESC) AS RNK
                FROM ORDERS o
               GROUP BY
                     o.STATUS
          )
          SELECT
                 r.*
            FROM RANKED r
           WHERE r.RNK <= 10
           ORDER BY
                 r.RNK""",
          """
          -- STATUS 마다 AMOUNT 합계 순위, 상위 10 개 (같은 값은 같은 순위)
          WITH RANKED AS (
              SELECT
                     o.STATUS
                   , SUM(o.AMOUNT) AS SUM_AMOUNT
                   , RANK() OVER (ORDER BY SUM(o.AMOUNT) DESC) AS RNK
                FROM ORDERS o
               GROUP BY
                     o.STATUS
          )
          SELECT
                 r.*
            FROM RANKED r
           WHERE r.RNK <= 10
           ORDER BY
                 r.RNK"""),
      new Example("대시보드 통계", "상태별 건수 한 줄 (대시보드 카드)", "전체 · 상태 값마다 건수를 한 줄에 칸으로 놓습니다. 대시보드 카드 숫자에 바로 씁니다.",
          """
          -- 대시보드 카드: 전체 건수와 STATUS 값마다 건수를 한 줄에
          SELECT
                 COUNT(*) AS TOTAL
               , SUM(CASE WHEN o.STATUS = '접수' THEN 1 ELSE 0 END) AS "접수"
               , SUM(CASE WHEN o.STATUS = '진행' THEN 1 ELSE 0 END) AS "진행"
               , SUM(CASE WHEN o.STATUS = '완료' THEN 1 ELSE 0 END) AS "완료"
            FROM ORDERS o
           WHERE 1 = 1""",
          """
          -- 대시보드 카드: 전체 건수와 status 값마다 건수를 한 줄에
          SELECT
                 COUNT(*) AS TOTAL
               , SUM(CASE WHEN o.STATUS = '접수' THEN 1 ELSE 0 END) AS "접수"
               , SUM(CASE WHEN o.STATUS = '진행' THEN 1 ELSE 0 END) AS "진행"
               , SUM(CASE WHEN o.STATUS = '완료' THEN 1 ELSE 0 END) AS "완료"
            FROM ORDERS o
           WHERE 1 = 1"""),
      new Example("대시보드 통계", "합계 줄 붙이기 (ROLLUP)", "상태별 건수 · 합계 아래에 「합계」 줄을 하나 더 붙입니다.",
          """
          -- STATUS 별 건수 · 합계 아래에 「합계」 줄
          SELECT
                 CASE WHEN GROUPING(o.STATUS) = 1 THEN '합계' ELSE CAST(o.STATUS AS VARCHAR(100)) END AS STATUS
               , COUNT(*) AS CNT
               , SUM(o.AMOUNT) AS SUM_AMOUNT
            FROM ORDERS o
           WHERE 1 = 1
           GROUP BY ROLLUP(o.STATUS)
           ORDER BY
                 GROUPING(o.STATUS)
               , o.STATUS""",
          """
          -- STATUS 별 건수 · 합계 아래에 「합계」 줄
          SELECT
                 CASE WHEN GROUPING(o.STATUS) = 1 THEN '합계' ELSE CAST(o.STATUS AS VARCHAR(100)) END AS STATUS
               , COUNT(*) AS CNT
               , SUM(o.AMOUNT) AS SUM_AMOUNT
            FROM ORDERS o
           WHERE 1 = 1
           GROUP BY ROLLUP(o.STATUS)
           ORDER BY
                 GROUPING(o.STATUS)
               , o.STATUS"""),
      new Example("대시보드 통계", "0 건인 날짜도 빠짐없이", "기간 안의 모든 날짜를 만들고 건수를 붙입니다. 행이 없는 날은 0 으로 나옵니다(그래프용).",
          """
          -- 시작일 ~ 끝일의 모든 날짜와 건수. 행이 없는 날은 0
          WITH DAYS AS (
              SELECT TO_DATE(:fromDt, 'YYYY-MM-DD') + LEVEL - 1 AS DT
                FROM DUAL
             CONNECT BY LEVEL <= TO_DATE(:toDt, 'YYYY-MM-DD') - TO_DATE(:fromDt, 'YYYY-MM-DD') + 1
          )
          , CNT AS (
              SELECT
                     TRUNC(o.ORDER_DT) AS DT
                   , COUNT(*) AS CNT
                FROM ORDERS o
               WHERE o.ORDER_DT >= TO_DATE(:fromDt, 'YYYY-MM-DD')
                 AND o.ORDER_DT < TO_DATE(:toDt, 'YYYY-MM-DD') + 1
               GROUP BY
                     TRUNC(o.ORDER_DT)
          )
          SELECT
                 d.DT
               , NVL(c.CNT, 0) AS CNT
            FROM DAYS d
               , CNT c
           WHERE d.DT = c.DT(+)
           ORDER BY
                 d.DT""",
          """
          -- 시작일 ~ 끝일의 모든 날짜와 건수. 행이 없는 날은 0
          WITH DAYS AS (
              SELECT CAST(g AS DATE) AS DT
                FROM GENERATE_SERIES(CAST(:fromDt AS DATE), CAST(:toDt AS DATE), INTERVAL '1 DAY') g
          )
          , CNT AS (
              SELECT
                     CAST(o.ORDER_DT AS DATE) AS DT
                   , COUNT(*) AS CNT
                FROM ORDERS o
               WHERE o.ORDER_DT >= CAST(:fromDt AS DATE)
                 AND o.ORDER_DT < CAST(:toDt AS DATE) + 1
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
      new Example("대시보드 통계", "숫자 카드 한 줄 (전체 · 열림 · 이번 달 등록 · 기한 지남)", "대시보드 맨 위 숫자 카드를 쿼리 하나로. 조건을 CASE 로 나눠 한 번만 훑습니다.",
          """
          SELECT
                 COUNT(*) AS TOTAL
               , SUM(CASE WHEN i.STATUS = 'OPEN' THEN 1 ELSE 0 END) AS OPEN_CNT
               , SUM(CASE WHEN i.REG_DT >= TRUNC(SYSDATE, 'MM') THEN 1 ELSE 0 END) AS THIS_MONTH
               , SUM(CASE WHEN i.STATUS = 'OPEN' AND i.REG_DT < TRUNC(SYSDATE) - 30 THEN 1 ELSE 0 END) AS OVER_30D
               , SUM(CASE WHEN i.CLOSE_DT >= TRUNC(SYSDATE) THEN 1 ELSE 0 END) AS CLOSED_TODAY
            FROM ISSUE i""",
          """
          SELECT
                 COUNT(*) AS TOTAL
               , SUM(CASE WHEN i.STATUS = 'OPEN' THEN 1 ELSE 0 END) AS OPEN_CNT
               , SUM(CASE WHEN i.REG_DT >= CAST(DATE_TRUNC('MONTH', CURRENT_DATE) AS DATE) THEN 1 ELSE 0 END) AS THIS_MONTH
               , SUM(CASE WHEN i.STATUS = 'OPEN' AND i.REG_DT < CURRENT_DATE - 30 THEN 1 ELSE 0 END) AS OVER_30D
               , SUM(CASE WHEN i.CLOSE_DT >= CURRENT_DATE THEN 1 ELSE 0 END) AS CLOSED_TODAY
            FROM ISSUE i"""),
      new Example("대시보드 통계", "상태별 건수와 비율 (도넛 차트)", "상태마다 건수와 전체 대비 비율. 공통코드 이름까지 붙입니다.",
          """
          SELECT
                 i.STATUS
               , COUNT(*) AS CNT
               , ROUND(RATIO_TO_REPORT(COUNT(*)) OVER () * 100, 1) AS PCT
            FROM ISSUE i
           GROUP BY
                 i.STATUS
           ORDER BY
                 CNT DESC""",
          """
          SELECT
                 i.STATUS
               , COUNT(*) AS CNT
               , ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1) AS PCT
            FROM ISSUE i
           GROUP BY
                 i.STATUS
           ORDER BY
                 CNT DESC"""),
      new Example("대시보드 통계", "최근 12주 주별 등록 · 완료 (빈 주 0)", "주 달력을 먼저 만들고 등록 · 완료를 붙여 빈 주도 0 으로 그립니다(선 차트).",
          """
          WITH w AS (
          SELECT
                 TRUNC(SYSDATE, 'IW') - (LEVEL - 1) * 7 AS WK
            FROM DUAL
          CONNECT BY LEVEL <= 12
          )
          SELECT
                 TO_CHAR(w.WK, 'YYYY-MM-DD') AS WEEK_START
               , (SELECT COUNT(*) FROM ISSUE i WHERE i.REG_DT >= w.WK AND i.REG_DT < w.WK + 7) AS REGISTERED
               , (SELECT COUNT(*) FROM ISSUE i WHERE i.CLOSE_DT >= w.WK AND i.CLOSE_DT < w.WK + 7) AS CLOSED
            FROM w
           ORDER BY
                 w.WK""",
          """
          WITH w AS (
          SELECT
                 CAST(DATE_TRUNC('WEEK', CURRENT_DATE) AS DATE) - g * 7 AS WK
            FROM GENERATE_SERIES(0, 11) g
          )
          SELECT
                 TO_CHAR(w.WK, 'YYYY-MM-DD') AS WEEK_START
               , (SELECT COUNT(*) FROM ISSUE i WHERE i.REG_DT >= w.WK AND i.REG_DT < w.WK + 7) AS REGISTERED
               , (SELECT COUNT(*) FROM ISSUE i WHERE i.CLOSE_DT >= w.WK AND i.CLOSE_DT < w.WK + 7) AS CLOSED
            FROM w
           ORDER BY
                 w.WK"""),
      new Example("대시보드 통계", "날마다 열려 있던 건수 (미결 추이 · 번다운)", "그날 기준 등록됐고 아직 안 닫힌 건수를 날마다 셉니다.",
          """
          WITH d AS (
          SELECT
                 TRUNC(SYSDATE) - LEVEL + 1 AS DT
            FROM DUAL
          CONNECT BY LEVEL <= 60
          )
          SELECT
                 d.DT
               , (SELECT COUNT(*)
                    FROM ISSUE i
                   WHERE i.REG_DT <= d.DT
                     AND (i.CLOSE_DT IS NULL OR i.CLOSE_DT > d.DT)) AS OPEN_CNT
            FROM d
           ORDER BY
                 d.DT""",
          """
          WITH d AS (
          SELECT
                 CURRENT_DATE - g AS DT
            FROM GENERATE_SERIES(0, 59) g
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
      new Example("대시보드 통계", "담당자별 업무량 순위 TOP 10", "열린 이슈 수로 순위를 매기고 위 10명.",
          """
          SELECT
                 x.*
            FROM (
                  SELECT
                         u.USER_NAME
                       , COUNT(*) AS OPEN_CNT
                       , RANK() OVER (ORDER BY COUNT(*) DESC) AS RNK
                    FROM ISSUE i, USERS u
                   WHERE i.OWNER_ID = u.USER_ID
                     AND i.STATUS = 'OPEN'
                   GROUP BY
                         u.USER_NAME
                 ) x
           WHERE x.RNK <= 10
           ORDER BY
                 x.RNK""",
          """
          SELECT
                 x.*
            FROM (
                  SELECT
                         u.USER_NAME
                       , COUNT(*) AS OPEN_CNT
                       , RANK() OVER (ORDER BY COUNT(*) DESC) AS RNK
                    FROM ISSUE i, USERS u
                   WHERE i.OWNER_ID = u.USER_ID
                     AND i.STATUS = 'OPEN'
                   GROUP BY
                         u.USER_NAME
                 ) x
           WHERE x.RNK <= 10
           ORDER BY
                 x.RNK"""),
      new Example("대시보드 통계", "위 5개 + 나머지는 「기타」로 묶기", "막대 · 파이 차트에서 항목이 많을 때 상위 5개만 두고 나머지를 하나로.",
          """
          SELECT
                 CASE WHEN x.RN <= 5 THEN x.PART_NO ELSE '기타' END AS PART_NO
               , SUM(x.CNT) AS CNT
            FROM (
                  SELECT
                         i.PART_NO
                       , COUNT(*) AS CNT
                       , ROW_NUMBER() OVER (ORDER BY COUNT(*) DESC) AS RN
                    FROM ISSUE i
                   GROUP BY
                         i.PART_NO
                 ) x
           GROUP BY
                 CASE WHEN x.RN <= 5 THEN x.PART_NO ELSE '기타' END
           ORDER BY
                 MIN(x.RN)""",
          """
          SELECT
                 CASE WHEN x.RN <= 5 THEN x.PART_NO ELSE '기타' END AS PART_NO
               , SUM(x.CNT) AS CNT
            FROM (
                  SELECT
                         i.PART_NO
                       , COUNT(*) AS CNT
                       , ROW_NUMBER() OVER (ORDER BY COUNT(*) DESC) AS RN
                    FROM ISSUE i
                   GROUP BY
                         i.PART_NO
                 ) x
           GROUP BY
                 CASE WHEN x.RN <= 5 THEN x.PART_NO ELSE '기타' END
           ORDER BY
                 MIN(x.RN)"""),
      new Example("대시보드 통계", "등급 × 상태 가로표 (매트릭스 표)", "등급이 줄, 상태가 칸. 맨 끝 칸과 맨 아래 줄에 합계.",
          """
          SELECT
                 NVL(i.SEVERITY, '합계') AS SEVERITY
               , SUM(CASE WHEN i.STATUS = 'OPEN' THEN 1 ELSE 0 END) AS OPEN_CNT
               , SUM(CASE WHEN i.STATUS = 'CLOSE' THEN 1 ELSE 0 END) AS CLOSE_CNT
               , COUNT(*) AS TOTAL
            FROM ISSUE i
           GROUP BY
                 ROLLUP(i.SEVERITY)
           ORDER BY
                 GROUPING(i.SEVERITY)
               , i.SEVERITY""",
          """
          SELECT
                 COALESCE(i.SEVERITY, '합계') AS SEVERITY
               , SUM(CASE WHEN i.STATUS = 'OPEN' THEN 1 ELSE 0 END) AS OPEN_CNT
               , SUM(CASE WHEN i.STATUS = 'CLOSE' THEN 1 ELSE 0 END) AS CLOSE_CNT
               , COUNT(*) AS TOTAL
            FROM ISSUE i
           GROUP BY
                 ROLLUP(i.SEVERITY)
           ORDER BY
                 GROUPING(i.SEVERITY)
               , i.SEVERITY"""),
      new Example("대시보드 통계", "경과일 구간별 분포 (0~7 · 8~30 · 31~90 · 90일 넘음)", "열린 건이 얼마나 오래 묵었는지 구간으로.",
          """
          SELECT
                 CASE
                   WHEN TRUNC(SYSDATE) - i.REG_DT <= 7 THEN '1. 0~7일'
                   WHEN TRUNC(SYSDATE) - i.REG_DT <= 30 THEN '2. 8~30일'
                   WHEN TRUNC(SYSDATE) - i.REG_DT <= 90 THEN '3. 31~90일'
                   ELSE '4. 90일 넘음'
                 END AS AGE
               , COUNT(*) AS CNT
            FROM ISSUE i
           WHERE i.STATUS = 'OPEN'
           GROUP BY
                 CASE
                   WHEN TRUNC(SYSDATE) - i.REG_DT <= 7 THEN '1. 0~7일'
                   WHEN TRUNC(SYSDATE) - i.REG_DT <= 30 THEN '2. 8~30일'
                   WHEN TRUNC(SYSDATE) - i.REG_DT <= 90 THEN '3. 31~90일'
                   ELSE '4. 90일 넘음'
                 END
           ORDER BY
                 1""",
          """
          SELECT
                 CASE
                   WHEN CURRENT_DATE - i.REG_DT <= 7 THEN '1. 0~7일'
                   WHEN CURRENT_DATE - i.REG_DT <= 30 THEN '2. 8~30일'
                   WHEN CURRENT_DATE - i.REG_DT <= 90 THEN '3. 31~90일'
                   ELSE '4. 90일 넘음'
                 END AS AGE
               , COUNT(*) AS CNT
            FROM ISSUE i
           WHERE i.STATUS = 'OPEN'
           GROUP BY
                 1
           ORDER BY
                 1"""),
      new Example("대시보드 통계", "월별 처리 시간 추이 (평균 · 중앙값 · 90%)", "닫힌 건의 처리 날 수를 월별로. 평균은 몇 건 때문에 튀므로 중앙값과 90% 값도 같이.",
          """
          SELECT
                 TO_CHAR(i.CLOSE_DT, 'YYYY-MM') AS YM
               , COUNT(*) AS CNT
               , ROUND(AVG(i.CLOSE_DT - i.REG_DT), 1) AS AVG_DAYS
               , MEDIAN(i.CLOSE_DT - i.REG_DT) AS MEDIAN_DAYS
               , PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY i.CLOSE_DT - i.REG_DT) AS P90_DAYS
            FROM ISSUE i
           WHERE i.CLOSE_DT IS NOT NULL
           GROUP BY
                 TO_CHAR(i.CLOSE_DT, 'YYYY-MM')
           ORDER BY
                 1""",
          """
          SELECT
                 TO_CHAR(i.CLOSE_DT, 'YYYY-MM') AS YM
               , COUNT(*) AS CNT
               , ROUND(AVG(i.CLOSE_DT - i.REG_DT), 1) AS AVG_DAYS
               , PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY i.CLOSE_DT - i.REG_DT) AS MEDIAN_DAYS
               , PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY i.CLOSE_DT - i.REG_DT) AS P90_DAYS
            FROM ISSUE i
           WHERE i.CLOSE_DT IS NOT NULL
           GROUP BY
                 TO_CHAR(i.CLOSE_DT, 'YYYY-MM')
           ORDER BY
                 1"""),
      new Example("대시보드 통계", "월별 지연률 추이", "그달 끝나야 했던 일정 중 늦게 끝났거나 아직 안 끝난 비율.",
          """
          SELECT
                 TO_CHAR(s.PLAN_END, 'YYYY-MM') AS YM
               , COUNT(*) AS DUE
               , SUM(CASE WHEN NVL(s.ACT_END, TRUNC(SYSDATE)) > s.PLAN_END THEN 1 ELSE 0 END) AS LATE
               , ROUND(SUM(CASE WHEN NVL(s.ACT_END, TRUNC(SYSDATE)) > s.PLAN_END THEN 1 ELSE 0 END) * 100 / COUNT(*), 1) AS LATE_PCT
            FROM SCHEDULE s
           WHERE s.PLAN_END < TRUNC(SYSDATE)
           GROUP BY
                 TO_CHAR(s.PLAN_END, 'YYYY-MM')
           ORDER BY
                 1""",
          """
          SELECT
                 TO_CHAR(s.PLAN_END, 'YYYY-MM') AS YM
               , COUNT(*) AS DUE
               , SUM(CASE WHEN COALESCE(s.ACT_END, CURRENT_DATE) > s.PLAN_END THEN 1 ELSE 0 END) AS LATE
               , ROUND(SUM(CASE WHEN COALESCE(s.ACT_END, CURRENT_DATE) > s.PLAN_END THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 1) AS LATE_PCT
            FROM SCHEDULE s
           WHERE s.PLAN_END < CURRENT_DATE
           GROUP BY
                 TO_CHAR(s.PLAN_END, 'YYYY-MM')
           ORDER BY
                 1"""),
      new Example("대시보드 통계", "계획 누적 vs 실적 누적 (S 곡선)", "날짜마다 그날까지 끝나야 할 일정 수와 실제 끝난 수를 누적으로. 두 선 사이가 밀린 양입니다.",
          """
          WITH d AS (
          SELECT
                 TO_DATE(:fromDt, 'YYYY-MM-DD') + LEVEL - 1 AS DT
            FROM DUAL
          CONNECT BY LEVEL <= TO_DATE(:toDt, 'YYYY-MM-DD') - TO_DATE(:fromDt, 'YYYY-MM-DD') + 1
          )
          SELECT
                 d.DT
               , (SELECT COUNT(*) FROM SCHEDULE s WHERE s.PROJ_ID = :projId AND s.PLAN_END <= d.DT) AS PLAN_CUM
               , (SELECT COUNT(*) FROM SCHEDULE s WHERE s.PROJ_ID = :projId AND s.ACT_END <= d.DT) AS ACT_CUM
            FROM d
           ORDER BY
                 d.DT""",
          """
          WITH d AS (
          SELECT
                 CAST(g AS DATE) AS DT
            FROM GENERATE_SERIES(TO_DATE(:fromDt, 'YYYY-MM-DD'), TO_DATE(:toDt, 'YYYY-MM-DD'), INTERVAL '1 DAY') g
          )
          SELECT
                 d.DT
               , (SELECT COUNT(*) FROM SCHEDULE s WHERE s.PROJ_ID = :projId AND s.PLAN_END <= d.DT) AS PLAN_CUM
               , (SELECT COUNT(*) FROM SCHEDULE s WHERE s.PROJ_ID = :projId AND s.ACT_END <= d.DT) AS ACT_CUM
            FROM d
           ORDER BY
                 d.DT"""),
      new Example("대시보드 통계", "오늘 · 어제 · 지난주 같은 요일 비교", "같은 지표를 세 날짜로 나란히. 요일 영향을 빼고 보려고 지난주 같은 요일을 씁니다.",
          """
          SELECT
                 SUM(CASE WHEN i.REG_DT = TRUNC(SYSDATE) THEN 1 ELSE 0 END) AS TODAY
               , SUM(CASE WHEN i.REG_DT = TRUNC(SYSDATE) - 1 THEN 1 ELSE 0 END) AS YESTERDAY
               , SUM(CASE WHEN i.REG_DT = TRUNC(SYSDATE) - 7 THEN 1 ELSE 0 END) AS LAST_WEEK_SAME_DAY
            FROM ISSUE i
           WHERE i.REG_DT >= TRUNC(SYSDATE) - 7""",
          """
          SELECT
                 SUM(CASE WHEN i.REG_DT = CURRENT_DATE THEN 1 ELSE 0 END) AS TODAY
               , SUM(CASE WHEN i.REG_DT = CURRENT_DATE - 1 THEN 1 ELSE 0 END) AS YESTERDAY
               , SUM(CASE WHEN i.REG_DT = CURRENT_DATE - 7 THEN 1 ELSE 0 END) AS LAST_WEEK_SAME_DAY
            FROM ISSUE i
           WHERE i.REG_DT >= CURRENT_DATE - 7"""),
      new Example("대시보드 통계", "이번 달 vs 지난달 증감과 화살표", "두 달 값을 나란히 놓고 증감 · 증감률 · ▲▼ 표시.",
          """
          SELECT
                 x.THIS_M
               , x.LAST_M
               , x.THIS_M - x.LAST_M AS DIFF
               , ROUND((x.THIS_M - x.LAST_M) * 100 / NULLIF(x.LAST_M, 0), 1) AS DIFF_PCT
               , CASE SIGN(x.THIS_M - x.LAST_M) WHEN 1 THEN '▲' WHEN -1 THEN '▼' ELSE '-' END AS ARROW
            FROM (
                  SELECT
                         SUM(CASE WHEN i.REG_DT >= TRUNC(SYSDATE, 'MM') THEN 1 ELSE 0 END) AS THIS_M
                       , SUM(CASE WHEN i.REG_DT < TRUNC(SYSDATE, 'MM') THEN 1 ELSE 0 END) AS LAST_M
                    FROM ISSUE i
                   WHERE i.REG_DT >= ADD_MONTHS(TRUNC(SYSDATE, 'MM'), -1)
                 ) x""",
          """
          SELECT
                 x.THIS_M
               , x.LAST_M
               , x.THIS_M - x.LAST_M AS DIFF
               , ROUND((x.THIS_M - x.LAST_M) * 100.0 / NULLIF(x.LAST_M, 0), 1) AS DIFF_PCT
               , CASE SIGN(x.THIS_M - x.LAST_M) WHEN 1 THEN '▲' WHEN -1 THEN '▼' ELSE '-' END AS ARROW
            FROM (
                  SELECT
                         SUM(CASE WHEN i.REG_DT >= DATE_TRUNC('MONTH', CURRENT_DATE) THEN 1 ELSE 0 END) AS THIS_M
                       , SUM(CASE WHEN i.REG_DT < DATE_TRUNC('MONTH', CURRENT_DATE) THEN 1 ELSE 0 END) AS LAST_M
                    FROM ISSUE i
                   WHERE i.REG_DT >= DATE_TRUNC('MONTH', CURRENT_DATE) - INTERVAL '1 MONTH'
                 ) x"""),
      new Example("대시보드 통계", "부서 · 담당자 소계와 총계가 있는 표", "부서별 소계, 맨 아래 총계를 한 번에(ROLLUP). 소계 줄에는 「소계」 글자를.",
          """
          SELECT
                 CASE WHEN GROUPING(u.DEPT_ID) = 1 THEN '총계' ELSE TO_CHAR(u.DEPT_ID) END AS DEPT
               , CASE WHEN GROUPING(u.USER_NAME) = 1 AND GROUPING(u.DEPT_ID) = 0 THEN '소계' ELSE u.USER_NAME END AS OWNER
               , COUNT(*) AS CNT
               , SUM(CASE WHEN i.STATUS = 'OPEN' THEN 1 ELSE 0 END) AS OPEN_CNT
            FROM ISSUE i, USERS u
           WHERE i.OWNER_ID = u.USER_ID
           GROUP BY
                 ROLLUP(u.DEPT_ID, u.USER_NAME)
           ORDER BY
                 GROUPING(u.DEPT_ID)
               , u.DEPT_ID
               , GROUPING(u.USER_NAME)
               , u.USER_NAME""",
          """
          SELECT
                 CASE WHEN GROUPING(u.DEPT_ID) = 1 THEN '총계' ELSE CAST(u.DEPT_ID AS VARCHAR) END AS DEPT
               , CASE WHEN GROUPING(u.USER_NAME) = 1 AND GROUPING(u.DEPT_ID) = 0 THEN '소계' ELSE u.USER_NAME END AS OWNER
               , COUNT(*) AS CNT
               , SUM(CASE WHEN i.STATUS = 'OPEN' THEN 1 ELSE 0 END) AS OPEN_CNT
            FROM ISSUE i, USERS u
           WHERE i.OWNER_ID = u.USER_ID
           GROUP BY
                 ROLLUP(u.DEPT_ID, u.USER_NAME)
           ORDER BY
                 GROUPING(u.DEPT_ID)
               , u.DEPT_ID
               , GROUPING(u.USER_NAME)
               , u.USER_NAME"""),
      new Example("대시보드 통계", "프로젝트마다 지금 단계 (안 끝난 첫 단계)", "단계가 순서대로일 때 아직 안 끝난 가장 앞 단계가 지금 위치입니다.",
          """
          SELECT
                 x.PROJ_ID
               , x.STEP_SEQ
               , x.STEP_NAME
               , x.PLAN_DT
            FROM (
                  SELECT
                         s.*
                       , ROW_NUMBER() OVER (PARTITION BY s.PROJ_ID ORDER BY s.STEP_SEQ) AS RN
                    FROM APQP_STEP s
                   WHERE s.DONE_DT IS NULL
                 ) x
           WHERE x.RN = 1""",
          """
          SELECT
                 x.PROJ_ID
               , x.STEP_SEQ
               , x.STEP_NAME
               , x.PLAN_DT
            FROM (
                  SELECT
                         s.*
                       , ROW_NUMBER() OVER (PARTITION BY s.PROJ_ID ORDER BY s.STEP_SEQ) AS RN
                    FROM APQP_STEP s
                   WHERE s.DONE_DT IS NULL
                 ) x
           WHERE x.RN = 1"""),
      new Example("대시보드 통계", "차종별 프로젝트 · 부품 · 열린 이슈 수", "차종 한 줄에 여러 표의 건수를 따로 묶어 붙입니다(먼저 묶고 붙여야 곱해지지 않음).",
          """
          SELECT
                 p.CAR_TYPE
               , COUNT(*) AS PROJECTS
               , SUM(NVL(s.PARTS, 0)) AS PARTS
               , SUM(NVL(i.OPEN_CNT, 0)) AS OPEN_ISSUES
            FROM PROJECT p
               , (SELECT x.PROJ_ID, COUNT(DISTINCT x.PART_NO) AS PARTS FROM SCHEDULE x GROUP BY x.PROJ_ID) s
               , (SELECT y.PROJ_ID, COUNT(*) AS OPEN_CNT FROM ISSUE y WHERE y.STATUS = 'OPEN' GROUP BY y.PROJ_ID) i
           WHERE p.PROJ_ID = s.PROJ_ID(+)
             AND p.PROJ_ID = i.PROJ_ID(+)
           GROUP BY
                 p.CAR_TYPE""",
          """
          SELECT
                 p.CAR_TYPE
               , COUNT(*) AS PROJECTS
               , SUM(COALESCE(s.PARTS, 0)) AS PARTS
               , SUM(COALESCE(i.OPEN_CNT, 0)) AS OPEN_ISSUES
            FROM PROJECT p
            LEFT OUTER JOIN (SELECT x.PROJ_ID, COUNT(DISTINCT x.PART_NO) AS PARTS FROM SCHEDULE x GROUP BY x.PROJ_ID) s
              ON p.PROJ_ID = s.PROJ_ID
            LEFT OUTER JOIN (SELECT y.PROJ_ID, COUNT(*) AS OPEN_CNT FROM ISSUE y WHERE y.STATUS = 'OPEN' GROUP BY y.PROJ_ID) i
              ON p.PROJ_ID = i.PROJ_ID
           GROUP BY
                 p.CAR_TYPE"""),
      new Example("대시보드 통계", "이번 달 마감 예정 대비 완료", "이번 달 계획 끝일인 일정 중 끝난 것 · 남은 것 · 남은 날.",
          """
          SELECT
                 COUNT(*) AS DUE_THIS_MONTH
               , COUNT(s.ACT_END) AS DONE
               , COUNT(*) - COUNT(s.ACT_END) AS REMAIN
               , LAST_DAY(TRUNC(SYSDATE)) - TRUNC(SYSDATE) AS DAYS_LEFT
            FROM SCHEDULE s
           WHERE s.PLAN_END >= TRUNC(SYSDATE, 'MM')
             AND s.PLAN_END < ADD_MONTHS(TRUNC(SYSDATE, 'MM'), 1)""",
          """
          SELECT
                 COUNT(*) AS DUE_THIS_MONTH
               , COUNT(s.ACT_END) AS DONE
               , COUNT(*) - COUNT(s.ACT_END) AS REMAIN
               , CAST(DATE_TRUNC('MONTH', CURRENT_DATE) + INTERVAL '1 MONTH' AS DATE) - 1 - CURRENT_DATE AS DAYS_LEFT
            FROM SCHEDULE s
           WHERE s.PLAN_END >= DATE_TRUNC('MONTH', CURRENT_DATE)
             AND s.PLAN_END < DATE_TRUNC('MONTH', CURRENT_DATE) + INTERVAL '1 MONTH'"""),
      new Example("대시보드 통계", "월별 등록 · 완료 · 누적 미결", "달마다 들어온 것, 끝난 것, 그달 말 기준 남은 것(누적).",
          """
          SELECT
                 x.YM
               , SUM(x.REG) AS REGISTERED
               , SUM(x.CLS) AS CLOSED
               , SUM(SUM(x.REG) - SUM(x.CLS)) OVER (ORDER BY x.YM) AS BACKLOG
            FROM (
                  SELECT TO_CHAR(i.REG_DT, 'YYYY-MM') AS YM, 1 AS REG, 0 AS CLS FROM ISSUE i
                   UNION ALL
                  SELECT TO_CHAR(i.CLOSE_DT, 'YYYY-MM'), 0, 1 FROM ISSUE i WHERE i.CLOSE_DT IS NOT NULL
                 ) x
           GROUP BY
                 x.YM
           ORDER BY
                 x.YM""",
          """
          SELECT
                 x.YM
               , SUM(x.REG) AS REGISTERED
               , SUM(x.CLS) AS CLOSED
               , SUM(SUM(x.REG) - SUM(x.CLS)) OVER (ORDER BY x.YM) AS BACKLOG
            FROM (
                  SELECT TO_CHAR(i.REG_DT, 'YYYY-MM') AS YM, 1 AS REG, 0 AS CLS FROM ISSUE i
                   UNION ALL
                  SELECT TO_CHAR(i.CLOSE_DT, 'YYYY-MM'), 0, 1 FROM ISSUE i WHERE i.CLOSE_DT IS NOT NULL
                 ) x
           GROUP BY
                 x.YM
           ORDER BY
                 x.YM"""),
      new Example("대시보드 통계", "처리 기한(SLA) 지킨 비율 (등급별)", "등급마다 정한 처리 일수 안에 닫힌 비율. 등급별 기한은 CASE 로 둡니다.",
          """
          SELECT
                 i.SEVERITY
               , COUNT(*) AS CLOSED_CNT
               , SUM(CASE WHEN i.CLOSE_DT - i.REG_DT <= CASE i.SEVERITY WHEN 'A' THEN 3 WHEN 'B' THEN 7 ELSE 14 END THEN 1 ELSE 0 END) AS IN_SLA
               , ROUND(SUM(CASE WHEN i.CLOSE_DT - i.REG_DT <= CASE i.SEVERITY WHEN 'A' THEN 3 WHEN 'B' THEN 7 ELSE 14 END THEN 1 ELSE 0 END) * 100 / COUNT(*), 1) AS SLA_PCT
            FROM ISSUE i
           WHERE i.CLOSE_DT IS NOT NULL
           GROUP BY
                 i.SEVERITY""",
          """
          SELECT
                 i.SEVERITY
               , COUNT(*) AS CLOSED_CNT
               , SUM(CASE WHEN i.CLOSE_DT - i.REG_DT <= CASE i.SEVERITY WHEN 'A' THEN 3 WHEN 'B' THEN 7 ELSE 14 END THEN 1 ELSE 0 END) AS IN_SLA
               , ROUND(SUM(CASE WHEN i.CLOSE_DT - i.REG_DT <= CASE i.SEVERITY WHEN 'A' THEN 3 WHEN 'B' THEN 7 ELSE 14 END THEN 1 ELSE 0 END) * 100 / COUNT(*), 1) AS SLA_PCT
            FROM ISSUE i
           WHERE i.CLOSE_DT IS NOT NULL
           GROUP BY
                 i.SEVERITY"""),
      new Example("대시보드 통계", "담당자 순위 변화 (지난달 순위 → 이번 달 순위)", "이번 달 처리 건수 순위와 지난달 순위를 나란히, 오른 · 내린 칸까지.",
          """
          SELECT
                 t.OWNER_ID
               , t.CNT AS THIS_CNT
               , t.RNK AS THIS_RNK
               , l.RNK AS LAST_RNK
               , l.RNK - t.RNK AS UP
            FROM (SELECT i.OWNER_ID, COUNT(*) AS CNT, RANK() OVER (ORDER BY COUNT(*) DESC) AS RNK FROM ISSUE i WHERE i.CLOSE_DT >= TRUNC(SYSDATE, 'MM') GROUP BY i.OWNER_ID) t
               , (SELECT i.OWNER_ID, RANK() OVER (ORDER BY COUNT(*) DESC) AS RNK FROM ISSUE i WHERE i.CLOSE_DT >= ADD_MONTHS(TRUNC(SYSDATE, 'MM'), -1) AND i.CLOSE_DT < TRUNC(SYSDATE, 'MM') GROUP BY i.OWNER_ID) l
           WHERE t.OWNER_ID = l.OWNER_ID(+)
           ORDER BY
                 t.RNK""",
          """
          SELECT
                 t.OWNER_ID
               , t.CNT AS THIS_CNT
               , t.RNK AS THIS_RNK
               , l.RNK AS LAST_RNK
               , l.RNK - t.RNK AS UP
            FROM (SELECT i.OWNER_ID, COUNT(*) AS CNT, RANK() OVER (ORDER BY COUNT(*) DESC) AS RNK FROM ISSUE i WHERE i.CLOSE_DT >= DATE_TRUNC('MONTH', CURRENT_DATE) GROUP BY i.OWNER_ID) t
            LEFT OUTER JOIN (SELECT i.OWNER_ID, RANK() OVER (ORDER BY COUNT(*) DESC) AS RNK FROM ISSUE i WHERE i.CLOSE_DT >= DATE_TRUNC('MONTH', CURRENT_DATE) - INTERVAL '1 MONTH' AND i.CLOSE_DT < DATE_TRUNC('MONTH', CURRENT_DATE) GROUP BY i.OWNER_ID) l
              ON t.OWNER_ID = l.OWNER_ID
           ORDER BY
                 t.RNK"""),
      new Example("대시보드 통계", "히트맵 (요일 × 담당자 처리 건수)", "담당자를 줄로, 요일을 칸으로. 색을 칠해 쏠림을 봅니다.",
          """
          SELECT
                 i.OWNER_ID
               , SUM(CASE WHEN TO_CHAR(i.CLOSE_DT, 'D') = '2' THEN 1 ELSE 0 END) AS MON
               , SUM(CASE WHEN TO_CHAR(i.CLOSE_DT, 'D') = '3' THEN 1 ELSE 0 END) AS TUE
               , SUM(CASE WHEN TO_CHAR(i.CLOSE_DT, 'D') = '4' THEN 1 ELSE 0 END) AS WED
               , SUM(CASE WHEN TO_CHAR(i.CLOSE_DT, 'D') = '5' THEN 1 ELSE 0 END) AS THU
               , SUM(CASE WHEN TO_CHAR(i.CLOSE_DT, 'D') = '6' THEN 1 ELSE 0 END) AS FRI
            FROM ISSUE i
           WHERE i.CLOSE_DT IS NOT NULL
           GROUP BY
                 i.OWNER_ID""",
          """
          SELECT
                 i.OWNER_ID
               , SUM(CASE WHEN EXTRACT(ISODOW FROM i.CLOSE_DT) = 1 THEN 1 ELSE 0 END) AS MON
               , SUM(CASE WHEN EXTRACT(ISODOW FROM i.CLOSE_DT) = 2 THEN 1 ELSE 0 END) AS TUE
               , SUM(CASE WHEN EXTRACT(ISODOW FROM i.CLOSE_DT) = 3 THEN 1 ELSE 0 END) AS WED
               , SUM(CASE WHEN EXTRACT(ISODOW FROM i.CLOSE_DT) = 4 THEN 1 ELSE 0 END) AS THU
               , SUM(CASE WHEN EXTRACT(ISODOW FROM i.CLOSE_DT) = 5 THEN 1 ELSE 0 END) AS FRI
            FROM ISSUE i
           WHERE i.CLOSE_DT IS NOT NULL
           GROUP BY
                 i.OWNER_ID"""),
      new Example("대시보드 통계", "상태마다 머문 시간 (감사 로그로 상태 이력)", "상태 칸 변경 이력에서 다음 변경까지 걸린 시간을 상태별로 평균 냅니다.",
          """
          SELECT
                 x.NEW_VAL AS STATUS
               , COUNT(*) AS CNT
               , ROUND(AVG((CAST(x.NEXT_TS AS DATE) - CAST(x.LOG_TS AS DATE)) * 24), 1) AS AVG_HOURS
            FROM (
                  SELECT
                         a.PK_VAL
                       , a.NEW_VAL
                       , a.LOG_TS
                       , LEAD(a.LOG_TS) OVER (PARTITION BY a.TABLE_NAME, a.PK_VAL ORDER BY a.LOG_TS) AS NEXT_TS
                    FROM AUDIT_LOG a
                   WHERE a.TABLE_NAME = 'ISSUE'
                     AND a.COL_NAME = 'STATUS'
                 ) x
           WHERE x.NEXT_TS IS NOT NULL
           GROUP BY
                 x.NEW_VAL""",
          """
          SELECT
                 x.NEW_VAL AS STATUS
               , COUNT(*) AS CNT
               , ROUND(CAST(AVG(EXTRACT(EPOCH FROM (x.NEXT_TS - x.LOG_TS))) / 3600 AS NUMERIC), 1) AS AVG_HOURS
            FROM (
                  SELECT
                         a.PK_VAL
                       , a.NEW_VAL
                       , a.LOG_TS
                       , LEAD(a.LOG_TS) OVER (PARTITION BY a.TABLE_NAME, a.PK_VAL ORDER BY a.LOG_TS) AS NEXT_TS
                    FROM AUDIT_LOG a
                   WHERE a.TABLE_NAME = 'ISSUE'
                     AND a.COL_NAME = 'STATUS'
                 ) x
           WHERE x.NEXT_TS IS NOT NULL
           GROUP BY
                 x.NEW_VAL"""),
      new Example("대시보드 통계", "목표선과 함께 (월 실적 · 목표 · 달성 여부)", "부서 · 달마다 실적과 목표를 붙이고 달성 표시.",
          """
          SELECT
                 t.YM
               , t.DEPT_ID
               , t.TARGET_AMT
               , NVL(SUM(s.QTY * s.PRICE), 0) AS ACTUAL
               , CASE WHEN NVL(SUM(s.QTY * s.PRICE), 0) >= t.TARGET_AMT THEN '달성' ELSE '미달' END AS RESULT
            FROM SALES_TARGET t
               , SALES_ORDER s
           WHERE t.DEPT_ID = s.DEPT_ID(+)
             AND t.YM = TO_CHAR(s.SO_DT(+), 'YYYY-MM')
           GROUP BY
                 t.YM
               , t.DEPT_ID
               , t.TARGET_AMT
           ORDER BY
                 t.YM
               , t.DEPT_ID""",
          """
          SELECT
                 t.YM
               , t.DEPT_ID
               , t.TARGET_AMT
               , COALESCE(SUM(s.QTY * s.PRICE), 0) AS ACTUAL
               , CASE WHEN COALESCE(SUM(s.QTY * s.PRICE), 0) >= t.TARGET_AMT THEN '달성' ELSE '미달' END AS RESULT
            FROM SALES_TARGET t
            LEFT OUTER JOIN SALES_ORDER s
              ON t.DEPT_ID = s.DEPT_ID
             AND t.YM = TO_CHAR(s.SO_DT, 'YYYY-MM')
           GROUP BY
                 t.YM
               , t.DEPT_ID
               , t.TARGET_AMT
           ORDER BY
                 t.YM
               , t.DEPT_ID"""),
      new Example("거슬러 올라가는 통계", "이슈 → 부품 → 프로젝트 → 부서 단계별 소계", "맨 아래 이슈에서 출발해 부품, 프로젝트, 담당 부서로 올라가며 각 단계 소계와 총계를 냅니다.",
          """
          SELECT
                 p.DEPT_ID
               , i.PROJ_ID
               , i.PART_NO
               , COUNT(*) AS ISSUES
               , SUM(CASE WHEN i.STATUS = 'OPEN' THEN 1 ELSE 0 END) AS OPEN_CNT
               , GROUPING_ID(p.DEPT_ID, i.PROJ_ID, i.PART_NO) AS LVL
            FROM ISSUE i, PROJECT p
           WHERE i.PROJ_ID = p.PROJ_ID
           GROUP BY
                 ROLLUP(p.DEPT_ID, i.PROJ_ID, i.PART_NO)
           ORDER BY
                 p.DEPT_ID
               , i.PROJ_ID
               , i.PART_NO""",
          """
          SELECT
                 p.DEPT_ID
               , i.PROJ_ID
               , i.PART_NO
               , COUNT(*) AS ISSUES
               , SUM(CASE WHEN i.STATUS = 'OPEN' THEN 1 ELSE 0 END) AS OPEN_CNT
               , GROUPING(p.DEPT_ID, i.PROJ_ID, i.PART_NO) AS LVL
            FROM ISSUE i, PROJECT p
           WHERE i.PROJ_ID = p.PROJ_ID
           GROUP BY
                 ROLLUP(p.DEPT_ID, i.PROJ_ID, i.PART_NO)
           ORDER BY
                 p.DEPT_ID
               , i.PROJ_ID
               , i.PART_NO"""),
      new Example("거슬러 올라가는 통계", "부서마다 아래 부서까지 합친 이슈 수 (트리 위로 쌓기)", "부서 트리에서 각 부서를 시작점으로 아래를 펼친 뒤, 시작 부서별로 묶으면 하위 부서 건수가 위로 쌓입니다.",
          """
          SELECT
                 x.ROOT_DEPT
               , d.DEPT_NAME
               , COUNT(i.ISSUE_ID) AS ISSUES_WITH_SUB
            FROM (
                  SELECT
                         CONNECT_BY_ROOT t.DEPT_ID AS ROOT_DEPT
                       , t.DEPT_ID
                    FROM DEPT t
                 CONNECT BY PRIOR t.DEPT_ID = t.PARENT_DEPT_ID
                 ) x
               , DEPT d
               , PROJECT p
               , ISSUE i
           WHERE x.ROOT_DEPT = d.DEPT_ID
             AND x.DEPT_ID = p.DEPT_ID(+)
             AND p.PROJ_ID = i.PROJ_ID(+)
           GROUP BY
                 x.ROOT_DEPT
               , d.DEPT_NAME
           ORDER BY
                 x.ROOT_DEPT""",
          """
          WITH RECURSIVE x AS (
          SELECT
                 t.DEPT_ID AS ROOT_DEPT
               , t.DEPT_ID
               , 1 AS LVL
            FROM DEPT t
           UNION ALL
          SELECT
                 x.ROOT_DEPT
               , t.DEPT_ID
               , x.LVL + 1
            FROM DEPT t, x
           WHERE t.PARENT_DEPT_ID = x.DEPT_ID
             AND x.LVL < 20
          )
          SELECT
                 x.ROOT_DEPT
               , d.DEPT_NAME
               , COUNT(i.ISSUE_ID) AS ISSUES_WITH_SUB
            FROM DEPT d
               , x
            LEFT OUTER JOIN PROJECT p
              ON x.DEPT_ID = p.DEPT_ID
            LEFT OUTER JOIN ISSUE i
              ON p.PROJ_ID = i.PROJ_ID
           WHERE x.ROOT_DEPT = d.DEPT_ID
           GROUP BY
                 x.ROOT_DEPT
               , d.DEPT_NAME
           ORDER BY
                 x.ROOT_DEPT"""),
      new Example("거슬러 올라가는 통계", "불량 실적 → 작업 지시 → 부품 → 완제품별 불량 합", "공정 실적의 불량을 부품으로 묶고, BOM 을 위로 거슬러 올라가 그 부품이 들어가는 완제품마다 합칩니다.",
          """
          WITH ng AS (
          SELECT
                 w.PART_NO
               , SUM(r.NG_QTY) AS NG
            FROM WORK_RESULT r, WORK_ORDER w
           WHERE r.WO_NO = w.WO_NO
           GROUP BY
                 w.PART_NO
          ), up AS (
          SELECT
                 CONNECT_BY_ROOT b.CHILD_PART AS PART_NO
               , b.PARENT_PART
            FROM BOM b
          CONNECT BY NOCYCLE b.CHILD_PART = PRIOR b.PARENT_PART
          )
          SELECT
                 up.PARENT_PART AS PRODUCT
               , SUM(ng.NG) AS NG_FROM_PARTS
            FROM ng, up
           WHERE ng.PART_NO = up.PART_NO
             AND up.PARENT_PART IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM BOM t WHERE t.CHILD_PART = up.PARENT_PART AND t.PARENT_PART IS NOT NULL)
           GROUP BY
                 up.PARENT_PART
           ORDER BY
                 2 DESC""",
          """
          WITH RECURSIVE ng AS (
          SELECT
                 w.PART_NO
               , SUM(r.NG_QTY) AS NG
            FROM WORK_RESULT r, WORK_ORDER w
           WHERE r.WO_NO = w.WO_NO
           GROUP BY
                 w.PART_NO
          ), up AS (
          SELECT
                 b.CHILD_PART AS PART_NO
               , b.PARENT_PART
               , 1 AS LVL
            FROM BOM b
           UNION
          SELECT
                 up.PART_NO
               , b.PARENT_PART
               , up.LVL + 1
            FROM BOM b, up
           WHERE b.CHILD_PART = up.PARENT_PART
             AND up.LVL < 20
          )
          SELECT
                 up.PARENT_PART AS PRODUCT
               , SUM(ng.NG) AS NG_FROM_PARTS
            FROM ng, up
           WHERE ng.PART_NO = up.PART_NO
             AND up.PARENT_PART IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM BOM t WHERE t.CHILD_PART = up.PARENT_PART AND t.PARENT_PART IS NOT NULL)
           GROUP BY
                 up.PARENT_PART
           ORDER BY
                 2 DESC"""),
      new Example("거슬러 올라가는 통계", "자식 표에서 부모를 찾아 맨 위까지 올라가 묶기", "메뉴 하나하나(자식)에서 출발해 부모를 거슬러 최상위 메뉴를 찾고, 최상위 메뉴별로 권한 수를 셉니다.",
          """
          SELECT
                 x.TOP_MENU
               , COUNT(*) AS GRANTS
            FROM (
                  SELECT
                         m.MENU_ID
                       , CONNECT_BY_ROOT m.MENU_NAME AS TOP_MENU
                    FROM MENU m
                   START WITH m.PARENT_ID IS NULL
                 CONNECT BY NOCYCLE PRIOR m.MENU_ID = m.PARENT_ID
                 ) x
               , ROLE_MENU rm
           WHERE x.MENU_ID = rm.MENU_ID
           GROUP BY
                 x.TOP_MENU""",
          """
          WITH RECURSIVE x AS (
          SELECT
                 m.MENU_ID
               , m.MENU_NAME AS TOP_MENU
               , 1 AS LVL
            FROM MENU m
           WHERE m.PARENT_ID IS NULL
           UNION ALL
          SELECT
                 m.MENU_ID
               , x.TOP_MENU
               , x.LVL + 1
            FROM MENU m, x
           WHERE m.PARENT_ID = x.MENU_ID
             AND x.LVL < 20
          )
          SELECT
                 x.TOP_MENU
               , COUNT(*) AS GRANTS
            FROM x, ROLE_MENU rm
           WHERE x.MENU_ID = rm.MENU_ID
           GROUP BY
                 x.TOP_MENU"""),
      new Example("거슬러 올라가는 통계", "결재 대기 → 문서 → 프로젝트 → 부서 (어디서 막혀 있나)", "대기 중인 결재 줄에서 시작해 결재자의 부서로 올라가 부서별 대기 줄 수 · 문서 수와 총계.",
          """
          SELECT
                 u.DEPT_ID
               , COUNT(*) AS WAITING
               , COUNT(DISTINCT a.DOC_ID) AS DOCS
            FROM APPROVAL a, USERS u
           WHERE a.APPROVER_ID = u.USER_ID
             AND a.STATUS = 'WAIT'
           GROUP BY
                 ROLLUP(u.DEPT_ID)""",
          """
          SELECT
                 u.DEPT_ID
               , COUNT(*) AS WAITING
               , COUNT(DISTINCT a.DOC_ID) AS DOCS
            FROM APPROVAL a, USERS u
           WHERE a.APPROVER_ID = u.USER_ID
             AND a.STATUS = 'WAIT'
           GROUP BY
                 ROLLUP(u.DEPT_ID)"""),
      new Example("거슬러 올라가는 통계", "일정 지연 → 프로젝트 → 부서 → 상위 부서 소계", "지연 일정을 프로젝트 부서로 묶고, 부서 트리를 위로 올라가며 상위 부서 소계까지.",
          """
          SELECT
                 x.ROOT_DEPT
               , COUNT(*) AS LATE_STEPS
            FROM SCHEDULE s
               , PROJECT p
               , (SELECT CONNECT_BY_ROOT t.DEPT_ID AS ROOT_DEPT, t.DEPT_ID FROM DEPT t CONNECT BY PRIOR t.DEPT_ID = t.PARENT_DEPT_ID) x
           WHERE s.PROJ_ID = p.PROJ_ID
             AND p.DEPT_ID = x.DEPT_ID
             AND NVL(s.ACT_END, TRUNC(SYSDATE)) > s.PLAN_END
           GROUP BY
                 x.ROOT_DEPT
           ORDER BY
                 x.ROOT_DEPT""",
          """
          WITH RECURSIVE x AS (
          SELECT
                 t.DEPT_ID AS ROOT_DEPT
               , t.DEPT_ID
               , 1 AS LVL
            FROM DEPT t
           UNION ALL
          SELECT
                 x.ROOT_DEPT
               , t.DEPT_ID
               , x.LVL + 1
            FROM DEPT t, x
           WHERE t.PARENT_DEPT_ID = x.DEPT_ID
             AND x.LVL < 20
          )
          SELECT
                 x.ROOT_DEPT
               , COUNT(*) AS LATE_STEPS
            FROM SCHEDULE s, PROJECT p, x
           WHERE s.PROJ_ID = p.PROJ_ID
             AND p.DEPT_ID = x.DEPT_ID
             AND COALESCE(s.ACT_END, CURRENT_DATE) > s.PLAN_END
           GROUP BY
                 x.ROOT_DEPT
           ORDER BY
                 x.ROOT_DEPT"""),
      new Example("거슬러 올라가는 통계", "참조를 거꾸로 따라가 원천 찾기 (외래 키 역추적 집계)", "출하에서 수주로, 수주에서 고객으로 거꾸로 따라가 고객 등급별 출하량을 셉니다. 각 단계 키를 차례로 잇습니다.",
          """
          SELECT
                 c.GRADE
               , COUNT(DISTINCT so.SO_NO) AS ORDERS
               , SUM(sh.QTY) AS SHIP_QTY
            FROM SHIPMENT sh, SALES_ORDER so, CUSTOMER c
           WHERE sh.SO_NO = so.SO_NO
             AND sh.LINE_NO = so.LINE_NO
             AND so.CUST_ID = c.CUST_ID
           GROUP BY
                 c.GRADE
           ORDER BY
                 SHIP_QTY DESC""",
          """
          SELECT
                 c.GRADE
               , COUNT(DISTINCT so.SO_NO) AS ORDERS
               , SUM(sh.QTY) AS SHIP_QTY
            FROM SHIPMENT sh, SALES_ORDER so, CUSTOMER c
           WHERE sh.SO_NO = so.SO_NO
             AND sh.LINE_NO = so.LINE_NO
             AND so.CUST_ID = c.CUST_ID
           GROUP BY
                 c.GRADE
           ORDER BY
                 SHIP_QTY DESC"""),
      new Example("거슬러 올라가는 통계", "어느 표가 이 표를 참조하나 (외래 키 거꾸로 찾기)", "표 이름을 넣으면 그 표를 부모로 삼는 자식 표와 칼럼. 통계 쿼리를 짤 때 어디까지 따라갈지 고릅니다.",
          """
          SELECT
                 c.TABLE_NAME AS CHILD_TABLE
               , cc.COLUMN_NAME AS CHILD_COLUMN
               , p.TABLE_NAME AS PARENT_TABLE
               , c.CONSTRAINT_NAME
            FROM USER_CONSTRAINTS c, USER_CONSTRAINTS p, USER_CONS_COLUMNS cc
           WHERE c.R_CONSTRAINT_NAME = p.CONSTRAINT_NAME
             AND c.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
             AND c.CONSTRAINT_TYPE = 'R'
             AND p.TABLE_NAME = UPPER(:tableName)
           ORDER BY
                 1, 2""",
          """
          SELECT
                 c.CONRELID::REGCLASS AS CHILD_TABLE
               , a.ATTNAME AS CHILD_COLUMN
               , c.CONFRELID::REGCLASS AS PARENT_TABLE
               , c.CONNAME AS CONSTRAINT_NAME
            FROM PG_CONSTRAINT c, PG_ATTRIBUTE a
           WHERE c.CONTYPE = 'f'
             AND a.ATTRELID = c.CONRELID
             AND a.ATTNUM = ANY (c.CONKEY)
             AND c.CONFRELID = CAST(LOWER(:tableName) AS REGCLASS)
           ORDER BY
                 1, 2"""),
      new Example("거슬러 올라가는 통계", "외래 키 따라 위로 전부 (표 관계 계층)", "한 표에서 시작해 부모 표, 그 부모 표 … 끝까지. 통계를 어느 마스터까지 올려 묶을 수 있는지 봅니다.",
          """
          SELECT
                 LEVEL AS UP
               , c.TABLE_NAME AS CHILD_TABLE
               , p.TABLE_NAME AS PARENT_TABLE
            FROM USER_CONSTRAINTS c, USER_CONSTRAINTS p
           WHERE c.R_CONSTRAINT_NAME = p.CONSTRAINT_NAME
             AND c.CONSTRAINT_TYPE = 'R'
           START WITH c.TABLE_NAME = UPPER(:tableName)
          CONNECT BY NOCYCLE c.TABLE_NAME = PRIOR p.TABLE_NAME""",
          """
          WITH RECURSIVE up AS (
          SELECT
                 1 AS UP
               , c.CONRELID AS CHILD
               , c.CONFRELID AS PARENT
            FROM PG_CONSTRAINT c
           WHERE c.CONTYPE = 'f'
             AND c.CONRELID = CAST(LOWER(:tableName) AS REGCLASS)
           UNION
          SELECT
                 up.UP + 1
               , c.CONRELID
               , c.CONFRELID
            FROM PG_CONSTRAINT c, up
           WHERE c.CONTYPE = 'f'
             AND c.CONRELID = up.PARENT
             AND up.UP < 20
          )
          SELECT
                 up.UP
               , up.CHILD::REGCLASS AS CHILD_TABLE
               , up.PARENT::REGCLASS AS PARENT_TABLE
            FROM up"""),
      new Example("Oracle 함수", "DECODE (값마다 다른 글자)", "DECODE(칼럼, 값1, 결과1, 값2, 결과2, 그 밖) — CASE 의 짧은 꼴. PostgreSQL 은 CASE 로 씁니다.",
          """
          SELECT
                 p.PROJ_ID
               , DECODE(p.STATUS, 'ING', '진행', 'DONE', '완료', 'HOLD', '보류', '모름') AS STATUS_NAME
            FROM PROJECT p""",
          """
          SELECT
                 p.PROJ_ID
               , CASE p.STATUS WHEN 'ING' THEN '진행' WHEN 'DONE' THEN '완료' WHEN 'HOLD' THEN '보류' ELSE '모름' END AS STATUS_NAME
            FROM PROJECT p"""),
      new Example("Oracle 함수", "DECODE 로 세기 (조건부 COUNT · SUM)", "SUM(DECODE(상태, 'OPEN', 1, 0)) 은 옛 Oracle 쿼리에 아주 많이 나오는 꼴입니다.",
          """
          SELECT
                 i.PROJ_ID
               , SUM(DECODE(i.STATUS, 'OPEN', 1, 0)) AS OPEN_CNT
               , SUM(DECODE(i.STATUS, 'CLOSE', 1, 0)) AS CLOSE_CNT
            FROM ISSUE i
           GROUP BY
                 i.PROJ_ID""",
          """
          SELECT
                 i.PROJ_ID
               , COUNT(*) FILTER (WHERE i.STATUS = 'OPEN') AS OPEN_CNT
               , COUNT(*) FILTER (WHERE i.STATUS = 'CLOSE') AS CLOSE_CNT
            FROM ISSUE i
           GROUP BY
                 i.PROJ_ID"""),
      new Example("Oracle 함수", "NVL2 · NULLIF · COALESCE", "NVL2(값, 있을 때, 없을 때). NULLIF(a, b) 는 같으면 NULL(0 으로 나누기 막기). COALESCE 는 앞에서부터 처음 NULL 아닌 값.",
          """
          SELECT
                 s.SCH_ID
               , NVL2(s.ACT_END, '완료', '진행 중') AS STATE
               , COALESCE(s.ACT_END, s.PLAN_END, s.PLAN_START) AS SOME_DT
               , 100 / NULLIF(s.STEP_SEQ - 1, 0) AS SAFE_DIV
            FROM SCHEDULE s""",
          """
          SELECT
                 s.SCH_ID
               , CASE WHEN s.ACT_END IS NOT NULL THEN '완료' ELSE '진행 중' END AS STATE
               , COALESCE(s.ACT_END, s.PLAN_END, s.PLAN_START) AS SOME_DT
               , 100 / NULLIF(s.STEP_SEQ - 1, 0) AS SAFE_DIV
            FROM SCHEDULE s"""),
      new Example("Oracle 함수", "GREATEST · LEAST (여러 칸 중 큰 값 · 작은 값)", "줄 안의 여러 칼럼 중 가장 큰 · 작은 값. NULL 이 하나라도 있으면 Oracle 은 NULL, PostgreSQL 은 NULL 을 빼고 셉니다.",
          """
          SELECT
                 s.SCH_ID
               , GREATEST(s.PLAN_END, NVL(s.ACT_END, s.PLAN_END)) AS LAST_DT
               , LEAST(s.PLAN_START, NVL(s.ACT_START, s.PLAN_START)) AS FIRST_DT
            FROM SCHEDULE s""",
          """
          SELECT
                 s.SCH_ID
               , GREATEST(s.PLAN_END, s.ACT_END) AS LAST_DT
               , LEAST(s.PLAN_START, s.ACT_START) AS FIRST_DT
            FROM SCHEDULE s"""),
      new Example("Oracle 함수", "PIVOT (줄을 칸으로)", "상태 값을 칸으로 펼칩니다. IN 안의 값이 칸 이름이 됩니다(11g 이상). PostgreSQL 은 FILTER 로 같은 결과.",
          """
          SELECT
                 *
            FROM (SELECT i.PROJ_ID, i.STATUS FROM ISSUE i)
           PIVOT (
                 COUNT(*) FOR STATUS IN ('OPEN' AS OPEN_CNT, 'CLOSE' AS CLOSE_CNT)
                 )
           ORDER BY
                 PROJ_ID""",
          """
          SELECT
                 i.PROJ_ID
               , COUNT(*) FILTER (WHERE i.STATUS = 'OPEN') AS OPEN_CNT
               , COUNT(*) FILTER (WHERE i.STATUS = 'CLOSE') AS CLOSE_CNT
            FROM ISSUE i
           GROUP BY
                 i.PROJ_ID
           ORDER BY
                 i.PROJ_ID"""),
      new Example("Oracle 함수", "PIVOT 여러 값 (월이 칸, 수량 · 금액)", "월별 가로표를 PIVOT 으로. 집계를 둘 넣으면 칸 이름 뒤에 _QTY, _AMT 가 붙습니다.",
          """
          SELECT
                 *
            FROM (SELECT o.PART_NO, TO_CHAR(o.SO_DT, 'MM') AS MM, o.QTY, o.QTY * o.PRICE AS AMT FROM SALES_ORDER o)
           PIVOT (
                 SUM(QTY) AS QTY, SUM(AMT) AS AMT FOR MM IN ('08' AS M08, '09' AS M09, '10' AS M10)
                 )""",
          """
          SELECT
                 o.PART_NO
               , SUM(o.QTY) FILTER (WHERE TO_CHAR(o.SO_DT, 'MM') = '08') AS M08_QTY
               , SUM(o.QTY * o.PRICE) FILTER (WHERE TO_CHAR(o.SO_DT, 'MM') = '08') AS M08_AMT
               , SUM(o.QTY) FILTER (WHERE TO_CHAR(o.SO_DT, 'MM') = '09') AS M09_QTY
               , SUM(o.QTY * o.PRICE) FILTER (WHERE TO_CHAR(o.SO_DT, 'MM') = '09') AS M09_AMT
               , SUM(o.QTY) FILTER (WHERE TO_CHAR(o.SO_DT, 'MM') = '10') AS M10_QTY
               , SUM(o.QTY * o.PRICE) FILTER (WHERE TO_CHAR(o.SO_DT, 'MM') = '10') AS M10_AMT
            FROM SALES_ORDER o
           GROUP BY
                 o.PART_NO"""),
      new Example("Oracle 함수", "UNPIVOT (칸을 줄로)", "가로로 놓인 칸(S1 · S2 …)을 줄로 세웁니다. 엑셀 꼴 표를 정규화할 때 씁니다.",
          """
          SELECT
                 *
            FROM (
                  SELECT
                         s.PROJ_ID
                       , MAX(CASE WHEN s.STEP_SEQ = 1 THEN s.PLAN_END END) AS S1
                       , MAX(CASE WHEN s.STEP_SEQ = 2 THEN s.PLAN_END END) AS S2
                       , MAX(CASE WHEN s.STEP_SEQ = 3 THEN s.PLAN_END END) AS S3
                    FROM SCHEDULE s
                   GROUP BY
                         s.PROJ_ID
                 )
          UNPIVOT (
                 PLAN_END FOR STEP IN (S1 AS '1', S2 AS '2', S3 AS '3')
                 )""",
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
           CROSS JOIN LATERAL (VALUES ('1', x.S1), ('2', x.S2), ('3', x.S3)) AS v(STEP, PLAN_END)
           WHERE v.PLAN_END IS NOT NULL"""),
      new Example("Oracle 함수", "NEXT_DAY · LAST_DAY (다음 월요일 · 그달 말일)", "NEXT_DAY 의 요일 이름은 DB 언어 설정을 따릅니다(한국어면 '월요일'). 숫자로 쓰려면 TRUNC(날짜, 'IW') + 7 이 안전합니다.",
          """
          SELECT
                 TRUNC(SYSDATE, 'IW') + 7 AS NEXT_MONDAY
               , LAST_DAY(TRUNC(SYSDATE)) AS MONTH_END
               , LAST_DAY(ADD_MONTHS(TRUNC(SYSDATE), -1)) AS LAST_MONTH_END
            FROM DUAL""",
          """
          SELECT
                 CAST(DATE_TRUNC('WEEK', CURRENT_DATE) AS DATE) + 7 AS NEXT_MONDAY
               , CAST(DATE_TRUNC('MONTH', CURRENT_DATE) + INTERVAL '1 MONTH' AS DATE) - 1 AS MONTH_END
               , CAST(DATE_TRUNC('MONTH', CURRENT_DATE) AS DATE) - 1 AS LAST_MONTH_END"""),
      new Example("Oracle 함수", "TO_TIMESTAMP · SYSTIMESTAMP · 밀리초", "초 아래까지 다룰 때. FF3 은 밀리초 세 자리.",
          """
          SELECT
                 SYSTIMESTAMP AS NOW_TS
               , TO_CHAR(SYSTIMESTAMP, 'YYYY-MM-DD HH24:MI:SS.FF3') AS NOW_MS
               , TO_TIMESTAMP('2026-11-07 13:05:01.123', 'YYYY-MM-DD HH24:MI:SS.FF3') AS PARSED
            FROM DUAL""",
          """
          SELECT
                 CURRENT_TIMESTAMP AS NOW_TS
               , TO_CHAR(CURRENT_TIMESTAMP, 'YYYY-MM-DD HH24:MI:SS.MS') AS NOW_MS
               , TO_TIMESTAMP('2026-11-07 13:05:01.123', 'YYYY-MM-DD HH24:MI:SS.MS') AS PARSED"""),
      new Example("Oracle 함수", "날짜 빼서 시간 · 분 · 초로 (HH:MI:SS 꼴)", "Oracle 날짜끼리 빼면 일 단위 숫자. × 24 는 시간, × 1440 은 분. NUMTODSINTERVAL 로 보기 좋은 꼴로.",
          """
          SELECT
                 e.EQUIP_ID
               , ROUND((e.END_TS - e.START_TS) * 24, 2) AS HOURS
               , ROUND((e.END_TS - e.START_TS) * 1440) AS MINUTES
               , NUMTODSINTERVAL(CAST(e.END_TS AS DATE) - CAST(e.START_TS AS DATE), 'DAY') AS DURATION
               , TO_CHAR(TRUNC(SYSDATE) + (CAST(e.END_TS AS DATE) - CAST(e.START_TS AS DATE)), 'HH24:MI:SS') AS HHMISS
            FROM EQUIP_LOG e""",
          """
          SELECT
                 e.EQUIP_ID
               , ROUND(CAST(EXTRACT(EPOCH FROM e.END_TS - e.START_TS) / 3600 AS NUMERIC), 2) AS HOURS
               , ROUND(EXTRACT(EPOCH FROM e.END_TS - e.START_TS) / 60) AS MINUTES
               , e.END_TS - e.START_TS AS DURATION
               , TO_CHAR(e.END_TS - e.START_TS, 'HH24:MI:SS') AS HHMISS
            FROM EQUIP_LOG e"""),
      new Example("Oracle 함수", "RPAD · LPAD · TRANSLATE (고정 길이 · 글자 바꾸기)", "고정 길이 파일을 만들 때 RPAD 로 오른쪽을 공백으로 채웁니다. TRANSLATE 는 글자 하나씩 바꿉니다(- 와 / 를 . 으로).",
          """
          SELECT
                 RPAD(p.PART_NO, 20, ' ') || LPAD(TO_CHAR(p.UNIT_COST), 10, '0') AS FIXED_LINE
               , TRANSLATE('2026-11/07', '-/', '..') AS DOTTED
            FROM PART p""",
          """
          SELECT
                 RPAD(p.PART_NO, 20, ' ') || LPAD(CAST(p.UNIT_COST AS VARCHAR), 10, '0') AS FIXED_LINE
               , TRANSLATE('2026-11/07', '-/', '..') AS DOTTED
            FROM PART p"""),
      new Example("Oracle 함수", "REGEXP_COUNT · REGEXP_REPLACE (정규식 세기 · 바꾸기)", "쉼표 개수로 항목 수 세기, 연속 공백을 하나로.",
          """
          SELECT
                 p.PART_NO
               , REGEXP_COUNT(p.TAGS, ',') + 1 AS TAG_CNT
               , REGEXP_REPLACE('A   B    C', ' {2,}', ' ') AS ONE_SPACE
            FROM PART p
           WHERE p.TAGS IS NOT NULL""",
          """
          SELECT
                 p.PART_NO
               , LENGTH(p.TAGS) - LENGTH(REPLACE(p.TAGS, ',', '')) + 1 AS TAG_CNT
               , REGEXP_REPLACE('A   B    C', ' {2,}', ' ', 'g') AS ONE_SPACE
            FROM PART p
           WHERE p.TAGS IS NOT NULL"""),
      new Example("Oracle 함수", "LENGTH vs LENGTHB (한글 칸 넘침 점검)", "VARCHAR2(100 BYTE) 칸에는 한글이 33~50자밖에 안 들어갑니다. 바이트로 넘칠 값을 미리 찾습니다.",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
               , LENGTH(p.PART_NAME) AS CHARS
               , LENGTHB(p.PART_NAME) AS BYTES
            FROM PART p
           WHERE LENGTHB(p.PART_NAME) > :maxBytes""",
          """
          SELECT
                 p.PART_NO
               , p.PART_NAME
               , LENGTH(p.PART_NAME) AS CHARS
               , OCTET_LENGTH(p.PART_NAME) AS BYTES
            FROM PART p
           WHERE OCTET_LENGTH(p.PART_NAME) > :maxBytes"""),
      new Example("Oracle 함수", "LISTAGG 가 4000바이트를 넘을 때 (ON OVERFLOW · XMLAGG)", "LISTAGG 는 결과가 4000바이트를 넘으면 ORA-01489. 12.2 이상은 ON OVERFLOW TRUNCATE, 그 전은 XMLAGG 로 CLOB 을 만듭니다.",
          """
          SELECT
                 b.PARENT_PART
               , LISTAGG(b.CHILD_PART, ', ' ON OVERFLOW TRUNCATE '…' WITH COUNT) WITHIN GROUP (ORDER BY b.SEQ) AS CHILDREN
               , RTRIM(XMLAGG(XMLELEMENT(E, b.CHILD_PART || ', ') ORDER BY b.SEQ).EXTRACT('//text()').GETCLOBVAL(), ', ') AS CHILDREN_CLOB
            FROM BOM b
           GROUP BY
                 b.PARENT_PART""",
          """
          SELECT
                 b.PARENT_PART
               , STRING_AGG(b.CHILD_PART, ', ' ORDER BY b.SEQ) AS CHILDREN
            FROM BOM b
           GROUP BY
                 b.PARENT_PART"""),
      new Example("Oracle 함수", "위에서 n건 (FETCH FIRST · ROWNUM)", "12c 이상은 FETCH FIRST n ROWS ONLY. 11g 이하는 정렬을 안쪽에 두고 바깥에서 ROWNUM.",
          """
          SELECT
                 o.ORDER_ID
               , o.AMOUNT
            FROM ORDERS o
           ORDER BY
                 o.AMOUNT DESC
           FETCH FIRST 10 ROWS ONLY""",
          """
          SELECT
                 o.ORDER_ID
               , o.AMOUNT
            FROM ORDERS o
           ORDER BY
                 o.AMOUNT DESC
           FETCH FIRST 10 ROWS ONLY"""),
      new Example("Oracle 함수", "같은 값이면 함께 (FETCH FIRST … WITH TIES)", "10등과 같은 값인 줄까지 다 가져옵니다.",
          """
          SELECT
                 o.ORDER_ID
               , o.AMOUNT
            FROM ORDERS o
           ORDER BY
                 o.AMOUNT DESC
           FETCH FIRST 10 ROWS WITH TIES""",
          """
          SELECT
                 o.ORDER_ID
               , o.AMOUNT
            FROM ORDERS o
           ORDER BY
                 o.AMOUNT DESC
           FETCH FIRST 10 ROWS WITH TIES"""),
      new Example("Oracle 함수", "KEEP (DENSE_RANK FIRST / LAST) — 가장 최근 줄의 다른 칸", "묶음마다 가장 최근(또는 처음) 줄의 다른 칼럼 값을 한 번에. 서브쿼리 없이 씁니다.",
          """
          SELECT
                 r.PART_NO
               , MAX(r.REV) AS LAST_REV
               , MAX(r.STATUS) KEEP (DENSE_RANK LAST ORDER BY r.REV) AS LAST_STATUS
               , MAX(r.WEIGHT) KEEP (DENSE_RANK FIRST ORDER BY r.REV) AS FIRST_WEIGHT
            FROM PART_REV r
           GROUP BY
                 r.PART_NO""",
          """
          SELECT
                 r.PART_NO
               , MAX(r.REV) AS LAST_REV
               , (ARRAY_AGG(r.STATUS ORDER BY r.REV DESC))[1] AS LAST_STATUS
               , (ARRAY_AGG(r.WEIGHT ORDER BY r.REV))[1] AS FIRST_WEIGHT
            FROM PART_REV r
           GROUP BY
                 r.PART_NO"""),
      new Example("Oracle 함수", "ROWID 로 중복 지우기 · 한 줄 고치기", "키가 없는 표에서 같은 줄 중 하나만 남길 때 ROWID 를 씁니다. PostgreSQL 은 ctid.",
          """
          DELETE FROM UPLOAD_TMP u
           WHERE u.ROWID NOT IN (
                   SELECT MIN(x.ROWID)
                     FROM UPLOAD_TMP x
                    GROUP BY
                          x.PART_NO
                 )""",
          """
          DELETE FROM UPLOAD_TMP u
           WHERE u.CTID NOT IN (
                   SELECT MIN(x.CTID)
                     FROM UPLOAD_TMP x
                    GROUP BY
                          x.PART_NO
                 )"""),
      new Example("Oracle 함수", "ADD_MONTHS · MONTHS_BETWEEN · 말일 처리", "1월 31일 + 1달 = 2월 28(29)일. MONTHS_BETWEEN 은 소수로 나와 TRUNC 로 개월 수만.",
          """
          SELECT
                 ADD_MONTHS(DATE '2026-01-31', 1) AS FEB_END
               , TRUNC(MONTHS_BETWEEN(DATE '2026-11-07', DATE '2025-03-15')) AS MONTHS
            FROM DUAL""",
          """
          SELECT
                 DATE '2026-01-31' + INTERVAL '1 MONTH' AS FEB_END
               , EXTRACT(YEAR FROM AGE(DATE '2026-11-07', DATE '2025-03-15')) * 12 + EXTRACT(MONTH FROM AGE(DATE '2026-11-07', DATE '2025-03-15')) AS MONTHS"""),
      new Example("Oracle 함수", "TO_CHAR 숫자 꼴 (천 단위 쉼표 · 소수 · 퍼센트)", "FM 은 앞 공백을 없앱니다. 9 는 자리, 0 은 0 으로 채울 자리.",
          """
          SELECT
                 TO_CHAR(1234567.891, 'FM999,999,990.00') AS MONEY
               , TO_CHAR(0.4567 * 100, 'FM990.0') || '%' AS PCT
               , TO_CHAR(7, 'FM000') AS PADDED
            FROM DUAL""",
          """
          SELECT
                 TO_CHAR(1234567.891, 'FM999,999,990.00') AS MONEY
               , TO_CHAR(0.4567 * 100, 'FM990.0') || '%' AS PCT
               , TO_CHAR(7, 'FM000') AS PADDED"""),
      new Example("Oracle 함수", "WM_CONCAT 대신 쓸 것 (옛 쿼리 고치기)", "WM_CONCAT 은 12c 에서 없어졌습니다. 같은 일을 LISTAGG 로.",
          """
          SELECT
                 b.PARENT_PART
               , LISTAGG(b.CHILD_PART, ',') WITHIN GROUP (ORDER BY b.CHILD_PART) AS CHILDREN
            FROM BOM b
           GROUP BY
                 b.PARENT_PART""",
          """
          SELECT
                 b.PARENT_PART
               , STRING_AGG(b.CHILD_PART, ',' ORDER BY b.CHILD_PART) AS CHILDREN
            FROM BOM b
           GROUP BY
                 b.PARENT_PART"""),
      new Example("Oracle 함수", "SUBSTR · INSTR 뒤에서부터 (음수 위치)", "SUBSTR(글, -3) 은 끝 3글자, INSTR(글, '-', -1) 은 마지막 '-' 위치. 파일 확장자 · 마지막 조각 꺼내기.",
          """
          SELECT
                 p.PART_NO
               , SUBSTR(p.PART_NO, -3) AS LAST3
               , SUBSTR(p.TAGS, INSTR(p.TAGS, ',', -1) + 1) AS LAST_TAG
            FROM PART p""",
          """
          SELECT
                 p.PART_NO
               , RIGHT(p.PART_NO, 3) AS LAST3
               , SPLIT_PART(p.TAGS, ',', -1) AS LAST_TAG
            FROM PART p"""),
      new Example("Oracle 함수", "REGEXP_SUBSTR n 번째 조각 · REGEXP_INSTR", "'A-B-C' 의 n 번째 조각과 숫자가 처음 나오는 위치.",
          """
          SELECT
                 REGEXP_SUBSTR('A-B-C', '[^-]+', 1, 3) AS THIRD
               , REGEXP_INSTR('ABC123', '[0-9]') AS FIRST_DIGIT_POS
            FROM DUAL""",
          """
          SELECT
                 SPLIT_PART('A-B-C', '-', 3) AS THIRD
               , POSITION(SUBSTRING('ABC123' FROM '[0-9]') IN 'ABC123') AS FIRST_DIGIT_POS"""),
      new Example("Oracle 함수", "TRIM LEADING · LTRIM · RTRIM (앞 0 · 뒤 공백 지우기)", "앞에 붙은 0 을 지우거나 오른쪽 공백 · 점을 지웁니다.",
          """
          SELECT
                 TRIM(LEADING '0' FROM '000123') AS NO_ZERO
               , LTRIM('  abc') AS LEFT_TRIM
               , RTRIM('abc...', '.') AS RIGHT_TRIM
            FROM DUAL""",
          """
          SELECT
                 TRIM(LEADING '0' FROM '000123') AS NO_ZERO
               , LTRIM('  abc') AS LEFT_TRIM
               , RTRIM('abc...', '.') AS RIGHT_TRIM"""),
      new Example("Oracle 함수", "INITCAP · CONCAT · CHR · ASCII", "첫 글자 대문자, 두 글 잇기, 글자 ↔ 번호.",
          """
          SELECT
                 INITCAP('hello world') AS CAP
               , CONCAT('A', 'B') AS JOINED
               , CHR(65) AS CH
               , ASCII('A') AS CODE
            FROM DUAL""",
          """
          SELECT
                 INITCAP('hello world') AS CAP
               , CONCAT('A', 'B') AS JOINED
               , CHR(65) AS CH
               , ASCII('A') AS CODE"""),
      new Example("Oracle 함수", "TRUNC · ROUND 숫자 (자리 · 음수 자리)", "ROUND(123456, -3) 은 천 단위 반올림, TRUNC(12.345, 1) 은 소수 한 자리 버림.",
          """
          SELECT
                 ROUND(123456, -3) AS R_THOUSAND
               , TRUNC(12.345, 1) AS T1
               , CEIL(12.1) AS UP
               , FLOOR(12.9) AS DOWN
            FROM DUAL""",
          """
          SELECT
                 ROUND(123456, -3) AS R_THOUSAND
               , TRUNC(12.345, 1) AS T1
               , CEIL(12.1) AS UP
               , FLOOR(12.9) AS DOWN"""),
      new Example("Oracle 함수", "MOD · ABS · SIGN · POWER · SQRT", "나머지, 절댓값, 부호(-1 · 0 · 1), 거듭제곱, 제곱근.",
          """
          SELECT
                 MOD(10, 3) AS M
               , ABS(-5) AS A
               , SIGN(-3) AS S
               , POWER(2, 10) AS P
               , SQRT(16) AS Q
            FROM DUAL""",
          """
          SELECT
                 MOD(10, 3) AS M
               , ABS(-5) AS A
               , SIGN(-3) AS S
               , POWER(2, 10) AS P
               , SQRT(16) AS Q"""),
      new Example("Oracle 함수", "TRUNC · ROUND 날짜 (일 · 월 · 연 · 주 시작)", "TRUNC(날짜, 'MM') 은 그달 1일, 'IW' 는 그 주 월요일, ROUND 는 가까운 쪽으로.",
          """
          SELECT
                 TRUNC(SYSDATE) AS TODAY
               , TRUNC(SYSDATE, 'MM') AS MONTH_START
               , TRUNC(SYSDATE, 'YYYY') AS YEAR_START
               , TRUNC(SYSDATE, 'IW') AS WEEK_MON
               , TRUNC(SYSDATE, 'Q') AS QUARTER_START
            FROM DUAL""",
          """
          SELECT
                 CURRENT_DATE AS TODAY
               , CAST(DATE_TRUNC('MONTH', CURRENT_DATE) AS DATE) AS MONTH_START
               , CAST(DATE_TRUNC('YEAR', CURRENT_DATE) AS DATE) AS YEAR_START
               , CAST(DATE_TRUNC('WEEK', CURRENT_DATE) AS DATE) AS WEEK_MON
               , CAST(DATE_TRUNC('QUARTER', CURRENT_DATE) AS DATE) AS QUARTER_START"""),
      new Example("Oracle 함수", "TO_CHAR 날짜 꼴 모음 (요일 · 분기 · 주 · 오전/오후)", "자주 쓰는 날짜 꼴: 요일 이름, 분기, 연중 몇째 주, 24시 · 12시.",
          """
          SELECT
                 TO_CHAR(SYSDATE, 'YYYY-MM-DD HH24:MI:SS') AS FULL_DT
               , TO_CHAR(SYSDATE, 'YYYY"년" MM"월" DD"일"') AS KOR_DT
               , TO_CHAR(SYSDATE, 'DY') AS WEEKDAY
               , TO_CHAR(SYSDATE, 'Q') AS QUARTER
               , TO_CHAR(SYSDATE, 'IW') AS ISO_WEEK
               , TO_CHAR(SYSDATE, 'AM HH:MI') AS AMPM
            FROM DUAL""",
          """
          SELECT
                 TO_CHAR(NOW(), 'YYYY-MM-DD HH24:MI:SS') AS FULL_DT
               , TO_CHAR(NOW(), 'YYYY"년" MM"월" DD"일"') AS KOR_DT
               , TO_CHAR(NOW(), 'Dy') AS WEEKDAY
               , TO_CHAR(NOW(), 'Q') AS QUARTER
               , TO_CHAR(NOW(), 'IW') AS ISO_WEEK
               , TO_CHAR(NOW(), 'AM HH:MI') AS AMPM"""),
      new Example("Oracle 함수", "EXTRACT (연 · 월 · 일 · 시 숫자로)", "날짜에서 숫자 하나만 꺼냅니다. 시 · 분은 TIMESTAMP 에서만 됩니다.",
          """
          SELECT
                 EXTRACT(YEAR FROM SYSDATE) AS Y
               , EXTRACT(MONTH FROM SYSDATE) AS M
               , EXTRACT(DAY FROM SYSDATE) AS D
               , EXTRACT(HOUR FROM SYSTIMESTAMP) AS H
            FROM DUAL""",
          """
          SELECT
                 EXTRACT(YEAR FROM NOW()) AS Y
               , EXTRACT(MONTH FROM NOW()) AS M
               , EXTRACT(DAY FROM NOW()) AS D
               , EXTRACT(HOUR FROM NOW()) AS H"""),
      new Example("Oracle 함수", "NUMTODSINTERVAL · NUMTOYMINTERVAL (숫자를 기간으로)", "분 · 시간 · 달 수를 칸 값으로 받아 날짜에 더할 때.",
          """
          SELECT
                 SYSTIMESTAMP + NUMTODSINTERVAL(90, 'MINUTE') AS AFTER_90M
               , SYSDATE + NUMTOYMINTERVAL(2, 'MONTH') AS AFTER_2M
            FROM DUAL""",
          """
          SELECT
                 NOW() + MAKE_INTERVAL(MINS => 90) AS AFTER_90M
               , CURRENT_DATE + MAKE_INTERVAL(MONTHS => 2) AS AFTER_2M"""),
      new Example("Oracle 함수", "CAST · TO_NUMBER · TO_DATE 형 바꾸기", "글자 ↔ 숫자 ↔ 날짜. 꼴이 틀리면 오류가 나므로 먼저 거르세요(「숫자인지 확인」).",
          """
          SELECT
                 TO_NUMBER('1,234', '9,999') AS N
               , TO_DATE('20261107', 'YYYYMMDD') AS D
               , CAST('123' AS NUMBER) AS C
               , CAST(SYSDATE AS TIMESTAMP) AS TS
            FROM DUAL""",
          """
          SELECT
                 TO_NUMBER('1,234', '9,999') AS N
               , TO_DATE('20261107', 'YYYYMMDD') AS D
               , CAST('123' AS NUMERIC) AS C
               , CAST(CURRENT_DATE AS TIMESTAMP) AS TS"""),
      new Example("Oracle 함수", "SYS_GUID · ORA_HASH · STANDARD_HASH (고유 값 · 해시)", "겹치지 않는 키 만들기, 값 비교용 해시, 나눠 처리할 때 몫 정하기.",
          """
          SELECT
                 SYS_GUID() AS GUID
               , ORA_HASH('P001', 9) AS BUCKET
               , STANDARD_HASH('P001', 'SHA256') AS SHA
            FROM DUAL""",
          """
          SELECT
                 GEN_RANDOM_UUID() AS GUID
               , MOD(ABS(HASHTEXT('P001')), 10) AS BUCKET
               , MD5('P001') AS SHA"""),
      new Example("Oracle 함수", "SYS_CONTEXT · USER (접속 정보)", "지금 계정, 접속 PC, IP, 프로그램 이름. 감사 로그에 남길 때 씁니다.",
          """
          SELECT
                 USER AS DB_USER
               , SYS_CONTEXT('USERENV', 'OS_USER') AS OS_USER
               , SYS_CONTEXT('USERENV', 'HOST') AS HOST
               , SYS_CONTEXT('USERENV', 'IP_ADDRESS') AS IP
               , SYS_CONTEXT('USERENV', 'MODULE') AS MODULE
            FROM DUAL""",
          """
          SELECT
                 CURRENT_USER AS DB_USER
               , INET_CLIENT_ADDR() AS IP
               , CURRENT_SETTING('application_name') AS MODULE"""),
      new Example("Oracle 함수", "NULLS FIRST · NULLS LAST", "빈 값을 맨 앞 · 맨 뒤로. Oracle 은 오름차순이면 NULL 이 뒤, 내림차순이면 앞이 기본입니다.",
          """
          SELECT
                 c.CAPA_NO
               , c.DUE_DT
            FROM CAPA c
           ORDER BY
                 c.DUE_DT DESC NULLS LAST""",
          """
          SELECT
                 c.CAPA_NO
               , c.DUE_DT
            FROM CAPA c
           ORDER BY
                 c.DUE_DT DESC NULLS LAST"""),
      new Example("Oracle 함수", "INSERT ALL (한 번에 여러 표 · 여러 줄)", "한 SELECT 결과를 조건에 따라 여러 표에 나눠 넣습니다. PostgreSQL 은 INSERT 를 나눠 씁니다.",
          """
          INSERT ALL
            WHEN STATUS = '완료' THEN INTO ORDERS_BAK (ORDER_ID, CUST_ID, AMOUNT, STATUS, ORDER_DT) VALUES (ORDER_ID, CUST_ID, AMOUNT, STATUS, ORDER_DT)
          SELECT
                 o.ORDER_ID
               , o.CUST_ID
               , o.AMOUNT
               , o.STATUS
               , o.ORDER_DT
            FROM ORDERS o
           WHERE NOT EXISTS (SELECT 1 FROM ORDERS_BAK b WHERE b.ORDER_ID = o.ORDER_ID)""",
          """
          INSERT INTO ORDERS_BAK (ORDER_ID, CUST_ID, AMOUNT, STATUS, ORDER_DT)
          SELECT
                 o.ORDER_ID
               , o.CUST_ID
               , o.AMOUNT
               , o.STATUS
               , o.ORDER_DT
            FROM ORDERS o
           WHERE o.STATUS = '완료'
             AND NOT EXISTS (SELECT 1 FROM ORDERS_BAK b WHERE b.ORDER_ID = o.ORDER_ID)"""),
      new Example("Oracle 함수", "MERGE 에 DELETE 절 (고치면서 조건 맞는 줄 지우기)", "MERGE 로 고친 뒤 상태가 STOP 이 된 줄은 지웁니다. PostgreSQL 15 이상은 WHEN MATCHED AND … THEN DELETE.",
          """
          MERGE INTO ERP_PART e
          USING PART p
             ON (e.PART_NO = p.PART_NO)
           WHEN MATCHED THEN
                UPDATE
                   SET e.PART_NAME = p.PART_NAME
                DELETE WHERE p.STATUS = 'STOP'""",
          """
          MERGE INTO ERP_PART e
          USING PART p
             ON (e.PART_NO = p.PART_NO)
           WHEN MATCHED AND p.STATUS = 'STOP' THEN
                DELETE
           WHEN MATCHED THEN
                UPDATE
                   SET PART_NAME = p.PART_NAME"""),
      new Example("Oracle 함수", "RATIO_TO_REPORT (전체 중 비율 짧게)", "SUM OVER () 로 나누는 것과 같습니다. 0~1 값이라 100 을 곱합니다.",
          """
          SELECT
                 o.STATUS
               , COUNT(*) AS CNT
               , ROUND(RATIO_TO_REPORT(COUNT(*)) OVER () * 100, 1) AS PCT
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
      new Example("Oracle 함수", "FIRST_VALUE · LAST_VALUE · NTH_VALUE", "그룹 안 첫 값 · 끝 값 · n 번째 값. LAST_VALUE 는 창 범위를 끝까지 넓혀야 맞게 나옵니다.",
          """
          SELECT
                 o.CUST_ID
               , o.ORDER_ID
               , FIRST_VALUE(o.AMOUNT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT) AS FIRST_AMT
               , LAST_VALUE(o.AMOUNT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS LAST_AMT
               , NTH_VALUE(o.AMOUNT, 2) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS SECOND_AMT
            FROM ORDERS o""",
          """
          SELECT
                 o.CUST_ID
               , o.ORDER_ID
               , FIRST_VALUE(o.AMOUNT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT) AS FIRST_AMT
               , LAST_VALUE(o.AMOUNT) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS LAST_AMT
               , NTH_VALUE(o.AMOUNT, 2) OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS SECOND_AMT
            FROM ORDERS o"""),
      new Example("Oracle 함수", "LISTAGG DISTINCT (겹친 값 빼고 잇기, 19c 이상)", "같은 값이 여러 번 나와도 한 번만 잇습니다. 18c 이하는 안쪽에서 DISTINCT 로 먼저 거릅니다.",
          """
          SELECT
                 i.ORDER_ID
               , LISTAGG(DISTINCT i.PART_NO, ', ') WITHIN GROUP (ORDER BY i.PART_NO) AS PARTS
            FROM ORDER_ITEM i
           GROUP BY
                 i.ORDER_ID""",
          """
          SELECT
                 i.ORDER_ID
               , STRING_AGG(DISTINCT i.PART_NO, ', ' ORDER BY i.PART_NO) AS PARTS
            FROM ORDER_ITEM i
           GROUP BY
                 i.ORDER_ID"""),
      new Example("Oracle 함수", "MEDIAN · STATS_MODE · PERCENTILE_CONT", "중앙값, 가장 많이 나온 값, 90% 지점 값.",
          """
          SELECT
                 MEDIAN(o.AMOUNT) AS MED
               , STATS_MODE(o.STATUS) AS MODE_STATUS
               , PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY o.AMOUNT) AS P90
            FROM ORDERS o""",
          """
          SELECT
                 PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY o.AMOUNT) AS MED
               , MODE() WITHIN GROUP (ORDER BY o.STATUS) AS MODE_STATUS
               , PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY o.AMOUNT) AS P90
            FROM ORDERS o"""),
      new Example("Oracle 함수", "STDDEV · VARIANCE · CORR (흩어짐 · 상관)", "표준편차, 분산, 두 값의 상관(-1 ~ 1).",
          """
          SELECT
                 ROUND(STDDEV(i.PRICE), 2) AS SD
               , ROUND(VARIANCE(i.PRICE), 2) AS VAR
               , ROUND(CORR(i.QTY, i.PRICE), 3) AS QTY_PRICE_CORR
            FROM ORDER_ITEM i""",
          """
          SELECT
                 ROUND(STDDEV(i.PRICE), 2) AS SD
               , ROUND(VARIANCE(i.PRICE), 2) AS VAR
               , ROUND(CAST(CORR(i.QTY, i.PRICE) AS NUMERIC), 3) AS QTY_PRICE_CORR
            FROM ORDER_ITEM i"""),
      new Example("Oracle 함수", "APPROX_COUNT_DISTINCT (큰 표 겹치지 않는 수 어림)", "정확한 COUNT(DISTINCT) 보다 훨씬 빠른 어림값. 대시보드 숫자에 씁니다.",
          """
          SELECT
                 APPROX_COUNT_DISTINCT(l.USER_ID) AS APPROX_USERS
               , COUNT(DISTINCT l.USER_ID) AS EXACT_USERS
            FROM LOGIN_LOG l""",
          """
          SELECT
                 COUNT(DISTINCT l.USER_ID) AS EXACT_USERS
            FROM LOGIN_LOG l"""),
      new Example("Oracle 함수", "XMLTABLE · XMLAGG (XML 꺼내기 · 만들기)", "XML 글에서 값을 칸으로 꺼내거나 줄을 XML 로 묶습니다.",
          """
          SELECT
                 x.PART_NO
               , x.QTY
            FROM XMLTABLE('/items/item' PASSING XMLTYPE('<items><item><no>P001</no><qty>2</qty></item></items>')
                   COLUMNS PART_NO VARCHAR2(30) PATH 'no', QTY NUMBER PATH 'qty') x""",
          """
          SELECT
                 x.PART_NO
               , x.QTY
            FROM XMLTABLE('/items/item' PASSING XMLPARSE(DOCUMENT '<items><item><no>P001</no><qty>2</qty></item></items>')
                   COLUMNS PART_NO VARCHAR(30) PATH 'no', QTY NUMERIC PATH 'qty') x"""),
      new Example("Oracle 함수", "CONNECT BY 로 글자 나누기 (쉼표 글자 → 줄)", "11g 에서 자주 쓰는 꼴. 쉼표 수 + 1 만큼 줄을 만들어 n 번째 조각을 꺼냅니다.",
          """
          SELECT
                 p.PART_NO
               , TRIM(REGEXP_SUBSTR(p.TAGS, '[^,]+', 1, n.N)) AS TAG
            FROM PART p
               , (SELECT LEVEL AS N FROM DUAL CONNECT BY LEVEL <= 20) n
           WHERE p.TAGS IS NOT NULL
             AND n.N <= REGEXP_COUNT(p.TAGS, ',') + 1""",
          """
          SELECT
                 p.PART_NO
               , TRIM(t.TAG) AS TAG
            FROM PART p
           CROSS JOIN LATERAL REGEXP_SPLIT_TO_TABLE(p.TAGS, ',') AS t(TAG)
           WHERE p.TAGS IS NOT NULL"""),
      new Example("Oracle 함수", "DUMP · VSIZE · LENGTHB (보이지 않는 글자 · 실제 크기)", "같아 보이는데 비교가 안 맞을 때 글자 코드를 그대로 봅니다.",
          """
          SELECT
                 p.PART_NAME
               , DUMP(p.PART_NAME, 1016) AS BYTES_HEX
               , VSIZE(p.PART_NAME) AS BYTE_SIZE
            FROM PART p
           WHERE p.PART_NO = :partNo""",
          """
          SELECT
                 p.PART_NAME
               , ENCODE(CONVERT_TO(p.PART_NAME, 'UTF8'), 'hex') AS BYTES_HEX
               , OCTET_LENGTH(p.PART_NAME) AS BYTE_SIZE
            FROM PART p
           WHERE p.PART_NO = :partNo"""),
      new Example("Oracle 함수", "BITAND (권한 · 옵션 비트 값 보기)", "숫자 하나에 여러 켜짐/꺼짐을 담은 칸에서 한 자리만 봅니다.",
          """
          SELECT
                 r.ROLE_ID
               , DECODE(BITAND(8, 4), 4, 'Y', 'N') AS HAS_FLAG_4
            FROM ROLE_USER r""",
          """
          SELECT
                 r.ROLE_ID
               , CASE WHEN (8 & 4) = 4 THEN 'Y' ELSE 'N' END AS HAS_FLAG_4
            FROM ROLE_USER r"""),
      new Example("Oracle 함수", "FROM_TZ · SYS_EXTRACT_UTC (시간대 바꾸기)", "한국 시각 ↔ 세계 표준시. 해외 법인 · 인터페이스 시각 맞출 때.",
          """
          SELECT
                 FROM_TZ(CAST(SYSDATE AS TIMESTAMP), 'Asia/Seoul') AT TIME ZONE 'UTC' AS UTC_TIME
               , SYS_EXTRACT_UTC(SYSTIMESTAMP) AS UTC_NOW
            FROM DUAL""",
          """
          SELECT
                 (CAST(NOW() AS TIMESTAMP) AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'UTC' AS UTC_TIME
               , NOW() AT TIME ZONE 'UTC' AS UTC_NOW"""),
      new Example("Oracle 함수", "ROWNUM 함정 (정렬보다 먼저 붙음)", "ROWNUM 은 ORDER BY 전에 붙어서 바깥에서 자르면 엉뚱한 10건이 나옵니다. 안쪽에서 정렬 후 바깥에서 자릅니다.",
          """
          SELECT
                 x.*
            FROM (
                  SELECT
                         o.ORDER_ID
                       , o.AMOUNT
                    FROM ORDERS o
                   ORDER BY
                         o.AMOUNT DESC
                 ) x
           WHERE ROWNUM <= 10""",
          """
          SELECT
                 o.ORDER_ID
               , o.AMOUNT
            FROM ORDERS o
           ORDER BY
                 o.AMOUNT DESC
           LIMIT 10"""),
      new Example("Oracle 함수", "DBMS_RANDOM (아무 값 · 섞기)", "아무 숫자 · 아무 글자, 그리고 순서 섞기.",
          """
          SELECT
                 p.PART_NO
               , ROUND(DBMS_RANDOM.VALUE(1, 100)) AS RND
               , DBMS_RANDOM.STRING('U', 5) AS RND_TEXT
            FROM PART p
           ORDER BY
                 DBMS_RANDOM.VALUE""",
          """
          SELECT
                 p.PART_NO
               , ROUND(CAST(1 + RANDOM() * 99 AS NUMERIC)) AS RND
               , UPPER(SUBSTR(MD5(RANDOM()::TEXT), 1, 5)) AS RND_TEXT
            FROM PART p
           ORDER BY
                 RANDOM()"""),
      new Example("Oracle 함수", "WITH 안에 함수 (WITH FUNCTION, 12c 이상)", "쿼리 안에서만 쓰는 작은 함수를 바로 정의합니다. 함수를 만들 권한이 없을 때 씁니다.",
          """
          WITH
            FUNCTION FN_GRADE(P_AMT NUMBER) RETURN VARCHAR2 IS
            BEGIN
              RETURN CASE WHEN P_AMT >= 100000 THEN 'A' WHEN P_AMT >= 10000 THEN 'B' ELSE 'C' END;
            END;
          SELECT
                 o.ORDER_ID
               , FN_GRADE(o.AMOUNT) AS GRADE
            FROM ORDERS o""",
          """
          SELECT
                 o.ORDER_ID
               , CASE WHEN o.AMOUNT >= 100000 THEN 'A' WHEN o.AMOUNT >= 10000 THEN 'B' ELSE 'C' END AS GRADE
            FROM ORDERS o"""),
      new Example("Oracle 함수", "PL/SQL 결과 찍기 (DBMS_OUTPUT · RAISE NOTICE)", "블록 안에서 값을 출력 창에 찍어 확인합니다.",
          """
          DECLARE
            V_CNT NUMBER;
          BEGIN
            SELECT COUNT(*) INTO V_CNT FROM ORDERS;
            DBMS_OUTPUT.PUT_LINE('주문 수: ' || V_CNT);
          END;""",
          """
          DO $$
          DECLARE
            V_CNT BIGINT;
          BEGIN
            V_CNT := (SELECT COUNT(*) FROM ORDERS);
            RAISE NOTICE '주문 수: %', V_CNT;
          END $$"""),
      new Example("Oracle 함수", "RETURNING INTO (넣은 번호 받기, PL/SQL)", "INSERT 한 줄의 새 번호를 변수로 받습니다.",
          """
          DECLARE
            V_ID NUMBER;
          BEGIN
            INSERT INTO ORDERS (ORDER_ID, CUST_ID, STATUS, ORDER_DT, AMOUNT)
            VALUES (SEQ_ORDER.NEXTVAL, 1, '접수', TRUNC(SYSDATE), 0)
            RETURNING ORDER_ID INTO V_ID;
            DBMS_OUTPUT.PUT_LINE(V_ID);
          END;""",
          """
          INSERT INTO ORDERS (ORDER_ID, CUST_ID, STATUS, ORDER_DT, AMOUNT)
          VALUES (NEXTVAL('seq_order'), 1, '접수', CURRENT_DATE, 0)
          RETURNING ORDER_ID"""),
      new Example("Oracle 함수", "CASE 두 꼴 (값 비교 · 조건 비교)", "CASE 칼럼 WHEN 값 … 은 = 비교만, CASE WHEN 조건 … 은 아무 조건이나 됩니다.",
          """
          SELECT
                 o.ORDER_ID
               , CASE o.STATUS WHEN '접수' THEN 1 WHEN '진행' THEN 2 ELSE 3 END AS STEP
               , CASE WHEN o.AMOUNT >= 100000 THEN '큰 주문' WHEN o.AMOUNT IS NULL THEN '금액 없음' ELSE '보통' END AS SIZE_TEXT
            FROM ORDERS o""",
          """
          SELECT
                 o.ORDER_ID
               , CASE o.STATUS WHEN '접수' THEN 1 WHEN '진행' THEN 2 ELSE 3 END AS STEP
               , CASE WHEN o.AMOUNT >= 100000 THEN '큰 주문' WHEN o.AMOUNT IS NULL THEN '금액 없음' ELSE '보통' END AS SIZE_TEXT
            FROM ORDERS o"""),
      new Example("Oracle 함수", "LNNVL · NANVL (NULL · 숫자 아님 처리)", "LNNVL(조건) 은 조건이 거짓이거나 NULL 이면 참. NOT 과 달리 NULL 줄도 고릅니다.",
          """
          SELECT
                 o.ORDER_ID
               , o.STATUS
            FROM ORDERS o
           WHERE LNNVL(o.STATUS = '완료')""",
          """
          SELECT
                 o.ORDER_ID
               , o.STATUS
            FROM ORDERS o
           WHERE o.STATUS IS DISTINCT FROM '완료'"""),
      new Example("Oracle 함수", "PRIOR · LEVEL · CONNECT_BY_ROOT 함께 (부모 이름 붙여 펼치기)", "CONNECT BY 안에서 PRIOR 칼럼으로 부모 줄 값을 꺼냅니다.",
          """
          SELECT
                 LEVEL AS LVL
               , m.MENU_NAME
               , PRIOR m.MENU_NAME AS PARENT_NAME
               , CONNECT_BY_ROOT m.MENU_NAME AS ROOT_NAME
            FROM MENU m
           START WITH m.PARENT_ID IS NULL
          CONNECT BY PRIOR m.MENU_ID = m.PARENT_ID""",
          """
          WITH RECURSIVE t AS (
          SELECT
                 1 AS LVL
               , m.MENU_ID
               , m.MENU_NAME
               , CAST(NULL AS VARCHAR) AS PARENT_NAME
               , m.MENU_NAME AS ROOT_NAME
            FROM MENU m
           WHERE m.PARENT_ID IS NULL
           UNION ALL
          SELECT
                 t.LVL + 1
               , m.MENU_ID
               , m.MENU_NAME
               , t.MENU_NAME
               , t.ROOT_NAME
            FROM MENU m
               , t
           WHERE m.PARENT_ID = t.MENU_ID
             AND t.LVL < 20
          )
          SELECT
                 t.LVL
               , t.MENU_NAME
               , t.PARENT_NAME
               , t.ROOT_NAME
            FROM t"""),
      new Example("Oracle 함수", "TABLE(시스템 함수) · 컬렉션 펼치기", "SYS.ODCIVARCHAR2LIST 로 값 목록을 표처럼 씁니다. IN 목록을 하나의 값으로 받을 때.",
          """
          SELECT
                 COLUMN_VALUE AS PART_NO
            FROM TABLE(SYS.ODCIVARCHAR2LIST('P001', 'P002', 'P003'))""",
          """
          SELECT
                 X AS PART_NO
            FROM UNNEST(ARRAY['P001', 'P002', 'P003']) AS X"""),
      new Example("Oracle 함수", "오라클 날짜 빼기 = 일 수 (소수는 시간)", "DATE − DATE 는 일 수입니다. 0.5 는 12시간. × 24 하면 시간, × 1440 하면 분.",
          """
          SELECT
                 b.JOB_NAME
               , ROUND((CAST(b.END_TS AS DATE) - CAST(b.START_TS AS DATE)) * 1440, 1) AS MINUTES
            FROM BATCH_LOG b""",
          """
          SELECT
                 b.JOB_NAME
               , ROUND(CAST(EXTRACT(EPOCH FROM (b.END_TS - b.START_TS)) / 60 AS NUMERIC), 1) AS MINUTES
            FROM BATCH_LOG b"""),
      new Example("Oracle 함수", "TO_CHAR 로 0 채운 번호 · FM 으로 공백 빼기", "TO_CHAR(5, 'FM000') 은 '005'. FM 이 없으면 앞에 부호 자리 공백이 붙습니다.",
          """
          SELECT
                 TO_CHAR(5, 'FM000') AS NO3
               , '[' || TO_CHAR(5, '000') || ']' AS WITH_SPACE
               , TO_CHAR(SYSDATE, 'FMMM/DD') AS NO_ZERO_DT
            FROM DUAL""",
          """
          SELECT
                 TO_CHAR(5, 'FM000') AS NO3
               , '[' || TO_CHAR(5, '000') || ']' AS WITH_SPACE
               , TO_CHAR(NOW(), 'FMMM/DD') AS NO_ZERO_DT"""),
      new Example("Oracle 함수", "ROLLUP 에서 GROUPING_ID (어느 소계 줄인지)", "소계 · 총계 줄을 숫자 하나로 구분합니다. 0 은 상세, 1 은 소계, 3 은 총계.",
          """
          SELECT
                 s.DEPT_ID
               , s.PART_NO
               , SUM(s.QTY * s.PRICE) AS AMT
               , GROUPING_ID(s.DEPT_ID, s.PART_NO) AS GID
            FROM SALES_ORDER s
           GROUP BY ROLLUP(s.DEPT_ID, s.PART_NO)
           ORDER BY
                 s.DEPT_ID
               , GID
               , s.PART_NO""",
          """
          SELECT
                 s.DEPT_ID
               , s.PART_NO
               , SUM(s.QTY * s.PRICE) AS AMT
               , GROUPING(s.DEPT_ID, s.PART_NO) AS GID
            FROM SALES_ORDER s
           GROUP BY ROLLUP(s.DEPT_ID, s.PART_NO)
           ORDER BY
                 s.DEPT_ID
               , GID
               , s.PART_NO"""),
      new Example("Oracle 함수", "MODEL 대신 쓰는 이월 계산 (앞 달 잔액 + 이번 달)", "분석 함수 SUM OVER 로 앞 달까지 쌓은 값에 이번 달을 더해 잔액 줄을 만듭니다.",
          """
          SELECT
                 b.YM
               , b.DEPT_ID
               , b.BUDGET_AMT
               , SUM(b.BUDGET_AMT) OVER (PARTITION BY b.DEPT_ID ORDER BY b.YM ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS CARRIED
            FROM BUDGET b""",
          """
          SELECT
                 b.YM
               , b.DEPT_ID
               , b.BUDGET_AMT
               , SUM(b.BUDGET_AMT) OVER (PARTITION BY b.DEPT_ID ORDER BY b.YM ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS CARRIED
            FROM BUDGET b"""),
      new Example("Oracle 함수", "SAMPLE (표에서 몇 % 만 뽑기)", "큰 표를 다 읽지 않고 일부만 뽑아 봅니다. 결과는 실행마다 다릅니다.",
          """
          SELECT
                 o.*
            FROM ORDERS SAMPLE (10) o""",
          """
          SELECT
                 o.*
            FROM ORDERS o TABLESAMPLE BERNOULLI (10)"""),
      new Example("Oracle 함수", "AS OF TIMESTAMP (지운 · 고친 데이터 되살려 보기)", "언두가 남아 있으면 몇 분 전 모습을 볼 수 있습니다(플래시백). PostgreSQL 에는 없습니다.",
          """
          SELECT
                 o.*
            FROM ORDERS AS OF TIMESTAMP (SYSTIMESTAMP - INTERVAL '10' MINUTE) o
           WHERE o.ORDER_ID = :orderId""",
          """
          SELECT
                 o.*
            FROM ORDERS o
           WHERE o.ORDER_ID = CAST(:orderId AS INTEGER)"""),
      new Example("트랜잭션 · 처리 순서", "부모 먼저 넣고 자식 넣기", "외래 키가 있으면 부모(주문)를 먼저 넣고, 그 번호로 자식(품목)을 넣은 뒤 COMMIT 합니다. 이 문장은 부모 한 줄입니다.",
          """
          INSERT INTO ORDERS (ORDER_ID, CUST_ID, STATUS, ORDER_DT, AMOUNT)
          VALUES (:orderId, :custId, '접수', TRUNC(SYSDATE), 0)""",
          """
          INSERT INTO ORDERS (ORDER_ID, CUST_ID, STATUS, ORDER_DT, AMOUNT)
          VALUES (CAST(:orderId AS INTEGER), CAST(:custId AS INTEGER), '접수', CURRENT_DATE, 0)"""),
      new Example("트랜잭션 · 처리 순서", "고친 줄 수 확인하며 고치기 (낙관적 잠금)", "읽을 때의 고친 시각이 그대로일 때만 고칩니다. 0 건이면 누가 먼저 고친 것입니다.",
          """
          UPDATE ORDERS o
             SET o.STATUS = :newStatus
               , o.REG_DT = SYSTIMESTAMP
           WHERE o.ORDER_ID = :orderId
             AND o.REG_DT = :readRegDt""",
          """
          UPDATE ORDERS o
             SET STATUS = :newStatus
               , REG_DT = CURRENT_TIMESTAMP
           WHERE o.ORDER_ID = CAST(:orderId AS INTEGER)
             AND o.REG_DT = CAST(:readRegDt AS TIMESTAMP)"""),
      new Example("시험 데이터 만들기", "번호 1 ~ N 줄 만들기", "시험 데이터의 뼈대. N 만큼 줄을 만듭니다.",
          """
          SELECT
                 LEVEL AS N
            FROM DUAL
          CONNECT BY LEVEL <= 100""",
          """
          SELECT
                 g AS N
            FROM GENERATE_SERIES(1, 100) g"""),
      new Example("시험 데이터 만들기", "가짜 주문 100건 만들기 (보기만)", "번호 · 아무 날짜 · 아무 금액 · 돌아가는 상태. INSERT … SELECT 앞에 붙이면 넣기가 됩니다.",
          """
          SELECT
                 100000 + LEVEL AS ORDER_ID
               , MOD(LEVEL, 10) + 1 AS CUST_ID
               , TRUNC(SYSDATE) - MOD(LEVEL * 7, 90) AS ORDER_DT
               , ROUND(DBMS_RANDOM.VALUE(1000, 100000)) AS AMOUNT
               , DECODE(MOD(LEVEL, 3), 0, '접수', 1, '진행', '완료') AS STATUS
            FROM DUAL
          CONNECT BY LEVEL <= 100""",
          """
          SELECT
                 100000 + g AS ORDER_ID
               , MOD(g, 10) + 1 AS CUST_ID
               , CURRENT_DATE - MOD(g * 7, 90) AS ORDER_DT
               , ROUND(CAST(1000 + RANDOM() * 99000 AS NUMERIC)) AS AMOUNT
               , CASE MOD(g, 3) WHEN 0 THEN '접수' WHEN 1 THEN '진행' ELSE '완료' END AS STATUS
            FROM GENERATE_SERIES(1, 100) g"""),
      new Example("시험 데이터 만들기", "부품 번호 꼴 만들기 (P0001 ~)", "앞 글자 + 0 채운 번호. 채번 꼴을 미리 볼 때.",
          """
          SELECT
                 'P' || LPAD(LEVEL, 4, '0') AS PART_NO
            FROM DUAL
          CONNECT BY LEVEL <= 20""",
          """
          SELECT
                 'P' || LPAD(CAST(g AS VARCHAR), 4, '0') AS PART_NO
            FROM GENERATE_SERIES(1, 20) g"""),
      new Example("시험 데이터 만들기", "운영 데이터 일부만 복사해 시험 표 만들기", "구조와 일부 데이터만 떠서 시험용 표를 만듭니다. 개인 정보 칸은 가립니다.",
          """
          CREATE TABLE CUSTOMER_TEST AS
          SELECT
                 c.CUST_ID
               , SUBSTR(c.CUST_NAME, 1, 1) || '**' AS CUST_NAME
               , c.GRADE
            FROM CUSTOMER c
           WHERE ROWNUM <= 100""",
          """
          CREATE TABLE CUSTOMER_TEST AS
          SELECT
                 c.CUST_ID
               , SUBSTR(c.CUST_NAME, 1, 1) || '**' AS CUST_NAME
               , c.GRADE
            FROM CUSTOMER c
           LIMIT 100"""),
      new Example("개인 정보 가리기", "이름 가운데 가리기 (홍*동)", "첫 글자와 끝 글자만 두고 가운데를 * 로. 두 글자면 끝 글자를 가립니다.",
          """
          SELECT
                 c.CUST_ID
               , CASE
                   WHEN LENGTH(c.CUST_NAME) <= 2 THEN SUBSTR(c.CUST_NAME, 1, 1) || '*'
                   ELSE SUBSTR(c.CUST_NAME, 1, 1) || RPAD('*', LENGTH(c.CUST_NAME) - 2, '*') || SUBSTR(c.CUST_NAME, -1)
                 END AS MASKED
            FROM CUSTOMER c""",
          """
          SELECT
                 c.CUST_ID
               , CASE
                   WHEN LENGTH(c.CUST_NAME) <= 2 THEN SUBSTR(c.CUST_NAME, 1, 1) || '*'
                   ELSE SUBSTR(c.CUST_NAME, 1, 1) || REPEAT('*', LENGTH(c.CUST_NAME) - 2) || RIGHT(c.CUST_NAME, 1)
                 END AS MASKED
            FROM CUSTOMER c"""),
      new Example("개인 정보 가리기", "전화번호 가운데 가리기 (010-****-1234)", "숫자만 남긴 뒤 가운데 네 자리를 가립니다.",
          """
          SELECT
                 o.ORDER_ID
               , REGEXP_REPLACE(o.TEL, '^(\\d{2,3})-?(\\d{3,4})-?(\\d{4})$', '\\1-****-\\3') AS MASKED_TEL
            FROM ORDERS o""",
          """
          SELECT
                 o.ORDER_ID
               , REGEXP_REPLACE(o.TEL, '^(\\d{2,3})-?(\\d{3,4})-?(\\d{4})$', '\\1-****-\\3') AS MASKED_TEL
            FROM ORDERS o"""),
      new Example("개인 정보 가리기", "이메일 앞부분 가리기 (ab***@mail.com)", "@ 앞 두 글자만 남기고 가립니다.",
          """
          SELECT
                 o.ORDER_ID
               , SUBSTR(o.EMAIL, 1, 2) || '***' || SUBSTR(o.EMAIL, INSTR(o.EMAIL, '@')) AS MASKED_EMAIL
            FROM ORDERS o
           WHERE o.EMAIL LIKE '%@%'""",
          """
          SELECT
                 o.ORDER_ID
               , SUBSTR(o.EMAIL, 1, 2) || '***' || SUBSTR(o.EMAIL, POSITION('@' IN o.EMAIL)) AS MASKED_EMAIL
            FROM ORDERS o
           WHERE o.EMAIL LIKE '%@%'"""),
      new Example("뷰 · 권한", "자주 쓰는 조인을 뷰로", "조인을 뷰로 만들어 두면 화면 쿼리가 짧아집니다. 뷰는 데이터를 따로 갖지 않습니다.",
          """
          CREATE OR REPLACE VIEW V_ORDER_CUST AS
          SELECT
                 o.ORDER_ID
               , o.ORDER_DT
               , o.AMOUNT
               , c.CUST_NAME
            FROM ORDERS o
               , CUSTOMER c
           WHERE o.CUST_ID = c.CUST_ID(+)""",
          """
          CREATE OR REPLACE VIEW V_ORDER_CUST AS
          SELECT
                 o.ORDER_ID
               , o.ORDER_DT
               , o.AMOUNT
               , c.CUST_NAME
            FROM ORDERS o
            LEFT OUTER JOIN CUSTOMER c
              ON o.CUST_ID = c.CUST_ID"""),
      new Example("뷰 · 권한", "다른 계정에 읽기 권한 주기", "표를 다른 계정이 읽게 합니다. 쓰기까지 주려면 INSERT · UPDATE · DELETE 를 더합니다.",
          """
          GRANT SELECT ON ORDERS TO REPORT_USER""",
          """
          GRANT SELECT ON ORDERS TO REPORT_USER"""),
      new Example("뷰 · 권한", "내가 가진 권한 보기", "지금 계정이 받은 표 권한 목록.",
          """
          SELECT
                 p.OWNER
               , p.TABLE_NAME
               , p.PRIVILEGE
            FROM USER_TAB_PRIVS p
           ORDER BY
                 p.TABLE_NAME""",
          """
          SELECT
                 p.TABLE_SCHEMA
               , p.TABLE_NAME
               , p.PRIVILEGE_TYPE
            FROM INFORMATION_SCHEMA.TABLE_PRIVILEGES p
           WHERE p.GRANTEE = CURRENT_USER
           ORDER BY
                 p.TABLE_NAME"""),
      new Example("프로시저 · 블록", "한 번만 돌리는 블록 (반복하며 고치기)", "여러 줄을 하나씩 처리할 때. Oracle 은 BEGIN … END, PostgreSQL 은 DO 블록입니다.",
          """
          BEGIN
            FOR r IN (SELECT f.IF_ID FROM IF_PART f WHERE f.STATUS = 'ERROR') LOOP
              UPDATE IF_PART
                 SET STATUS = 'READY'
                   , RETRY_CNT = RETRY_CNT + 1
               WHERE IF_ID = r.IF_ID;
            END LOOP;
          END;""",
          """
          DO $$
          DECLARE
            r RECORD;
          BEGIN
            FOR r IN SELECT f.IF_ID FROM IF_PART f WHERE f.STATUS = 'ERROR' LOOP
              UPDATE IF_PART
                 SET STATUS = 'READY'
                   , RETRY_CNT = RETRY_CNT + 1
               WHERE IF_ID = r.IF_ID;
            END LOOP;
          END $$"""),
      new Example("프로시저 · 블록", "값 하나 돌려주는 함수 만들기 (코드 → 이름)", "자주 쓰는 코드 이름 찾기를 함수로. 큰 쿼리에서 줄마다 부르면 느릴 수 있습니다.",
          """
          CREATE OR REPLACE FUNCTION FN_CODE_NAME(P_GROUP IN VARCHAR2, P_CODE IN VARCHAR2) RETURN VARCHAR2 IS
            V_NAME COMMON_CODE.CODE_NAME%TYPE;
          BEGIN
            SELECT c.CODE_NAME INTO V_NAME
              FROM COMMON_CODE c
             WHERE c.GROUP_CD = P_GROUP
               AND c.CODE = P_CODE;
            RETURN V_NAME;
          EXCEPTION
            WHEN NO_DATA_FOUND THEN
              RETURN NULL;
          END;""",
          """
          CREATE OR REPLACE FUNCTION FN_CODE_NAME(P_GROUP VARCHAR, P_CODE VARCHAR) RETURNS VARCHAR AS $$
            SELECT c.CODE_NAME
              FROM COMMON_CODE c
             WHERE c.GROUP_CD = P_GROUP
               AND c.CODE = P_CODE
          $$ LANGUAGE SQL STABLE"""),
      new Example("프로시저 · 블록", "오류가 나도 다음 줄 계속 (줄마다 예외 잡기)", "한 줄이 실패해도 멈추지 않고 실패 표시를 남긴 뒤 다음 줄로 넘어갑니다.",
          """
          BEGIN
            FOR r IN (SELECT f.IF_ID, f.PART_NO, f.PART_NAME FROM IF_PART f WHERE f.STATUS = 'READY') LOOP
              BEGIN
                UPDATE ERP_PART
                   SET PART_NAME = r.PART_NAME
                 WHERE PART_NO = r.PART_NO;
                UPDATE IF_PART SET STATUS = 'DONE' WHERE IF_ID = r.IF_ID;
              EXCEPTION
                WHEN OTHERS THEN
                  UPDATE IF_PART SET STATUS = 'ERROR', ERR_MSG = SUBSTR(SQLERRM, 1, 200) WHERE IF_ID = r.IF_ID;
              END;
            END LOOP;
          END;""",
          """
          DO $$
          DECLARE
            r RECORD;
          BEGIN
            FOR r IN SELECT f.IF_ID, f.PART_NO, f.PART_NAME FROM IF_PART f WHERE f.STATUS = 'READY' LOOP
              BEGIN
                UPDATE ERP_PART
                   SET PART_NAME = r.PART_NAME
                 WHERE PART_NO = r.PART_NO;
                UPDATE IF_PART SET STATUS = 'DONE' WHERE IF_ID = r.IF_ID;
              EXCEPTION
                WHEN OTHERS THEN
                  UPDATE IF_PART SET STATUS = 'ERROR', ERR_MSG = LEFT(SQLERRM, 200) WHERE IF_ID = r.IF_ID;
              END;
            END LOOP;
          END $$"""),
      new Example("PostgreSQL 함수", "STRING_AGG (여러 줄을 한 칸에)", "Oracle LISTAGG 와 같습니다. 순서는 안쪽 ORDER BY 로.",
          """
          SELECT
                 i.ORDER_ID
               , LISTAGG(i.PART_NO, ', ') WITHIN GROUP (ORDER BY i.LINE_NO) AS PARTS
            FROM ORDER_ITEM i
           GROUP BY
                 i.ORDER_ID""",
          """
          SELECT
                 i.ORDER_ID
               , STRING_AGG(i.PART_NO, ', ' ORDER BY i.LINE_NO) AS PARTS
            FROM ORDER_ITEM i
           GROUP BY
                 i.ORDER_ID"""),
      new Example("PostgreSQL 함수", "FILTER (조건부 집계를 짧게)", "SUM(CASE WHEN …) 대신 COUNT(*) FILTER (WHERE …). Oracle 은 CASE 로 씁니다.",
          """
          SELECT
                 o.CUST_ID
               , COUNT(*) AS TOTAL
               , COUNT(CASE WHEN o.STATUS = '완료' THEN 1 END) AS DONE_CNT
               , SUM(CASE WHEN o.STATUS = '완료' THEN o.AMOUNT END) AS DONE_AMT
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID""",
          """
          SELECT
                 o.CUST_ID
               , COUNT(*) AS TOTAL
               , COUNT(*) FILTER (WHERE o.STATUS = '완료') AS DONE_CNT
               , SUM(o.AMOUNT) FILTER (WHERE o.STATUS = '완료') AS DONE_AMT
            FROM ORDERS o
           GROUP BY
                 o.CUST_ID"""),
      new Example("PostgreSQL 함수", "DISTINCT ON (그룹마다 첫 줄 하나)", "고객마다 가장 최근 주문 한 줄. ORDER BY 앞 칼럼이 DISTINCT ON 칼럼과 같아야 합니다.",
          """
          SELECT
                 x.*
            FROM (
                  SELECT
                         o.*
                       , ROW_NUMBER() OVER (PARTITION BY o.CUST_ID ORDER BY o.ORDER_DT DESC) AS RN
                    FROM ORDERS o
                 ) x
           WHERE x.RN = 1""",
          """
          SELECT DISTINCT ON (o.CUST_ID)
                 o.*
            FROM ORDERS o
           ORDER BY
                 o.CUST_ID
               , o.ORDER_DT DESC"""),
      new Example("PostgreSQL 함수", ":: 로 형 바꾸기 · TO_CHAR · TO_DATE", "PostgreSQL 은 '2026-01-01'::date 처럼 :: 로 형을 바꿀 수 있습니다. CAST(… AS …) 와 같습니다.",
          """
          SELECT
                 TO_DATE('2026-01-01', 'YYYY-MM-DD') AS D
               , TO_NUMBER('123') AS N
               , TO_CHAR(SYSDATE, 'YYYY-MM-DD HH24:MI') AS NOW_TEXT
            FROM DUAL""",
          """
          SELECT
                 '2026-01-01'::DATE AS D
               , '123'::NUMERIC AS N
               , TO_CHAR(NOW(), 'YYYY-MM-DD HH24:MI') AS NOW_TEXT"""),
      new Example("PostgreSQL 함수", "INTERVAL 로 날짜 계산", "PostgreSQL 은 날짜 + INTERVAL '3 month'. Oracle 은 ADD_MONTHS · 숫자 더하기.",
          """
          SELECT
                 ADD_MONTHS(TRUNC(SYSDATE), 3) AS AFTER_3M
               , TRUNC(SYSDATE) - 7 AS BEFORE_7D
               , SYSDATE + 2 / 24 AS AFTER_2H
            FROM DUAL""",
          """
          SELECT
                 CURRENT_DATE + INTERVAL '3 MONTH' AS AFTER_3M
               , CURRENT_DATE - INTERVAL '7 DAY' AS BEFORE_7D
               , NOW() + INTERVAL '2 HOUR' AS AFTER_2H"""),
      new Example("PostgreSQL 함수", "AGE · EXTRACT (나이 · 경과 기간)", "두 날짜 사이를 년 · 월 · 일로. EXTRACT 로 한 부분만 꺼냅니다.",
          """
          SELECT
                 p.PROJ_ID
               , TRUNC(MONTHS_BETWEEN(SYSDATE, p.START_DT)) AS MONTHS
               , EXTRACT(YEAR FROM p.START_DT) AS START_YEAR
            FROM PROJECT p""",
          """
          SELECT
                 p.PROJ_ID
               , AGE(CURRENT_DATE, p.START_DT) AS ELAPSED
               , EXTRACT(YEAR FROM AGE(CURRENT_DATE, p.START_DT)) * 12 + EXTRACT(MONTH FROM AGE(CURRENT_DATE, p.START_DT)) AS MONTHS
               , EXTRACT(YEAR FROM p.START_DT) AS START_YEAR
            FROM PROJECT p"""),
      new Example("PostgreSQL 함수", "RETURNING (넣은 · 고친 줄 바로 받기)", "INSERT · UPDATE · DELETE 뒤 바뀐 줄을 바로 돌려받습니다. Oracle 은 PL/SQL 의 RETURNING INTO.",
          """
          UPDATE IF_PART f
             SET f.STATUS = 'READY'
           WHERE f.STATUS = 'ERROR'""",
          """
          UPDATE IF_PART f
             SET STATUS = 'READY'
           WHERE f.STATUS = 'ERROR'
          RETURNING f.IF_ID, f.PART_NO"""),
      new Example("PostgreSQL 함수", "UPDATE … FROM (다른 표와 이어 고치기)", "PostgreSQL 은 UPDATE 에 FROM 을 붙여 다른 표 값으로 고칩니다. Oracle 은 MERGE 나 서브쿼리.",
          """
          MERGE INTO ERP_PART e
          USING PART p
             ON (e.PART_NO = p.PART_NO)
           WHEN MATCHED THEN
                UPDATE
                   SET e.UNIT_COST = p.UNIT_COST""",
          """
          UPDATE ERP_PART e
             SET UNIT_COST = p.UNIT_COST
            FROM PART p
           WHERE e.PART_NO = p.PART_NO"""),
      new Example("PostgreSQL 함수", "DELETE … USING (다른 표와 이어 지우기)", "PostgreSQL 은 DELETE 에 USING 으로 다른 표를 붙입니다.",
          """
          DELETE FROM ORDERS_BAK b
           WHERE EXISTS (SELECT 1 FROM ORDERS o WHERE o.ORDER_ID = b.ORDER_ID)""",
          """
          DELETE FROM ORDERS_BAK b
           USING ORDERS o
           WHERE o.ORDER_ID = b.ORDER_ID"""),
      new Example("PostgreSQL 함수", "ARRAY · ANY (값 목록을 배열로)", "IN 대신 = ANY(배열). 화면에서 받은 여러 값을 배열 하나로 넘길 때 씁니다.",
          """
          SELECT
                 p.PART_NO
            FROM PART p
           WHERE p.PART_NO IN ('P001', 'P002', 'P003')""",
          """
          SELECT
                 p.PART_NO
            FROM PART p
           WHERE p.PART_NO = ANY (ARRAY['P001', 'P002', 'P003'])"""),
      new Example("PostgreSQL 함수", "LATERAL (줄마다 서브쿼리 결과 여러 칸)", "고객마다 최근 주문 3건을 붙입니다. Oracle 12c 이상은 CROSS APPLY · LATERAL.",
          """
          SELECT
                 c.CUST_ID
               , r.ORDER_ID
               , r.ORDER_DT
            FROM CUSTOMER c
           CROSS APPLY (
                  SELECT
                         o.ORDER_ID
                       , o.ORDER_DT
                    FROM ORDERS o
                   WHERE o.CUST_ID = c.CUST_ID
                   ORDER BY
                         o.ORDER_DT DESC
                   FETCH FIRST 3 ROWS ONLY
                 ) r""",
          """
          SELECT
                 c.CUST_ID
               , r.ORDER_ID
               , r.ORDER_DT
            FROM CUSTOMER c
           CROSS JOIN LATERAL (
                  SELECT
                         o.ORDER_ID
                       , o.ORDER_DT
                    FROM ORDERS o
                   WHERE o.CUST_ID = c.CUST_ID
                   ORDER BY
                         o.ORDER_DT DESC
                   LIMIT 3
                 ) r"""),
      new Example("PostgreSQL 함수", "SPLIT_PART · REGEXP_SPLIT_TO_TABLE (글자 나누기)", "'A-B-C' 의 두 번째 조각, 또는 쉼표 글자를 줄로 펼치기.",
          """
          SELECT
                 REGEXP_SUBSTR('A-B-C', '[^-]+', 1, 2) AS SECOND_PART
            FROM DUAL""",
          """
          SELECT
                 SPLIT_PART('A-B-C', '-', 2) AS SECOND_PART
               , (SELECT STRING_AGG(x, '|') FROM REGEXP_SPLIT_TO_TABLE('a,b,c', ',') x) AS SPLIT_JOINED"""),
      new Example("PostgreSQL 함수", "GENERATE_SERIES (번호 · 날짜 줄 만들기)", "Oracle 의 CONNECT BY LEVEL 과 같은 일을 합니다.",
          """
          SELECT
                 TRUNC(SYSDATE) + LEVEL - 1 AS DT
            FROM DUAL
          CONNECT BY LEVEL <= 7""",
          """
          SELECT
                 CAST(g AS DATE) AS DT
            FROM GENERATE_SERIES(CURRENT_DATE, CURRENT_DATE + 6, INTERVAL '1 DAY') g"""),
      new Example("PostgreSQL 함수", "COALESCE · NULLIF · GREATEST (NVL 대신)", "PostgreSQL 에는 NVL · NVL2 · DECODE 가 없습니다. COALESCE · CASE 로 씁니다.",
          """
          SELECT
                 NVL(p.UNIT_COST, 0) AS COST
               , NVL2(p.OWNER_ID, 'Y', 'N') AS HAS_OWNER
               , DECODE(p.STATUS, 'USE', '사용', '중지') AS STATUS_NAME
            FROM PART p""",
          """
          SELECT
                 COALESCE(p.UNIT_COST, 0) AS COST
               , CASE WHEN p.OWNER_ID IS NOT NULL THEN 'Y' ELSE 'N' END AS HAS_OWNER
               , CASE p.STATUS WHEN 'USE' THEN '사용' ELSE '중지' END AS STATUS_NAME
            FROM PART p"""),
      new Example("PostgreSQL 함수", "ILIKE (대소문자 무시 LIKE)", "PostgreSQL 은 ILIKE 로 대소문자를 무시합니다. Oracle 은 UPPER 를 양쪽에.",
          """
          SELECT
                 p.PART_NO
            FROM PART p
           WHERE UPPER(p.PART_NAME) LIKE UPPER('%bolt%')""",
          """
          SELECT
                 p.PART_NO
            FROM PART p
           WHERE p.PART_NAME ILIKE '%bolt%'"""),
      new Example("PostgreSQL 함수", "시퀀스 다음 번호 (NEXTVAL)", "Oracle 은 시퀀스.NEXTVAL, PostgreSQL 은 NEXTVAL('시퀀스').",
          """
          SELECT
                 SEQ_ORDER.NEXTVAL AS NEXT_ID
            FROM DUAL""",
          """
          SELECT
                 NEXTVAL('seq_order') AS NEXT_ID"""),
      new Example("DB 운영", "지금 접속한 세션 (프로그램 · 사용자별)", "누가 어떤 프로그램으로 몇 개 붙어 있는지. 접속이 새는지 볼 때 씁니다.",
          """
          SELECT
                 s.USERNAME
               , s.PROGRAM
               , s.MACHINE
               , s.STATUS
               , COUNT(*) AS SESSIONS
            FROM V$SESSION s
           WHERE s.TYPE = 'USER'
           GROUP BY
                 s.USERNAME
               , s.PROGRAM
               , s.MACHINE
               , s.STATUS
           ORDER BY
                 SESSIONS DESC""",
          """
          SELECT
                 a.USENAME AS USERNAME
               , a.APPLICATION_NAME AS PROGRAM
               , a.CLIENT_ADDR AS MACHINE
               , a.STATE AS STATUS
               , COUNT(*) AS SESSIONS
            FROM PG_STAT_ACTIVITY a
           WHERE a.BACKEND_TYPE = 'client backend'
           GROUP BY
                 a.USENAME
               , a.APPLICATION_NAME
               , a.CLIENT_ADDR
               , a.STATE
           ORDER BY
                 SESSIONS DESC"""),
      new Example("DB 운영", "오래 놀고 있는 트랜잭션 (idle in transaction)", "트랜잭션을 연 채 손 놓은 세션은 락을 쥐고 있습니다. 5분 넘은 것.",
          """
          SELECT
                 s.SID
               , s.SERIAL#
               , s.USERNAME
               , s.PROGRAM
               , t.START_TIME
               , ROUND((SYSDATE - TO_DATE(t.START_TIME, 'MM/DD/YY HH24:MI:SS')) * 1440) AS OPEN_MIN
            FROM V$SESSION s, V$TRANSACTION t
           WHERE s.TADDR = t.ADDR
             AND s.STATUS = 'INACTIVE'
             AND TO_DATE(t.START_TIME, 'MM/DD/YY HH24:MI:SS') < SYSDATE - 5 / 1440""",
          """
          SELECT
                 a.PID
               , a.USENAME
               , a.APPLICATION_NAME
               , a.XACT_START
               , ROUND(EXTRACT(EPOCH FROM NOW() - a.XACT_START) / 60) AS OPEN_MIN
            FROM PG_STAT_ACTIVITY a
           WHERE a.STATE = 'idle in transaction'
             AND a.XACT_START < NOW() - INTERVAL '5 MINUTE'"""),
      new Example("DB 운영", "세션 끊기 (확인 뒤에만)", "위에서 찾은 세션을 끊습니다. 되돌릴 수 없으니 SID · SERIAL# 을 꼭 확인하고 되돌릴 수 없으니 SID · 번호를 꼭 확인하세요.lt;SID되돌릴 수 없으니 SID · 번호를 꼭 확인하세요.gt; 자리에 넣으세요.",
          """
          ALTER SYSTEM KILL SESSION '<SID>,<SERIAL#>' IMMEDIATE""",
          """
          SELECT
                 PG_TERMINATE_BACKEND(a.PID)
            FROM PG_STAT_ACTIVITY a
           WHERE a.PID = :pid"""),
      new Example("DB 운영", "안 쓰는 색인 (한 번도 안 읽힘)", "PostgreSQL 은 통계에서 읽힌 횟수 0 인 색인. Oracle 은 ALTER INDEX … MONITORING USAGE 를 켠 뒤 USER_OBJECT_USAGE 로 봅니다(12.2 이상은 DBA_INDEX_USAGE).",
          """
          SELECT
                 u.INDEX_NAME
               , u.TABLE_NAME
               , u.MONITORING
               , u.USED
               , u.START_MONITORING
            FROM USER_OBJECT_USAGE u
           WHERE u.USED = 'NO'""",
          """
          SELECT
                 s.RELNAME AS TABLE_NAME
               , s.INDEXRELNAME AS INDEX_NAME
               , s.IDX_SCAN
               , PG_SIZE_PRETTY(PG_RELATION_SIZE(s.INDEXRELID)) AS SIZE
            FROM PG_STAT_USER_INDEXES s, PG_INDEX i
           WHERE s.INDEXRELID = i.INDEXRELID
             AND s.IDX_SCAN = 0
             AND NOT i.INDISUNIQUE
           ORDER BY
                 PG_RELATION_SIZE(s.INDEXRELID) DESC"""),
      new Example("DB 운영", "통계가 오래된 표", "통계를 모은 뒤 많이 바뀐 표는 실행 계획이 틀어집니다.",
          """
          SELECT
                 s.TABLE_NAME
               , s.NUM_ROWS
               , s.LAST_ANALYZED
               , s.STALE_STATS
            FROM USER_TAB_STATISTICS s
           WHERE s.STALE_STATS = 'YES'
              OR s.LAST_ANALYZED IS NULL
           ORDER BY
                 s.LAST_ANALYZED NULLS FIRST""",
          """
          SELECT
                 s.RELNAME AS TABLE_NAME
               , s.N_LIVE_TUP
               , s.N_MOD_SINCE_ANALYZE
               , GREATEST(s.LAST_ANALYZE, s.LAST_AUTOANALYZE) AS LAST_ANALYZED
            FROM PG_STAT_USER_TABLES s
           WHERE s.N_MOD_SINCE_ANALYZE > s.N_LIVE_TUP * 0.1
              OR GREATEST(s.LAST_ANALYZE, s.LAST_AUTOANALYZE) IS NULL
           ORDER BY
                 s.N_MOD_SINCE_ANALYZE DESC"""),
      new Example("DB 운영", "깨진 객체 (INVALID 뷰 · 프로시저)", "Oracle 은 표가 바뀌면 그것을 쓰는 뷰 · 패키지가 INVALID 가 됩니다. PostgreSQL 은 이런 상태가 없어 의존 객체 목록을 봅니다.",
          """
          SELECT
                 o.OBJECT_TYPE
               , o.OBJECT_NAME
               , o.LAST_DDL_TIME
            FROM USER_OBJECTS o
           WHERE o.STATUS = 'INVALID'
           ORDER BY
                 1, 2""",
          """
          SELECT DISTINCT
                 v.RELNAME AS VIEW_NAME
               , t.RELNAME AS USES_TABLE
            FROM PG_DEPEND d, PG_REWRITE r, PG_CLASS v, PG_CLASS t
           WHERE d.OBJID = r.OID
             AND r.EV_CLASS = v.OID
             AND d.REFOBJID = t.OID
             AND v.OID <> t.OID
             AND v.RELKIND = 'v'
           ORDER BY
                 1, 2"""),
      new Example("DB 운영", "표 공간 · DB 크기", "공간이 얼마나 찼는지. Oracle 은 DBA 권한이 있어야 DBA_ 뷰가 보입니다.",
          """
          SELECT
                 f.TABLESPACE_NAME
               , ROUND(SUM(f.BYTES) / 1024 / 1024) AS TOTAL_MB
               , ROUND(NVL(MAX(s.FREE_MB), 0)) AS FREE_MB
            FROM DBA_DATA_FILES f
               , (SELECT x.TABLESPACE_NAME, SUM(x.BYTES) / 1024 / 1024 AS FREE_MB FROM DBA_FREE_SPACE x GROUP BY x.TABLESPACE_NAME) s
           WHERE f.TABLESPACE_NAME = s.TABLESPACE_NAME(+)
           GROUP BY
                 f.TABLESPACE_NAME""",
          """
          SELECT
                 d.DATNAME
               , PG_SIZE_PRETTY(PG_DATABASE_SIZE(d.DATNAME)) AS SIZE
            FROM PG_DATABASE d
           ORDER BY
                 PG_DATABASE_SIZE(d.DATNAME) DESC"""),
      new Example("DB 운영", "많이 읽고 많이 쓰는 표 (전체 훑기 많은 표)", "색인 없이 표를 통째로 읽은 횟수가 많은 표. 색인을 볼 후보입니다.",
          """
          SELECT
                 s.OBJECT_NAME
               , s.STATISTIC_NAME
               , s.VALUE
            FROM V$SEGMENT_STATISTICS s
           WHERE s.OWNER = USER
             AND s.STATISTIC_NAME IN ('logical reads', 'physical reads')
           ORDER BY
                 s.VALUE DESC
           FETCH FIRST 20 ROWS ONLY""",
          """
          SELECT
                 s.RELNAME
               , s.SEQ_SCAN
               , s.SEQ_TUP_READ
               , s.IDX_SCAN
               , s.N_TUP_INS + s.N_TUP_UPD + s.N_TUP_DEL AS WRITES
            FROM PG_STAT_USER_TABLES s
           ORDER BY
                 s.SEQ_TUP_READ DESC
           LIMIT 20"""),
      new Example("DB 운영", "표 · 칼럼 설명 한 번에 보기 (표 정의서)", "표 정의서를 만들 때: 칼럼 순서 · 형 · 길이 · 빈 값 허용 · 설명.",
          """
          SELECT
                 c.TABLE_NAME
               , c.COLUMN_ID
               , c.COLUMN_NAME
               , c.DATA_TYPE || CASE WHEN c.DATA_TYPE LIKE '%CHAR%' THEN '(' || c.CHAR_LENGTH || ')' WHEN c.DATA_PRECISION IS NOT NULL THEN '(' || c.DATA_PRECISION || ',' || c.DATA_SCALE || ')' END AS COL_TYPE
               , c.NULLABLE
               , m.COMMENTS
            FROM USER_TAB_COLUMNS c
               , USER_COL_COMMENTS m
           WHERE c.TABLE_NAME = m.TABLE_NAME(+)
             AND c.COLUMN_NAME = m.COLUMN_NAME(+)
             AND c.TABLE_NAME = UPPER(:tableName)
           ORDER BY
                 c.COLUMN_ID""",
          """
          SELECT
                 c.TABLE_NAME
               , c.ORDINAL_POSITION
               , c.COLUMN_NAME
               , c.DATA_TYPE || CASE WHEN c.CHARACTER_MAXIMUM_LENGTH IS NOT NULL THEN '(' || c.CHARACTER_MAXIMUM_LENGTH || ')' WHEN c.NUMERIC_PRECISION IS NOT NULL THEN '(' || c.NUMERIC_PRECISION || ',' || c.NUMERIC_SCALE || ')' ELSE '' END AS COL_TYPE
               , c.IS_NULLABLE
               , COL_DESCRIPTION(CAST(c.TABLE_SCHEMA || '.' || c.TABLE_NAME AS REGCLASS), c.ORDINAL_POSITION) AS COMMENTS
            FROM INFORMATION_SCHEMA.COLUMNS c
           WHERE c.TABLE_SCHEMA = CURRENT_SCHEMA()
             AND c.TABLE_NAME = LOWER(:tableName)
           ORDER BY
                 c.ORDINAL_POSITION"""),
      new Example("DB 운영", "칼럼 이름이 같은데 형이 다른 곳 (표끼리 비교)", "같은 이름 칼럼이 표마다 다른 형 · 길이면 조인에서 형 바뀜이 생깁니다.",
          """
          SELECT
                 c.COLUMN_NAME
               , COUNT(DISTINCT c.DATA_TYPE || '(' || c.DATA_LENGTH || ')') AS TYPE_CNT
               , LISTAGG(c.TABLE_NAME || ':' || c.DATA_TYPE || '(' || c.DATA_LENGTH || ')', ', ') WITHIN GROUP (ORDER BY c.TABLE_NAME) AS DETAIL
            FROM USER_TAB_COLUMNS c
           GROUP BY
                 c.COLUMN_NAME
          HAVING COUNT(DISTINCT c.DATA_TYPE || '(' || c.DATA_LENGTH || ')') > 1""",
          """
          SELECT
                 c.COLUMN_NAME
               , COUNT(DISTINCT c.DATA_TYPE || '(' || COALESCE(c.CHARACTER_MAXIMUM_LENGTH, c.NUMERIC_PRECISION, 0) || ')') AS TYPE_CNT
               , STRING_AGG(c.TABLE_NAME || ':' || c.DATA_TYPE, ', ' ORDER BY c.TABLE_NAME) AS DETAIL
            FROM INFORMATION_SCHEMA.COLUMNS c
           WHERE c.TABLE_SCHEMA = CURRENT_SCHEMA()
           GROUP BY
                 c.COLUMN_NAME
          HAVING COUNT(DISTINCT c.DATA_TYPE || '(' || COALESCE(c.CHARACTER_MAXIMUM_LENGTH, c.NUMERIC_PRECISION, 0) || ')') > 1"""),
      new Example("DB 운영", "기본 키가 없는 표", "키가 없으면 중복이 쌓이고 MERGE · 수정이 어렵습니다.",
          """
          SELECT
                 t.TABLE_NAME
            FROM USER_TABLES t
           WHERE NOT EXISTS (
                  SELECT 1
                    FROM USER_CONSTRAINTS c
                   WHERE c.TABLE_NAME = t.TABLE_NAME
                     AND c.CONSTRAINT_TYPE = 'P'
                 )""",
          """
          SELECT
                 t.TABLE_NAME
            FROM INFORMATION_SCHEMA.TABLES t
           WHERE t.TABLE_SCHEMA = CURRENT_SCHEMA()
             AND t.TABLE_TYPE = 'BASE TABLE'
             AND NOT EXISTS (
                  SELECT 1
                    FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS c
                   WHERE c.TABLE_SCHEMA = t.TABLE_SCHEMA
                     AND c.TABLE_NAME = t.TABLE_NAME
                     AND c.CONSTRAINT_TYPE = 'PRIMARY KEY'
                 )"""),
      new Example("DB 운영", "외래 키 칼럼에 색인이 없는 것", "자식 표의 외래 키 칼럼에 색인이 없으면 부모 수정 · 삭제 때 잠금이 커집니다.",
          """
          SELECT
                 c.TABLE_NAME
               , cc.COLUMN_NAME
               , c.CONSTRAINT_NAME
            FROM USER_CONSTRAINTS c
               , USER_CONS_COLUMNS cc
           WHERE c.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
             AND c.CONSTRAINT_TYPE = 'R'
             AND NOT EXISTS (
                  SELECT 1
                    FROM USER_IND_COLUMNS i
                   WHERE i.TABLE_NAME = cc.TABLE_NAME
                     AND i.COLUMN_NAME = cc.COLUMN_NAME
                     AND i.COLUMN_POSITION = 1
                 )""",
          """
          SELECT
                 c.CONRELID::REGCLASS AS TABLE_NAME
               , a.ATTNAME AS COLUMN_NAME
               , c.CONNAME AS CONSTRAINT_NAME
            FROM PG_CONSTRAINT c
               , PG_ATTRIBUTE a
           WHERE c.CONTYPE = 'f'
             AND a.ATTRELID = c.CONRELID
             AND a.ATTNUM = c.CONKEY[1]
             AND NOT EXISTS (
                  SELECT 1
                    FROM PG_INDEX i
                   WHERE i.INDRELID = c.CONRELID
                     AND i.INDKEY[0] = c.CONKEY[1]
                 )"""),
      new Example("DB 운영", "표마다 정확한 건수 (모든 표 한 번에)", "통계 어림이 아니라 실제로 셉니다. 큰 DB 에서는 오래 걸립니다.",
          """
          SELECT
                 t.TABLE_NAME
               , TO_NUMBER(EXTRACTVALUE(XMLTYPE(DBMS_XMLGEN.GETXML('SELECT COUNT(*) C FROM ' || t.TABLE_NAME)), '/ROWSET/ROW/C')) AS ROW_CNT
            FROM USER_TABLES t
           ORDER BY
                 ROW_CNT DESC""",
          """
          SELECT
                 t.TABLE_NAME
               , (XPATH('/row/c/text()', QUERY_TO_XML('SELECT COUNT(*) AS c FROM ' || QUOTE_IDENT(t.TABLE_NAME), FALSE, TRUE, '')))[1]::TEXT::BIGINT AS ROW_CNT
            FROM INFORMATION_SCHEMA.TABLES t
           WHERE t.TABLE_SCHEMA = CURRENT_SCHEMA()
             AND t.TABLE_TYPE = 'BASE TABLE'
           ORDER BY
                 ROW_CNT DESC"""),
      new Example("DB 운영", "최근에 바뀐 객체 (표 · 뷰 · 프로시저)", "누가 무엇을 고쳤는지 모를 때 최근 7일 바뀐 DB 객체.",
          """
          SELECT
                 o.OBJECT_TYPE
               , o.OBJECT_NAME
               , o.LAST_DDL_TIME
            FROM USER_OBJECTS o
           WHERE o.LAST_DDL_TIME >= SYSDATE - 7
           ORDER BY
                 o.LAST_DDL_TIME DESC""",
          """
          SELECT
                 c.RELKIND AS OBJECT_TYPE
               , c.RELNAME AS OBJECT_NAME
               , s.LAST_ANALYZE
            FROM PG_CLASS c
               , PG_STAT_USER_TABLES s
           WHERE c.OID = s.RELID
           ORDER BY
                 s.LAST_ANALYZE DESC NULLS LAST"""),
      new Example("DB 운영", "시퀀스 지금 값과 표 최대 번호 비교", "시퀀스가 표의 최대 번호보다 작으면 다음 INSERT 에서 키가 겹칩니다.",
          """
          SELECT
                 s.SEQUENCE_NAME
               , s.LAST_NUMBER
               , (SELECT MAX(o.ORDER_ID) FROM ORDERS o) AS MAX_ID
            FROM USER_SEQUENCES s
           WHERE s.SEQUENCE_NAME = 'SEQ_ORDER'""",
          """
          SELECT
                 s.SEQUENCENAME AS SEQUENCE_NAME
               , s.LAST_VALUE
               , (SELECT MAX(o.ORDER_ID) FROM ORDERS o) AS MAX_ID
            FROM PG_SEQUENCES s
           WHERE s.SEQUENCENAME = 'seq_order'"""),
      new Example("DB 운영", "표 공간 사용률 (% · 자동 늘림 최대)", "표 공간이 몇 % 찼는지, 자동으로 늘어날 수 있는 최대까지 보면 얼마나 남았는지. DBA 권한이 있어야 합니다. PostgreSQL 은 표 공간마다 크기만 보입니다.",
          """
          SELECT
                 d.TABLESPACE_NAME
               , ROUND(d.TOTAL_MB) AS TOTAL_MB
               , ROUND(d.TOTAL_MB - NVL(f.FREE_MB, 0)) AS USED_MB
               , ROUND((d.TOTAL_MB - NVL(f.FREE_MB, 0)) / d.TOTAL_MB * 100, 1) AS USED_PCT
               , ROUND(d.MAX_MB) AS MAX_MB
               , ROUND((d.TOTAL_MB - NVL(f.FREE_MB, 0)) / d.MAX_MB * 100, 1) AS USED_OF_MAX_PCT
            FROM (
                  SELECT
                         df.TABLESPACE_NAME
                       , SUM(df.BYTES) / 1024 / 1024 AS TOTAL_MB
                       , SUM(GREATEST(df.MAXBYTES, df.BYTES)) / 1024 / 1024 AS MAX_MB
                    FROM DBA_DATA_FILES df
                   GROUP BY
                         df.TABLESPACE_NAME
                 ) d
               , (
                  SELECT
                         fs.TABLESPACE_NAME
                       , SUM(fs.BYTES) / 1024 / 1024 AS FREE_MB
                    FROM DBA_FREE_SPACE fs
                   GROUP BY
                         fs.TABLESPACE_NAME
                 ) f
           WHERE d.TABLESPACE_NAME = f.TABLESPACE_NAME(+)
           ORDER BY
                 USED_OF_MAX_PCT DESC""",
          """
          SELECT
                 t.SPCNAME AS TABLESPACE_NAME
               , PG_SIZE_PRETTY(PG_TABLESPACE_SIZE(t.OID)) AS USED_SIZE
               , PG_TABLESPACE_LOCATION(t.OID) AS LOCATION
            FROM PG_TABLESPACE t
           ORDER BY
                 PG_TABLESPACE_SIZE(t.OID) DESC"""),
      new Example("DB 운영", "용량 큰 것 (표 · 색인 · LOB 를 표 이름으로)", "색인 · LOB 세그먼트도 어느 표 것인지 붙여 큰 순으로 30개. PostgreSQL 은 표 · 색인 · TOAST 크기를 나눠 봅니다.",
          """
          SELECT *
            FROM (
                  SELECT
                         s.SEGMENT_TYPE
                       , s.SEGMENT_NAME
                       , COALESCE(i.TABLE_NAME, l.TABLE_NAME, s.SEGMENT_NAME) AS TABLE_NAME
                       , ROUND(s.BYTES / 1024 / 1024) AS SIZE_MB
                    FROM USER_SEGMENTS s
                       , USER_INDEXES i
                       , USER_LOBS l
                   WHERE s.SEGMENT_NAME = i.INDEX_NAME(+)
                     AND s.SEGMENT_NAME = l.SEGMENT_NAME(+)
                   ORDER BY
                         s.BYTES DESC
                 )
           WHERE ROWNUM <= 30""",
          """
          SELECT
                 c.RELNAME AS TABLE_NAME
               , PG_SIZE_PRETTY(PG_TOTAL_RELATION_SIZE(c.OID)) AS TOTAL_SIZE
               , PG_SIZE_PRETTY(PG_RELATION_SIZE(c.OID)) AS TABLE_SIZE
               , PG_SIZE_PRETTY(PG_INDEXES_SIZE(c.OID)) AS INDEX_SIZE
               , PG_SIZE_PRETTY(PG_TOTAL_RELATION_SIZE(c.OID) - PG_RELATION_SIZE(c.OID) - PG_INDEXES_SIZE(c.OID)) AS TOAST_SIZE
            FROM PG_CLASS c
           WHERE c.RELNAMESPACE = CURRENT_SCHEMA()::REGNAMESPACE
             AND c.RELKIND IN ('r', 'p')
           ORDER BY
                 PG_TOTAL_RELATION_SIZE(c.OID) DESC
           LIMIT 30"""),
      new Example("DB 운영", "깨진 색인 (UNUSABLE) 과 다시 만드는 문장", "파티션을 지우거나 표를 옮긴 뒤 색인이 UNUSABLE 이 되면 그 색인을 쓰는 SQL 이 오류 · 느려집니다. FIX_SQL 을 돌리면 다시 만듭니다.",
          """
          SELECT
                 i.INDEX_NAME
               , i.TABLE_NAME
               , i.STATUS
               , 'ALTER INDEX ' || i.INDEX_NAME || ' REBUILD' AS FIX_SQL
            FROM USER_INDEXES i
           WHERE i.STATUS = 'UNUSABLE'
           UNION ALL
          SELECT
                 p.INDEX_NAME
               , i.TABLE_NAME
               , p.STATUS
               , 'ALTER INDEX ' || p.INDEX_NAME || ' REBUILD PARTITION ' || p.PARTITION_NAME
            FROM USER_IND_PARTITIONS p
               , USER_INDEXES i
           WHERE p.INDEX_NAME = i.INDEX_NAME
             AND p.STATUS = 'UNUSABLE'""",
          """
          SELECT
                 i.RELNAME AS INDEX_NAME
               , x.INDRELID::REGCLASS AS TABLE_NAME
               , 'REINDEX INDEX ' || i.RELNAME AS FIX_SQL
            FROM PG_INDEX x
               , PG_CLASS i
           WHERE x.INDEXRELID = i.OID
             AND i.RELNAMESPACE = CURRENT_SCHEMA()::REGNAMESPACE
             AND NOT x.INDISVALID"""),
      new Example("DB 운영", "배치 작업 (JOB) 목록 · 다음 실행 시각", "DB 안에서 도는 예약 작업. 옛 DBMS_JOB 은 USER_JOBS 에 있습니다. PostgreSQL 은 pg_cron 확장이 있을 때만.",
          """
          SELECT
                 j.JOB_NAME
               , j.ENABLED
               , j.STATE
               , j.REPEAT_INTERVAL
               , j.LAST_START_DATE
               , j.NEXT_RUN_DATE
               , j.FAILURE_COUNT
            FROM USER_SCHEDULER_JOBS j
           ORDER BY
                 j.NEXT_RUN_DATE""",
          """
          SELECT
                 j.JOBID
               , j.JOBNAME
               , j.SCHEDULE
               , j.COMMAND
               , j.ACTIVE
            FROM CRON.JOB j
           ORDER BY
                 j.JOBID"""),
      new Example("DB 운영", "배치 작업 실행 이력 · 실패 (최근 7일)", "언제 돌았고 얼마나 걸렸고 무슨 오류였는지. 실패만 보려면 STATUS 조건을 더합니다.",
          """
          SELECT
                 d.JOB_NAME
               , d.ACTUAL_START_DATE
               , d.RUN_DURATION
               , d.STATUS
               , d.ERROR#
               , d.ADDITIONAL_INFO
            FROM USER_SCHEDULER_JOB_RUN_DETAILS d
           WHERE d.ACTUAL_START_DATE >= SYSTIMESTAMP - INTERVAL '7' DAY
           ORDER BY
                 d.ACTUAL_START_DATE DESC""",
          """
          SELECT
                 r.JOBID
               , r.START_TIME
               , r.END_TIME - r.START_TIME AS RUN_DURATION
               , r.STATUS
               , r.RETURN_MESSAGE
            FROM CRON.JOB_RUN_DETAILS r
           WHERE r.START_TIME >= NOW() - INTERVAL '7 days'
           ORDER BY
                 r.START_TIME DESC"""),
      new Example("DB 운영", "잠긴 계정 · 곧 비밀번호 만료되는 계정", "WAS 접속이 갑자기 안 될 때(ORA-28000 잠김 · ORA-28001 만료). DBA_USERS 는 DBA 권한이 있어야 하고, 없으면 USER_USERS 로 내 계정만 봅니다.",
          """
          SELECT
                 u.USERNAME
               , u.ACCOUNT_STATUS
               , u.LOCK_DATE
               , u.EXPIRY_DATE
               , u.PROFILE
            FROM DBA_USERS u
           WHERE (u.ACCOUNT_STATUS <> 'OPEN'
                  OR u.EXPIRY_DATE < SYSDATE + 30)
           ORDER BY
                 u.EXPIRY_DATE""",
          """
          SELECT
                 r.ROLNAME
               , r.ROLCANLOGIN
               , r.ROLVALIDUNTIL
            FROM PG_ROLES r
           WHERE r.ROLCANLOGIN
             AND r.ROLVALIDUNTIL < NOW() + INTERVAL '30 days'
           ORDER BY
                 r.ROLVALIDUNTIL"""),
      new Example("DB 운영", "휴지통에서 지운 표 되살리기 (FLASHBACK TO BEFORE DROP)", "DROP 한 표가 휴지통에 남아 있으면 FIX_SQL 로 되살립니다. PURGE 로 지웠으면 없습니다. PostgreSQL 은 휴지통이 없어 백업에서만 되살립니다.",
          """
          SELECT
                 r.ORIGINAL_NAME
               , r.OBJECT_NAME
               , r.TYPE
               , r.DROPTIME
               , 'FLASHBACK TABLE ' || r.ORIGINAL_NAME || ' TO BEFORE DROP' AS FIX_SQL
            FROM USER_RECYCLEBIN r
           WHERE r.TYPE = 'TABLE'
           ORDER BY
                 r.DROPTIME DESC""",
          """
          SELECT
                 'PostgreSQL 은 휴지통이 없어 DROP 한 표는 백업(pg_dump)에서만 되살립니다.' AS NOTE"""),
      new Example("DB 운영", "DB 버전 · 문자셋 (한글이 몇 바이트인가)", "AL32UTF8 은 한글 한 자 3바이트, KO16MSWIN949 는 2바이트. 칼럼 길이 · 한글 깨짐을 볼 때 먼저 확인합니다.",
          """
          SELECT
                 (SELECT v.BANNER FROM V$VERSION v WHERE ROWNUM = 1) AS VERSION
               , (SELECT p.VALUE FROM NLS_DATABASE_PARAMETERS p WHERE p.PARAMETER = 'NLS_CHARACTERSET') AS CHARSET
               , (SELECT p.VALUE FROM NLS_DATABASE_PARAMETERS p WHERE p.PARAMETER = 'NLS_NCHAR_CHARACTERSET') AS NCHAR_CHARSET
               , (SELECT p.VALUE FROM NLS_DATABASE_PARAMETERS p WHERE p.PARAMETER = 'NLS_LENGTH_SEMANTICS') AS LENGTH_SEMANTICS
            FROM DUAL""",
          """
          SELECT
                 VERSION() AS VERSION
               , PG_ENCODING_TO_CHAR(d.ENCODING) AS CHARSET
               , d.DATCOLLATE
               , d.DATCTYPE
            FROM PG_DATABASE d
           WHERE d.DATNAME = CURRENT_DATABASE()"""),
      new Example("DB 운영", "DB 링크 목록 (어느 DB 로 이어지나)", "다른 DB 를 부르는 링크와 접속 대상. PostgreSQL 은 외부 서버(postgres_fdw · dblink) 목록입니다.",
          """
          SELECT
                 l.OWNER
               , l.DB_LINK
               , l.USERNAME
               , l.HOST
               , l.CREATED
            FROM ALL_DB_LINKS l
           ORDER BY
                 l.OWNER
               , l.DB_LINK""",
          """
          SELECT
                 s.SRVNAME
               , w.FDWNAME
               , s.SRVOPTIONS
            FROM PG_FOREIGN_SERVER s
               , PG_FOREIGN_DATA_WRAPPER w
           WHERE s.SRVFDW = w.OID
           ORDER BY
                 s.SRVNAME"""),
      new Example("DB 운영", "통계 뒤로 많이 바뀐 표 · 정리(VACUUM) 필요한 표", "Oracle 은 통계 수집 뒤 넣기 · 고치기 · 지우기 건수(바로 보려면 DBMS_STATS.FLUSH_DATABASE_MONITORING_INFO 먼저). PostgreSQL 은 지운 줄 찌꺼기(dead tuple)가 많은 표.",
          """
          SELECT
                 m.TABLE_NAME
               , t.NUM_ROWS
               , m.INSERTS
               , m.UPDATES
               , m.DELETES
               , ROUND((m.INSERTS + m.UPDATES + m.DELETES) / NULLIF(t.NUM_ROWS, 0) * 100, 1) AS CHANGED_PCT
               , m.TIMESTAMP AS SINCE
            FROM USER_TAB_MODIFICATIONS m
               , USER_TABLES t
           WHERE m.TABLE_NAME = t.TABLE_NAME
             AND m.PARTITION_NAME IS NULL
           ORDER BY
                 m.INSERTS + m.UPDATES + m.DELETES DESC""",
          """
          SELECT
                 s.RELNAME AS TABLE_NAME
               , s.N_LIVE_TUP
               , s.N_DEAD_TUP
               , ROUND(100.0 * s.N_DEAD_TUP / NULLIF(s.N_LIVE_TUP + s.N_DEAD_TUP, 0), 1) AS DEAD_PCT
               , s.LAST_VACUUM
               , s.LAST_AUTOVACUUM
            FROM PG_STAT_USER_TABLES s
           ORDER BY
                 s.N_DEAD_TUP DESC"""),
      new Example("DB 운영", "DB 설정값 찾기 (이름 일부로)", "open_cursors · processes · statement_timeout 같은 설정을 이름 일부로 찾습니다.",
          """
          SELECT
                 p.NAME
               , p.VALUE
               , p.ISDEFAULT
               , p.DESCRIPTION
            FROM V$PARAMETER p
           WHERE p.NAME LIKE '%' || LOWER(:keyword) || '%'
           ORDER BY
                 p.NAME""",
          """
          SELECT
                 s.NAME
               , s.SETTING
               , s.UNIT
               , s.SOURCE
               , s.SHORT_DESC
            FROM PG_SETTINGS s
           WHERE s.NAME ILIKE '%' || :keyword || '%'
           ORDER BY
                 s.NAME"""),
      new Example("DB 운영", "세션 · 프로세스 한도 얼마나 찼나 (ORA-00018 · too many connections)", "접속이 안 될 때 지금 수 · 지금까지 최대 · 한도를 봅니다.",
          """
          SELECT
                 r.RESOURCE_NAME
               , r.CURRENT_UTILIZATION
               , r.MAX_UTILIZATION
               , r.LIMIT_VALUE
            FROM V$RESOURCE_LIMIT r
           WHERE r.RESOURCE_NAME IN ('sessions', 'processes', 'transactions')""",
          """
          SELECT
                 COUNT(*) AS NOW_CONN
               , CURRENT_SETTING('max_connections') AS LIMIT_CONN
               , CURRENT_SETTING('superuser_reserved_connections') AS RESERVED_CONN
            FROM PG_STAT_ACTIVITY a
           WHERE a.BACKEND_TYPE = 'client backend'"""),
      new Example("DB 운영", "설치된 옵션 · 확장 목록", "파티션 · 압축 같은 옵션이 켜져 있는지(Oracle), 어떤 확장이 깔려 있는지(PostgreSQL).",
          """
          SELECT
                 o.PARAMETER
               , o.VALUE
            FROM V$OPTION o
           ORDER BY
                 o.PARAMETER""",
          """
          SELECT
                 e.EXTNAME
               , e.EXTVERSION
            FROM PG_EXTENSION e
           ORDER BY
                 e.EXTNAME"""),
      new Example("개발 · 배포 점검", "개발 · 운영 표 구조 비교 (칼럼 다른 것)", "개발 DB 에서 운영 DB 링크(PROD_LINK)로 칼럼 · 형 · 길이 · NULL 허용을 맞대어 한쪽에만 있거나 다른 줄만 봅니다. PostgreSQL 은 같은 DB 의 두 스키마를 비교합니다.",
          """
          SELECT
                 'DEV 에만' AS SIDE
               , x.*
            FROM (
                  SELECT
                         c.TABLE_NAME
                       , c.COLUMN_NAME
                       , c.DATA_TYPE
                       , c.DATA_LENGTH
                       , c.NULLABLE
                    FROM USER_TAB_COLUMNS c
                   MINUS
                  SELECT
                         c.TABLE_NAME
                       , c.COLUMN_NAME
                       , c.DATA_TYPE
                       , c.DATA_LENGTH
                       , c.NULLABLE
                    FROM USER_TAB_COLUMNS@PROD_LINK c
                 ) x
           UNION ALL
          SELECT
                 'PROD 에만'
               , y.*
            FROM (
                  SELECT
                         c.TABLE_NAME
                       , c.COLUMN_NAME
                       , c.DATA_TYPE
                       , c.DATA_LENGTH
                       , c.NULLABLE
                    FROM USER_TAB_COLUMNS@PROD_LINK c
                   MINUS
                  SELECT
                         c.TABLE_NAME
                       , c.COLUMN_NAME
                       , c.DATA_TYPE
                       , c.DATA_LENGTH
                       , c.NULLABLE
                    FROM USER_TAB_COLUMNS c
                 ) y
           ORDER BY
                 2, 3, 1""",
          """
          SELECT
                 'DEV 에만' AS SIDE
               , x.*
            FROM (
                  SELECT
                         c.TABLE_NAME
                       , c.COLUMN_NAME
                       , c.DATA_TYPE
                       , c.CHARACTER_MAXIMUM_LENGTH
                       , c.IS_NULLABLE
                    FROM INFORMATION_SCHEMA.COLUMNS c
                   WHERE c.TABLE_SCHEMA = :devSchema
                  EXCEPT
                  SELECT
                         c.TABLE_NAME
                       , c.COLUMN_NAME
                       , c.DATA_TYPE
                       , c.CHARACTER_MAXIMUM_LENGTH
                       , c.IS_NULLABLE
                    FROM INFORMATION_SCHEMA.COLUMNS c
                   WHERE c.TABLE_SCHEMA = :prodSchema
                 ) x
           UNION ALL
          SELECT
                 'PROD 에만'
               , y.*
            FROM (
                  SELECT
                         c.TABLE_NAME
                       , c.COLUMN_NAME
                       , c.DATA_TYPE
                       , c.CHARACTER_MAXIMUM_LENGTH
                       , c.IS_NULLABLE
                    FROM INFORMATION_SCHEMA.COLUMNS c
                   WHERE c.TABLE_SCHEMA = :prodSchema
                  EXCEPT
                  SELECT
                         c.TABLE_NAME
                       , c.COLUMN_NAME
                       , c.DATA_TYPE
                       , c.CHARACTER_MAXIMUM_LENGTH
                       , c.IS_NULLABLE
                    FROM INFORMATION_SCHEMA.COLUMNS c
                   WHERE c.TABLE_SCHEMA = :devSchema
                 ) y
           ORDER BY
                 2, 3, 1"""),
      new Example("개발 · 배포 점검", "개발 · 운영 객체 비교 (표 · 뷰 · 프로시저가 한쪽에만)", "배포 빠짐을 찾습니다. 운영 DB 링크(PROD_LINK)로 객체 이름 · 종류를 맞댑니다. PostgreSQL 은 두 스키마의 표 · 뷰를 맞댑니다.",
          """
          SELECT
                 'DEV 에만' AS SIDE
               , x.*
            FROM (
                  SELECT
                         o.OBJECT_TYPE
                       , o.OBJECT_NAME
                    FROM USER_OBJECTS o
                   WHERE o.OBJECT_NAME NOT LIKE 'BIN$%'
                   MINUS
                  SELECT
                         o.OBJECT_TYPE
                       , o.OBJECT_NAME
                    FROM USER_OBJECTS@PROD_LINK o
                 ) x
           UNION ALL
          SELECT
                 'PROD 에만'
               , y.*
            FROM (
                  SELECT
                         o.OBJECT_TYPE
                       , o.OBJECT_NAME
                    FROM USER_OBJECTS@PROD_LINK o
                   WHERE o.OBJECT_NAME NOT LIKE 'BIN$%'
                   MINUS
                  SELECT
                         o.OBJECT_TYPE
                       , o.OBJECT_NAME
                    FROM USER_OBJECTS o
                 ) y
           ORDER BY
                 2, 3""",
          """
          SELECT
                 'DEV 에만' AS SIDE
               , x.*
            FROM (
                  SELECT
                         t.TABLE_TYPE AS OBJECT_TYPE
                       , t.TABLE_NAME AS OBJECT_NAME
                    FROM INFORMATION_SCHEMA.TABLES t
                   WHERE t.TABLE_SCHEMA = :devSchema
                  EXCEPT
                  SELECT
                         t.TABLE_TYPE
                       , t.TABLE_NAME
                    FROM INFORMATION_SCHEMA.TABLES t
                   WHERE t.TABLE_SCHEMA = :prodSchema
                 ) x
           UNION ALL
          SELECT
                 'PROD 에만'
               , y.*
            FROM (
                  SELECT
                         t.TABLE_TYPE
                       , t.TABLE_NAME
                    FROM INFORMATION_SCHEMA.TABLES t
                   WHERE t.TABLE_SCHEMA = :prodSchema
                  EXCEPT
                  SELECT
                         t.TABLE_TYPE
                       , t.TABLE_NAME
                    FROM INFORMATION_SCHEMA.TABLES t
                   WHERE t.TABLE_SCHEMA = :devSchema
                 ) y
           ORDER BY
                 2, 3"""),
      new Example("개발 · 배포 점검", "칼럼 길이를 줄이기 전에 넘치는 값 찾기", "VARCHAR2(200) → (100) 으로 줄이기 전에, 새 길이를 넘는 줄. Oracle 은 바이트(LENGTHB), PostgreSQL 은 글자 수로 셉니다.",
          """
          SELECT
                 o.ORDER_ID
               , o.REMARK
               , LENGTHB(o.REMARK) AS BYTE_LEN
            FROM ORDERS o
           WHERE LENGTHB(o.REMARK) > :newLength
           ORDER BY
                 BYTE_LEN DESC""",
          """
          SELECT
                 o.ORDER_ID
               , o.REMARK
               , LENGTH(o.REMARK) AS CHAR_LEN
            FROM ORDERS o
           WHERE LENGTH(o.REMARK) > CAST(:newLength AS INTEGER)
           ORDER BY
                 CHAR_LEN DESC"""),
      new Example("개발 · 배포 점검", "글자 칼럼을 숫자로 바꾸기 전에 안 바뀌는 값 찾기", "VARCHAR2 → NUMBER 로 바꾸거나 TO_NUMBER 로 비교하기 전에 숫자가 아닌 값. 날짜는 VALIDATE_CONVERSION(칼럼 AS DATE, 'YYYYMMDD'). Oracle 12.2 이상.",
          """
          SELECT
                 o.ORDER_ID
               , o.QTY_TEXT
            FROM ORDERS o
           WHERE o.QTY_TEXT IS NOT NULL
             AND VALIDATE_CONVERSION(o.QTY_TEXT AS NUMBER) = 0""",
          """
          SELECT
                 o.ORDER_ID
               , o.QTY_TEXT
            FROM ORDERS o
           WHERE o.QTY_TEXT IS NOT NULL
             AND o.QTY_TEXT !~ '^ *-?[0-9]+([.][0-9]+)? *$'"""),
      new Example("개발 · 배포 점검", "깨진 객체 다시 컴파일하는 문장 만들기", "배포 뒤 INVALID 객체마다 컴파일 문장을 만듭니다. 한 번에 하려면 EXEC DBMS_UTILITY.COMPILE_SCHEMA(USER, FALSE). PostgreSQL 은 INVALID 상태가 없어 깨진 뷰가 없습니다(표를 바꿀 때 막힘).",
          """
          SELECT
                 o.OBJECT_TYPE
               , o.OBJECT_NAME
               , 'ALTER ' || DECODE(o.OBJECT_TYPE, 'PACKAGE BODY', 'PACKAGE', 'TYPE BODY', 'TYPE', o.OBJECT_TYPE) || ' ' || o.OBJECT_NAME || DECODE(o.OBJECT_TYPE, 'PACKAGE BODY', ' COMPILE BODY', 'TYPE BODY', ' COMPILE BODY', ' COMPILE') AS FIX_SQL
            FROM USER_OBJECTS o
           WHERE o.STATUS = 'INVALID'
           ORDER BY
                 DECODE(o.OBJECT_TYPE, 'PACKAGE', 1, 'TYPE', 1, 2)
               , o.OBJECT_NAME""",
          """
          SELECT
                 'PostgreSQL 은 표를 바꿀 때 뷰가 깨지지 않게 막아 INVALID 객체가 없습니다.' AS NOTE"""),
      new Example("개발 · 배포 점검", "설명이 빈 칼럼 (표 정의서 빠짐)", "칼럼 설명을 안 단 곳. 산출물 · 표 정의서 내기 전에 채웁니다.",
          """
          SELECT
                 c.TABLE_NAME
               , c.COLUMN_NAME
               , c.DATA_TYPE
               , t.COMMENTS AS TABLE_COMMENT
            FROM USER_TAB_COLUMNS c
               , USER_COL_COMMENTS m
               , USER_TAB_COMMENTS t
           WHERE c.TABLE_NAME = m.TABLE_NAME
             AND c.COLUMN_NAME = m.COLUMN_NAME
             AND c.TABLE_NAME = t.TABLE_NAME
             AND t.TABLE_TYPE = 'TABLE'
             AND c.TABLE_NAME NOT LIKE 'BIN$%'
             AND m.COMMENTS IS NULL
           ORDER BY
                 c.TABLE_NAME
               , c.COLUMN_ID""",
          """
          SELECT
                 t.RELNAME AS TABLE_NAME
               , a.ATTNAME AS COLUMN_NAME
               , FORMAT_TYPE(a.ATTTYPID, a.ATTTYPMOD) AS DATA_TYPE
               , OBJ_DESCRIPTION(t.OID, 'pg_class') AS TABLE_COMMENT
            FROM PG_CLASS t
               , PG_ATTRIBUTE a
           WHERE a.ATTRELID = t.OID
             AND t.RELKIND = 'r'
             AND t.RELNAMESPACE = CURRENT_SCHEMA()::REGNAMESPACE
             AND a.ATTNUM > 0
             AND NOT a.ATTISDROPPED
             AND COL_DESCRIPTION(t.OID, a.ATTNUM) IS NULL
           ORDER BY
                 t.RELNAME
               , a.ATTNUM"""),
      new Example("개발 · 배포 점검", "표 데이터로 INSERT 문 만들기 (코드 표 옮길 때)", "개발 DB 의 코드 표를 운영에 넣을 INSERT 문을 줄마다 만듭니다. 작은따옴표는 두 번으로 바꿉니다.",
          """
          SELECT
                 'INSERT INTO CODE_DTL (GRP_CD, CD, CD_NM, SORT_SEQ, USE_YN) VALUES ('
                 || '''' || d.GRP_CD || ''', '
                 || '''' || d.CD || ''', '
                 || '''' || REPLACE(d.CD_NM, '''', '''''') || ''', '
                 || NVL(TO_CHAR(d.SORT_SEQ), 'NULL') || ', '
                 || '''' || d.USE_YN || ''');' AS INSERT_SQL
            FROM CODE_DTL d
           WHERE d.GRP_CD = :grpCd
           ORDER BY
                 d.SORT_SEQ""",
          """
          SELECT
                 FORMAT('INSERT INTO CODE_DTL (GRP_CD, CD, CD_NM, SORT_SEQ, USE_YN) VALUES (%L, %L, %L, %s, %L);', d.GRP_CD, d.CD, d.CD_NM, COALESCE(d.SORT_SEQ::TEXT, 'NULL'), d.USE_YN) AS INSERT_SQL
            FROM CODE_DTL d
           WHERE d.GRP_CD = :grpCd
           ORDER BY
                 d.SORT_SEQ"""),
      new Example("개발 · 배포 점검", "배포 전 오늘 바꾼 객체 목록 (배포 대상 뽑기)", "오늘(또는 기준일 뒤) 만들거나 고친 표 · 뷰 · 프로시저를 배포 목록으로. PostgreSQL 은 객체 변경 시각을 남기지 않아 DDL 로그 · 이벤트 트리거가 있어야 합니다.",
          """
          SELECT
                 o.OBJECT_TYPE
               , o.OBJECT_NAME
               , o.CREATED
               , o.LAST_DDL_TIME
               , o.STATUS
            FROM USER_OBJECTS o
           WHERE o.LAST_DDL_TIME >= TRUNC(SYSDATE)
             AND o.OBJECT_NAME NOT LIKE 'BIN$%'
           ORDER BY
                 o.OBJECT_TYPE
               , o.OBJECT_NAME""",
          """
          SELECT
                 'PostgreSQL 은 객체를 바꾼 시각을 남기지 않습니다. DDL 이벤트 트리거로 따로 기록해야 합니다.' AS NOTE"""));
}
