package ei.db.bench;

import ei.db.DbKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DB 오류 풀이: 흔한 Oracle(ORA-nnnnn) · PostgreSQL(SQLSTATE) 오류의 뜻 · 흔한 원인 · 고치는 법을 한국어로.
 * 메시지에서 문제 이름(칼럼 · 표)과 위치(PostgreSQL 「Position: n」 · 「위치: n」)를 뽑고, 다른 DB 문법을 쓴 흔적도 짚는다.
 * IntelliJ 에 기대지 않는다. 모르는 오류는 null.
 */
public final class DbErrors {
  private DbErrors() {}

  /** position 은 문장 안 0 부터 센 위치(모르면 -1), word 는 문제 이름(모르면 null). */
  public record Help(String code, String title, String cause, String fix, List<String> hints, int position, String word) {}

  private record Entry(String title, String cause, String fix) {}

  private static final Pattern ORA = Pattern.compile("ORA-(\\d{5})");
  private static final Pattern POSITION = Pattern.compile("(?:Position|위치)\\s*[:：]\\s*(\\d+)");
  private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

  private static final Map<String, Entry> ORACLE = Map.ofEntries(
      Map.entry("00904", new Entry("칼럼 이름이 틀렸습니다", "그 표에 없는 칼럼 이름이거나 오타입니다. 별칭(a.)이 다른 표를 가리키거나, SELECT 에서 붙인 별칭을 WHERE 에서 쓴 경우도 이렇게 납니다.", "칼럼 이름 · 별칭을 확인하세요. 자동완성(Ctrl+Space)으로 고르면 틀리지 않습니다.")),
      Map.entry("00942", new Entry("표나 뷰가 없습니다", "표 이름 오타, 다른 스키마의 표, 권한 없는 표입니다.", "스키마.표 로 쓰거나 이름을 확인하세요. 탐색기에서 표를 끌어다 쓰면 정확합니다.")),
      Map.entry("00936", new Entry("식이 빠졌습니다", "쉼표 뒤가 비었거나(SELECT 목록 끝의 쉼표), 조건의 값이 비었습니다.", "오류 위치 앞뒤의 쉼표 · 연산자 뒤를 보세요.")),
      Map.entry("00933", new Entry("문장 끝이 이상합니다", "절 순서가 틀렸거나(ORDER BY 뒤에 WHERE), Oracle 에 없는 LIMIT 을 썼거나, 끝에 ; 가 두 개입니다.", "절 순서는 SELECT → FROM → WHERE → GROUP BY → HAVING → ORDER BY 입니다.")),
      Map.entry("00907", new Entry("닫는 괄호가 없습니다", "괄호 짝이 안 맞거나, 괄호 안에 쓸 수 없는 것을 썼습니다(서브쿼리 안 ORDER BY 등).", "여는 괄호 · 닫는 괄호 수를 맞춰 보세요.")),
      Map.entry("00918", new Entry("칼럼이 어느 표 것인지 모호합니다", "두 표에 같은 이름 칼럼이 있는데 별칭 없이 썼습니다.", "칼럼 앞에 별칭을 붙이세요(o.CUST_ID).")),
      Map.entry("00979", new Entry("GROUP BY 에 없는 칼럼입니다", "SELECT · ORDER BY 의 일반 칼럼이 GROUP BY 에 없습니다.", "그 칼럼을 GROUP BY 에 넣거나 MAX() 같은 집계로 감싸세요.")),
      Map.entry("00937", new Entry("집계와 일반 칼럼이 섞였습니다", "COUNT · SUM 과 일반 칼럼을 함께 고르고 GROUP BY 가 없습니다.", "일반 칼럼으로 GROUP BY 를 넣으세요.")),
      Map.entry("00934", new Entry("여기에는 집계 함수를 못 씁니다", "WHERE 에 COUNT · SUM 같은 집계를 썼습니다.", "집계 조건은 GROUP BY 뒤 HAVING 에 쓰세요.")),
      Map.entry("01476", new Entry("0 으로 나눴습니다", "나누는 값이 0 인 행이 있습니다.", "나누는 값을 NULLIF(값, 0) 으로 감싸면 그 행은 빈 값이 됩니다.")),
      Map.entry("01722", new Entry("숫자가 아닌 값을 숫자로 바꾸려 했습니다", "숫자 칼럼과 글자를 비교했거나, 글자 칼럼에 숫자가 아닌 값이 섞여 있습니다.", "비교하는 값의 형식을 맞추세요(숫자 칼럼 = 123, 글자 칼럼 = '123').")),
      Map.entry("01861", new Entry("날짜 글자의 꼴이 다릅니다", "TO_DATE 꼴과 값이 다르거나, 꼴 없이 날짜 글자를 비교했습니다.", "TO_DATE('2026-11-07', 'YYYY-MM-DD') 처럼 꼴을 함께 쓰세요.")),
      Map.entry("01843", new Entry("월이 잘못됐습니다", "날짜 글자의 월 자리가 1~12 가 아니거나 꼴이 다릅니다.", "TO_DATE 의 꼴(YYYY-MM-DD)과 값을 맞추세요.")),
      Map.entry("01400", new Entry("비울 수 없는 칼럼에 빈 값을 넣으려 했습니다", "NOT NULL 칼럼에 값을 안 줬습니다.", "그 칼럼 값을 넣거나 기본값을 확인하세요.")),
      Map.entry("00001", new Entry("같은 키가 이미 있습니다", "PK · UNIQUE 칼럼에 이미 있는 값을 넣었습니다.", "먼저 있는지 보거나 「있으면 수정, 없으면 넣기」(MERGE)를 쓰세요.")),
      Map.entry("02291", new Entry("부모 행이 없습니다", "외래 키가 가리키는 부모 표에 그 값이 없습니다.", "부모 표에 먼저 넣거나 값을 확인하세요.")),
      Map.entry("02292", new Entry("자식 행이 있어 지울 수 없습니다", "다른 표가 이 행을 외래 키로 가리키고 있습니다.", "자식 행을 먼저 지우거나 옮기세요.")),
      Map.entry("01427", new Entry("한 값이 와야 할 곳에 여러 행이 왔습니다", "= (SELECT …) 나 SELECT 목록의 서브쿼리가 2행 이상을 돌려줬습니다.", "IN (SELECT …) 으로 바꾸거나 조건을 더 좁히세요.")),
      Map.entry("00923", new Entry("FROM 이 있어야 할 곳에 없습니다", "SELECT 목록의 쉼표가 빠졌거나 별칭 앞에 따옴표가 잘못됐습니다.", "칼럼 사이 쉼표를 확인하세요.")),
      Map.entry("01008", new Entry("값을 다 넣지 않았습니다", "이름 자리(:이름 · ?)에 넣을 값이 비었습니다.", "실행할 때 모든 이름 자리에 값을 넣으세요.")),
      Map.entry("12899", new Entry("값이 칼럼 길이보다 깁니다", "글자 칼럼 길이를 넘는 값을 넣었습니다(한글은 한 글자가 3바이트일 수 있습니다).", "값을 줄이거나 칼럼 길이를 확인하세요.")),
      Map.entry("00054", new Entry("다른 사람이 잠근 행입니다", "다른 세션이 그 행을 고치는 중(커밋 전)입니다.", "잠시 뒤 다시 하거나 그 세션이 커밋 · 롤백하게 하세요.")),
      Map.entry("00911", new Entry("쓸 수 없는 글자가 있습니다", "문장 끝 ; 가 남았거나, 전각 문자(，“” 등)나 보이지 않는 글자가 섞였습니다.", "오류 위치의 글자를 지우고 다시 쳐 보세요.")),
      Map.entry("00932", new Entry("형식이 맞지 않습니다", "날짜와 숫자, 글자와 LOB 처럼 비교할 수 없는 형식끼리 비교했습니다.", "TO_CHAR · TO_DATE · TO_NUMBER 로 형식을 맞추세요.")),
      Map.entry("00920", new Entry("비교 연산자가 잘못됐습니다", "조건에 = · > · LIKE 같은 연산자가 빠졌거나 잘못 썼습니다.", "조건 모양을 확인하세요(칼럼 연산자 값).")),
      Map.entry("00905", new Entry("키워드가 빠졌습니다", "CASE 의 THEN · END, JOIN 의 ON 같은 짝 키워드가 빠졌습니다.", "오류 위치 앞의 문장 모양을 보세요.")),
      Map.entry("01719", new Entry("(+) 를 OR · IN 과 함께 못 씁니다", "옛 방식 바깥 조인 조건에 OR 나 IN 을 썼습니다.", "표준 방식 LEFT OUTER JOIN … ON 으로 바꾸세요(도구 → 연결 모양 바꾸기).")),
      Map.entry("01795", new Entry("IN 목록 값이 1000 개를 넘었습니다", "Oracle 은 IN 목록 하나에 1000 개까지만 받습니다.", "도구 → IN 목록 만들기가 1000 개씩 OR 로 나눠 줍니다.")));

