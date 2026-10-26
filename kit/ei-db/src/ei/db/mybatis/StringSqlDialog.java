package ei.db.mybatis;


import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import ei.db.EiDbBundle;
import ei.db.FileTypes;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;

/** 「문자열 SQL 정리」 결과: 왼쪽 정리한 SQL, 오른쪽 매퍼 XML 문장. 읽기 전용이고 복사 단추로 가져간다. */
final class StringSqlDialog extends DialogWrapper {

  private static final int CODE_WIDTH = 520;
  private static final int CODE_HEIGHT = 480;

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
    JPanel p = new JPanel(new GridLayout(1, 2, JBUI.scale(16), 0));
    p.add(section(EiDbBundle.message("stringsql.section.sql"), EiDbBundle.message("stringsql.copy.sql"), "sql", sql));
    p.add(section(EiDbBundle.message("stringsql.section.xml"), EiDbBundle.message("stringsql.copy.xml"), "xml", xml));
    return p;
  }

  private JComponent section(String title, String copyText, String extension, String text) {
    EditorTextField editor = new EditorTextField(EditorFactory.getInstance().createDocument(text), project,
                                                 FileTypes.byExtension(extension), true, false);
    editor.setPreferredSize(JBUI.size(CODE_WIDTH, CODE_HEIGHT));
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
