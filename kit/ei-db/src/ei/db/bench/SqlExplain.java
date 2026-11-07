package ei.db.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 쉬운 쿼리 설명: 절마다(WITH · SELECT · FROM/JOIN · WHERE · GROUP BY · HAVING · ORDER BY …) 그 부분이 하는 일을 한국어로.
 * 구문 분석이 아니라 흔한 꼴을 알아보는 것이라, 모르는 꼴은 「그대로 둔 조건」 처럼 일반 설명을 단다. IntelliJ 에 기대지 않는다.
 */
public final class SqlExplain {
  private SqlExplain() {}

  /** 설명 한 덩어리: 절 이름, 그 절의 SQL(한 줄로 줄임), 설명 줄들. */
  public record Step(String part, String sql, List<String> lines) {}

  private static final int SQL_PREVIEW = 120;
  private static final Pattern AGG = Pattern.compile("(?<![\\w$#])(COUNT|SUM|AVG|MIN|MAX|LISTAGG|STRING_AGG)\\s*\\(", Pattern.CASE_INSENSITIVE);
  private static final Pattern WINDOW = Pattern.compile("(?<![\\w$#])(ROW_NUMBER|RANK|DENSE_RANK|LAG|LEAD|SUM|COUNT|AVG|MAX|MIN)\\s*\\([^()]*\\)\\s*OVER\\s*\\(", Pattern.CASE_INSENSITIVE);
  private static final Pattern BIND = Pattern.compile("(:[A-Za-z_]\\w*|#\\{[^}]+}|\\$\\{[^}]+}|\\?)");

  public static List<Step> explain(String sql) {
    List<Step> out = new ArrayList<>();
    List<SqlParts.Part> parts = SqlParts.split(sql);
    if (parts.isEmpty()) return out;
    for (SqlParts.Part p : parts) {
      List<String> lines = new ArrayList<>();
      String body = p.body();
      String upper = SqlParts.mask(body).toUpperCase(Locale.ROOT);
      switch (p.key()) {
        case "WITH" -> {
          List<String> names = new ArrayList<>();
          for (SqlTools.Cte c : SqlTools.ctes(sql)) names.add(c.name());
          lines.add("먼저 이름 붙인 중간 결과를 만듭니다: " + String.join(", ", names) + ". 아래에서 이 이름을 표처럼 씁니다.");
          if (upper.startsWith("RECURSIVE")) lines.add("RECURSIVE: 자기 자신을 다시 불러 계층(부모 → 자식)을 따라 내려갑니다.");
          lines.add("도구 → 조각 실행에서 단계마다 결과를 따로 볼 수 있습니다.");
        }
        case "SELECT" -> select(body, upper, lines);
        case "FROM" -> from(body, lines);
        case "WHERE" -> where(body, lines);
        case "GROUP BY" -> {
          lines.add("다음 값이 같은 행끼리 한 줄로 묶습니다: " + SqlParts.oneLine(body) + ". 묶은 줄마다 건수 · 합계 같은 집계를 냅니다.");
          if (upper.contains("ROLLUP")) lines.add("ROLLUP: 묶은 줄 아래에 합계 줄을 더 붙입니다.");
        }
        case "HAVING" -> lines.add("묶은 뒤의 결과를 거릅니다(WHERE 는 묶기 전, HAVING 은 묶은 뒤): " + SqlParts.oneLine(body));
        case "ORDER BY", "ORDER SIBLINGS BY" -> {
          List<String> items = new ArrayList<>();
          for (String o : SqlParts.splitCommas(body)) {
            String u = o.toUpperCase(Locale.ROOT);
            items.add(SqlParts.oneLine(o.replaceAll("(?i)\\s+(ASC|DESC)\\b.*$", "")) + (u.matches("(?s).*\\bDESC\\b.*") ? " 큰 것부터" : " 작은 것부터"));
          }
          lines.add("결과를 이 순서로 늘어놓습니다: " + String.join(", ", items) + ".");
          if (p.key().startsWith("ORDER SIBLINGS")) lines.add("SIBLINGS: 계층 모양은 지키고 같은 부모 아래끼리만 정렬합니다.");
        }
        case "CONNECT BY" -> lines.add("계층을 따라 내려갑니다. PRIOR 가 붙은 쪽이 부모입니다: " + SqlParts.oneLine(body));
        case "START WITH" -> lines.add("계층의 맨 위(시작) 행: " + SqlParts.oneLine(body));
        case "UNION" -> lines.add("위 결과와 아래 결과를 이어 붙이고 똑같은 줄은 하나만 남깁니다.");
        case "UNION ALL" -> lines.add("위 결과와 아래 결과를 그대로 이어 붙입니다(중복도 남김, UNION 보다 빠름).");
        case "MINUS", "EXCEPT" -> lines.add("위 결과에서 아래 결과에 있는 줄을 뺍니다(위에만 있는 것).");
        case "INTERSECT" -> lines.add("위와 아래 양쪽에 다 있는 줄만 남깁니다.");
        case "FETCH" -> lines.add("앞의 몇 건만 가져옵니다: " + SqlParts.oneLine(body));
        case "LIMIT" -> lines.add("앞의 " + SqlParts.oneLine(body) + " 건만 가져옵니다.");
        case "OFFSET" -> lines.add("앞의 " + SqlParts.oneLine(body).replaceAll("(?i)\\s*ROWS?$", "") + " 건을 건너뜁니다.");
        case "FOR UPDATE" -> lines.add("읽은 행을 잠급니다. 커밋 · 롤백할 때까지 다른 사람이 그 행을 고치지 못합니다.");
        case "UPDATE" -> lines.add("이 표의 행을 고칩니다: " + SqlParts.oneLine(body));
        case "SET" -> lines.add("이 칼럼을 이 값으로 바꿉니다: " + SqlParts.oneLine(body));
        case "DELETE" -> lines.add("조건에 맞는 행을 지웁니다. 실행 전에 같은 조건의 SELECT 로 건수를 먼저 보세요(도구 → SELECT ↔ UPDATE/DELETE).");
        case "INSERT" -> lines.add("새 행을 넣습니다.");
        case "INTO" -> lines.add("넣을 표와 칼럼: " + SqlParts.oneLine(body));
        case "VALUES" -> lines.add("넣을 값: " + SqlParts.oneLine(body));
        case "MERGE" -> lines.add("있으면 고치고 없으면 넣습니다(MERGE).");
        case "USING" -> lines.add("비교할 원본: " + SqlParts.oneLine(body));
        default -> lines.add(SqlParts.oneLine(body));
      }
      out.add(new Step(p.key(), preview(body), lines));
    }
    String first = parts.get(0).key();
    if (first.equals("SELECT") || first.equals("WITH")) {
      out.add(new Step("실행 순서", "", List.of("DB 는 쓴 순서가 아니라 FROM(표 · 연결) → WHERE(거르기) → GROUP BY(묶기) → HAVING → SELECT(칼럼) → ORDER BY(정렬) 순서로 처리합니다.",
          "그래서 SELECT 에서 붙인 별칭은 WHERE 에서 못 쓰고 ORDER BY 에서는 씁니다.")));
    }
    return out;
  }

