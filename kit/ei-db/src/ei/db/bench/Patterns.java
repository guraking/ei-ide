package ei.db.bench;

import ei.db.DbKind;
import ei.db.gen.Names;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 목적으로 만드는 SQL 뼈대(중복 찾기 · 그룹별 최신 · 계층 …).
 * 표 · 칼럼 이름만 채우면 DB 종류에 맞는 문장이 나온다. IntelliJ 에 기대지 않는다(자체 점검에서 바로 부른다).
 */
public final class Patterns {
  private Patterns() {}

  /** 채울 칸. 무늬마다 필요한 칸만 화면에 보인다. */
  public enum Field {
    TABLE("테이블"), TABLE2("비교할 테이블 · 다른 테이블"), KEYS("기준 칼럼 (같은 값이면 같은 것)"), COLS("함께 볼 · 바꿀 칼럼"),
    GROUP("묶을 칼럼 (예: 상태, 부서)"), VALUE("값 칼럼 (금액 · 수량)"), ORDER("최신 · 순서를 가를 칼럼"),
    PARENT("부모를 가리키는 칼럼"), STATUS("상태 칼럼"), STATUS_VALUES("상태 값들 (쉼표로)");

    public final String label;

    Field(String label) { this.label = label; }
  }

  /** 무늬 하나. */
  public enum Kind {
    LIST("칼럼 골라 조회", "고른 칼럼만 최신 순으로 봅니다.", EnumSet.of(Field.TABLE, Field.COLS, Field.ORDER)),
    PERIOD("날짜 구간 조회", "시작일부터 끝일까지(끝일 하루 전체 포함) 들어온 행을 봅니다.", EnumSet.of(Field.TABLE, Field.ORDER)),
    TOP_N("최신 10건만", "순서 칼럼이 큰(최신) 순으로 앞 10건만 봅니다.", EnumSet.of(Field.TABLE, Field.ORDER)),
    PAGING("페이지 나누기", "화면 목록처럼 몇 번째부터 몇 건을 잘라 봅니다(건너뛸 건수 · 한 쪽 건수는 값 자리).", EnumSet.of(Field.TABLE, Field.ORDER)),
    COUNT_BY("그룹별 건수", "묶을 칼럼 값마다 몇 건인지 많은 순으로 봅니다.", EnumSet.of(Field.TABLE, Field.GROUP)),
    SUM_BY("그룹별 합계 · 평균 · 최대 · 최소", "묶을 칼럼 값마다 값 칼럼의 건수 · 합계 · 평균 · 최대 · 최소를 봅니다.", EnumSet.of(Field.TABLE, Field.GROUP, Field.VALUE)),
    RATIO("그룹별 비율 (%)", "묶을 칼럼 값마다 건수와 전체 중 비율을 봅니다.", EnumSet.of(Field.TABLE, Field.GROUP)),
    MONTHLY("월별 건수 · 합계", "날짜 칼럼을 달로 묶어 달마다 건수(값 칼럼을 넣으면 합계도)를 봅니다.", EnumSet.of(Field.TABLE, Field.ORDER, Field.VALUE)),
    MIN_MAX("칼럼 값 훑어보기", "한 칼럼의 전체 건수 · 채워진 건수 · 서로 다른 값 수 · 최소 · 최대를 한 줄로 봅니다.", EnumSet.of(Field.TABLE, Field.VALUE)),
    NULL_ROWS("빈 값(NULL)인 행 찾기", "고른 칼럼 중 하나라도 비어 있는 행을 봅니다.", EnumSet.of(Field.TABLE, Field.COLS)),
    NULL_COUNT("칼럼별 빈 값 수", "고른 칼럼마다 비어 있는 행이 몇 건인지 한 줄로 봅니다.", EnumSet.of(Field.TABLE, Field.COLS)),
    COUNT_ALL("전체 건수", "테이블에 행이 모두 몇 건인지 봅니다.", EnumSet.of(Field.TABLE)),
    SAMPLE("아무거나 10건 보기", "어떤 값이 들어 있는지 앞에서 10건만 봅니다.", EnumSet.of(Field.TABLE)),
    DISTINCT("서로 다른 값만 보기 (DISTINCT)", "고른 칼럼의 값을 겹치지 않게 한 번씩만 봅니다.", EnumSet.of(Field.TABLE, Field.COLS)),
    COUNT_DISTINCT("칼럼별 서로 다른 값 수", "고른 칼럼마다 값의 종류가 몇 가지인지 한 줄로 봅니다.", EnumSet.of(Field.TABLE, Field.COLS)),
    KEYWORD("글자가 들어간 행 찾기 (LIKE)", "고른 칼럼 중 하나에라도 찾는 글자가 들어 있는 행을 봅니다(찾는 글자는 값 자리).", EnumSet.of(Field.TABLE, Field.COLS)),
    IN_LIST("여러 값 중 하나인 행 (IN)", "칼럼 값이 적어 준 값들 중 하나인 행을 봅니다.", EnumSet.of(Field.TABLE, Field.STATUS, Field.STATUS_VALUES)),
    NOT_IN_LIST("여러 값이 아닌 행 (NOT IN)", "칼럼 값이 적어 준 값들에 들지 않는 행을 봅니다(빈 값도 함께).", EnumSet.of(Field.TABLE, Field.STATUS, Field.STATUS_VALUES)),
    RANGE("값 범위로 찾기 (BETWEEN)", "칼럼 값이 최소부터 최대 사이인 행을 봅니다(최소 · 최대는 값 자리).", EnumSet.of(Field.TABLE, Field.VALUE)),
    TODAY("오늘 들어온 행", "날짜 칼럼이 오늘인 행을 봅니다.", EnumSet.of(Field.TABLE, Field.ORDER)),
    RECENT_DAYS("최근 7일", "날짜 칼럼이 7일 전부터 지금까지인 행을 최신 순으로 봅니다.", EnumSet.of(Field.TABLE, Field.ORDER)),
    THIS_MONTH("이번 달", "날짜 칼럼이 이번 달 1일부터 말일까지인 행을 봅니다.", EnumSet.of(Field.TABLE, Field.ORDER)),
    DAILY("일별 건수 · 합계", "날짜 칼럼을 날로 묶어 날마다 건수(값 칼럼을 넣으면 합계도)를 봅니다.", EnumSet.of(Field.TABLE, Field.ORDER, Field.VALUE)),
    YEARLY("연도별 건수 · 합계", "날짜 칼럼을 해로 묶어 해마다 건수(값 칼럼을 넣으면 합계도)를 봅니다.", EnumSet.of(Field.TABLE, Field.ORDER, Field.VALUE)),
    HAVING_MIN("N건 이상인 그룹만 (HAVING)", "묶을 칼럼 값마다 세어 건수가 기준 이상인 것만 봅니다(기준은 값 자리).", EnumSet.of(Field.TABLE, Field.GROUP)),
    ROLLUP("소계 · 합계 붙이기 (ROLLUP)", "묶을 칼럼 값마다의 건수 · 합계 아래에 소계와 전체 합계 줄을 붙입니다.", EnumSet.of(Field.TABLE, Field.GROUP, Field.VALUE)),
    FIRST_LAST("그룹별 처음 · 마지막 날짜", "묶을 칼럼 값마다 가장 이른 날짜 · 가장 늦은 날짜 · 건수를 봅니다.", EnumSet.of(Field.TABLE, Field.GROUP, Field.ORDER)),
    KEEP_LAST("그룹별 가장 최근 값 한 칸", "묶을 칼럼 값마다 날짜가 가장 늦은 행의 값을 한 칸으로 봅니다.", EnumSet.of(Field.TABLE, Field.GROUP, Field.VALUE, Field.ORDER)),
    MAX_ROW("값이 가장 큰 행", "칼럼 값이 테이블에서 가장 큰 행을 봅니다(같은 값이면 모두).", EnumSet.of(Field.TABLE, Field.VALUE)),
    ABOVE_AVG("평균보다 큰 행", "칼럼 값이 전체 평균보다 큰 행을 큰 순으로 봅니다.", EnumSet.of(Field.TABLE, Field.VALUE)),
    LENGTHS("글자 수 훑어보기", "칼럼 값의 가장 짧은 · 가장 긴 · 평균 글자 수를 봅니다.", EnumSet.of(Field.TABLE, Field.VALUE)),
    TRIM_CHECK("앞뒤 공백이 붙은 값 찾기", "고른 칼럼 값의 앞이나 뒤에 빈칸이 붙어 있는 행을 봅니다.", EnumSet.of(Field.TABLE, Field.COLS)),
    DUPLICATES("중복 찾기", "기준 칼럼 값이 같은 행이 2건 이상인 값과 건수를 봅니다.", EnumSet.of(Field.TABLE, Field.KEYS)),
    DUPLICATE_ROWS("중복 행 전체 보기", "중복된 행을 지우기 전에 한 줄씩 다 봅니다. 같은 값끼리 순번(RN)을 매겨 2 이상이 지울 대상입니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.ORDER)),
    LATEST("그룹별 최신 1건", "기준 칼럼마다 순서 칼럼이 가장 큰(최신) 행 하나만 봅니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.ORDER)),
    EXISTS("있는지 확인 (EXISTS)", "다른 테이블에 짝이 있는 행만 봅니다. 짝이 여러 개여도 한 번만 나옵니다(JOIN 과 달리 늘어나지 않음).", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS)),
    NOT_EXISTS("없는 것 찾기 (NOT EXISTS)", "다른 테이블에 짝이 없는 행만 봅니다. NOT IN 과 달리 NULL 이 있어도 맞게 나옵니다.", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS)),
    CTE("WITH 로 나눠 쓰기", "긴 쿼리를 이름 붙인 단계(base → summary)로 나눕니다. 단계마다 따로 실행해 볼 수 있습니다(도구 → 조각 실행).", EnumSet.of(Field.TABLE, Field.GROUP)),
    HIERARCHY("계층 (부모-자식) 조회", "메뉴 · 조직 · 부품 구성처럼 부모를 가리키는 칼럼으로 위에서 아래로 펼칩니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.PARENT)),
    RANK("누적 합 · 순위", "줄마다 앞에서부터 쌓인 합과 값 순위를 붙입니다(행 수는 그대로).", EnumSet.of(Field.TABLE, Field.VALUE, Field.ORDER)),
    DIFF("두 테이블 차이 비교", "두 테이블의 기준 칼럼 값 중 한쪽에만 있는 것을 봅니다(이관 · 백업 비교).", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS)),
    PIVOT("세로 → 가로 바꾸기", "상태 값마다 칸을 하나씩 만들어 한 줄에 놓습니다.", EnumSet.of(Field.TABLE, Field.GROUP, Field.STATUS, Field.STATUS_VALUES, Field.VALUE)),
    TOP_PER_GROUP("그룹별 최신 3건", "기준 칼럼마다 순서 칼럼이 큰(최신) 순으로 3건씩 봅니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.ORDER)),
    PREV_DIFF("앞 줄과 차이", "순서대로 놓고 줄마다 바로 앞 줄 값과 그 차이를 붙입니다.", EnumSet.of(Field.TABLE, Field.VALUE, Field.ORDER)),
    LIST_AGG("여러 줄을 한 칸에 합치기", "묶을 칼럼 값마다 값 칼럼을 쉼표로 이어 한 칸에 놓습니다.", EnumSet.of(Field.TABLE, Field.GROUP, Field.VALUE)),
    JOIN_LOOKUP("다른 테이블 칼럼 붙이기 (조인)", "기준 칼럼으로 다른 테이블을 이어 그 테이블의 칼럼을 옆에 붙입니다. 짝이 없어도 줄은 남습니다.", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS, Field.COLS)),
    GROUP_MAX_ROW("그룹별 값이 가장 큰 행", "기준 칼럼마다 값 칼럼이 가장 큰 행을 하나씩 봅니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.VALUE)),
    ROW_NUM("줄 번호 붙이기 (ROW_NUMBER)", "순서대로 놓고 줄마다 1부터 번호를 붙입니다.", EnumSet.of(Field.TABLE, Field.ORDER)),
    RUNNING_BY_GROUP("그룹별 누적 합", "기준 칼럼마다 따로, 순서대로 쌓인 합을 줄마다 붙입니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.VALUE, Field.ORDER)),
    RANK_BY_GROUP("그룹 안 순위", "기준 칼럼마다 따로, 값이 큰 순위를 줄마다 붙입니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.VALUE)),
    SHARE_IN_GROUP("그룹 안 비중 (%)", "줄마다 그 값이 자기 그룹 합계에서 차지하는 비율을 붙입니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.VALUE)),
    FIRST_VALUE("그룹의 첫 값 붙이기", "기준 칼럼마다 순서상 첫 줄의 값을 그 그룹의 모든 줄에 붙입니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.VALUE, Field.ORDER)),
    NEXT_ROW("다음 줄 값 붙이기 (LEAD)", "순서대로 놓고 줄마다 바로 다음 줄의 값을 붙입니다.", EnumSet.of(Field.TABLE, Field.VALUE, Field.ORDER)),
    MOVING_AVG("이동 평균 (최근 3줄)", "순서대로 놓고 줄마다 자기와 앞 두 줄의 평균을 붙입니다.", EnumSet.of(Field.TABLE, Field.VALUE, Field.ORDER)),
    NTILE("4등분 (상위 25% 찾기)", "값이 큰 순으로 네 묶음으로 나눠 줄마다 몇 번째 묶음인지 붙입니다.", EnumSet.of(Field.TABLE, Field.VALUE)),
    GAP("빠진 번호 찾기", "번호 칼럼에서 이어지지 않고 건너뛴 구간을 봅니다.", EnumSet.of(Field.TABLE, Field.VALUE)),
    CASE_LABEL("코드 값에 이름 붙이기 (CASE)", "칼럼 값마다 보여 줄 이름을 붙인 칸을 더합니다(이름은 만든 문장에서 고쳐 씁니다).", EnumSet.of(Field.TABLE, Field.STATUS, Field.STATUS_VALUES)),
    STATUS_ROW("값별 건수를 한 줄에", "전체 건수와 적어 준 값마다의 건수를 한 줄로 봅니다.", EnumSet.of(Field.TABLE, Field.STATUS, Field.STATUS_VALUES)),
    JOIN_INNER("두 테이블 모두에 있는 것만 (INNER JOIN)", "기준 칼럼이 두 테이블에 모두 있는 행만, 다른 테이블 칼럼을 옆에 붙여 봅니다.", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS, Field.COLS)),
    FULL_JOIN("양쪽 다 보기 (FULL OUTER JOIN)", "기준 칼럼을 두 테이블에서 나란히 놓아, 한쪽에만 있는 것은 반대쪽이 비어 보이게 합니다.", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS)),
    IN_SUBQUERY("다른 테이블에 있는 값만 (IN 서브쿼리)", "기준 칼럼 값이 다른 테이블에도 있는 행을 봅니다.", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS)),
    JOIN_COUNT("다른 테이블 건수 붙이기", "줄마다 다른 테이블에 같은 기준 칼럼을 가진 행이 몇 건인지 붙입니다.", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS)),
    COMPARE_VALUES("두 테이블 값이 다른 행", "기준 칼럼이 같은데 고른 칼럼 값이 서로 다른 행을 나란히 봅니다.", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS, Field.COLS)),
    SET_MINUS("집합: 이 테이블에만 있는 값 (MINUS)", "고른 칼럼 값 묶음 중 이 테이블에는 있고 다른 테이블에는 없는 것을 봅니다.", EnumSet.of(Field.TABLE, Field.TABLE2, Field.COLS)),
    SET_INTERSECT("집합: 두 테이블에 모두 있는 값 (INTERSECT)", "고른 칼럼 값 묶음 중 두 테이블에 모두 있는 것을 봅니다.", EnumSet.of(Field.TABLE, Field.TABLE2, Field.COLS)),
    SET_UNION("집합: 겹치는 것 빼고 합치기 (UNION)", "두 테이블의 고른 칼럼을 합치되 같은 값은 한 번만 봅니다.", EnumSet.of(Field.TABLE, Field.TABLE2, Field.COLS)),
    UNION_ALL("집합: 그대로 이어 붙이기 (UNION ALL)", "두 테이블의 같은 칼럼을 위아래로 이어 봅니다(어느 테이블인지 칸이 붙습니다).", EnumSet.of(Field.TABLE, Field.TABLE2, Field.COLS)),
    COUNT_COMPARE("두 테이블 건수 비교", "두 테이블의 전체 건수를 나란히 봅니다.", EnumSet.of(Field.TABLE, Field.TABLE2)),
    PARENT_COLS("부모 행의 칼럼 붙이기 (셀프 조인)", "같은 테이블에서 부모 행을 찾아 그 칼럼을 옆에 붙입니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.PARENT, Field.COLS)),
    LEAF("자식이 없는 행 (맨 끝)", "자기를 부모로 둔 행이 하나도 없는 행을 봅니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.PARENT)),
    ORPHAN("부모가 없는 행 (끊긴 것)", "부모 ID 가 적혀 있는데 그 부모 행이 없는 행을 봅니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.PARENT)),
    CHILD_COUNT("자식 수 붙이기", "줄마다 자기를 부모로 둔 행이 몇 건인지 붙입니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.PARENT)),
    INSERT_ROW("한 줄 넣기 (INSERT)", "고른 칼럼에 값을 넣는 INSERT 문을 만듭니다(값은 값 자리).", EnumSet.of(Field.TABLE, Field.COLS)),
    UPDATE_ROW("조건으로 수정 (UPDATE)", "찾을 칼럼이 맞는 행의 칼럼을 고칩니다(값은 값 자리).", EnumSet.of(Field.TABLE, Field.KEYS, Field.COLS)),
    DELETE_ROW("조건으로 삭제 (DELETE)", "찾을 칼럼이 맞는 행을 지웁니다(값은 값 자리).", EnumSet.of(Field.TABLE, Field.KEYS)),
    DELETE_DUP("중복 지우기 (하나만 남김)", "기준 칼럼이 같은 행 중 순서 칼럼이 가장 큰 하나만 남기고 지웁니다. 먼저 「중복 행 전체 보기」 로 확인하세요.", EnumSet.of(Field.TABLE, Field.KEYS, Field.ORDER)),
    UPDATE_FROM("다른 테이블 값으로 수정", "기준 칼럼이 같은 다른 테이블의 값으로 칼럼을 고칩니다.", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS, Field.COLS)),
    INSERT_MISSING("다른 테이블에서 없는 것만 넣기", "다른 테이블의 행 중 이 테이블에 아직 없는 것만 넣습니다.", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS, Field.COLS)),
    BACKUP("백업 테이블 만들기", "고치거나 지우기 전에 테이블을 통째로 _BAK 테이블로 복사해 둡니다.", EnumSet.of(Field.TABLE)),
    UPDATE_NULL("빈 값 채우기 (UPDATE)", "고른 칼럼이 비어 있는 행만 값을 채웁니다(값은 값 자리).", EnumSet.of(Field.TABLE, Field.COLS)),
    UPDATE_STATUS("상태 바꾸기 (UPDATE)", "찾을 칼럼이 맞는 행의 한 칼럼만 바꿉니다(값은 값 자리).", EnumSet.of(Field.TABLE, Field.KEYS, Field.STATUS)),
    DELETE_OLD("오래된 행 지우기", "날짜 칼럼이 기준일보다 앞선 행을 지웁니다(기준일은 값 자리).", EnumSet.of(Field.TABLE, Field.ORDER)),
    DELETE_NOT_IN("다른 테이블에 없는 행 지우기", "기준 칼럼이 다른 테이블에 없는 행을 지웁니다. 먼저 「없는 것 찾기」 로 확인하세요.", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS)),
    INSERT_COPY("다른 테이블에서 옮겨 넣기 (INSERT SELECT)", "다른 테이블의 고른 칼럼을 통째로 이 테이블에 넣습니다.", EnumSet.of(Field.TABLE, Field.TABLE2, Field.COLS)),
    COPY_STRUCT("빈 테이블로 복사 (구조만)", "칼럼 구조만 같은 빈 테이블을 _COPY 이름으로 만듭니다.", EnumSet.of(Field.TABLE)),
    ADD_INDEX("인덱스 만들기", "자주 찾는 칼럼에 인덱스를 만듭니다.", EnumSet.of(Field.TABLE, Field.KEYS)),
    UPSERT("있으면 수정, 없으면 넣기", "기준 칼럼으로 찾아 있으면 UPDATE, 없으면 INSERT 합니다(Oracle MERGE, PostgreSQL ON CONFLICT, MySQL ON DUPLICATE KEY).", EnumSet.of(Field.TABLE, Field.KEYS, Field.COLS));

    public final String label;
    public final String description;
    public final Set<Field> fields;

    Kind(String label, String description, Set<Field> fields) {
      this.label = label;
      this.description = description;
      this.fields = fields;
    }

    @Override public String toString() { return label; }
  }

