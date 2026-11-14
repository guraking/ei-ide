package ei.db.mybatis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 코드 흐름 창의 판정 규칙: URL 잇기, 화면 글에서 URL 찾기, DTO 이름, 낱말 찾기. IntelliJ 클래스를 쓰지 않는다(FlowRulesCheck).
 */
final class FlowRules {
  private FlowRules() {}

  /** 매핑 어노테이션 → HTTP 방식. RequestMapping 은 method 속성을 따로 본다. */
  private static final Map<String, String> HTTP_OF = Map.of(
      "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT", "DeleteMapping", "DELETE", "PatchMapping", "PATCH");

  /** DTO 로 보는 클래스 이름 끝. */
  private static final List<String> DTO_SUFFIXES = List.of("Dto", "DTO", "Vo", "VO");

  /** URL 의 {변수} 자리와 스프링 설정값 자리 ${이름}. */
  private static final Pattern VARIABLE = Pattern.compile("\\$?\\{[^}]*}");
  /** 화면 글에서 변수 자리에 올 수 있는 글(따옴표 · 공백 · 빗금 · 물음표 없음). */
  private static final String VARIABLE_TEXT = "[^/'\"`\\s?]+";
  /** URL 앞에 올 수 있는 글자: 따옴표 · 빗금(앞에 다른 경로가 붙음) · 템플릿 끝(} 는 ${base}/… 꼴). */
  private static final String URL_BEFORE = "(?<=['\"`/}])";
  /** URL 뒤에 오면 안 되는 글자(이름 · 확장자가 더 이어짐). */
  private static final String URL_AFTER = "(?![A-Za-z0-9_.\\-])";

  /** XML 주석과 태그(이름으로 시작하는 &lt;…&gt;). 「a &lt; b」 처럼 띄어 쓴 부등호는 태그가 아니다. */
  private static final Pattern TAG = Pattern.compile("<!--.*?-->|<!\\[CDATA\\[|]]>|</?[A-Za-z][^<>]*>", Pattern.DOTALL);
  /** 화면 글로 보지 않는 폴더 이름(받은 라이브러리 · 빌드 결과 · 빌드 출력 복사본). */
  private static final Set<String> FRONT_SKIP_DIRS = Set.of("node_modules", "dist", "bin", "build", "out", "target");
  /** 화면 글 낱말 조각을 이루는 글자 밖의 글자로 자른다(빗금 · 따옴표 · 공백 등). */
  private static final Pattern TOKEN_SPLIT = Pattern.compile("[^A-Za-z0-9_.\\-]+");
  /** 낱말 조각으로 미리 거를 수 있는 URL 마디(이 글자만으로 된 것). */
  private static final Pattern TOKEN_ONLY = Pattern.compile("[A-Za-z0-9_.\\-]+");

  /** 어노테이션 짧은 이름과 RequestMapping 의 method 값들(RequestMethod.POST 꼴도 됨) → 「GET」, 「GET · POST」, 없으면 빈 글. */
  static String http(String annotation, List<String> methods) {
    String fixed = HTTP_OF.get(annotation);
    if (fixed != null) return fixed;
    List<String> names = new ArrayList<>();
    for (String m : methods) {
      String n = m.substring(m.lastIndexOf('.') + 1).trim();
      if (!n.isEmpty() && !names.contains(n)) names.add(n);
    }
    return String.join(" · ", names);
  }

  /** 클래스 경로와 메서드 경로를 잇는다. 빗금은 하나로, 앞에 하나, 끝에는 없다(둘 다 비면 「/」). */
  static String join(String classPath, String methodPath) {
    String joined = "/" + classPath.trim() + "/" + methodPath.trim();
    String s = joined.replaceAll("/{2,}", "/");
    if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
    return s;
  }

  /**
   * 화면 글에서 이 URL 을 부르는 곳을 찾는 정규식. 앞의 빗금은 빼고 찾는다(axios 기본 주소가 앞을 채우는 경우).
   * {변수} 마디는 따옴표 · 빗금이 없는 글로 본다. 앞의 변수 마디들(${api.prefix} 같은 설정값 · 판 번호)은 빼고,
   * 끝의 변수 마디들은 빼고 「마디/」 까지만 본다(문자열을 이어 붙이는 경우).
   * 글자 마디가 없거나(「/」, 「/{id}」) 찾을 수 없으면 null.
   */
  static Pattern frontPattern(String url) {
    List<String> parts = parts(url);
    List<String> kept = trimmed(parts);
    if (kept.isEmpty()) return null;
    StringBuilder re = new StringBuilder(URL_BEFORE);
    for (int i = 0; i < kept.size(); i++) {
      if (i > 0) re.append('/');
      re.append(segment(kept.get(i)));
    }
    re.append(isVariable(parts.get(parts.size() - 1)) ? "/" : URL_AFTER);
    return Pattern.compile(re.toString());
  }

  /**
   * {@link #frontPattern} 이 맞는 글이면 반드시 낱말 조각({@link #tokens})으로 들어 있는 URL 마디들.
   * 화면 파일마다 정규식을 돌리기 전에 이것으로 거른다. 변수가 섞이거나 다른 글자가 든 마디는 넣지 않는다.
   */
  static List<String> literalTokens(String url) {
    List<String> out = new ArrayList<>();
    for (String p : trimmed(parts(url))) {
      if (TOKEN_ONLY.matcher(p).matches()) out.add(p);
    }
    return out;
  }

