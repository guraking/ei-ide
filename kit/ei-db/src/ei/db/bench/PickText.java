package ei.db.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 고르는 글 칸(PickField)의 글 규칙. 항목은 「이름」 또는 「이름  — 설명」 이고, 칸에는 이름만 들어간다.
 * 여러 값 칸(many)은 쉼표로 잇는다: 거를 때는 마지막 쉼표 뒤 글만 본다. 고르면 없던 이름은 뒤에 붙이고 있던 이름은 뺀다.
 * 치다가 고르면(all 아님) 치던 마지막 조각은 고른 이름으로 바뀐다.
 */
final class PickText {
  /** 항목에서 이름과 설명 사이. */
  static final String COMMENT_GAP = "  — ";

  private PickText() { }

  static List<String> filter(List<String> items, String text, boolean many, boolean all) {
    if (all) return new ArrayList<>(items);
    String needle = (many ? tail(text) : text).strip().toLowerCase(Locale.ROOT);
    List<String> out = new ArrayList<>();
    for (String item : items) if (item.toLowerCase(Locale.ROOT).contains(needle)) out.add(item);
    return out;
  }

  /** item 을 고른 뒤 칸에 놓일 글. */
  static String picked(String text, String item, boolean many, boolean all) {
    String name = item.split(COMMENT_GAP)[0].strip();
    if (!many) return name;
    List<String> names = names(all ? text : text.substring(0, text.lastIndexOf(',') + 1));
    if (!names.removeIf(name::equalsIgnoreCase)) names.add(name);
    return String.join(", ", names);
  }

  /** 여러 값 칸의 글에 item 의 이름이 이미 들어 있는지. */
  static boolean chosen(String text, String item) {
    String name = item.split(COMMENT_GAP)[0].strip();
    for (String n : names(text)) if (n.equalsIgnoreCase(name)) return true;
    return false;
  }

  /** 쉼표로 나눈 이름들(빈 조각은 뺀다). */
  static List<String> names(String text) {
    List<String> out = new ArrayList<>();
    for (String s : text.split(",")) if (!s.isBlank()) out.add(s.strip());
    return out;
  }

  private static String tail(String text) {
    return text.substring(text.lastIndexOf(',') + 1);
  }
}