  /** 칸 이름: 무늬마다 그 일에 맞는 이름, 없으면 Field 기본 이름. */
  public static String label(Kind k, Field f) {
    String own = switch (k) {
      case HIERARCHY -> f == Field.KEYS ? "자기 ID 칼럼 (예: MENU_ID)" : f == Field.PARENT ? "부모 ID 칼럼 (예: PARENT_MENU_ID)" : null;
      case LATEST -> f == Field.KEYS ? "이 값마다 1건 (예: CUST_ID)" : f == Field.ORDER ? "최신을 가를 칼럼 (예: REG_DT)" : null;
      case DUPLICATE_ROWS -> f == Field.ORDER ? "남길 행을 가를 칼럼 (큰 것 = RN 1)" : null;
      case EXISTS, NOT_EXISTS -> f == Field.TABLE2 ? "짝을 찾을 테이블" : f == Field.KEYS ? "두 테이블에 같은 이름으로 있는 칼럼" : null;
      case DIFF -> f == Field.TABLE2 ? "비교할 테이블 (예: 백업 테이블)" : f == Field.KEYS ? "비교할 칼럼 (두 테이블에 같은 이름)" : null;
      case UPSERT -> f == Field.KEYS ? "찾을 칼럼 (PK)" : f == Field.COLS ? "넣을 · 고칠 칼럼" : null;
      case PIVOT -> f == Field.GROUP ? "줄로 둘 칼럼" : f == Field.STATUS ? "칸으로 펼칠 칼럼" : f == Field.STATUS_VALUES ? "칸이 될 값들 (쉼표로)" : f == Field.VALUE ? "더할 값 칼럼" : null;
      case RANK -> f == Field.VALUE ? "순위 · 누적할 값 칼럼" : f == Field.ORDER ? "누적 순서 칼럼 (예: 날짜)" : null;
      case CTE, COUNT_BY, RATIO, HAVING_MIN -> f == Field.GROUP ? "묶을 칼럼" : null;
      case DISTINCT, COUNT_DISTINCT -> f == Field.COLS ? "볼 칼럼" : null;
      case KEYWORD -> f == Field.COLS ? "찾아볼 칼럼 (글자 칼럼)" : null;
      case TRIM_CHECK -> f == Field.COLS ? "살펴볼 칼럼 (글자 칼럼)" : null;
      case IN_LIST, NOT_IN_LIST, STATUS_ROW, CASE_LABEL -> f == Field.STATUS ? "값을 볼 칼럼" : f == Field.STATUS_VALUES ? "값들 (쉼표로)" : null;
      case RANGE -> f == Field.VALUE ? "범위를 볼 칼럼" : null;
      case TODAY, RECENT_DAYS, THIS_MONTH, DELETE_OLD -> f == Field.ORDER ? "날짜 칼럼 (예: REG_DT)" : null;
      case DAILY, YEARLY -> f == Field.ORDER ? "날짜 칼럼 (예: REG_DT)" : f == Field.VALUE ? "더할 값 칼럼 (비워도 됨)" : null;
      case ROLLUP -> f == Field.GROUP ? "묶을 칼럼 (여러 개면 앞 칼럼마다 소계)" : f == Field.VALUE ? "더할 값 칼럼 (금액 · 수량)" : null;
      case FIRST_LAST -> f == Field.GROUP ? "묶을 칼럼" : f == Field.ORDER ? "날짜 칼럼 (예: REG_DT)" : null;
      case KEEP_LAST -> f == Field.GROUP ? "묶을 칼럼" : f == Field.VALUE ? "보여 줄 값 칼럼" : f == Field.ORDER ? "최신을 가를 칼럼 (예: REG_DT)" : null;
      case MAX_ROW, ABOVE_AVG, NTILE -> f == Field.VALUE ? "값 칼럼 (금액 · 수량)" : null;
      case LENGTHS -> f == Field.VALUE ? "글자 칼럼" : null;
      case GAP -> f == Field.VALUE ? "번호 칼럼 (예: SEQ)" : null;
      case GROUP_MAX_ROW -> f == Field.KEYS ? "이 값마다 1건 (예: CUST_ID)" : f == Field.VALUE ? "가장 큰 것을 가를 값 칼럼" : null;
      case ROW_NUM -> f == Field.ORDER ? "번호를 매길 순서 칼럼" : null;
      case RUNNING_BY_GROUP -> f == Field.KEYS ? "그룹 칼럼 (예: CUST_ID)" : f == Field.VALUE ? "쌓을 값 칼럼" : f == Field.ORDER ? "순서 칼럼 (예: 날짜)" : null;
      case RANK_BY_GROUP, SHARE_IN_GROUP -> f == Field.KEYS ? "그룹 칼럼 (예: CUST_ID)" : f == Field.VALUE ? "값 칼럼 (금액 · 수량)" : null;
      case FIRST_VALUE -> f == Field.KEYS ? "그룹 칼럼 (예: CUST_ID)" : f == Field.VALUE ? "붙일 값 칼럼" : f == Field.ORDER ? "순서 칼럼 (예: 날짜)" : null;
      case NEXT_ROW -> f == Field.VALUE ? "다음 줄에서 가져올 값 칼럼" : f == Field.ORDER ? "순서 칼럼 (예: 날짜)" : null;
      case MOVING_AVG -> f == Field.VALUE ? "평균 낼 값 칼럼" : f == Field.ORDER ? "순서 칼럼 (예: 날짜)" : null;
      case JOIN_INNER -> f == Field.TABLE2 ? "붙일 테이블" : f == Field.KEYS ? "두 테이블에 같은 이름으로 있는 칼럼" : f == Field.COLS ? "붙일 테이블에서 가져올 칼럼" : null;
      case FULL_JOIN, IN_SUBQUERY -> f == Field.TABLE2 ? "견줄 테이블" : f == Field.KEYS ? "두 테이블에 같은 이름으로 있는 칼럼" : null;
      case JOIN_COUNT -> f == Field.TABLE2 ? "건수를 셀 테이블" : f == Field.KEYS ? "두 테이블에 같은 이름으로 있는 칼럼" : null;
      case COMPARE_VALUES -> f == Field.TABLE2 ? "견줄 테이블" : f == Field.KEYS ? "두 테이블에 같은 이름으로 있는 칼럼 (PK)" : f == Field.COLS ? "값을 견줄 칼럼 (두 테이블에 같은 이름)" : null;
      case SET_MINUS, SET_INTERSECT, SET_UNION -> f == Field.TABLE2 ? "견줄 테이블" : f == Field.COLS ? "견줄 칼럼 (두 테이블에 같은 이름)" : null;
      case UNION_ALL -> f == Field.TABLE2 ? "이어 붙일 테이블" : f == Field.COLS ? "볼 칼럼 (두 테이블에 같은 이름)" : null;
      case COUNT_COMPARE -> f == Field.TABLE2 ? "견줄 테이블" : null;
      case PARENT_COLS -> f == Field.KEYS ? "자기 ID 칼럼" : f == Field.COLS ? "부모 행에서 가져올 칼럼" : null;
      case LEAF, ORPHAN, CHILD_COUNT -> f == Field.KEYS ? "자기 ID 칼럼" : null;
      case UPDATE_NULL -> f == Field.COLS ? "채울 칼럼" : null;
      case UPDATE_STATUS -> f == Field.KEYS ? "찾을 칼럼 (PK)" : f == Field.STATUS ? "바꿀 칼럼 (예: STATUS)" : null;
      case DELETE_NOT_IN -> f == Field.TABLE ? "지울 테이블" : f == Field.TABLE2 ? "기준 테이블 (여기 없는 것을 지움)" : f == Field.KEYS ? "두 테이블에 같은 이름으로 있는 칼럼" : null;
      case INSERT_COPY -> f == Field.TABLE ? "넣을 테이블" : f == Field.TABLE2 ? "가져올 테이블" : f == Field.COLS ? "옮길 칼럼 (두 테이블에 같은 이름)" : null;
      case ADD_INDEX -> f == Field.KEYS ? "인덱스 칼럼 (순서대로)" : null;
      case LIST -> f == Field.COLS ? "볼 칼럼" : f == Field.ORDER ? "정렬할 칼럼 (큰 것이 위)" : null;
      case PERIOD -> f == Field.ORDER ? "날짜 칼럼 (예: REG_DT)" : null;
      case TOP_N, PAGING -> f == Field.ORDER ? "정렬할 칼럼 (큰 것이 위)" : null;
      case SUM_BY -> f == Field.GROUP ? "묶을 칼럼" : f == Field.VALUE ? "더할 값 칼럼 (금액 · 수량)" : null;
      case MONTHLY -> f == Field.ORDER ? "날짜 칼럼 (예: REG_DT)" : f == Field.VALUE ? "더할 값 칼럼 (비워도 됨)" : null;
      case MIN_MAX -> f == Field.VALUE ? "훑어볼 칼럼" : null;
      case NULL_ROWS, NULL_COUNT -> f == Field.COLS ? "빈 값을 볼 칼럼" : null;
      case TOP_PER_GROUP -> f == Field.KEYS ? "이 값마다 3건 (예: CUST_ID)" : f == Field.ORDER ? "최신을 가를 칼럼 (예: REG_DT)" : null;
      case PREV_DIFF -> f == Field.VALUE ? "차이를 볼 값 칼럼" : f == Field.ORDER ? "순서 칼럼 (예: 날짜)" : null;
      case LIST_AGG -> f == Field.GROUP ? "묶을 칼럼" : f == Field.VALUE ? "이어 붙일 칼럼" : null;
      case JOIN_LOOKUP -> f == Field.TABLE2 ? "붙일 테이블 (예: 코드 · 고객 테이블)" : f == Field.KEYS ? "두 테이블에 같은 이름으로 있는 칼럼" : f == Field.COLS ? "붙일 테이블에서 가져올 칼럼" : null;
      case INSERT_ROW -> f == Field.COLS ? "넣을 칼럼" : null;
      case UPDATE_ROW -> f == Field.KEYS ? "찾을 칼럼 (PK)" : f == Field.COLS ? "고칠 칼럼" : null;
      case DELETE_ROW -> f == Field.KEYS ? "찾을 칼럼 (PK)" : null;
      case DELETE_DUP -> f == Field.ORDER ? "남길 행을 가를 칼럼 (가장 큰 것만 남김)" : null;
      case UPDATE_FROM -> f == Field.TABLE ? "고칠 테이블" : f == Field.TABLE2 ? "값을 가져올 테이블" : f == Field.KEYS ? "두 테이블에 같은 이름으로 있는 칼럼" : f == Field.COLS ? "고칠 칼럼 (두 테이블에 같은 이름)" : null;
      case INSERT_MISSING -> f == Field.TABLE ? "넣을 테이블" : f == Field.TABLE2 ? "가져올 테이블" : f == Field.KEYS ? "있는지 볼 칼럼 (PK)" : f == Field.COLS ? "함께 넣을 칼럼" : null;
      default -> null;
    };
    return own != null ? own : f.label;
  }

