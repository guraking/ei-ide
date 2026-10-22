package ei.db.console;

import com.intellij.ui.JBColor;
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
  /** 조회 건수 꼬리표 바탕. */
  static final Color SUCCESS_BG = JBColor.namedColor("EiDatabase.countBackground", new JBColor(0xDDF3E0, 0x253D2A));
  /** 미반영 변경 꼬리표 글 · 바탕. */
  static final Color PENDING = JBColor.namedColor("EiDatabase.pending", new JBColor(0x9A5B00, 0xE5A84B));
  static final Color PENDING_BG = JBColor.namedColor("EiDatabase.pendingBackground", new JBColor(0xFCEBCF, 0x4A3B22));
  /** 결과 표의 숫자 글자색. */
  static final Color NUMBER = JBColor.namedColor("EiDatabase.number", new JBColor(0x1750EB, 0x6FB8E8));
  /** 접속 상태 점 · 운영 꼬리표. 탐색기와 같은 키를 쓴다. */
  static final Color CONNECTED = JBColor.namedColor("EiDatabase.connected", new JBColor(0x3E9B47, 0x5FB865));
  static final Color DISCONNECTED = JBColor.GRAY;
  static final Color PRODUCTION = JBColor.namedColor("EiDatabase.production", new JBColor(0xD9363E, 0xF75464));
  static final Color PRODUCTION_BG = JBColor.namedColor("EiDatabase.productionBackground", new JBColor(0xFADBDC, 0x4D2A2C));
}
