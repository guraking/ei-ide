package ei.db.mybatis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 위험 구간 검사(RiskSqlInspection · RiskDollarInspection · RiskSecretInspection)의 글 판정. IntelliJ 에 기대지 않는다(점검 RiskRulesCheck).
 * 잘못 표시하는 것을 줄이려고, 확실한 꼴만 위험으로 본다.
 */
final class RiskRules {
  private RiskRules() {}

  /** 이어 붙인 값 · ${} 이 들어가는 자리. */
  enum Spot { VALUE, ORDER, NAME }

  /** 앞 조각을 이을 때 식 · ${} 자리에 넣는 글. 칼럼 이름 자리로도 값 자리로도 읽힌다. */
  static final String PLACEHOLDER = "x";

  private static final String OPERATOR = "(?:=|<>|!=|<=|>=|<|>|\\blike|\\bnot\\s+like)\\s*'?%?\\s*$";
  /** WHERE · AND · OR · SET · ON · HAVING 다음 「칼럼 비교연산자」 로 끝남. 앞 글이 SQL 이 아니어도 이 꼴이면 SQL 로 본다. */
  private static final Pattern KEYWORD_TAIL = Pattern.compile("(?is)\\b(?:where|and|or|set|on|having)\\s+[\\w.\"]+\\s*" + OPERATOR);
  /** 「, 칼럼 =」 로 끝남(UPDATE SET 의 둘째 칼럼 등). toString() 글과 갈리지 않아 앞 글이 SQL 일 때만 본다. */
  private static final Pattern COMMA_TAIL = Pattern.compile("(?is),\\s*[\\w.\"]+\\s*" + OPERATOR);
  /** IN ( · VALUES ( 안의 값 자리. */
  private static final Pattern LIST_TAIL = Pattern.compile("(?is)\\b(?:in|values)\\s*\\((?:[^()]*,)?\\s*'?\\s*$");
  /** ORDER BY 다음 칼럼 자리, 칼럼 뒤 ASC · DESC 자리. */
  private static final Pattern ORDER_TAIL = Pattern.compile(
      "(?is)\\border\\s+by\\s+(?:[\\w.\"]+(?:\\s+(?:asc|desc))?\\s*,\\s*)*(?:[\\w.\"]+\\s+)?$");
  /** FROM · JOIN · INTO · UPDATE · TABLE 다음 이름 자리. */
  private static final Pattern NAME_TAIL = Pattern.compile("(?is)\\b(?:from|join|into|update|table)\\s+(?:[\\w.\"]+\\s*,\\s*)*$");
  private static final Pattern SQL_WORD = Pattern.compile("(?i)\\b(?:select|insert|update|delete|merge|where)\\b");
  private static final Pattern TAG = Pattern.compile("<[^>]*>");

  /**
   * 자바 문자열 이어 붙이기: 식 바로 앞까지의 글(앞의 식은 {@link #PLACEHOLDER})로 그 식이 SQL 의 값 · 정렬 자리인지.
   * 위험이 아니면 null. 표 이름 자리(FROM 뒤)는 보지 않는다(코드에서 고른 표 이름이 흔하다).
   */
  static Spot concatSpot(String before) {
    if (ORDER_TAIL.matcher(before).find()) return Spot.ORDER;
    if (KEYWORD_TAIL.matcher(before).find() || LIST_TAIL.matcher(before).find()) return Spot.VALUE;
    if (COMMA_TAIL.matcher(before).find() && SQL_WORD.matcher(before).find()) return Spot.VALUE;
    return null;
  }

  /** 매퍼 SQL 의 ${} 한 개. start · end 는 「${」 부터 「}」 다음까지, name 은 안쪽 글(앞뒤 공백 뺌). */
  record Dollar(int start, int end, String name) {}

  /** text 의 [from, to) 안의 ${…}. 닫는 } 가 없으면 to 까지를 하나로 본다. */
  static List<Dollar> dollars(CharSequence text, int from, int to) {
    List<Dollar> out = new ArrayList<>();
    int i = from;
    while (i + 1 < to) {
      if (text.charAt(i) == '$' && text.charAt(i + 1) == '{') {
        int close = i + 2;
        while (close < to && text.charAt(close) != '}') close++;
        int end = close < to ? close + 1 : to;
        out.add(new Dollar(i, end, text.subSequence(i + 2, close).toString().strip()));
        i = end;
      } else {
        i++;
      }
    }
    return out;
  }

  /** 매퍼 SQL: ${} 바로 앞까지의 글(태그는 지움, 앞의 ${} 는 {@link #PLACEHOLDER})로 이름 · 정렬 · 값 자리를 가른다. */
  static Spot dollarSpot(String before) {
    String sql = TAG.matcher(before).replaceAll(" ");
    if (ORDER_TAIL.matcher(sql).find()) return Spot.ORDER;
    if (!sql.isEmpty()) {
      char last = sql.charAt(sql.length() - 1);
      if (Character.isLetterOrDigit(last) || last == '_' || last == '.') return Spot.NAME;
    }
    return NAME_TAIL.matcher(sql).find() ? Spot.NAME : Spot.VALUE;
  }

  // ---- 민감정보 ----

  /** 이름과 상관없이 값만 보고 정해지는 민감정보. */
  enum Secret { JDBC, URL_LOGIN, PRIVATE_KEY, CLOUD_KEY }