  /** 칸이 비었을 때 회색으로 보일 예시. */
  public static String hint(Field f) {
    return switch (f) {
      case TABLE, TABLE2 -> "예: TB_ORDER  (치거나 목록에서 고르기)";
      case KEYS, COLS -> "예: CUST_ID, ORDER_NO  (쉼표로 여러 개)";
      case STATUS_VALUES -> "예: 접수, 진행, 완료";
      default -> "칼럼 이름  (치거나 목록에서 고르기)";
    };
  }

  /** 채운 값. 칼럼 목록은 쉼표로 나눈 이름, 빈 칸은 빈 글. */
  public record Fill(DbKind kind, QueryBuilder.JoinStyle style, QueryBuilder.Bind bind, String table, String table2, List<String> keys,
                     List<String> cols, String group, String value, String order, String parent, String status,
                     List<String> statusValues) {}

  /** 값 칼럼을 비워도 되는 무늬(비우면 건수만 센다). */
  private static final Set<Kind> VALUE_OPTIONAL = EnumSet.of(Kind.PIVOT, Kind.MONTHLY, Kind.DAILY, Kind.YEARLY);
  /** 「최신 10건만」 의 건수, 「그룹별 최신 3건」 의 건수. 만든 문장에서 숫자만 고쳐 쓴다. */
  private static final int TOP_ROWS = 10;
  private static final int GROUP_ROWS = 3;
  private static final String BACKUP_SUFFIX = "_BAK";
  private static final String COPY_SUFFIX = "_COPY";
  private static final String INDEX_PREFIX = "IX_";
  /** 「최근 7일」 의 날 수, 「이동 평균」 이 함께 보는 앞 줄 수, 「4등분」 의 묶음 수. 만든 문장에서 숫자만 고쳐 쓴다. */
  private static final int RECENT_DAYS = 7;
  private static final int MOVING_BEFORE = 2;
  private static final int TILES = 4;
  /** EXCEPT · INTERSECT 는 MySQL 8.0.31 부터 된다. */
  private static final String MYSQL_SET = ". MySQL 8.0.31 이상";
  /** FETCH FIRST · OFFSET 은 Oracle 12c 부터 된다. */
  private static final String ORACLE_12C = ". Oracle 12c 이상";
  private static final Pattern PLAIN_ALIAS = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