  private static void select(String body, String upper, List<String> lines) {
    String list = body.replaceFirst("(?is)^\\s*(DISTINCT|UNIQUE|ALL)\\s+", "");
    List<String> items = SqlParts.splitCommas(list);
    lines.add("결과에 보일 칼럼 " + items.size() + "개를 고릅니다.");
    if (upper.startsWith("DISTINCT") || upper.startsWith("UNIQUE")) lines.add("DISTINCT: 모든 칸이 똑같은 줄은 하나만 남깁니다.");
    String masked = SqlParts.blankNested(SqlParts.mask(list));
    if (WINDOW.matcher(masked).find()) lines.add("OVER(…): 행을 묶지 않고 줄마다 순번 · 누적 · 앞뒤 값을 계산합니다(PARTITION BY 는 무리 나누기, ORDER BY 는 순서).");
    else if (AGG.matcher(masked).find()) lines.add("집계 함수(COUNT · SUM · AVG · MAX · MIN)가 있어 여러 행을 한 줄로 줄입니다.");
    if (upper.contains("CASE")) lines.add("CASE … WHEN … THEN … END: 조건에 따라 다른 값을 보입니다.");
    if (upper.matches("(?s).*\\b(NVL|COALESCE)\\s*\\(.*")) lines.add("NVL · COALESCE: 값이 비어(NULL) 있으면 대신 보일 값을 씁니다.");
    if (upper.contains("(SELECT")) lines.add("괄호 안 SELECT: 줄마다 다른 표에서 값 하나를 찾아 옵니다(행이 많으면 느릴 수 있음).");
    if (upper.matches("(?s).*\\bROWNUM\\b.*")) lines.add("ROWNUM: 가져오는 순서대로 매긴 번호입니다.");
    if (items.size() == 1 && items.get(0).strip().endsWith("*")) lines.add("* 는 모든 칼럼입니다.");
  }

  private static void from(String body, List<String> lines) {
    List<SqlParts.Source> sources = SqlParts.sources(body);
    List<String> names = new ArrayList<>();
    for (SqlParts.Source s : sources) names.add(s.subquery() ? "(괄호 안 결과) " + nz(s.alias()) : SqlParts.simpleName(s.name()) + (s.alias() == null ? "" : " (" + s.alias() + ")"));
    lines.add((sources.size() == 1 ? "표 " : "표 " + sources.size() + "개 ") + String.join(", ", names) + " 에서 읽습니다.");
    boolean comma = false;
    for (int i = 1; i < sources.size(); i++) {
      SqlParts.Source s = sources.get(i);
      String who = s.ref();
      if (s.join() == null) { comma = true; continue; }
      String on = s.on() == null ? "" : " 잇는 조건: " + SqlParts.oneLine(s.on());
      switch (s.join()) {
        case "INNER" -> lines.add(who + " 를 안쪽 조인: 양쪽에 짝이 있는 행만 남깁니다." + on);
        case "LEFT" -> lines.add(who + " 를 왼쪽 바깥 조인: 앞 표 행은 모두 남기고 " + who + " 는 짝이 있으면 붙입니다(없으면 빈 칸)." + on);
        case "RIGHT" -> lines.add(who + " 를 오른쪽 바깥 조인: " + who + " 행은 모두 남기고 앞 표는 짝이 있으면 붙입니다." + on);
        case "FULL" -> lines.add(who + " 를 양쪽 바깥 조인: 양쪽 행을 모두 남기고 짝이 있으면 나란히 붙입니다." + on);
        case "CROSS" -> lines.add(who + " 를 CROSS JOIN: 모든 조합(곱하기)을 만듭니다. 행이 크게 늘어납니다.");
        default -> lines.add(who + " 를 잇습니다." + on);
      }
    }
    if (comma) lines.add("쉼표로 나열한 표는 WHERE 의 조건으로 잇습니다(옛 방식). 잇는 조건이 빠지면 모든 조합이 나옵니다.");
    for (SqlParts.Source s : sources) if (s.subquery()) lines.add("괄호 안 SELECT 결과를 표처럼 씁니다(인라인 뷰).");
  }

