package ei.db.gen;

import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.event.DocumentEvent;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Font;

/** 생성기 화면들이 함께 쓰는 색 · 글꼴. 색은 테마 색을 쓰고 PK · FK 꼬리표와 성공 표시만 밝은 · 어두운 짝을 둔다. */
final class Ui {
  private Ui() {}

  static final Color PK = new JBColor(new Color(0xB86E00), new Color(0xE0A64F));
  static final Color FK = new JBColor(new Color(0x2F63D3), new Color(0x6F9DF7));
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