  /** 비어서 만들 수 없는 칸(화면에 「채워 주세요」 로 보인다). 다 채웠으면 빈 목록. 값 칼럼 · 묶을 칼럼 등 비어도 되는 칸은 넣지 않는다. */
  public static List<Field> missing(Kind k, Fill f) {
    List<Field> out = new ArrayList<>();
    if (blank(f.table())) out.add(Field.TABLE);
    if (k.fields.contains(Field.TABLE2) && blank(f.table2())) out.add(Field.TABLE2);
    if (k.fields.contains(Field.KEYS) && f.keys().isEmpty()) out.add(Field.KEYS);
    if (k.fields.contains(Field.COLS) && f.cols().isEmpty()) out.add(Field.COLS);
    if (k.fields.contains(Field.ORDER) && blank(f.order())) out.add(Field.ORDER);
    if (k.fields.contains(Field.PARENT) && blank(f.parent())) out.add(Field.PARENT);
    if (k.fields.contains(Field.STATUS) && blank(f.status())) out.add(Field.STATUS);
    if (k.fields.contains(Field.STATUS_VALUES) && f.statusValues().isEmpty()) out.add(Field.STATUS_VALUES);
    if (k.fields.contains(Field.VALUE) && !VALUE_OPTIONAL.contains(k) && blank(f.value())) out.add(Field.VALUE);
    if (k.fields.contains(Field.GROUP) && blank(f.group())) out.add(Field.GROUP);
    return out;
  }

  /** 문장. missing 이 비지 않으면 IllegalArgumentException. 값 자리가 MyBatis 면 부등호 줄을 CDATA 로 감싼다. */
  public static String render(Kind k, Fill f) {
    String sql = body(k, f);
    return f.bind() == QueryBuilder.Bind.MYBATIS ? QueryBuilder.cdata(sql) : sql;
  }

