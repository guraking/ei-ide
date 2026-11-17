package ei.db.mybatis;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 「바꾼 것 다시 시험」의 판정 규칙(IntelliJ 클래스 없음).
 * 문장 · 메서드 글은 공백 차이와 XML 주석을 무시하고 견준다(줄 맞춤만 바꾼 것은 바뀐 것으로 보지 않는다).
 */
final class RetestRules {
  private RetestRules() {}

  private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
  private static final Pattern SPACES = Pattern.compile("\\s+");

  /** API 다시 보내기 결과의 갈래. */
  enum ApiVerdict { FAILED, NO_BASE, STATUS, BODY, SAME }

  /** text 의 ranges 글을 이어 XML 주석을 빼고 공백 덩이를 한 칸으로 줄인 것. 범위가 글 밖이면 그 범위는 건너뛴다. */
  static String statementText(CharSequence text, List<int[]> ranges) {
    StringBuilder out = new StringBuilder();
    for (int[] r : ranges) {
      if (r[0] < 0 || r[0] > r[1] || r[1] > text.length()) continue;
      out.append(text, r[0], r[1]).append('\n');
    }
    return code(XML_COMMENT.matcher(out).replaceAll(" "));
  }

  /** 공백 덩이를 한 칸으로 줄이고 앞뒤 공백을 뗀 글. */
  static String code(CharSequence text) {
    return SPACES.matcher(text).replaceAll(" ").strip();
  }

  /** 메서드 짝짓기 키: 「클래스#이름(파라미터 형식,…)」. 클래스 이름이 없으면(익명 클래스) 빈 글. */
  static String methodKey(String owner, String name, List<String> paramTypes) {
    return (owner == null ? "" : owner) + "#" + name + "(" + String.join(",", paramTypes) + ")";
  }

  /** beforeCode 는 저장한 응답이 없으면 음수. */
  static ApiVerdict apiVerdict(String error, int beforeCode, int nowCode, boolean same) {
    if (error != null) return ApiVerdict.FAILED;
    if (beforeCode < 0) return ApiVerdict.NO_BASE;
    if (beforeCode != nowCode) return ApiVerdict.STATUS;
    return same ? ApiVerdict.SAME : ApiVerdict.BODY;
  }
}
