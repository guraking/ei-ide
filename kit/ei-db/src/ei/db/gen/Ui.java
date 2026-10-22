package ei.db.gen;

import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.console.Badge;
import org.jetbrains.annotations.NotNull;

import javax.swing.event.DocumentEvent;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Font;

/** 생성기 화면들이 함께 쓰는 색 · 글꼴. 색은 테마 색을 쓰고 PK · FK 꼬리표 · ERD 강조 · 성공 표시는 테마 키와 밝은 · 어두운 기본값을 둔다. */
final class Ui {
  private Ui() {}

  static final Color PK = Badge.PK;
  static final Color PK_BG = Badge.PK_BACKGROUND;
  static final Color FK = Badge.FK;
  static final Color FK_BG = Badge.FK_BACKGROUND;
  /** ERD 의 고른 표 테두리 · 이어진 선과 고른 표 머리 바탕. */
  static final Color SELECTION = JBColor.namedColor("EiDatabase.selection", JBUI.CurrentTheme.Focus.focusColor());
  static final Color SELECTION_BG = JBColor.namedColor("EiDatabase.selectionBackground", new JBColor(0xDCE6FA, 0x2E3B52));
  /** ERD 표 머리 색 띠. 이름 토막(ErdLayout.category)으로 고른다. */
  static final Color[] CATEGORY = {
      JBColor.namedColor("EiDatabase.erd.cat1", new JBColor(0x9B4DCA, 0xBF5AF2)),
      JBColor.namedColor("EiDatabase.erd.cat2", new JBColor(0x2F63D3, 0x4C8DF6)),
      JBColor.namedColor("EiDatabase.erd.cat3", new JBColor(0x1E9E96, 0x40C8C0)),
      JBColor.namedColor("EiDatabase.erd.cat4", new JBColor(0x3E9B47, 0x5FB865)),
      JBColor.namedColor("EiDatabase.erd.cat5", new JBColor(0xD6335A, 0xFF5C7C)),
      JBColor.namedColor("EiDatabase.erd.cat6", new JBColor(0xC27000, 0xFF9F0A)),
      JBColor.namedColor("EiDatabase.erd.cat7", new JBColor(0x1C8FC4, 0x64D2FF)),
      JBColor.namedColor("EiDatabase.erd.cat8", new JBColor(0x5856D6, 0x7D7AFF)),
      JBColor.namedColor("EiDatabase.erd.cat9", new JBColor(0xB08A00, 0xFFD60A)),
      JBColor.namedColor("EiDatabase.erd.cat10", new JBColor(0xC94F6D, 0xFF6482)),
  };
  static final Color SUCCESS = JBColor.namedColor("Label.successForeground", new JBColor(new Color(0x368746), new Color(0x5FB865)));

  static Color muted() {
    return UIUtil.getContextHelpForeground();
  }

  static Font mono() {
    return new Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.label().getSize());
  }

  static void onChange(JTextComponent field, Runnable r) {
    field.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
        r.run();
      }
    });
  }
}