  /** 호스트까지 들어간 JDBC 주소. 「jdbc:oracle:thin:@」 처럼 앞부분뿐이면 아니다. 메모리 DB(h2:mem 등)는 // · @ 가 없어 빠진다. */
  private static final Pattern JDBC = Pattern.compile("(?i)^jdbc:[a-z0-9]+:\\S*?(?:@|//)[\\w.\\[\\]-]+");
  private static final Pattern URL_LOGIN = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://[^/\\s:@]+:[^/\\s@]+@[\\w.-]+");
  private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----");
  /** AWS 접근 키 ID. */
  private static final Pattern CLOUD_KEY = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b");

  static Secret secretLiteral(String value) {
    if (JDBC.matcher(value).find()) return Secret.JDBC;
    if (URL_LOGIN.matcher(value).find()) return Secret.URL_LOGIN;
    if (PRIVATE_KEY.matcher(value).find()) return Secret.PRIVATE_KEY;
    if (CLOUD_KEY.matcher(value).find()) return Secret.CLOUD_KEY;
    return null;
  }

  private static final Set<String> SECRET_WORDS = Set.of("password", "passwd", "pwd", "pw", "pass", "passphrase", "secret",
      "token", "credential", "credentials", "apikey", "privatekey", "accesskey", "secretkey");
  /** 이 낱말 다음 key 면 키 값이다(api key, private key 등). */
  private static final Set<String> KEY_OWNERS = Set.of("api", "access", "private", "secret", "client", "app", "signing", "encryption", "encrypt", "aes", "hmac", "jwt");
  private static final String KEY = "key";
  /** 마지막 낱말이 이것이면 비밀번호 자체가 아니라 그 이름 · 설명 · 규칙이다(PASSWORD_PARAM, tokenHeader 등). */
  private static final Set<String> NOT_VALUE = Set.of("param", "parameter", "params", "field", "label", "msg", "message", "prop",
      "property", "properties", "name", "names", "path", "file", "column", "col", "header", "attr", "attribute", "type", "policy",
      "length", "len", "rule", "rules", "hint", "placeholder", "encoder", "regex", "pattern", "id", "yn", "flag", "count", "cnt",
      "expire", "expired", "expiry", "date", "dt", "time", "mode", "check", "valid", "validator", "error", "err", "title", "text",
      "txt", "input", "form", "tag", "prefix", "suffix", "format", "url", "uri", "endpoint", "timeout", "size", "min", "max",
      "enabled", "required", "change", "reset", "confirm", "history", "hash", "template", "view", "page", "button", "btn", "query",
      "sql", "div", "class", "style", "kind", "status", "cookie", "storage", "store");
  private static final Pattern WORD_SPLIT = Pattern.compile("[_\\-.\\s]+|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
  /** 이 길이보다 짧은 값은 비밀번호로 보지 않는다(빈 값 · 표시용 짧은 글). */
  private static final int MIN_SECRET = 4;
  private static final Pattern PROPERTY_KEY = Pattern.compile("^[a-z][a-z0-9_-]*(?:\\.[a-z0-9_-]+)+$");
  private static final Pattern CONSTANT_NAME = Pattern.compile("^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+$");
  private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9]*(?:_[a-z0-9]+)+$|^[a-z]+(?:[A-Z][a-z0-9]*)+$");
  private static final Pattern VIEW_PATH = Pattern.compile("^/|^[\\w-]+(?:/[\\w.-]+)+$");
  private static final Pattern TEMPLATE = Pattern.compile("\\$\\{|#\\{|%[sd]|\\{\\d+}");

  static List<String> words(String name) {
    List<String> out = new ArrayList<>();
    for (String w : WORD_SPLIT.split(name)) {
      if (!w.isEmpty()) out.add(w.toLowerCase(Locale.ROOT));
    }
    return out;
  }

  /** 변수 · 설정 이름이 비밀번호 · 키 · 토큰 값을 담는 이름인지. */
  static boolean secretName(String name) {
    List<String> w = words(name);
    if (w.isEmpty()) return false;
    boolean keyPair = false;
    boolean secret = false;
    for (int i = 0; i < w.size(); i++) {
      if (SECRET_WORDS.contains(w.get(i))) secret = true;
      if (KEY.equals(w.get(i)) && i > 0 && KEY_OWNERS.contains(w.get(i - 1))) keyPair = true;
    }
    String last = w.get(w.size() - 1);
    if (keyPair && KEY.equals(last)) return true;
    return (secret || keyPair) && !NOT_VALUE.contains(last) && !KEY.equals(last);
  }

  /** 비밀 이름에 대입된 글이 실제 비밀 값처럼 보이는지. 설정 키 · 상수 이름 · 화면 경로 · 틀 글 · 한글 문구는 아니다. */
  static boolean secretValue(String name, String value) {
    String v = value.strip();
    if (v.length() < MIN_SECRET || !v.equals(value)) return false;
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      if (Character.isWhitespace(c) || c > '~') return false;
    }
    if (TEMPLATE.matcher(v).find() || PROPERTY_KEY.matcher(v).matches() || CONSTANT_NAME.matcher(v).matches()
        || VIEW_PATH.matcher(v).find()) return false;
    if (v.chars().distinct().count() == 1) return false;
    String lower = v.toLowerCase(Locale.ROOT);
    if (lower.equals(name.toLowerCase(Locale.ROOT))) return false;
    List<String> own = words(v);
    if (own.size() == 1 && SECRET_WORDS.contains(own.get(0))) return false;
    if (IDENTIFIER.matcher(v).matches()) {
      for (String w : own) {
        if (SECRET_WORDS.contains(w) || KEY.equals(w)) return false;
      }
    }
    return true;
  }

  /** 「db.password」 · 「spring.datasource.password」 같은 설정 키의 마지막 마디. */
  static String lastSegment(String key) {
    int dot = key.lastIndexOf('.');
    return dot < 0 ? key : key.substring(dot + 1);
  }
}
