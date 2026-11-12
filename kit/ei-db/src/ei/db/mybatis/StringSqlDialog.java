package ei.db.mybatis;


import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import ei.db.EiDbBundle;
import ei.db.gen.Ui;
import ei.db.FileTypes;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Dimension;

/** 「문자열 SQL 정리」 결과: 왼쪽 정리한 SQL, 오른쪽 매퍼 XML 문장. 읽기 전용이고 복사 단추로 가져간다. */
final class StringSqlDialog extends DialogWrapper {

  /** 왼쪽 칸 처음 비율(끌어 바꾼 비율은 기억한다). */
  private static final float SPLIT = 0.5f;
  private static final String SPLIT_KEY = "stringsql";
  /** 처음 열 때 화면 폭 · 높이에 대한 창 크기 비율. 사용자가 바꾼 크기는 IntelliJ 가 기억한다. */
  private static final double FIRST_WIDTH_RATIO = 0.6;
  private static final double FIRST_HEIGHT_RATIO = 0.55;
  private static final String DIMENSION_KEY = "ei.db.mybatis.StringSqlDialog";
  /** 분할선 양쪽 안쪽 여백(배율 전). */
  private static final int SECTION_GAP = 8;

  private final Project project;
  private final String sql;
  private final String xml;

  StringSqlDialog(Project project, String sql, String xml) {
    super(project);
    this.project = project;
    this.sql = sql;
    this.xml = xml;
    setTitle(EiDbBundle.message("stringsql.title"));
    setOKButtonText(EiDbBundle.message("common.close"));
    init();
  }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[] {getOKAction()};
  }

  @Override
  protected JComponent createCenterPanel() {
    JComponent left = section(EiDbBundle.message("stringsql.section.sql"), EiDbBundle.message("stringsql.copy.sql"), "sql", sql);
    JComponent right = section(EiDbBundle.message("stringsql.section.xml"), EiDbBundle.message("stringsql.copy.xml"), "xml", xml);
    left.setBorder(JBUI.Borders.emptyRight(SECTION_GAP));
    right.setBorder(JBUI.Borders.emptyLeft(SECTION_GAP));
    return Ui.split(SPLIT_KEY, SPLIT, left, right);
  }

  @Override
  protected @Nullable String getDimensionServiceKey() { return DIMENSION_KEY; }

  @Override
  public Dimension getInitialSize() { return Ui.firstSize(FIRST_WIDTH_RATIO, FIRST_HEIGHT_RATIO); }

  private JComponent section(String title, String copyText, String extension, String text) {
    EditorTextField editor = new EditorTextField(EditorFactory.getInstance().createDocument(text), project,
                                                 FileTypes.byExtension(extension), true, false);
    editor.addSettingsProvider(e -> {
      e.setVerticalScrollbarVisible(true);
      e.setHorizontalScrollbarVisible(true);
      e.getSettings().setLineNumbersShown(true);
    });
    JButton copy = new JButton(copyText);
    copy.addActionListener(e -> {
      CopyPasteManager.copyTextToClipboard(text);
      Notify.info(project, EiDbBundle.message("stringsql.copied", title));
    });
    JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
    bar.add(new JBLabel(title));
    bar.add(copy);
    JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    panel.add(bar, BorderLayout.NORTH);
    panel.add(editor, BorderLayout.CENTER);
    return panel;
  }
}