  private static String body(Kind k, Fill f) {
    if (!missing(k, f).isEmpty()) throw new IllegalArgumentException("채울 칸이 남았습니다.");
    DbKind db = f.kind();
    String t = name(db, f.table());
    String a = Names.alias(SqlParts.simpleName(f.table()));
    String t2 = blank(f.table2()) ? "" : name(db, f.table2());
    String b = blank(f.table2()) ? "" : other(a, Names.alias(SqlParts.simpleName(f.table2())));
    List<String> keys = cols(db, f.keys(), "");
    List<String> ak = cols(db, f.keys(), a + ".");
    // 묶을 칼럼은 쉼표로 여러 개 받는다. group 은 설명 글, ag 는 별칭 붙인 이름들, groupBy 는 GROUP BY 아래에 놓을 줄.
    List<String> groups = cols(db, PickText.names(f.group() == null ? "" : f.group()), "");
    String group = String.join(", ", groups);
    List<String> ag = prefix(groups, a + ".");
    String groupBy = String.join("\n     , ", ag);
    String value = blank(f.value()) ? null : name(db, f.value());
    String order = blank(f.order()) ? null : name(db, f.order());
    boolean oracle = db == DbKind.ORACLE;
    boolean old = oracle && f.style() == QueryBuilder.JoinStyle.OLD;
    String rows = "SELECT\n       " + a + ".*\n  FROM " + t + " " + a + "\n";
    String st = blank(f.status()) ? null : name(db, f.status());
    List<String> ac = cols(db, f.cols(), a + ".");
    String av = a + "." + value;
    String ao = a + "." + order;
    String by = String.join(", ", ak);
    return switch (k) {
      case DUPLICATES -> "-- 중복 찾기: " + String.join(", ", keys) + " 가 같은 행이 2건 이상인 값\n"
          + select(keys, "COUNT(*) AS CNT") + "  FROM " + t + "\n GROUP BY\n" + list(keys) + " HAVING COUNT(*) > 1\n ORDER BY\n       CNT DESC";
      case DUPLICATE_ROWS -> "-- 중복 행 전체: 같은 값끼리 " + order + " 큰 순으로 순번(RN). RN >= 2 가 지울 대상입니다\n"
          + "WITH ranked AS (\n    SELECT\n           " + a + ".*\n         , ROW_NUMBER() OVER (PARTITION BY " + String.join(", ", ak) + " ORDER BY " + a + "." + order + " DESC) AS RN\n"
          + "         , COUNT(*) OVER (PARTITION BY " + String.join(", ", ak) + ") AS CNT\n      FROM " + t + " " + a + "\n)\n"
          + "SELECT\n       r.*\n  FROM ranked r\n WHERE r.CNT > 1\n ORDER BY\n" + list(prefix(keys, "r.")) + "     , r.RN";
      case LATEST -> "-- 그룹별 최신 1건: " + String.join(", ", keys) + " 마다 " + order + " 가 가장 큰 행\n"
          + "WITH ranked AS (\n    SELECT\n           " + a + ".*\n         , ROW_NUMBER() OVER (PARTITION BY " + String.join(", ", ak) + " ORDER BY " + a + "." + order + " DESC) AS RN\n"
          + "      FROM " + t + " " + a + "\n)\nSELECT\n       r.*\n  FROM ranked r\n WHERE r.RN = 1";
      case EXISTS, NOT_EXISTS -> "-- " + (k == Kind.EXISTS ? "짝이 있는 행: " : "짝이 없는 행: ") + f.table2() + " 에 같은 " + String.join(", ", keys) + " 가 " + (k == Kind.EXISTS ? "있는" : "없는") + " " + f.table() + "\n"
          + "SELECT\n       " + a + ".*\n  FROM " + t + " " + a + "\n WHERE " + (k == Kind.EXISTS ? "EXISTS" : "NOT EXISTS") + " (\n        SELECT 1\n          FROM " + t2 + " " + b + "\n"
          + matchLines(keys, b, a, "         WHERE ", "           AND ") + "       )";
      case CTE -> "-- WITH 로 나눠 쓰기: base(거를 행) → summary(묶기) → 마지막 SELECT\n"
          + "WITH base AS (\n    SELECT\n           " + a + ".*\n      FROM " + t + " " + a + "\n     WHERE 1 = 1\n)\n"
          + ", summary AS (\n    SELECT\n           " + String.join("\n         , ", groups) + "\n         , COUNT(*) AS CNT\n      FROM base\n     GROUP BY\n           " + String.join("\n         , ", groups) + "\n)\n"
          + "SELECT\n       s.*\n  FROM summary s\n ORDER BY\n       s.CNT DESC";
      case HIERARCHY -> hierarchy(db, t, a, keys.get(0), name(db, f.parent()));
      case RANK -> "-- 누적 합 · 순위: " + order + " 순서로 쌓인 " + value + " 합계와 " + value + " 큰 순위(같은 값은 같은 순위)\n"
          + "SELECT\n       " + a + ".*\n     , SUM(" + a + "." + value + ") OVER (ORDER BY " + a + "." + order + ") AS CUM_" + bare(value) + "\n"
          + "     , RANK() OVER (ORDER BY " + a + "." + value + " DESC) AS RNK\n  FROM " + t + " " + a + "\n ORDER BY\n       " + a + "." + order;
      case DIFF -> {
        String minus = oracle ? "MINUS" : "EXCEPT";
        yield "-- 두 테이블 차이: 한쪽에만 있는 " + String.join(", ", keys) + " (WHERE 칸 = 어느 테이블에만 있는지)\n"
            + "SELECT\n       '" + bare(f.table()) + " 에만' AS WHERE_ONLY\n" + list(keys).replaceFirst("^       ", "     , ")
            + "  FROM (\n        SELECT " + String.join(", ", keys) + " FROM " + t + "\n        " + minus + "\n        SELECT " + String.join(", ", keys) + " FROM " + t2 + "\n       ) x\n"
            + "UNION ALL\nSELECT\n       '" + bare(f.table2()) + " 에만' AS WHERE_ONLY\n" + list(keys).replaceFirst("^       ", "     , ")
            + "  FROM (\n        SELECT " + String.join(", ", keys) + " FROM " + t2 + "\n        " + minus + "\n        SELECT " + String.join(", ", keys) + " FROM " + t + "\n       ) y";
      }
      case PIVOT -> {
        List<String> items = new ArrayList<>(ag);
        for (String sv : f.statusValues()) {
          String then = value == null ? "1" : a + "." + value;
          items.add("SUM(CASE WHEN " + a + "." + name(db, f.status()) + " = " + QueryBuilder.quote(db, sv) + " THEN " + then + " ELSE 0 END) AS " + label(sv));
        }
        yield "-- 세로 → 가로: " + group + " 마다 " + f.status() + " 값별 " + (value == null ? "건수" : value + " 합계") + "를 칸으로\n"
            + "SELECT\n" + list(items) + "  FROM " + t + " " + a + "\n GROUP BY\n       " + groupBy + "\n ORDER BY\n       " + groupBy;
      }
      case UPSERT -> upsert(db, f, t, keys, cols(db, f.cols(), ""));
      case LIST -> "-- 칼럼 골라 조회: " + order + " 큰(최신) 순\n"
          + "SELECT\n" + list(cols(db, f.cols(), a + ".")) + "  FROM " + t + " " + a + "\n ORDER BY\n       " + a + "." + order + " DESC";
      case PERIOD -> "-- 날짜 구간: 시작일 0시부터 끝일 하루 전체까지. 값은 YYYY-MM-DD\n"
          + "SELECT\n       " + a + ".*\n  FROM " + t + " " + a + "\n WHERE " + a + "." + order + " >= " + day(db, place(f, "FROM_DT"), false)
          + "\n   AND " + a + "." + order + " < " + day(db, place(f, "TO_DT"), true) + "\n ORDER BY\n       " + a + "." + order;
      case TOP_N -> "-- 최신 " + TOP_ROWS + "건: " + order + " 큰 순으로 앞에서 " + TOP_ROWS + "건(숫자는 고쳐 쓰세요)" + (oracle ? ORACLE_12C : "") + "\n"
          + "SELECT\n       " + a + ".*\n  FROM " + t + " " + a + "\n ORDER BY\n       " + a + "." + order + " DESC\n"
          + (oracle ? " FETCH FIRST " + TOP_ROWS + " ROWS ONLY" : " LIMIT " + TOP_ROWS);
      case PAGING -> "-- 페이지 나누기: " + order + " 큰 순으로 놓고 건너뛸 건수(offset) 뒤부터 한 쪽 건수(limit)만" + (oracle ? ORACLE_12C : "") + "\n"
          + "SELECT\n       " + a + ".*\n  FROM " + t + " " + a + "\n ORDER BY\n       " + a + "." + order + " DESC\n"
          + (oracle ? "OFFSET " + place(f, "OFFSET") + " ROWS FETCH NEXT " + place(f, "LIMIT") + " ROWS ONLY" : " LIMIT " + place(f, "LIMIT") + " OFFSET " + place(f, "OFFSET"));
      case COUNT_BY -> "-- 그룹별 건수: " + group + " 값마다 몇 건인지\n"
          + select(ag, "COUNT(*) AS CNT") + "  FROM " + t + " " + a + "\n GROUP BY\n       " + groupBy + "\n ORDER BY\n       CNT DESC";
      case SUM_BY -> "-- 그룹별 합계: " + group + " 값마다 " + value + " 의 건수 · 합계 · 평균 · 최대 · 최소\n"
          + select(ag, "COUNT(*) AS CNT\n     , SUM(" + a + "." + value + ") AS SUM_" + bare(value) + "\n     , ROUND(AVG(" + a + "." + value + "), 2) AS AVG_" + bare(value)
              + "\n     , MAX(" + a + "." + value + ") AS MAX_" + bare(value) + "\n     , MIN(" + a + "." + value + ") AS MIN_" + bare(value))
          + "  FROM " + t + " " + a + "\n GROUP BY\n       " + groupBy + "\n ORDER BY\n       SUM_" + bare(value) + " DESC";
      case RATIO -> "-- 그룹별 비율: " + group + " 값마다 건수와 전체 중 비율(%)\n"
          + select(ag, "COUNT(*) AS CNT\n     , ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 1) AS PCT")
          + "  FROM " + t + " " + a + "\n GROUP BY\n       " + groupBy + "\n ORDER BY\n       CNT DESC";
      case MONTHLY -> byPeriod(db, t, a, order, value, "월별", "달", "YYYY-MM", "%Y-%m", "YM");
      case DAILY -> byPeriod(db, t, a, order, value, "일별", "날", "YYYY-MM-DD", "%Y-%m-%d", "YMD");
      case YEARLY -> byPeriod(db, t, a, order, value, "연도별", "해", "YYYY", "%Y", "YY");
      case MIN_MAX -> "-- 칼럼 훑어보기: " + value + " 의 전체 건수 · 채워진 건수 · 서로 다른 값 수 · 최소 · 최대\n"
          + "SELECT\n" + list(List.of("COUNT(*) AS CNT", "COUNT(" + a + "." + value + ") AS FILLED", "COUNT(DISTINCT " + a + "." + value + ") AS KINDS",
              "MIN(" + a + "." + value + ") AS MIN_" + bare(value), "MAX(" + a + "." + value + ") AS MAX_" + bare(value))) + "  FROM " + t + " " + a;
      case NULL_ROWS -> "-- 빈 값인 행: " + String.join(", ", f.cols()) + " 중 하나라도 NULL 인 행\n"
          + "SELECT\n       " + a + ".*\n  FROM " + t + " " + a + "\n WHERE " + String.join(" IS NULL\n    OR ", cols(db, f.cols(), a + ".")) + " IS NULL";
      case NULL_COUNT -> {
        List<String> items = new ArrayList<>(List.of("COUNT(*) AS TOTAL"));
        for (String c : f.cols()) items.add("COUNT(*) - COUNT(" + a + "." + name(db, c) + ") AS " + bare(c) + "_EMPTY");
        yield "-- 칼럼별 빈 값 수: 전체 건수와 칼럼마다 NULL 인 건수\n" + "SELECT\n" + list(items) + "  FROM " + t + " " + a;
      }
      case TOP_PER_GROUP -> "-- 그룹별 최신 " + GROUP_ROWS + "건: " + String.join(", ", keys) + " 마다 " + order + " 큰 순으로 " + GROUP_ROWS + "건(숫자는 고쳐 쓰세요)\n"
          + "WITH ranked AS (\n    SELECT\n           " + a + ".*\n         , ROW_NUMBER() OVER (PARTITION BY " + String.join(", ", ak) + " ORDER BY " + a + "." + order + " DESC) AS RN\n"
          + "      FROM " + t + " " + a + "\n)\nSELECT\n       r.*\n  FROM ranked r\n WHERE r.RN <= " + GROUP_ROWS + "\n ORDER BY\n" + list(prefix(keys, "r.")) + "     , r.RN";
      case PREV_DIFF -> "-- 앞 줄과 차이: " + order + " 순서로 바로 앞 줄의 " + value + " 와 그 차이\n"
          + "SELECT\n       " + a + ".*\n     , LAG(" + a + "." + value + ") OVER (ORDER BY " + a + "." + order + ") AS PREV_" + bare(value) + "\n"
          + "     , " + a + "." + value + " - LAG(" + a + "." + value + ") OVER (ORDER BY " + a + "." + order + ") AS DIFF_" + bare(value) + "\n"
          + "  FROM " + t + " " + a + "\n ORDER BY\n       " + a + "." + order;
      case LIST_AGG -> {
        String v = a + "." + value;
        String joined = oracle ? "LISTAGG(" + v + ", ', ') WITHIN GROUP (ORDER BY " + v + ")"
            : db == DbKind.MYSQL ? "GROUP_CONCAT(" + v + " ORDER BY " + v + " SEPARATOR ', ')"
            : "STRING_AGG(CAST(" + v + " AS VARCHAR), ', ' ORDER BY " + v + ")";
        yield "-- 한 칸에 합치기: " + group + " 값마다 " + value + " 를 쉼표로 이어 한 칸에\n"
            + select(ag, joined + " AS " + bare(value) + "_LIST") + "  FROM " + t + " " + a + "\n GROUP BY\n       " + groupBy;
      }
      case JOIN_LOOKUP -> join(db, f, t, a, t2, b, keys, true);
      case JOIN_INNER -> join(db, f, t, a, t2, b, keys, false);
      case INSERT_ROW -> {
        List<String> all = cols(db, f.cols(), "");
        List<String> vals = new ArrayList<>();
        for (String c : all) vals.add(place(f, c));
        yield "-- 한 줄 넣기: 값은 실행할 때 넣습니다\n" + "INSERT INTO " + t + " (" + String.join(", ", all) + ")\nVALUES (" + String.join(", ", vals) + ")";
      }
      case UPDATE_ROW -> "-- 조건으로 수정: " + String.join(", ", keys) + " 가 맞는 행만. WHERE 를 지우면 모든 행이 바뀝니다\n"
          + "UPDATE " + t + "\n   SET " + String.join("\n     , ", equalsPlace(f, cols(db, f.cols(), ""))) + "\n WHERE " + String.join("\n   AND ", equalsPlace(f, keys));
      case DELETE_ROW -> "-- 조건으로 삭제: " + String.join(", ", keys) + " 가 맞는 행만. WHERE 를 지우면 모든 행이 지워집니다\n"
          + "DELETE FROM " + t + "\n WHERE " + String.join("\n   AND ", equalsPlace(f, keys));
      case DELETE_DUP -> deleteDuplicates(db, t, a, keys, ak, order);
      case UPDATE_FROM -> updateFrom(db, f, t, a, t2, b, keys, cols(db, f.cols(), ""));
      case INSERT_MISSING -> {
        List<String> all = new ArrayList<>(keys);
        all.addAll(cols(db, f.cols(), ""));
        yield "-- 없는 것만 넣기: " + f.table2() + " 의 행 중 " + f.table() + " 에 같은 " + String.join(", ", keys) + " 가 없는 것만\n"
            + "INSERT INTO " + t + " (" + String.join(", ", all) + ")\nSELECT\n" + list(prefix(all, b + ".")) + "  FROM " + t2 + " " + b + "\n WHERE NOT EXISTS (\n        SELECT 1\n          FROM " + t + " " + a + "\n"
            + matchLines(keys, a, b, "         WHERE ", "           AND ") + "       )";
      }
      case BACKUP -> "-- 백업 테이블: 고치기 전에 통째로 복사. 이미 있으면 이름을 바꾸세요\n"
          + "CREATE TABLE " + name(db, f.table().strip().replace("\"", "") + BACKUP_SUFFIX) + " AS\nSELECT\n       *\n  FROM " + t;
      case COUNT_ALL -> "-- 전체 건수\n" + "SELECT\n       COUNT(*) AS CNT\n  FROM " + t + " " + a;
      case SAMPLE -> "-- 아무거나 " + TOP_ROWS + "건: 정렬 없이 앞에서 " + TOP_ROWS + "건(숫자는 고쳐 쓰세요)\n" + rows + (oracle ? " WHERE ROWNUM <= " + TOP_ROWS : " LIMIT " + TOP_ROWS);
      case DISTINCT -> "-- 서로 다른 값: " + String.join(", ", f.cols()) + " 를 겹치지 않게 한 번씩\n"
          + "SELECT DISTINCT\n" + list(ac) + "  FROM " + t + " " + a + "\n ORDER BY\n" + list(ac).stripTrailing();
      case COUNT_DISTINCT -> {
        List<String> items = new ArrayList<>(List.of("COUNT(*) AS TOTAL"));
        for (String c : f.cols()) items.add("COUNT(DISTINCT " + a + "." + name(db, c) + ") AS " + bare(c) + "_KINDS");
        yield "-- 칼럼별 서로 다른 값 수: 전체 건수와 칼럼마다 값의 종류 수\n" + "SELECT\n" + list(items) + "  FROM " + t + " " + a;
      }
      case KEYWORD -> {
        String like = " LIKE " + concat(db, "'%'", place(f, "KEYWORD"), "'%'");
        yield "-- 글자가 들어간 행: " + String.join(", ", f.cols()) + " 중 하나에라도 찾는 글자가 들어 있는 행\n"
            + rows + " WHERE " + String.join(like + "\n    OR ", ac) + like;
      }
      case IN_LIST, NOT_IN_LIST -> {
        List<String> quoted = new ArrayList<>();
        for (String sv : f.statusValues()) quoted.add(QueryBuilder.quote(db, sv));
        String in = a + "." + st + (k == Kind.IN_LIST ? " IN (" : " NOT IN (") + String.join(", ", quoted) + ")";
        yield k == Kind.IN_LIST ? "-- 여러 값 중 하나: " + st + " 가 적어 준 값 중 하나인 행\n" + rows + " WHERE " + in
            : "-- 여러 값이 아닌 행: " + st + " 가 적어 준 값에 들지 않는 행(NOT IN 은 빈 값을 빼므로 IS NULL 을 함께 봅니다)\n" + rows + " WHERE (" + in + "\n    OR " + a + "." + st + " IS NULL)";
      }
      case RANGE -> "-- 값 범위: " + value + " 가 최소부터 최대 사이(양 끝 포함)\n"
          + rows + " WHERE " + av + " BETWEEN " + place(f, "MIN_" + bare(value)) + " AND " + place(f, "MAX_" + bare(value)) + "\n ORDER BY\n       " + av;
      case TODAY -> "-- 오늘 들어온 행: " + order + " 가 오늘 0시부터 내일 0시 전까지\n"
          + rows + " WHERE " + ao + " >= " + today(db) + "\n   AND " + ao + " < " + addDays(db, 1) + "\n ORDER BY\n       " + ao + " DESC";
      case RECENT_DAYS -> "-- 최근 " + RECENT_DAYS + "일: " + order + " 가 " + RECENT_DAYS + "일 전 0시부터 지금까지(숫자는 고쳐 쓰세요)\n"
          + rows + " WHERE " + ao + " >= " + addDays(db, -RECENT_DAYS) + "\n ORDER BY\n       " + ao + " DESC";
      case THIS_MONTH -> {
        String first = oracle ? "TRUNC(SYSDATE, 'MM')" : db == DbKind.MYSQL ? "DATE_FORMAT(CURDATE(), '%Y-%m-01')" : "DATE_TRUNC('month', CURRENT_DATE)";
        String next = oracle ? "ADD_MONTHS(" + first + ", 1)" : db == DbKind.MYSQL ? "DATE_ADD(" + first + ", INTERVAL 1 MONTH)" : first + " + INTERVAL '1 month'";
        yield "-- 이번 달: " + order + " 가 이번 달 1일 0시부터 다음 달 1일 0시 전까지\n"
            + rows + " WHERE " + ao + " >= " + first + "\n   AND " + ao + " < " + next + "\n ORDER BY\n       " + ao;
      }
      case HAVING_MIN -> "-- N건 이상인 그룹: " + group + " 값마다 세어 기준 건수 이상인 것만\n"
          + select(ag, "COUNT(*) AS CNT") + "  FROM " + t + " " + a + "\n GROUP BY\n       " + groupBy + "\nHAVING COUNT(*) >= " + place(f, "MIN_CNT") + "\n ORDER BY\n       CNT DESC";
      case ROLLUP -> "-- 소계 · 합계: " + group + " 값마다 건수 · " + value + " 합계. 묶을 칼럼이 빈 값으로 나오는 줄이 소계, 모두 빈 줄이 전체 합계\n"
          + select(ag, "COUNT(*) AS CNT\n     , SUM(" + av + ") AS SUM_" + bare(value)) + "  FROM " + t + " " + a + "\n GROUP BY\n       "
          + (db == DbKind.MYSQL ? groupBy + "\n  WITH ROLLUP" : "ROLLUP (" + by(ag) + ")");
      case FIRST_LAST -> "-- 그룹별 처음 · 마지막: " + group + " 값마다 가장 이른 · 가장 늦은 " + order + " 와 건수\n"
          + select(ag, "MIN(" + ao + ") AS FIRST_" + bare(order) + "\n     , MAX(" + ao + ") AS LAST_" + bare(order) + "\n     , COUNT(*) AS CNT")
          + "  FROM " + t + " " + a + "\n GROUP BY\n       " + groupBy + "\n ORDER BY\n       " + groupBy;
      case KEEP_LAST -> {
        String last = oracle ? "MAX(" + av + ") KEEP (DENSE_RANK LAST ORDER BY " + ao + ")"
            : db == DbKind.MYSQL ? "SUBSTRING_INDEX(GROUP_CONCAT(" + av + " ORDER BY " + ao + " DESC), ',', 1)"
            : "(ARRAY_AGG(" + av + " ORDER BY " + ao + " DESC))[1]";
        yield "-- 그룹별 가장 최근 값: " + group + " 값마다 " + order + " 가 가장 늦은 행의 " + value + "\n"
            + select(ag, last + " AS LAST_" + bare(value) + "\n     , MAX(" + ao + ") AS LAST_" + bare(order)) + "  FROM " + t + " " + a + "\n GROUP BY\n       " + groupBy;
      }
      case MAX_ROW -> "-- 값이 가장 큰 행: " + value + " 가 테이블에서 가장 큰 행(같은 값이면 모두)\n"
          + rows + " WHERE " + av + " = (\n        SELECT MAX(x." + value + ")\n          FROM " + t + " x\n       )";
      case ABOVE_AVG -> "-- 평균보다 큰 행: " + value + " 가 전체 평균보다 큰 행\n"
          + rows + " WHERE " + av + " > (\n        SELECT AVG(x." + value + ")\n          FROM " + t + " x\n       )\n ORDER BY\n       " + av + " DESC";
      case LENGTHS -> {
        String len = (db == DbKind.MYSQL ? "CHAR_LENGTH(" : "LENGTH(") + av + ")";
        yield "-- 글자 수 훑어보기: " + value + " 의 가장 짧은 · 가장 긴 · 평균 글자 수\n"
            + "SELECT\n" + list(List.of("MIN(" + len + ") AS MIN_LEN", "MAX(" + len + ") AS MAX_LEN", "ROUND(AVG(" + len + "), 1) AS AVG_LEN")) + "  FROM " + t + " " + a;
      }
      case TRIM_CHECK -> {
        List<String> tests = new ArrayList<>();
        for (String c : ac) tests.add(c + " <> TRIM(" + c + ")");
        yield "-- 앞뒤 공백: " + String.join(", ", f.cols()) + " 값의 앞이나 뒤에 빈칸이 붙은 행\n" + rows + " WHERE " + String.join("\n    OR ", tests);
      }
      case GROUP_MAX_ROW -> "-- 그룹별 값이 가장 큰 행: " + String.join(", ", keys) + " 마다 " + value + " 가 가장 큰 행 하나\n"
          + "WITH ranked AS (\n    SELECT\n           " + a + ".*\n         , ROW_NUMBER() OVER (PARTITION BY " + by + " ORDER BY " + av + " DESC) AS RN\n"
          + "      FROM " + t + " " + a + "\n)\nSELECT\n       r.*\n  FROM ranked r\n WHERE r.RN = 1";
      case ROW_NUM -> "-- 줄 번호: " + order + " 순서로 1부터\n"
          + "SELECT\n       ROW_NUMBER() OVER (ORDER BY " + ao + ") AS RN\n     , " + a + ".*\n  FROM " + t + " " + a + "\n ORDER BY\n       RN";
      case RUNNING_BY_GROUP -> "-- 그룹별 누적 합: " + String.join(", ", keys) + " 마다 따로, " + order + " 순서로 쌓인 " + value + " 합계\n"
          + "SELECT\n       " + a + ".*\n     , SUM(" + av + ") OVER (PARTITION BY " + by + " ORDER BY " + ao + ") AS CUM_" + bare(value) + "\n"
          + "  FROM " + t + " " + a + "\n ORDER BY\n" + list(ak) + "     , " + ao;
      case RANK_BY_GROUP -> "-- 그룹 안 순위: " + String.join(", ", keys) + " 마다 따로, " + value + " 큰 순위(같은 값은 같은 순위)\n"
          + "SELECT\n       " + a + ".*\n     , RANK() OVER (PARTITION BY " + by + " ORDER BY " + av + " DESC) AS RNK\n"
          + "  FROM " + t + " " + a + "\n ORDER BY\n" + list(ak) + "     , RNK";
      case SHARE_IN_GROUP -> "-- 그룹 안 비중: 줄마다 " + value + " 가 자기 " + String.join(", ", keys) + " 합계에서 차지하는 비율(%). 합계가 0 이면 빈 값\n"
          + "SELECT\n       " + a + ".*\n     , ROUND(100.0 * " + av + " / NULLIF(SUM(" + av + ") OVER (PARTITION BY " + by + "), 0), 1) AS PCT\n"
          + "  FROM " + t + " " + a + "\n ORDER BY\n" + list(ak) + "     , PCT DESC";
      case FIRST_VALUE -> "-- 그룹의 첫 값: " + String.join(", ", keys) + " 마다 " + order + " 순서상 첫 줄의 " + value + " 를 모든 줄에\n"
          + "SELECT\n       " + a + ".*\n     , FIRST_VALUE(" + av + ") OVER (PARTITION BY " + by + " ORDER BY " + ao + ") AS FIRST_" + bare(value) + "\n"
          + "  FROM " + t + " " + a + "\n ORDER BY\n" + list(ak) + "     , " + ao;
      case NEXT_ROW -> "-- 다음 줄 값: " + order + " 순서로 바로 다음 줄의 " + value + "(마지막 줄은 빈 값)\n"
          + "SELECT\n       " + a + ".*\n     , LEAD(" + av + ") OVER (ORDER BY " + ao + ") AS NEXT_" + bare(value) + "\n  FROM " + t + " " + a + "\n ORDER BY\n       " + ao;
      case MOVING_AVG -> "-- 이동 평균: " + order + " 순서로 자기와 앞 " + MOVING_BEFORE + "줄의 " + value + " 평균(숫자는 고쳐 쓰세요)\n"
          + "SELECT\n       " + a + ".*\n     , ROUND(AVG(" + av + ") OVER (ORDER BY " + ao + " ROWS BETWEEN " + MOVING_BEFORE + " PRECEDING AND CURRENT ROW), 2) AS MOVE_AVG_" + bare(value) + "\n"
          + "  FROM " + t + " " + a + "\n ORDER BY\n       " + ao;
      case NTILE -> "-- " + TILES + "등분: " + value + " 큰 순으로 " + TILES + "묶음. TILE = 1 이 가장 큰 묶음\n"
          + "SELECT\n       " + a + ".*\n     , NTILE(" + TILES + ") OVER (ORDER BY " + av + " DESC) AS TILE\n  FROM " + t + " " + a + "\n ORDER BY\n       " + av + " DESC";
      case GAP -> "-- 빠진 번호: " + value + " 가 이어지지 않는 구간(GAP_FROM 부터 GAP_TO 까지 없음)\n"
          + "WITH nxt AS (\n    SELECT\n           " + av + " AS NO\n         , LEAD(" + av + ") OVER (ORDER BY " + av + ") AS NEXT_NO\n      FROM " + t + " " + a + "\n)\n"
          + "SELECT\n       n.NO + 1 AS GAP_FROM\n     , n.NEXT_NO - 1 AS GAP_TO\n  FROM nxt n\n WHERE n.NEXT_NO - n.NO > 1\n ORDER BY\n       GAP_FROM";
      case CASE_LABEL -> {
        StringBuilder sb = new StringBuilder("-- 코드 값에 이름 붙이기: THEN 뒤 글을 보여 줄 이름으로 고쳐 쓰세요\n" + "SELECT\n       " + a + ".*\n     , CASE " + a + "." + st + "\n");
        for (String sv : f.statusValues()) sb.append("            WHEN ").append(QueryBuilder.quote(db, sv)).append(" THEN ").append(QueryBuilder.quote(db, sv)).append('\n');
        yield sb + "            ELSE '기타'\n       END AS " + bare(f.status()) + "_NM\n  FROM " + t + " " + a;
      }
      case STATUS_ROW -> {
        List<String> items = new ArrayList<>(List.of("COUNT(*) AS TOTAL"));
        for (String sv : f.statusValues()) items.add("SUM(CASE WHEN " + a + "." + st + " = " + QueryBuilder.quote(db, sv) + " THEN 1 ELSE 0 END) AS " + label(sv));
        yield "-- 값별 건수를 한 줄에: 전체 건수와 " + st + " 값마다의 건수\n" + "SELECT\n" + list(items) + "  FROM " + t + " " + a;
      }
      case FULL_JOIN -> {
        List<String> items = new ArrayList<>();
        for (String key : keys) {
          items.add(a + "." + key + " AS " + bare(key) + "_A");
          items.add(b + "." + key + " AS " + bare(key) + "_B");
        }
        String on = match(keys, a, b).replace(" AND ", "\n   AND ");
        String head = "-- 양쪽 다 보기: _A = " + f.table() + ", _B = " + f.table2() + ". 한쪽에만 있으면 반대쪽 칸이 비어 있습니다";
        String left = "SELECT\n" + list(items) + "  FROM " + t + " " + a + "\n  LEFT OUTER JOIN " + t2 + " " + b + "\n    ON " + on;
        yield db == DbKind.MYSQL
            ? head + "(MySQL 은 FULL OUTER JOIN 이 없어 왼쪽 · 오른쪽 조인을 합칩니다)\n" + left + "\nUNION\n" + left.replace("  LEFT OUTER JOIN", " RIGHT OUTER JOIN")
            : head + "\n" + left.replace("  LEFT OUTER JOIN", "  FULL OUTER JOIN");
      }
      case IN_SUBQUERY -> {
        boolean one = keys.size() == 1;
        yield "-- 다른 테이블에 있는 값만: " + String.join(", ", keys) + " 가 " + f.table2() + " 에도 있는 " + f.table() + " 행\n"
            + rows + " WHERE " + (one ? ak.get(0) : "(" + by + ")") + " IN (\n        SELECT " + String.join(", ", prefix(keys, b + ".")) + "\n          FROM " + t2 + " " + b + "\n       )";
      }
      case JOIN_COUNT -> "-- 다른 테이블 건수 붙이기: 줄마다 " + f.table2() + " 에 같은 " + String.join(", ", keys) + " 가 몇 건인지\n"
          + "SELECT\n       " + a + ".*\n     , (\n        SELECT COUNT(*)\n          FROM " + t2 + " " + b + "\n" + matchLines(keys, b, a, "         WHERE ", "           AND ") + "       ) AS " + bare(f.table2()) + "_CNT\n  FROM " + t + " " + a;
      case COMPARE_VALUES -> {
        List<String> items = new ArrayList<>(ak);
        List<String> differ = new ArrayList<>();
        for (String c : cols(db, f.cols(), "")) {
          items.add(a + "." + c + " AS " + bare(c) + "_A");
          items.add(b + "." + c + " AS " + bare(c) + "_B");
          differ.add(a + "." + c + " <> " + b + "." + c);
        }
        String on = match(keys, a, b).replace(" AND ", "\n   AND ");
        yield "-- 두 테이블 값이 다른 행: " + String.join(", ", keys) + " 는 같은데 값이 다른 것(_A = " + f.table() + ", _B = " + f.table2() + "). 한쪽이 빈 값이면 안 나옵니다\n"
            + "SELECT\n" + list(items) + (old ? "  FROM " + t + " " + a + "\n     , " + t2 + " " + b + "\n WHERE " + on + "\n   AND (" : "  FROM " + t + " " + a + "\n INNER JOIN " + t2 + " " + b + "\n    ON " + on + "\n WHERE (")
            + String.join("\n    OR ", differ) + ")";
      }
      case SET_MINUS, SET_INTERSECT, SET_UNION -> {
        String names = "SELECT\n       " + String.join("\n     , ", cols(db, f.cols(), "")) + "\n  FROM ";
        String op = k == Kind.SET_UNION ? "UNION" : k == Kind.SET_INTERSECT ? "INTERSECT" : oracle ? "MINUS" : "EXCEPT";
        String what = k == Kind.SET_UNION ? "두 테이블 값을 합치고 같은 값은 한 번만" : k == Kind.SET_INTERSECT ? "두 테이블에 모두 있는 값" : f.table() + " 에는 있고 " + f.table2() + " 에는 없는 값";
        yield "-- 집합 " + op + ": " + what + (db == DbKind.MYSQL && k != Kind.SET_UNION ? MYSQL_SET : "") + "\n" + names + t + "\n" + op + "\n" + names + t2;
      }
      case UNION_ALL -> {
        List<String> names = cols(db, f.cols(), "");
        yield "-- 두 테이블 합쳐 보기: 같은 칼럼을 위아래로. SRC = 어느 테이블에서 왔는지\n"
            + "SELECT\n       '" + bare(f.table()) + "' AS SRC\n     , " + String.join("\n     , ", names) + "\n  FROM " + t + "\nUNION ALL\n"
            + "SELECT\n       '" + bare(f.table2()) + "' AS SRC\n     , " + String.join("\n     , ", names) + "\n  FROM " + t2;
      }
      case COUNT_COMPARE -> "-- 두 테이블 건수 비교\n"
          + "SELECT\n       '" + bare(f.table()) + "' AS TABLE_NM\n     , COUNT(*) AS CNT\n  FROM " + t + "\nUNION ALL\n"
          + "SELECT\n       '" + bare(f.table2()) + "' AS TABLE_NM\n     , COUNT(*) AS CNT\n  FROM " + t2;
      case PARENT_COLS, LEAF, ORPHAN, CHILD_COUNT -> {
        String id = keys.get(0);
        String parent = name(db, f.parent());
        String c = other(a, "c");
        yield switch (k) {
          case PARENT_COLS -> {
            List<String> items = new ArrayList<>(List.of(a + ".*"));
            for (String col : cols(db, f.cols(), "")) items.add(c + "." + col + " AS PARENT_" + bare(col));
            yield "-- 부모 행의 칼럼 붙이기: 같은 테이블에서 " + parent + " 가 가리키는 행의 칼럼을 옆에(부모가 없어도 줄은 남음)\n"
                + "SELECT\n" + list(items) + (old
                    ? "  FROM " + t + " " + a + "\n     , " + t + " " + c + "\n WHERE " + a + "." + parent + " = " + c + "." + id + "(+)"
                    : "  FROM " + t + " " + a + "\n  LEFT OUTER JOIN " + t + " " + c + "\n    ON " + a + "." + parent + " = " + c + "." + id);
          }
          case LEAF -> "-- 자식이 없는 행: " + id + " 를 " + parent + " 로 둔 행이 하나도 없는 행\n"
              + rows + " WHERE NOT EXISTS (\n        SELECT 1\n          FROM " + t + " " + c + "\n         WHERE " + c + "." + parent + " = " + a + "." + id + "\n       )";
          case ORPHAN -> "-- 부모가 없는 행: " + parent + " 가 적혀 있는데 그 " + id + " 를 가진 행이 없는 행\n"
              + rows + " WHERE " + a + "." + parent + " IS NOT NULL\n   AND NOT EXISTS (\n        SELECT 1\n          FROM " + t + " " + c + "\n         WHERE " + c + "." + id + " = " + a + "." + parent + "\n       )";
          default -> "-- 자식 수 붙이기: 줄마다 " + id + " 를 " + parent + " 로 둔 행이 몇 건인지\n"
              + "SELECT\n       " + a + ".*\n     , (\n        SELECT COUNT(*)\n          FROM " + t + " " + c + "\n         WHERE " + c + "." + parent + " = " + a + "." + id + "\n       ) AS CHILD_CNT\n  FROM " + t + " " + a;
        };
      }
      case UPDATE_NULL -> {
        List<String> names = cols(db, f.cols(), "");
        List<String> set = new ArrayList<>();
        for (String c : names) set.add(c + " = COALESCE(" + c + ", " + place(f, c) + ")");
        yield "-- 빈 값 채우기: " + String.join(", ", names) + " 가 비어 있는 행만(이미 값이 있으면 그대로)\n"
            + "UPDATE " + t + "\n   SET " + String.join("\n     , ", set) + "\n WHERE " + String.join(" IS NULL\n    OR ", names) + " IS NULL";
      }
      case UPDATE_STATUS -> "-- 상태 바꾸기: " + String.join(", ", keys) + " 가 맞는 행의 " + st + " 만. WHERE 를 지우면 모든 행이 바뀝니다\n"
          + "UPDATE " + t + "\n   SET " + st + " = " + place(f, st) + "\n WHERE " + String.join("\n   AND ", equalsPlace(f, keys));
      case DELETE_OLD -> "-- 오래된 행 지우기: " + order + " 가 기준일(YYYY-MM-DD) 0시보다 앞선 행. 먼저 같은 WHERE 로 건수를 세어 보세요\n"
          + "DELETE FROM " + t + "\n WHERE " + order + " < " + day(db, place(f, "BEFORE_DT"), false);
      case DELETE_NOT_IN -> "-- 다른 테이블에 없는 행 지우기: " + f.table2() + " 에 같은 " + String.join(", ", keys) + " 가 없는 " + f.table() + " 행. 먼저 백업하고 건수를 확인하세요\n"
          + "DELETE FROM " + t + "\n WHERE NOT EXISTS (\n        SELECT 1\n          FROM " + t2 + " " + b + "\n" + matchLines(keys, b, t, "         WHERE ", "           AND ") + "       )";
      case INSERT_COPY -> {
        List<String> names = cols(db, f.cols(), "");
        yield "-- 다른 테이블에서 옮겨 넣기: " + f.table2() + " 의 " + String.join(", ", names) + " 를 통째로\n"
            + "INSERT INTO " + t + " (" + String.join(", ", names) + ")\nSELECT\n" + list(prefix(names, b + ".")) + "  FROM " + t2 + " " + b;
      }
      case COPY_STRUCT -> "-- 빈 테이블로 복사: 칼럼 구조만(행 · 인덱스 · 제약은 안 옮겨집니다)\n"
          + "CREATE TABLE " + name(db, f.table().strip().replace("\"", "") + COPY_SUFFIX) + " AS\nSELECT\n       *\n  FROM " + t + "\n WHERE 1 = 0";
      case ADD_INDEX -> {
        List<String> bares = new ArrayList<>();
        for (String key : f.keys()) bares.add(bare(key));
        yield "-- 인덱스 만들기: " + String.join(", ", keys) + " 로 자주 찾을 때. 이름이 길면 줄여 쓰세요\n"
            + "CREATE INDEX " + name(db, INDEX_PREFIX + bare(f.table()) + "_" + String.join("_", bares)) + " ON " + t + " (" + String.join(", ", keys) + ")";
      }
    };
  }

