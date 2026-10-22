package ei.db.console;

import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;

import java.awt.Color;

/** 콘솔 · 결과 · 확인창이 같이 쓰는 위험 · 경고 · 성공 색. 모두 테마 색이라 테마가 바꾸면 따라 바뀐다. */
final class UiColors {
  private UiColors() {}

  static final Color DANGER = NamedColorUtil.getErrorForeground();
  static final Color DANGER_TEXT = NamedColorUtil.getErrorForeground();
  /** 운영 경고 띠: 편집기 위 알림 띠(Banner)의 오류 바탕 · 테두리. */
  static final Color DANGER_BAND = JBUI.CurrentTheme.Banner.ERROR_BACKGROUND;
  static final Color DANGER_LINE = JBUI.CurrentTheme.Banner.ERROR_BORDER_COLOR;
  static final Color WARNING = JBUI.CurrentTheme.Label.warningForeground();
  static final Color SUCCESS = UIUtil.getLabelSuccessForeground();
}
