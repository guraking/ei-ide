package ei.db.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 고르는 글 칸(PickField)의 글 규칙. 항목은 「이름」 또는 「이름  — 설명」 이고, 칸에는 이름만 들어간다.
 * 여러 값 칸(many)은 쉼표로 잇는다: 거를 때는 마지막 쉼표 뒤 글만 보고, 고르면 그 글 자리에 넣는다.
 * 목록을 통째로 펼쳐(all) 고르면 거르지 않고, 여러 값 칸에서는 이미 있는 값 뒤에 쉼표로 붙인다.
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
    if (all && !tail(text).isBlank()) return text.stripTrailing() + ", " + name;
    return head(text) + name;
  }

  /** 마지막 쉼표까지(쉼표 뒤 한 칸 포함). 쉼표가 없으면 빈 글. */
  private static String head(String text) {
    int comma = text.lastIndexOf(',');
    return comma < 0 ? "" : text.substring(0, comma + 1) + " ";
  }

  private static String tail(String text) {
    return text.substring(text.lastIndexOf(',') + 1);
  }
}
