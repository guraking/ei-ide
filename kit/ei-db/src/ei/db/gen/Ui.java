package ei.db.gen;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.console.Badge;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.event.DocumentEvent;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;

/** 생성기 화면들이 함께 쓰는 색 · 글꼴. 색은 테마 색을 쓰고 PK · FK 꼬리표 · ERD 강조 · 성공 표시는 테마 키와 밝은 · 어두운 기본값을 둔다. */
public final class Ui {
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
    return UIUtil.getFontWithFallback(EditorColorsManager.getInstance().getGlobalScheme().getEditorFontName(), Font.PLAIN, JBUI.Fonts.label().getSize());
  }

  static void onChange(JTextComponent field, Runnable r) {
    field.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
        r.run();
      }
    });
  }

  /** 생성기 대화창의 칸 나눔 비율을 기억하는 키 머리. */
  private static final String SPLIT_KEY = "ei.db.gen.split.";

  /** 좌우 칸을 끌 수 있는 분할선으로 나눈다. 칸 폭은 비율로만 정하고(끌어 바꾼 비율은 key 로 기억), 안쪽 최소 폭이 비율을 이기지 않게 한다. */
  public static JComponent split(String key, float proportion, JComponent left, JComponent right) {
    OnePixelSplitter s = new OnePixelSplitter(false, proportion);
    s.setSplitterProportionKey(SPLIT_KEY + key);
    s.setHonorComponentsMinimumSize(false);
    s.setFirstComponent(left);
    s.setSecondComponent(right);
    return s;
  }

  /** 칸 너비를 기억하는 키 머리. */
  private static final String COLUMNS_KEY = "ei.db.gen.columns.";
  private static final String WIDTH_SEPARATOR = ",";
  /** 체크 · 꼬리표 칸의 글 양옆 여백 합(배율 전). */
  private static final int CELL_PADDING = 12;

  /**
   * 체크 · 꼬리표 칸: 머리 글 · sample 글(체크 칸이면 체크 상자도) 가운데 넓은 쪽에 여백을 더한 폭으로 묶는다. 글꼴 · 배율을 따른다.
   * 남은 칸은 창 폭에 따라 늘어난다.
   */
  public static void fitColumn(JTable table, int column, String sample) {
    TableColumn c = table.getColumnModel().getColumn(column);
    Font headFont = table.getTableHeader() != null ? table.getTableHeader().getFont() : table.getFont();
    int w = Math.max(table.getFontMetrics(headFont).stringWidth(String.valueOf(c.getHeaderValue())),
                     table.getFontMetrics(table.getFont()).stringWidth(sample));
    if (table.getColumnClass(column) == Boolean.class) w = Math.max(w, new JBCheckBox().getPreferredSize().width);
    w += JBUI.scale(CELL_PADDING);
    c.setMinWidth(w);
    c.setPreferredWidth(w);
    c.setMaxWidth(w);
  }

  /** 사용자가 끌어 바꾼 칸 너비를 key 로 기억하고 다음에 열 때 되살린다(칸 수가 같을 때만). 창 폭이 바뀌면 그 비율대로 늘거나 준다. */
  public static void rememberColumns(JTable table, String key) {
    PropertiesComponent props = PropertiesComponent.getInstance();
    String saved = props.getValue(COLUMNS_KEY + key);
    TableColumnModel cm = table.getColumnModel();
    if (saved != null) {
      String[] parts = saved.split(WIDTH_SEPARATOR);
      if (parts.length == cm.getColumnCount()) {
        try {
          for (int i = 0; i < parts.length; i++) {
            if (cm.getColumn(i).getMaxWidth() > cm.getColumn(i).getMinWidth()) cm.getColumn(i).setPreferredWidth(Integer.parseInt(parts[i].strip()));
          }
        } catch (NumberFormatException ex) {
          props.unsetValue(COLUMNS_KEY + key); // 망가진 값은 버리고 처음 너비를 쓴다.
        }
      }
    }
    cm.addColumnModelListener(new TableColumnModelListener() {
      @Override
      public void columnMarginChanged(ChangeEvent e) {
        if (table.getTableHeader() == null || table.getTableHeader().getResizingColumn() == null) return;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cm.getColumnCount(); i++) {
          if (i > 0) b.append(WIDTH_SEPARATOR);
          b.append(cm.getColumn(i).getWidth());
        }
        props.setValue(COLUMNS_KEY + key, b.toString());
      }

      @Override public void columnAdded(TableColumnModelEvent e) { }

      @Override public void columnRemoved(TableColumnModelEvent e) { }

      @Override public void columnMoved(TableColumnModelEvent e) { }

      @Override public void columnSelectionChanged(ListSelectionEvent e) { }
    });
  }

  /** 대화창 처음 크기: 화면 폭 · 높이에 대한 비율. 대화창이 getDimensionServiceKey 를 주면 사용자가 바꾼 크기가 이것보다 앞선다. */
  public static Dimension firstSize(double widthRatio, double heightRatio) {
    Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
    return new Dimension((int) (screen.width * widthRatio), (int) (screen.height * heightRatio));
  }
}
