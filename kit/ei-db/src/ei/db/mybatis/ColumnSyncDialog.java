package ei.db.mybatis;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.CheckboxTree;
import com.intellij.ui.CheckboxTreeBase;
import com.intellij.ui.CheckedTreeNode;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import ei.db.EiDbBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「DB 바뀐 것 코드에 맞추기」 미리보기. 왼쪽은 파일 › 고칠 것 체크 나무(손으로 할 일 · 알림은 체크 없음), 오른쪽은 고른 줄의 고친 뒤 모습.
 * 두 번 누르면 그 자리로 간다(창이 떠 있는 채로 에디터에서 볼 수 있게 모달이 아니다). 확인하면 체크된 줄들을 고친다.
 * 연 뒤 파일이 바뀌었으면 고치지 않는다(ColumnSyncAction.apply 가 수정 표시로 확인).
 */
final class ColumnSyncDialog extends DialogWrapper {
  /** 처음 열 때 화면에 대한 창 크기 몫. 사용자가 바꾼 크기는 DIMENSION_KEY 로 기억한다. */
  private static final double FIRST_WIDTH_RATIO = 0.6;
  private static final double FIRST_HEIGHT_RATIO = 0.55;
  private static final String DIMENSION_KEY = "ei.db.mybatis.ColumnSyncDialog";
  /** 나무가 차지하는 몫. 끈 값은 SPLIT_KEY 로 기억한다. */
  private static final float TREE_SPLIT = 0.5f;
  private static final String SPLIT_KEY = "ei.db.mybatis.ColumnSyncDialog.split";

  private final Project project;
  private final ColumnSyncAction.Plan plan;
  private final CheckboxTree tree;
  private final JBTextArea preview = new JBTextArea();

  ColumnSyncDialog(Project project, ColumnSyncAction.Plan plan) {
    super(project, true, IdeModalityType.MODELESS);
    this.project = project;
    this.plan = plan;
    CheckedTreeNode root = new CheckedTreeNode(null);
    Map<String, CheckedTreeNode> groups = new LinkedHashMap<>();
    for (ColumnSyncAction.Row r : plan.rows()) {
      CheckedTreeNode g = groups.computeIfAbsent(r.file().getPath() + "\n" + r.group(), k -> {
        CheckedTreeNode n = new CheckedTreeNode(r.group());
        n.setChecked(false);
        root.add(n);
        return n;
      });
      if (r.checkable()) {
        CheckedTreeNode leaf = new CheckedTreeNode(r);
        leaf.setChecked(r.checked());
        g.add(leaf);
      } else {
        g.add(new DefaultMutableTreeNode(r));
      }
    }
    // 파일 줄은 아래 고칠 것이 모두 체크됐을 때만 체크로 보인다(누르면 아래 줄을 모두 켜고 끈다).
    for (CheckedTreeNode g : groups.values()) {
      boolean all = false;
      for (int i = 0; i < g.getChildCount(); i++) {
        if (!(g.getChildAt(i) instanceof CheckedTreeNode leaf)) continue;
        if (!leaf.isChecked()) {
          all = false;
          break;
        }
        all = true;
      }
      g.setChecked(all);
    }
    tree = new CheckboxTree(new Renderer(), root, new CheckboxTreeBase.CheckPolicy(true, true, false, true));
    tree.setRootVisible(false);
    TreeUtil.expandAll(tree);
    tree.addTreeSelectionListener(e -> showPreview());
    new DoubleClickListener() {
      @Override
      protected boolean onDoubleClick(@NotNull MouseEvent event) {
        ColumnSyncAction.Row r = selected();
        if (r == null || !r.file().isValid()) return false;
        new OpenFileDescriptor(project, r.file(), r.at()).navigate(true);
        return true;
      }
    }.installOn(tree);
    preview.setEditable(false);
    preview.setFont(UIUtil.getFontWithFallback(EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN)));
    preview.setBorder(JBUI.Borders.empty(6, 10));
    setTitle(EiDbBundle.message("columnsync.title"));
    setOKButtonText(EiDbBundle.message("columnsync.ok"));
    init();
  }

  /** 체크된 줄(체크할 수 있는 것만). */
  List<ColumnSyncAction.Row> chosen() {
    return List.of(tree.getCheckedNodes(ColumnSyncAction.Row.class, null));
  }

  private @Nullable ColumnSyncAction.Row selected() {
    Object node = tree.getLastSelectedPathComponent();
    return node instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof ColumnSyncAction.Row r ? r : null;
  }

  private void showPreview() {
    ColumnSyncAction.Row r = selected();
    preview.setText(r == null ? "" : r.preview());
    preview.setCaretPosition(0);
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
    panel.add(new JBLabel(plan.summary()), BorderLayout.NORTH);
    JBSplitter split = new JBSplitter(false, TREE_SPLIT);
    split.setSplitterProportionKey(SPLIT_KEY);
    split.setHonorComponentsMinimumSize(false);
    split.setFirstComponent(ScrollPaneFactory.createScrollPane(tree));
    split.setSecondComponent(ScrollPaneFactory.createScrollPane(preview));
    panel.add(split, BorderLayout.CENTER);
    JBLabel hint = new JBLabel(EiDbBundle.message("columnsync.hint"));
    hint.setForeground(UIUtil.getContextHelpForeground());
    panel.add(hint, BorderLayout.SOUTH);
    return panel;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() { return tree; }

  @Override
  protected @Nullable String getDimensionServiceKey() { return DIMENSION_KEY; }

  @Override
  public Dimension getInitialSize() {
    Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
    return new Dimension((int) (screen.width * FIRST_WIDTH_RATIO), (int) (screen.height * FIRST_HEIGHT_RATIO));
  }

  @Override
  protected void doOKAction() {
    List<ColumnSyncAction.Row> rows = chosen();
    super.doOKAction();
    ColumnSyncAction.apply(project, rows);
  }

  @Override
  protected @Nullable ValidationInfo doValidate() {
    return chosen().isEmpty() ? new ValidationInfo(EiDbBundle.message("columnsync.none.chosen")) : null;
  }

  /** 파일 줄은 파일 이름, 고칠 것 줄은 그 설명. 손으로 할 일 · 알림은 회색. */
  private static final class Renderer extends CheckboxTree.CheckboxTreeCellRenderer {
    @Override
    public void customizeRenderer(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
      Object o = value instanceof DefaultMutableTreeNode n ? n.getUserObject() : null;
      if (o instanceof ColumnSyncAction.Row r) {
        getTextRenderer().append(r.label(), r.checkable() ? SimpleTextAttributes.REGULAR_ATTRIBUTES : SimpleTextAttributes.GRAYED_ATTRIBUTES);
        if (!r.checkable()) getTextRenderer().setIcon(AllIcons.General.Information);
      } else if (o instanceof String group) {
        getTextRenderer().append(group, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        getTextRenderer().setIcon(group.endsWith(".java") ? AllIcons.Nodes.Class : AllIcons.FileTypes.Xml);
      }
    }
  }
}