  /** 오늘 0시. */
  private static String today(DbKind db) {
    return db == DbKind.ORACLE ? "TRUNC(SYSDATE)" : db == DbKind.MYSQL ? "CURDATE()" : "CURRENT_DATE";
  }

  /** 오늘 0시에서 days 일 뒤(음수면 앞). */
  private static String addDays(DbKind db, int days) {
    if (db == DbKind.MYSQL) return "DATE_ADD(CURDATE(), INTERVAL " + days + " DAY)";
    return today(db) + (days < 0 ? " - " + -days : " + " + days);
  }

  private static String by(List<String> items) {
    return String.join(", ", items);
  }

  /** 날짜 칼럼을 글 모양(Oracle · PostgreSQL 은 oracleFormat, MySQL 은 mysqlFormat)으로 묶어 건수와, value 가 있으면 합계를 낸다. */
  private static String byPeriod(DbKind db, String t, String a, String order, String value, String title, String unit, String oracleFormat, String mysqlFormat, String alias) {
    String period = db == DbKind.MYSQL ? "DATE_FORMAT(" + a + "." + order + ", '" + mysqlFormat + "')" : "TO_CHAR(" + a + "." + order + ", '" + oracleFormat + "')";
    List<String> items = new ArrayList<>(List.of(period + " AS " + alias, "COUNT(*) AS CNT"));
    if (value != null) items.add("SUM(" + a + "." + value + ") AS SUM_" + bare(value));
    return "-- " + title + ": " + order + " 를 " + unit + "로 묶어 " + unit + "마다 건수" + (value == null ? "" : " · " + value + " 합계") + "\n"
        + "SELECT\n" + list(items) + "  FROM " + t + " " + a + "\n GROUP BY\n       " + period + "\n ORDER BY\n       " + alias;
  }