  /** text 의 낱말 조각들(영문 · 숫자 · _ · . · - 이 이어진 것). */
  static Set<String> tokens(CharSequence text) {
    Set<String> out = new HashSet<>();
    for (String t : TOKEN_SPLIT.split(text)) {
      if (!t.isEmpty()) out.add(t);
    }
    return out;
  }

  private static List<String> parts(String url) {
    List<String> parts = new ArrayList<>();
    for (String p : url.split("/")) {
      if (!p.isEmpty()) parts.add(p);
    }
    return parts;
  }

  /** 앞뒤의 변수 마디를 뺀 마디들. */
  private static List<String> trimmed(List<String> parts) {
    int begin = 0;
    while (begin < parts.size() && isVariable(parts.get(begin))) begin++;
    int end = parts.size();
    while (end > begin && isVariable(parts.get(end - 1))) end--;
    return parts.subList(begin, end);
  }

  private static boolean isVariable(String part) {
    return VARIABLE.matcher(part).matches();
  }

  /** 마디 하나: 글자는 그대로, {변수} 는 변수 자리 글. */
  private static String segment(String part) {
    StringBuilder out = new StringBuilder();
    Matcher m = VARIABLE.matcher(part);
    int at = 0;
    while (m.find()) {
      out.append(Pattern.quote(part.substring(at, m.start()))).append(VARIABLE_TEXT);
      at = m.end();
    }
    out.append(Pattern.quote(part.substring(at)));
    return out.toString();
  }

  /** text 에서 pattern 이 처음 나오는 위치. 없으면 -1. */
  static int find(Pattern pattern, CharSequence text) {
    Matcher m = pattern.matcher(text);
    return m.find() ? m.start() : -1;
  }

  /** DTO 로 볼 클래스 이름이면 true(Dto · DTO · Vo · VO 로 끝나고 그 앞에 글자가 있음). */
  static boolean isDtoName(String simpleName) {
    for (String s : DTO_SUFFIXES) {
      if (simpleName.length() > s.length() && simpleName.endsWith(s)) return true;
    }
    return false;
  }

  /** 낙타 표기 → 밑줄 표기(userName → user_name). */
  static String snake(String camel) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < camel.length(); i++) {
      char c = camel.charAt(i);
      if (Character.isUpperCase(c) && i > 0) out.append('_');
      out.append(Character.toLowerCase(c));
    }
    return out.toString();
  }

  /** text 에 word 가 이름 하나로(앞뒤가 글자 · 숫자 · _ · $ 가 아님) 대소문자 무시하고 나오면 true. 빈 word 는 false. */
  static boolean hasWord(CharSequence text, String word) {
    if (word.isEmpty()) return false;
    String t = text.toString().toLowerCase(Locale.ROOT);
    String w = word.toLowerCase(Locale.ROOT);
    for (int at = t.indexOf(w); at >= 0; at = t.indexOf(w, at + 1)) {
      boolean before = at == 0 || !isNamePart(t.charAt(at - 1));
      int end = at + w.length();
      boolean after = end == t.length() || !isNamePart(t.charAt(end));
      if (before && after) return true;
    }
    return false;
  }

  private static boolean isNamePart(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }

  /** XML 태그 · 주석 · CDATA 표시를 공백 하나로 바꾼 글(SQL 낱말 찾기용). */
  static String withoutTags(CharSequence xml) {
    return TAG.matcher(xml).replaceAll(" ");
  }

  /**
   * sql 에 표 이름이 낱말로 나오면 true. schema 가 있으면 「다른스키마.표」 는 세지 않는다(스키마 없이 쓴 「표」 는 센다).
   */
  static boolean hasTable(CharSequence sql, String schema, String table) {
    if (table.isEmpty()) return false;
    String t = sql.toString().toLowerCase(Locale.ROOT);
    String w = table.toLowerCase(Locale.ROOT);
    for (int at = t.indexOf(w); at >= 0; at = t.indexOf(w, at + 1)) {
      int end = at + w.length();
      if ((at > 0 && isNamePart(t.charAt(at - 1))) || (end < t.length() && isNamePart(t.charAt(end)))) continue;
      if (schema == null || at == 0 || t.charAt(at - 1) != '.') return true;
      int from = at - 1;
      while (from > 0 && isNamePart(t.charAt(from - 1))) from--;
      if (t.substring(from, at - 1).equalsIgnoreCase(schema)) return true;
    }
    return false;
  }

  /** 콘텐츠 뿌리에서 본 경로(빗금 구분)에 node_modules · dist · bin · build · out · target 폴더가 있으면 true. */
  static boolean skippedFront(String relativePath) {
    String[] parts = relativePath.split("/");
    for (int i = 0; i < parts.length - 1; i++) {
      if (FRONT_SKIP_DIRS.contains(parts[i])) return true;
    }
    return false;
  }

  /** 「스키마.표」 또는 「표」 입력을 따옴표 없이 [스키마(없으면 null), 표] 로. 앞뒤 공백은 뺀다. */
  static String[] splitTable(String input) {
    String s = input.trim().replace("\"", "");
    int dot = s.lastIndexOf('.');
    return dot < 0 ? new String[]{null, s} : new String[]{s.substring(0, dot), s.substring(dot + 1)};
  }
}
