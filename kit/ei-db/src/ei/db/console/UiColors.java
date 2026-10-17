package ei.db.console;

import com.intellij.ui.JBColor;

import java.awt.Color;

/** 콘솔 · 결과 · 확인창이 같이 쓰는 위험 · 경고 · 성공 색(라이트, 다크). 나머지는 테마 색을 쓴다. */
final class UiColors {
  private UiColors() {}

  static final Color DANGER = JBColor.namedColor("Label.errorForeground", new JBColor(new Color(0xC7222D), new Color(0xE5605A)));
  static final Color DANGER_TEXT = new JBColor(new Color(0xB3261E), new Color(0xFFB3AE));
  static final Color DANGER_BAND = new JBColor(new Color(0xFDECEA), new Color(0x4A2C2E));
  static final Color DANGER_LINE = new JBColor(new Color(0xF2B8B5), new Color(0x7A3A3A));
  static final Color WARNING = new JBColor(new Color(0xA35D00), new Color(0xE0A64F));
  static final Color SUCCESS = new JBColor(new Color(0x2E7D32), new Color(0x5FB865));
}