  private static final Map<String, Entry> POSTGRES = Map.ofEntries(
      Map.entry("42703", ORACLE.get("00904")),
      Map.entry("42P01", ORACLE.get("00942")),
      Map.entry("42601", new Entry("문법이 틀렸습니다", "쉼표 · 괄호 짝 · 키워드 순서가 틀렸거나, PostgreSQL 에 없는 Oracle 문법을 썼습니다.", "오류 위치 앞뒤를 보세요. 아래 「DB별 문법 차이」 도 확인하세요.")),
      Map.entry("42803", new Entry("GROUP BY 에 없는 칼럼입니다", "집계와 함께 쓴 일반 칼럼이 GROUP BY 에 없습니다.", "그 칼럼을 GROUP BY 에 넣거나 집계로 감싸세요.")),
      Map.entry("42702", ORACLE.get("00918")),
      Map.entry("22012", ORACLE.get("01476")),
      Map.entry("22P02", new Entry("값을 그 형식으로 바꿀 수 없습니다", "숫자 · 날짜 칼럼에 맞지 않는 글자를 비교하거나 넣었습니다.", "값의 형식을 칼럼에 맞추세요.")),
      Map.entry("22007", new Entry("날짜 · 시간 꼴이 다릅니다", "날짜 글자의 꼴이 맞지 않습니다.", "'2026-11-07' 꼴이나 TO_DATE(값, 'YYYY-MM-DD') 를 쓰세요.")),
      Map.entry("22008", new Entry("날짜 · 시간 값이 범위를 벗어났습니다", "없는 날짜(2월 30일 등)나 꼴이 다른 값입니다.", "값을 확인하세요.")),
      Map.entry("23505", ORACLE.get("00001")),
      Map.entry("23503", new Entry("외래 키에 맞지 않습니다", "부모 표에 없는 값을 넣었거나, 자식이 있는 부모 행을 지우려 했습니다.", "부모 · 자식 행을 확인하세요.")),
      Map.entry("23502", ORACLE.get("01400")),
      Map.entry("21000", ORACLE.get("01427")),
      Map.entry("42883", new Entry("그런 함수(또는 형식 조합)가 없습니다", "함수 이름이 틀렸거나 인자 형식이 맞지 않습니다. Oracle 함수(NVL · DECODE 등)를 썼을 수도 있습니다.", "인자를 CAST(값 AS 형식) 으로 맞추거나 PostgreSQL 함수로 바꾸세요.")),
      Map.entry("22001", ORACLE.get("12899")),
      Map.entry("42804", ORACLE.get("00932")),
      Map.entry("25P02", new Entry("앞 오류 때문에 트랜잭션이 멈췄습니다", "같은 트랜잭션 안에서 앞 문장이 실패하면 롤백 전까지 다른 문장을 받지 않습니다.", "롤백한 뒤 다시 실행하세요.")),
      Map.entry("55P03", ORACLE.get("00054")),
      Map.entry("57014", new Entry("실행을 멈췄습니다", "사용자가 멈췄거나 시간 제한에 걸렸습니다.", "조건을 좁히거나 「느린 곳 찾기」 로 실행 계획을 보세요.")));