  /** 기준 칼럼으로 다른 테이블을 이어 그 칼럼을 옆에 붙인다. outer 면 짝이 없어도 줄이 남는다. Oracle 「옛 방식」 은 콤마 조인. */
  private static String join(DbKind db, Fill f, String t, String a, String t2, String b, List<String> keys, boolean outer) {
    List<String> items = new ArrayList<>(List.of(a + ".*"));
    items.addAll(cols(db, f.cols(), b + "."));
    boolean old = db == DbKind.ORACLE && f.style() == QueryBuilder.JoinStyle.OLD;
    List<String> on = new ArrayList<>();
    for (String key : keys) on.add(a + "." + key + " = " + b + "." + key + (old && outer ? "(+)" : ""));
    return "-- " + (outer ? "다른 테이블 칼럼 붙이기: " : "두 테이블 모두에 있는 것만: ") + f.table2() + " 를 " + String.join(", ", keys) + " 로 이어 칼럼을 옆에" + (outer ? "(짝이 없어도 줄은 남음)" : "(짝이 없는 줄은 빠짐)") + "\n"
        + "SELECT\n" + list(items) + (old
            ? "  FROM " + t + " " + a + "\n     , " + t2 + " " + b + "\n WHERE " + String.join("\n   AND ", on)
            : "  FROM " + t + " " + a + "\n " + (outer ? " LEFT OUTER" : "INNER") + " JOIN " + t2 + " " + b + "\n    ON " + String.join("\n   AND ", on));
  }

  /** 날짜 값 자리. next 면 그 다음 날 0시(끝일 하루 전체를 넣으려고 < 로 비교한다). */
  private static String day(DbKind db, String place, boolean next) {
    return switch (db) {
      case ORACLE -> "TO_DATE(" + place + ", 'YYYY-MM-DD')" + (next ? " + 1" : "");
      case MYSQL -> next ? "DATE_ADD(" + place + ", INTERVAL 1 DAY)" : place;
      default -> "CAST(" + place + " AS DATE)" + (next ? " + 1" : "");
    };
  }

