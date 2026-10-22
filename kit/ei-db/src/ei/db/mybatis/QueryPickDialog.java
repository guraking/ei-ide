package ei.db.mybatis;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.EiDbBundle;
import ei.db.Notify;
import ei.db.console.SqlFormatter;
import org.jetbrains.annotations.NotNull;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 「보낼 쿼리」 고르기: 왼쪽 거르기 칸 · 쿼리 목록(시각 · 문장id · 매퍼), 오른쪽 고른 쿼리(값을 채우고 정렬한 SQL).
 * 거르기 칸의 ↑ · ↓ 는 목록 선택을 옮긴다.
 * 확인(콘솔로 보내기)이면 OK, 「실행」이면 RUN_EXIT 로 닫힌다. 「복사」는 클립보드에 넣고 닫는다. 고른 쿼리는 selected().
 */
final class QueryPickDialog extends DialogWrapper {
  static final int RUN_EXIT = NEXT_USER_EXIT_CODE;

  private static final int LIST_WIDTH = 380;
  private static final int PREVIEW_WIDTH = 640;
  private static final int HEIGHT = 480;
  private static final float SPLIT = 0.37f;
  /** 이름(주석 · 로거)이 없는 쿼리를 목록 한 줄에 보일 SQL 글자 수. */
  private static final int LABEL_SQL_LENGTH = 60;

  private final Project project;
  private final List<MyBatisLog.Entry> all;
  private final CollectionListModel<MyBatisLog.Entry> model;
  private final JBList<MyBatisLog.Entry> list;
  private final SearchTextField filter = new SearchTextField(false);
  private final EditorTextField preview;
  private final JBLabel meta = new JBLabel();

