package ei.db.console;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 두 조회 결과 비교(IntelliJ 클래스 없음). 칼럼은 이름으로(대소문자 무시) 맞추고, 행은 기준 칼럼 값으로 맞춘다.
 * 기준 칼럼이 없으면 행 순서로 맞춘다. 같은 기준 값이 여러 행이면 양쪽에서 나온 순서대로 짝짓는다.
 * 값 비교: null 은 null 과만 같고, 숫자는 형식과 소수 자리에 상관없이 값으로(1 = 1.0 = 1.00) 비교한다.
 */
final class ResultDiff {
  private ResultDiff() {}

  enum Kind { SAME, CHANGED, LEFT_ONLY, RIGHT_ONLY }

  /** 공통 칼럼 순서의 값. 한쪽에만 있는 행은 다른 쪽이 null. changed[i] 는 공통 칼럼 i 값이 다른지. */
  record Row(Kind kind, List<Object> left, List<Object> right, boolean[] changed) {}

  /** columns 는 공통 칼럼(왼쪽 이름). leftOnlyColumns · rightOnlyColumns 는 비교하지 않은 칼럼. */
  record Outcome(List<String> columns, List<String> leftOnlyColumns, List<String> rightOnlyColumns, List<Row> rows) {
    int count(Kind kind) {
      int n = 0;
      for (Row r : rows) {
        if (r.kind() == kind) n++;
      }
      return n;
    }
  }

  /** 이름이 같은(대소문자 무시) 칼럼, 왼쪽 순서. 한 결과 안에서 이름이 겹치면 처음 것만 쓴다. */
  static List<String> commonColumns(List<String> left, List<String> right) {
    Map<String, Integer> rightIndex = indexByName(right);
    List<String> out = new ArrayList<>();
    for (Map.Entry<String, Integer> e : indexByName(left).entrySet()) {
      if (rightIndex.containsKey(e.getKey())) out.add(left.get(e.getValue()));
    }
    return out;
  }

  /** keyColumns 는 공통 칼럼 이름(대소문자 무시). 비었으면 행 순서로 맞춘다. 공통 칼럼에 없는 이름은 IllegalArgumentException. */
  static Outcome compare(List<String> leftColumns, List<List<Object>> leftRows,
                         List<String> rightColumns, List<List<Object>> rightRows, List<String> keyColumns) {
    Map<String, Integer> leftIndex = indexByName(leftColumns);
    Map<String, Integer> rightIndex = indexByName(rightColumns);
    List<String> common = commonColumns(leftColumns, rightColumns);
    int[] l = new int[common.size()];
    int[] r = new int[common.size()];
    for (int i = 0; i < common.size(); i++) {
      String name = upper(common.get(i));
      l[i] = leftIndex.get(name);
      r[i] = rightIndex.get(name);
    }
    int[] lk = new int[keyColumns.size()];
    int[] rk = new int[keyColumns.size()];
    for (int i = 0; i < keyColumns.size(); i++) {
      String name = upper(keyColumns.get(i));
      Integer a = leftIndex.get(name);
      Integer b = rightIndex.get(name);
      if (a == null || b == null) throw new IllegalArgumentException("기준 칼럼 " + keyColumns.get(i) + " 이 양쪽 결과에 모두 있지 않습니다.");
      lk[i] = a;
      rk[i] = b;
    }

    Map<List<Object>, Deque<Integer>> rightByKey = new HashMap<>();
    for (int i = 0; i < rightRows.size(); i++) {
      rightByKey.computeIfAbsent(key(rightRows.get(i), rk, i), k -> new ArrayDeque<>()).add(i);
    }
    boolean[] rightUsed = new boolean[rightRows.size()];
    List<Row> rows = new ArrayList<>();
    for (int i = 0; i < leftRows.size(); i++) {
      List<Object> left = pick(leftRows.get(i), l);
      Deque<Integer> match = rightByKey.get(key(leftRows.get(i), lk, i));
      if (match == null || match.isEmpty()) {
        rows.add(new Row(Kind.LEFT_ONLY, left, null, new boolean[common.size()]));
        continue;
      }
      int j = match.poll();
      rightUsed[j] = true;
      List<Object> right = pick(rightRows.get(j), r);
      boolean[] changed = new boolean[common.size()];
      boolean any = false;
      for (int c = 0; c < changed.length; c++) {
        changed[c] = !same(left.get(c), right.get(c));
        any |= changed[c];
      }
      rows.add(new Row(any ? Kind.CHANGED : Kind.SAME, left, right, changed));
    }
    for (int j = 0; j < rightRows.size(); j++) {
      if (!rightUsed[j]) rows.add(new Row(Kind.RIGHT_ONLY, null, pick(rightRows.get(j), r), new boolean[common.size()]));
    }
    return new Outcome(common, onlyIn(leftColumns, rightIndex), onlyIn(rightColumns, leftIndex), rows);
  }

  /** 값이 같은지. 숫자는 값으로, 나머지는 equals. null 은 null 과만 같다. */
  static boolean same(Object a, Object b) {
    return Objects.equals(normalize(a), normalize(b));
  }

  /** 행을 맞출 열쇠. null 값도 하나의 값으로 본다(null 끼리 같음). 기준 칼럼이 없으면 행 번호. */
  static List<Object> key(List<Object> row, int[] keyIndex, int rowNumber) {
    if (keyIndex.length == 0) return List.of(rowNumber);
    // List.of 는 null 을 받지 않으므로 Arrays.asList 로 만든다.
    Object[] values = new Object[keyIndex.length];
    for (int i = 0; i < keyIndex.length; i++) values[i] = normalize(row.get(keyIndex[i]));
    return Arrays.asList(values);
  }

  /** 숫자를 소수 자리 없는 BigDecimal 로. NaN · 무한대는 그대로 둔다(BigDecimal 로 못 바꿈). */
  static Object normalize(Object v) {
    if (v instanceof BigDecimal d) return d.stripTrailingZeros();
    if (v instanceof BigInteger i) return new BigDecimal(i);
    if (v instanceof Double d && (d.isNaN() || d.isInfinite())) return v;
    if (v instanceof Float f && (f.isNaN() || f.isInfinite())) return v;
    if (v instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros();
    return v;
  }

  private static List<Object> pick(List<Object> row, int[] index) {
    List<Object> out = new ArrayList<>(index.length);
    for (int i : index) out.add(row.get(i));
    return out;
  }

  private static List<String> onlyIn(List<String> columns, Map<String, Integer> other) {
    List<String> out = new ArrayList<>();
    for (String c : columns) {
      if (!other.containsKey(upper(c))) out.add(c);
    }
    return out;
  }

  private static Map<String, Integer> indexByName(List<String> columns) {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (int i = 0; i < columns.size(); i++) out.putIfAbsent(upper(columns.get(i)), i);
    return out;
  }

  private static String upper(String name) {
    return name.toUpperCase(Locale.ROOT);
  }
}
