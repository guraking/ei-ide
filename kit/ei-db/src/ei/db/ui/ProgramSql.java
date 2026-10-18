package ei.db.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Oracle 패키지 · 프로시저 · 함수의 목록 · 소스 · 컴파일 오류 쿼리와 소스 → CREATE OR REPLACE 문 변환. 순수 자바.
 * 바인드는 모두 위치(?)이며 소유자 · 이름은 데이터 사전에 저장된 그대로(보통 대문자) 넘긴다.
 */
final class ProgramSql {
  private ProgramSql() {}

  static final String PACKAGE = "PACKAGE";
  static final String PACKAGE_BODY = "PACKAGE BODY";
  static final String INVALID = "INVALID";

  /** 화면에 한 줄로 보이는 프로그램. 패키지는 명세와 본문 중 하나라도 INVALID 면 INVALID. */
  record Item(String name, String type, String status) {
    boolean invalid() { return INVALID.equals(status); }
  }

  /** 바인드: 1 = 소유자. 결과: 이름, 형식, 상태. 패키지 본문은 merge 에서 명세 줄에 합친다. */
  static final String LIST = """
      SELECT
             o.OBJECT_NAME
           , o.OBJECT_TYPE
           , o.STATUS
        FROM ALL_OBJECTS o
       WHERE o.OWNER = ?
         AND o.OBJECT_TYPE IN ('PACKAGE', 'PACKAGE BODY', 'PROCEDURE', 'FUNCTION')
       ORDER BY o.OBJECT_TYPE, o.OBJECT_NAME""";

  /** 바인드: 1 = 소유자, 2 = 이름, 3 · 4 = 형식(패키지는 PACKAGE · PACKAGE BODY, 나머지는 같은 값 두 번). 결과: 형식, 글. */
  static final String SOURCE = """
      SELECT
             s.TYPE
           , s.TEXT
        FROM ALL_SOURCE s
       WHERE s.OWNER = ?
         AND s.NAME = ?
         AND s.TYPE IN (?, ?)
       ORDER BY s.TYPE, s.LINE""";

  /** 바인드: 1 = 소유자, 2 = 이름. 결과 창에 그대로 보인다. */
  static final String ERRORS = """
      SELECT
             e.TYPE
           , e.LINE
           , e.POSITION
           , e.TEXT
        FROM ALL_ERRORS e
       WHERE e.OWNER = ?
         AND e.NAME = ?
       ORDER BY e.TYPE, e.SEQUENCE""";

  /** 소스 쿼리의 3 · 4 번 바인드. */
  static List<Object> sourceTypes(String type) {
    return PACKAGE.equals(type) ? List.of(PACKAGE, PACKAGE_BODY) : List.of(type, type);
  }

  /** LIST 결과 행(이름, 형식, 상태)을 화면 줄로. 본문만 있고 명세가 없는 패키지는 없으므로 본문 행은 명세 줄의 상태에만 반영한다. */
  static List<Item> merge(List<String[]> rows) {
    Map<String, Item> out = new LinkedHashMap<>();
    for (String[] r : rows) {
      String type = PACKAGE_BODY.equals(r[1]) ? PACKAGE : r[1];
      String key = type + "\0" + r[0];
      Item old = out.get(key);
      String status = old != null && old.invalid() ? INVALID : r[2];
      out.put(key, new Item(r[0], type, status));
    }
    return new ArrayList<>(out.values());
  }

  // 소스 첫머리: 형식 + 이름(따옴표 · 소유자. 접두 가능). 그 뒤는 손대지 않는다.
  private static final Pattern HEAD = Pattern.compile(
      "^(\\s*)(PACKAGE\\s+BODY|PACKAGE|PROCEDURE|FUNCTION)(\\s+)(\"[^\"]+\"|[\\w$#]+)(\\s*\\.\\s*(\"[^\"]+\"|[\\w$#]+))?",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern PLAIN_OWNER = Pattern.compile("[A-Z][A-Z0-9_$#]*");

  /**
   * SOURCE 결과 행(형식, 글)을 콘솔에서 바로 실행할 문으로. 형식마다 「CREATE OR REPLACE …」 + 줄바꿈 + 「/」.
   * 이름에 소유자가 없으면 넣는다: 다른 스키마를 보고 있을 때 접속 사용자 스키마에 만들어지지 않게 하기 위해서다.
   * 행이 없으면 빈 문자열.
   */
  static String ddl(String owner, List<String[]> rows) {
    Map<String, StringBuilder> byType = new LinkedHashMap<>();
    for (String[] r : rows) byType.computeIfAbsent(r[0], k -> new StringBuilder()).append(r[1] == null ? "" : r[1]);
    StringBuilder out = new StringBuilder();
    for (StringBuilder src : byType.values()) {
      if (out.length() > 0) out.append('\n');
      out.append("CREATE OR REPLACE ").append(qualify(owner, src.toString().stripTrailing())).append("\n/\n");
    }
    return out.toString();
  }

  private static String qualify(String owner, String src) {
    Matcher m = HEAD.matcher(src);
    if (!m.find() || m.group(5) != null) return src.stripLeading();
    String head = m.group(2).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ") + " " + ownerSql(owner) + "." + m.group(4);
    return head + src.substring(m.end());
  }

  private static String ownerSql(String owner) {
    return PLAIN_OWNER.matcher(owner).matches() ? owner : "\"" + owner.replace("\"", "\"\"") + "\"";
  }
}