  /** entries 는 보일 순서(최근 것이 위), near 는 처음 고를 쿼리. */
  QueryPickDialog(Project project, List<MyBatisLog.Entry> entries, MyBatisLog.Entry near) {
    super(project);
    this.project = project;
    all = List.copyOf(entries);
    model = new CollectionListModel<>(new ArrayList<>(all));
    list = new JBList<>(model);
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setCellRenderer(new EntryRenderer());
    preview = new EditorTextField(EditorFactory.getInstance().createDocument(""), project,
                                  FileTypeManager.getInstance().getFileTypeByExtension("sql"), true, false);
    preview.addSettingsProvider(e -> {
      e.setVerticalScrollbarVisible(true);
      e.setHorizontalScrollbarVisible(true);
      e.getSettings().setLineNumbersShown(true);
    });
    meta.setForeground(UIUtil.getContextHelpForeground());
    filter.getTextEditor().getEmptyText().setText(EiDbBundle.message("querypick.filter.hint"));
    filter.addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
        refilter();
      }
    });
    filter.getTextEditor().addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        int step = e.getKeyCode() == KeyEvent.VK_DOWN ? 1 : e.getKeyCode() == KeyEvent.VK_UP ? -1 : 0;
        if (step == 0 || model.getSize() == 0) return;
        int next = Math.max(0, Math.min(model.getSize() - 1, list.getSelectedIndex() + step));
        list.setSelectedIndex(next);
        list.ensureIndexIsVisible(next);
        e.consume();
      }
    });
    list.addListSelectionListener(e -> showEntry(list.getSelectedValue()));
    new DoubleClickListener() {
      @Override
      protected boolean onDoubleClick(@NotNull MouseEvent e) {
        if (list.getSelectedValue() == null) return false;
        doOKAction();
        return true;
      }
    }.installOn(list);
    setTitle(EiDbBundle.message("querypick.title"));
    setOKButtonText(EiDbBundle.message("querypick.send"));
    init();
    list.setSelectedValue(near, true);
    showEntry(list.getSelectedValue());
  }

  MyBatisLog.Entry selected() {
    return list.getSelectedValue();
  }

  /** 미리보기 · 복사에 쓰는 SQL: 로그 값을 채우고 콘솔 「정렬」과 같이 정렬한다. 정렬에 실패하면 채운 SQL 그대로. */
  static String previewSql(MyBatisLog.Entry entry) {
    String sql = entry.runnableSql();
    try {
      return SqlFormatter.format(sql);
    } catch (RuntimeException ex) {
      return sql;
    }
  }

  @Override
  protected JComponent createCenterPanel() {
    JPanel right = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    right.add(meta, BorderLayout.NORTH);
    right.add(preview, BorderLayout.CENTER);
    JPanel left = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    left.add(filter, BorderLayout.NORTH);
    left.add(new JBScrollPane(list), BorderLayout.CENTER);
    left.setPreferredSize(JBUI.size(LIST_WIDTH, HEIGHT));
    right.setPreferredSize(JBUI.size(PREVIEW_WIDTH, HEIGHT));
    JBSplitter split = new JBSplitter(false, SPLIT);
    split.setFirstComponent(left);
    split.setSecondComponent(right);
    JBLabel hint = new JBLabel(EiDbBundle.message("querypick.hint"));
    hint.setForeground(UIUtil.getContextHelpForeground());
    JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
    panel.add(split, BorderLayout.CENTER);
    panel.add(hint, BorderLayout.SOUTH);
    new DumbAwareAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        if (list.getSelectedValue() != null) close(RUN_EXIT);
      }
    }.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)), panel);
    return panel;
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return filter.getTextEditor();
  }

  /** 거르기 글에 맞는 쿼리만 남긴다. 고른 쿼리가 남아 있으면 그대로, 아니면 첫 쿼리를 고른다. */
  private void refilter() {
    MyBatisLog.Entry keep = list.getSelectedValue();
    String text = filter.getText();
    List<MyBatisLog.Entry> shown = new ArrayList<>();
    for (MyBatisLog.Entry e : all) {
      if (MyBatisLog.matches(e, text)) shown.add(e);
    }
    model.replaceAll(shown);
    if (keep != null && shown.contains(keep)) list.setSelectedValue(keep, true);
    else if (!shown.isEmpty()) list.setSelectedIndex(0);
    showEntry(list.getSelectedValue());
  }

  @Override
  protected Action @NotNull [] createActions() {
    Action copy = new DialogWrapperAction(EiDbBundle.message("common.copy")) {
      @Override
      protected void doAction(ActionEvent e) {
        MyBatisLog.Entry entry = list.getSelectedValue();
        if (entry == null) return;
        CopyPasteManager.copyTextToClipboard(previewSql(entry));
        Notify.info(project, EiDbBundle.message("querypick.copied"));
        close(CANCEL_EXIT_CODE);
      }
    };
    Action run = new DialogWrapperAction(EiDbBundle.message("common.run")) {
      @Override
      protected void doAction(ActionEvent e) {
        if (list.getSelectedValue() != null) close(RUN_EXIT);
      }
    };
    return new Action[] {copy, run, getOKAction()};
  }

  @Override
  protected void doOKAction() {
    if (list.getSelectedValue() != null) super.doOKAction();
  }

  private void showEntry(MyBatisLog.Entry entry) {
    if (entry == null) {
      meta.setText(" ");
      preview.setText("");
      return;
    }
    List<String> parts = new ArrayList<>();
    if (entry.time != null) parts.add(entry.time);
    if (entry.thread != null) parts.add("[" + entry.thread + "]");
    parts.add(entry.elapsed == null ? "MyBatis" : "log4jdbc · " + entry.elapsed);
    meta.setText(String.join(" · ", parts));
    preview.setText(previewSql(entry));
  }

  /** 「시각  문장id  매퍼」. 이름이 없으면 「시각  SQL 앞부분」. */
  private static final class EntryRenderer extends ColoredListCellRenderer<MyBatisLog.Entry> {
    @Override
    protected void customizeCellRenderer(@NotNull JList<? extends MyBatisLog.Entry> list, MyBatisLog.Entry value, int index,
                                         boolean selected, boolean hasFocus) {
      if (value.time != null) append(value.time + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
      String name = MyBatisLog.queryName(value);
      if (name == null) {
        append(StringUtil.shortenTextWithEllipsis(value.sql.replaceAll("\\s+", " "), LABEL_SQL_LENGTH, 0));
        return;
      }
      int dot = name.lastIndexOf('.');
      append(name.substring(dot + 1), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
      if (dot > 0) append("  " + name.substring(0, dot), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
    }
  }
}