  private static List<String> equalsPlace(Fill f, List<String> columns) {
    List<String> out = new ArrayList<>();
    for (String c : columns) out.add(c + " = " + place(f, c));
    return out;
  }

  /** 같은 값끼리 order 가 가장 큰 행만 남긴다. Oracle 은 ROWID, PostgreSQL 은 CTID 로 행을 집고, MySQL 은 행 주소가 없어 자기 조인으로 지운다. */
  private static String deleteDuplicates(DbKind db, String t, String a, List<String> keys, List<String> ak, String order) {
    String head = "-- 중복 지우기: " + String.join(", ", keys) + " 가 같은 행 중 " + order + " 가 가장 큰 하나만 남김. 먼저 백업하고 건수를 확인하세요";
    if (db == DbKind.MYSQL) {
      String b = other(a, "b");
      return head + "(" + order + " 값까지 같은 행은 남습니다)\n" + "DELETE " + a + "\n  FROM " + t + " " + a + "\n INNER JOIN " + t + " " + b + "\n    ON " + match(keys, a, b).replace(" AND ", "\n   AND ")
          + "\n   AND " + a + "." + order + " < " + b + "." + order;
    }
    String rid = db == DbKind.ORACLE ? "ROWID" : "CTID";
    return head + "\n" + "DELETE FROM " + t + "\n WHERE " + rid + " IN (\n        SELECT x.RID\n          FROM (\n                SELECT\n                       " + a + "." + rid + " AS RID\n"
        + "                     , ROW_NUMBER() OVER (PARTITION BY " + String.join(", ", ak) + " ORDER BY " + a + "." + order + " DESC) AS RN\n                  FROM " + t + " " + a + "\n               ) x\n         WHERE x.RN > 1\n       )";
  }

  private static String updateFrom(DbKind db, Fill f, String t, String a, String t2, String b, List<String> keys, List<String> cols) {
    String head = "-- 다른 테이블 값으로 수정: " + String.join(", ", keys) + " 가 같은 " + f.table2() + " 의 값으로 " + String.join(", ", cols) + " 를 고침\n";
    List<String> set = new ArrayList<>();
    if (db == DbKind.POSTGRES) {
      for (String c : cols) set.add(c + " = " + b + "." + c);
      return head + "UPDATE " + t + " " + a + "\n   SET " + String.join("\n     , ", set) + "\n  FROM " + t2 + " " + b + "\n WHERE " + match(keys, a, b).replace(" AND ", "\n   AND ");
    }
    for (String c : cols) set.add(a + "." + c + " = " + b + "." + c);
    if (db == DbKind.MYSQL) {
      return head + "UPDATE " + t + " " + a + "\n INNER JOIN " + t2 + " " + b + "\n    ON " + match(keys, a, b).replace(" AND ", "\n   AND ") + "\n   SET " + String.join("\n     , ", set);
    }
    return head + "MERGE INTO " + t + " " + a + "\nUSING " + t2 + " " + b + "\n   ON (" + match(keys, a, b) + ")\n WHEN MATCHED THEN\n      UPDATE\n         SET " + String.join("\n           , ", set);
  }

  private static String hierarchy(DbKind db, String t, String a, String id, String parent) {
    String head = "-- 계층: " + parent + " 가 비어 있는 행(맨 위)부터 아래로. LVL 은 깊이, PATH 는 위에서부터의 경로\n";
    if (db == DbKind.ORACLE) {
      return head + "SELECT\n       LEVEL AS LVL\n     , SYS_CONNECT_BY_PATH(" + a + "." + id + ", ' > ') AS PATH\n     , " + a + ".*\n  FROM " + t + " " + a + "\n"
          + " START WITH " + a + "." + parent + " IS NULL\n CONNECT BY PRIOR " + a + "." + id + " = " + a + "." + parent + "\n ORDER SIBLINGS BY\n       " + a + "." + id;
    }
    // 고리(자기 자신 · 서로를 부모로 가리키는 행)가 있으면 WITH RECURSIVE 는 끝없이 돈다. 경로에 이미 있는 ID 는 다시 펼치지 않는다.
    // Oracle CONNECT BY 는 고리에서 ORA-01436 으로 멈추므로 그대로 둔다.
    // MySQL 은 CAST 에 VARCHAR 를 받지 않고(CHAR), 기본 설정에서 || 가 OR 라 CONCAT 으로 잇는다.
    String p = other(a, "p");
    String text = db == DbKind.MYSQL ? "CHAR(4000)" : "VARCHAR(4000)";
    return head.replace("\n", "(고리는 한 번만 펼침)\n") + "WITH RECURSIVE tree AS (\n    SELECT\n           1 AS LVL\n         , CAST(" + a + "." + id + " AS " + text + ") AS PATH\n         , " + a + ".*\n"
        + "      FROM " + t + " " + a + "\n     WHERE " + a + "." + parent + " IS NULL\n    UNION ALL\n    SELECT\n           " + p + ".LVL + 1\n"
        + "         , CAST(" + concat(db, p + ".PATH", "' > '", a + "." + id) + " AS " + text + ")\n         , " + a + ".*\n      FROM " + t + " " + a + "\n     INNER JOIN tree " + p + "\n        ON " + a + "." + parent + " = " + p + "." + id + "\n"
        + "     WHERE (" + concat(db, "' > '", p + ".PATH", "' > '") + ") NOT LIKE (" + concat(db, "'% > '", a + "." + id, "' > %'") + ")\n)\n"
        + "SELECT\n       tr.*\n  FROM tree tr\n ORDER BY\n       tr.PATH";
  }

  /** 글 잇기. MySQL 은 CONCAT(기본 설정에서 || 는 OR), 그 밖에는 ||. */
  private static String concat(DbKind db, String... parts) {
    return db == DbKind.MYSQL ? "CONCAT(" + String.join(", ", parts) + ")" : String.join(" || ", parts);
  }

  private static String upsert(DbKind db, Fill f, String t, List<String> keys, List<String> cols) {
    List<String> all = new ArrayList<>(keys);
    all.addAll(cols);
    if (db == DbKind.ORACLE) {
      List<String> src = new ArrayList<>();
      for (String c : all) src.add(place(f, c) + " AS " + c);
      List<String> set = new ArrayList<>();
      for (String c : cols) set.add("t." + c + " = s." + c);
      return "-- 있으면 수정, 없으면 넣기: " + String.join(", ", keys) + " 로 찾습니다\n"
          + "MERGE INTO " + t + " t\nUSING (\n        SELECT\n" + QueryBuilder.indent(list(src).stripTrailing(), "        ") + "\n          FROM DUAL\n      ) s\n"
          + "   ON (" + match(keys, "t", "s") + ")\n WHEN MATCHED THEN\n      UPDATE\n         SET " + String.join("\n           , ", set) + "\n"
          + " WHEN NOT MATCHED THEN\n      INSERT (" + String.join(", ", all) + ")\n      VALUES (" + String.join(", ", prefix(all, "s.")) + ")";
    }
    List<String> vals = new ArrayList<>();
    for (String c : all) vals.add(place(f, c));
    if (db == DbKind.MYSQL) {
      // 넣으려던 값은 VALUES(칼럼) 으로 부른다(8.0.20 부터 경고만 나오고 그대로 된다. 행 별칭 꼴은 8.0.19 미만에서 안 된다).
      List<String> set = new ArrayList<>();
      for (String c : cols) set.add(c + " = VALUES(" + c + ")");
      return "-- 있으면 수정, 없으면 넣기: " + String.join(", ", keys) + " 로 찾습니다. 기준 칼럼에 PK 나 UNIQUE 제약이 있어야 합니다\n"
          + "INSERT INTO " + t + " (" + String.join(", ", all) + ")\nVALUES (" + String.join(", ", vals) + ")\n"
          + "    ON DUPLICATE KEY UPDATE\n       " + String.join("\n     , ", set);
    }
    List<String> set = new ArrayList<>();
    for (String c : cols) set.add(c + " = EXCLUDED." + c);
    return "-- 있으면 수정, 없으면 넣기: " + String.join(", ", keys) + " 로 찾습니다. 기준 칼럼에 PK 나 UNIQUE 제약이 있어야 합니다\n"
        + "INSERT INTO " + t + " (" + String.join(", ", all) + ")\nVALUES (" + String.join(", ", vals) + ")\n"
        + "    ON CONFLICT (" + String.join(", ", keys) + ")\n    DO UPDATE\n          SET " + String.join("\n            , ", set);
  }

  private static String place(Fill f, String column) {
    String name = Names.camel(column.replace("\"", ""));
    return f.bind() == QueryBuilder.Bind.MYBATIS ? "#{" + name + "}" : ":" + name;
  }

  private static String select(List<String> items, String extra) {
    List<String> all = new ArrayList<>(items);
    all.add(extra);
    return "SELECT\n" + list(all);
  }

  /** 앞 쉼표 목록(끝에 줄바꿈). */
  private static String list(List<String> items) {
    StringBuilder sb = new StringBuilder();
    QueryBuilder.leading(sb, items, blanks(items.size()));
    return sb.toString();
  }

  private static String matchLines(List<String> keys, String inner, String outer, String first, String next) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < keys.size(); i++) {
      sb.append(i == 0 ? first : next).append(inner).append('.').append(keys.get(i)).append(" = ").append(outer).append('.').append(keys.get(i)).append('\n');
    }
    return sb.toString();
  }

  private static String match(List<String> keys, String left, String right) {
    List<String> out = new ArrayList<>();
    for (String k : keys) out.add(left + "." + k + " = " + right + "." + k);
    return String.join(" AND ", out);
  }

  private static List<String> cols(DbKind kind, List<String> names, String prefix) {
    List<String> out = new ArrayList<>();
    for (String n : names) out.add(prefix + name(kind, n));
    return out;
  }

  private static List<String> prefix(List<String> items, String prefix) {
    List<String> out = new ArrayList<>();
    for (String i : items) out.add(prefix + i);
    return out;
  }

  private static List<String> blanks(int n) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < n; i++) out.add("");
    return out;
  }

  /** 화면에서 넣은 이름(스키마.표 가능)을 SQL 표기로. 따옴표로 감싼 이름은 그대로. */
  private static String name(DbKind kind, String raw) {
    String s = raw.strip();
    if (s.startsWith("\"")) return s;
    StringBuilder out = new StringBuilder();
    for (String part : s.split("\\.")) {
      if (out.length() > 0) out.append('.');
      out.append(Names.sql(kind, kind == DbKind.ORACLE ? part.toUpperCase(Locale.ROOT) : part.toLowerCase(Locale.ROOT)));
    }
    return out.toString();
  }

  /** 따옴표 · 스키마를 뺀 대문자 이름(별칭 만들 때). */
  private static String bare(String name) {
    return SqlParts.simpleName(name).toUpperCase(Locale.ROOT);
  }

  /** 칸 이름: 영문 이름 꼴이면 대문자 그대로, 아니면(한글 · 공백) 큰따옴표로 감싼다. */
  private static String label(String value) {
    String v = value.strip();
    return PLAIN_ALIAS.matcher(v).matches() ? v.toUpperCase(Locale.ROOT) : '"' + v.replace("\"", "\"\"") + '"';
  }

  private static String other(String a, String b) {
    return a.equals(b) ? b + "2" : b;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