  private static final List<String[]> ORACLE_ONLY = List.of(
      new String[]{"\\bNVL\\s*\\(", "NVL 은 PostgreSQL 에 없습니다 → COALESCE"},
      new String[]{"\\bDECODE\\s*\\(", "DECODE 는 PostgreSQL 에 없습니다 → CASE WHEN"},
      new String[]{"\\bSYSDATE\\b", "SYSDATE 는 PostgreSQL 에 없습니다 → NOW() · CURRENT_DATE"},
      new String[]{"\\bFROM\\s+DUAL\\b", "DUAL 은 PostgreSQL 에 없습니다 → FROM 없이 SELECT"},
      new String[]{"\\bROWNUM\\b", "ROWNUM 은 PostgreSQL 에 없습니다 → LIMIT"},
      new String[]{"\\(\\s*\\+\\s*\\)", "(+) 는 PostgreSQL 에 없습니다 → LEFT OUTER JOIN … ON"},
      new String[]{"\\bMINUS\\b", "MINUS 는 PostgreSQL 에 없습니다 → EXCEPT"},
      new String[]{"\\bVARCHAR2\\b", "VARCHAR2 는 PostgreSQL 에 없습니다 → VARCHAR"});
  private static final List<String[]> POSTGRES_ONLY = List.of(
      new String[]{"\\bLIMIT\\b", "LIMIT 은 Oracle 에 없습니다 → ROWNUM 감싸기 · FETCH FIRST n ROWS ONLY(12c 이상)"},
      new String[]{"::", ":: 형 바꾸기는 Oracle 에 없습니다 → CAST(값 AS 형식)"},
      new String[]{"\\bILIKE\\b", "ILIKE 는 Oracle 에 없습니다 → UPPER(칼럼) LIKE UPPER(값)"},
      new String[]{"\\bNOW\\s*\\(\\s*\\)", "NOW() 는 Oracle 에 없습니다 → SYSDATE"},
      new String[]{"\\bEXCEPT\\b", "EXCEPT 는 Oracle 에 없습니다 → MINUS"},
      new String[]{"\\bSTRING_AGG\\s*\\(", "STRING_AGG 는 Oracle 에 없습니다 → LISTAGG … WITHIN GROUP"},
      new String[]{"\\bON\\s+CONFLICT\\b", "ON CONFLICT 는 Oracle 에 없습니다 → MERGE"});