  private static void where(String body, List<String> lines) {
    lines.add("다음 조건에 모두 맞는 행만 남깁니다.");
    for (String c : SqlParts.splitAnd(body)) {
      String one = SqlParts.oneLine(c);
      if (one.equals("1 = 1") || one.equals("1=1")) continue;
      lines.add("• " + one + " → " + condition(c));
    }
  }

  /** 조건 하나의 뜻. */
  static String condition(String c) {
    String masked = SqlParts.mask(c);
    String u = masked.toUpperCase(Locale.ROOT).strip();
    List<String> notes = new ArrayList<>();
    int refs = SqlParts.refs(c).size();
    if (u.contains("(+)")) notes.add("바깥 조인: (+) 가 붙은 쪽 표는 짝이 없어도 행이 남습니다");
    else if (refs >= 2 && u.matches("(?s)^[^()]*=[^()]*$")) notes.add("두 표를 잇는 조건");
    if (u.startsWith("NOT EXISTS")) notes.add("괄호 안 조건에 맞는 행이 하나도 없을 때만 남깁니다");
    else if (u.startsWith("EXISTS")) notes.add("괄호 안 조건에 맞는 행이 하나라도 있으면 남깁니다");
    if (u.matches("(?s).*\\bNOT\\s+IN\\s*\\(\\s*SELECT\\b.*")) notes.add("안쪽 결과에 없는 값만(안쪽에 NULL 이 있으면 아무것도 안 나옴)");
    else if (u.matches("(?s).*\\bIN\\s*\\(\\s*SELECT\\b.*")) notes.add("안쪽 SELECT 결과 중 하나와 같은 값");
    else if (u.matches("(?s).*\\bNOT\\s+IN\\s*\\(.*")) notes.add("괄호 안 값이 아닌 것");
    else if (u.matches("(?s).*\\bIN\\s*\\(.*")) notes.add(u.matches("(?s)^\\(.*\\)\\s*IN\\s*\\(.*") ? "여러 칼럼 짝이 목록 중 하나와 같은 것(튜플 IN)" : "괄호 안 값 중 하나");
    if (u.matches("(?s).*\\bBETWEEN\\b.*")) notes.add("두 값 사이(양끝 포함). 날짜면 끝일 0시까지만 잡힙니다");
    if (u.matches("(?s).*\\bIS\\s+NOT\\s+NULL\\b.*")) notes.add("값이 비어 있지 않은 것");
    else if (u.matches("(?s).*\\bIS\\s+NULL\\b.*")) notes.add("값이 비어 있는(NULL) 것");
    if (u.matches("(?s).*\\bLIKE\\b.*")) {
      String lit = c.replaceAll("(?s).*?'(.*)'.*", "$1");
      if (c.contains("'%' ||") && c.contains("|| '%'") || lit.startsWith("%") && lit.endsWith("%")) notes.add("글자가 들어 있는 것(앞에 % 가 있어 색인을 못 씁니다)");
      else if (c.contains("'%' ||") || lit.startsWith("%")) notes.add("글자로 끝나는 것(앞에 % 가 있어 색인을 못 씁니다)");
      else notes.add("글자로 시작하는 것");
    }
    if (u.matches("(?s).*\\bROWNUM\\s*<.*")) notes.add("앞의 몇 건만(정렬 전에 자르므로 정렬과 함께 쓰려면 바깥에서 감싸야 합니다)");
    Matcher b = BIND.matcher(masked);
    if (b.find()) notes.add("값 " + c.substring(b.start(), b.end()) + " 은 실행할 때 넣습니다");
    if (notes.isEmpty()) notes.add(u.contains(" OR ") ? "둘 중 하나라도 맞으면 됩니다(OR 는 괄호로 묶어야 AND 와 섞이지 않습니다)" : "이 조건에 맞는 것");
    return String.join(", ", notes);
  }

  private static String preview(String body) {
    String one = SqlParts.oneLine(body);
    return one.length() > SQL_PREVIEW ? one.substring(0, SQL_PREVIEW) + "…" : one;
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