  /** code 는 Oracle 이면 getErrorCode(0 이면 메시지의 ORA-), PostgreSQL 이면 SQLSTATE. 모르는 오류면 null. */
  public static Help explain(DbKind kind, int vendorCode, String sqlState, String message, String sql) {
    String msg = message == null ? "" : message;
    String code;
    Entry e;
    if (kind == DbKind.ORACLE) {
      Matcher m = ORA.matcher(msg);
      code = vendorCode > 0 ? String.format(Locale.ROOT, "%05d", vendorCode) : m.find() ? m.group(1) : null;
      e = code == null ? null : ORACLE.get(code);
      if (code != null) code = "ORA-" + code;
    } else {
      code = sqlState;
      e = code == null ? null : POSTGRES.get(code);
    }
    List<String> hints = hints(kind, sql == null ? "" : sql);
    if (e == null && hints.isEmpty()) return null;
    if (e == null) e = new Entry("다른 DB 문법을 썼을 수 있습니다", "이 DB 에서 다르게 쓰는 문법이 보입니다.", "아래 「DB별 문법 차이」 를 보세요. 도구 → DB 바꿔 쓰기가 바꿔 줍니다.");
    Matcher p = POSITION.matcher(msg);
    int position = p.find() ? Integer.parseInt(p.group(1)) - 1 : -1;
    Matcher q = QUOTED.matcher(msg);
    String word = q.find() ? q.group(1) : null;
    return new Help(code, e.title(), e.cause(), e.fix(), hints, position, word);
  }

  /** 이 DB 에 없는 다른 DB 문법. 문자열 · 주석 안은 보지 않는다. */
  static List<String> hints(DbKind kind, String sql) {
    String upper = SqlParts.mask(sql).toUpperCase(Locale.ROOT);
    List<String> out = new ArrayList<>();
    if (kind == DbKind.MYSQL) return out;
    for (String[] h : kind == DbKind.ORACLE ? POSTGRES_ONLY : ORACLE_ONLY) {
      if (Pattern.compile(h[0]).matcher(upper).find()) out.add(h[1]);
    }
    return out;
  }

  /** 오류 위치가 없을 때 문제 이름(칼럼 · 표)이 문장에서 처음 나온 곳. 없으면 -1. */
  public static int locate(Help help, String sql) {
    if (help.position() >= 0) return Math.min(help.position(), sql.length());
    if (help.word() == null) return -1;
    String w = help.word().contains(".") ? help.word().substring(help.word().lastIndexOf('.') + 1) : help.word();
    Matcher m = Pattern.compile("(?i)(?<![\\w$#])" + Pattern.quote(w) + "(?![\\w$#])").matcher(SqlParts.mask(sql));
    return m.find() ? m.start() : -1;
  }
}
