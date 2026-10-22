package local.bootservers;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 서버 묶음 하나를 만들거나 고치는 창. 왼쪽 서버 목록에서 골라 오른쪽 순서 목록에 넣고, 위 · 아래로 시작 순서를 정한다.
 * 이름은 비울 수 없고 다른 묶음과 겹칠 수 없으며, 순서 목록에 서버가 하나 이상 있어야 한다.
 */
final class ServerGroupDialog extends DialogWrapper {

    private static final int LIST_ROWS = 10;
    // 두 목록 사이 · 이름 줄과 목록 사이 간격
    private static final int LIST_GAP = 8;
    private static final int SECTION_GAP = 4;

    private final Map<String, String> labels;
    private final List<String> otherNames;
    private final JBTextField name = new JBTextField();
    private final CollectionListModel<String> available = new CollectionListModel<>();
    private final CollectionListModel<String> members = new CollectionListModel<>();
    private final JBList<String> availableList = new JBList<>(available);
    private final JBList<String> memberList = new JBList<>(members);

    /**
     * @param labels     서버 창의 줄 키 → 보일 이름(줄 순서)
     * @param otherNames 이름 겹침을 막을 다른 묶음 이름들
     */
    ServerGroupDialog(Project project, @Nullable ServerGroups.Group existing, List<String> initialMembers,
                      Map<String, String> labels, List<String> otherNames) {
        super(project);
        this.labels = labels;
        this.otherNames = otherNames;
        name.setText(existing == null ? "" : existing.name);
        name.getEmptyText().setText(BootServersBundle.message("group.dialog.name.hint"));
        for (String key : initialMembers) {
            members.add(key);
        }
        for (String key : labels.keySet()) {
            if (!initialMembers.contains(key)) {
                available.add(key);
            }
        }
        setTitle(existing == null ? BootServersBundle.message("group.dialog.title.new") : BootServersBundle.message("group.dialog.title.edit"));
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        ListCellRenderer<String> renderer = new SimpleListCellRenderer<>() {
            @Override
            public void customize(@NotNull JList<? extends String> list, String key, int index, boolean selected, boolean hasFocus) {
                setText(labels.getOrDefault(key, BootServersBundle.message("group.dialog.missing", key)));
            }
        };
        availableList.setCellRenderer(renderer);
        memberList.setCellRenderer(renderer);
        availableList.setVisibleRowCount(LIST_ROWS);
        memberList.setVisibleRowCount(LIST_ROWS);
        availableList.getEmptyText().setText(BootServersBundle.message("group.dialog.available.empty"));
        memberList.getEmptyText().setText(BootServersBundle.message("group.dialog.order.empty"));
        availableList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    addSelected();
                }
            }
        });
        JPanel order = ToolbarDecorator.createDecorator(memberList)
                .setAddAction(button -> addSelected())
                .setRemoveAction(button -> removeSelected())
                .createPanel();
        JPanel lists = new JPanel(new GridLayout(1, 2, JBUI.scale(LIST_GAP), 0));
        lists.add(FormBuilder.createFormBuilder()
                .addComponent(new JBLabel(BootServersBundle.message("group.dialog.available")))
                .addComponentFillVertically(ScrollPaneFactory.createScrollPane(availableList), 0)
                .getPanel());
        lists.add(FormBuilder.createFormBuilder()
                .addComponent(new JBLabel(BootServersBundle.message("group.dialog.order")))
                .addComponentFillVertically(order, 0)
                .getPanel());
        return FormBuilder.createFormBuilder()
                .addLabeledComponent(BootServersBundle.message("group.dialog.name"), name)
                .addComponentFillVertically(lists, JBUI.scale(SECTION_GAP))
                .getPanel();
    }

    private void addSelected() {
        for (String key : availableList.getSelectedValuesList()) {
            available.remove(key);
            members.add(key);
        }
    }

    private void removeSelected() {
        for (String key : memberList.getSelectedValuesList()) {
            members.remove(key);
            if (labels.containsKey(key)) {
                available.add(key);
            }
        }
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return name;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String n = name.getText().strip();
        if (n.isEmpty()) {
            return new ValidationInfo(BootServersBundle.message("group.dialog.name.required"), name);
        }
        if (otherNames.contains(n)) {
            return new ValidationInfo(BootServersBundle.message("group.dialog.name.duplicate"), name);
        }
        if (members.getSize() == 0) {
            return new ValidationInfo(BootServersBundle.message("group.dialog.members.required"), memberList);
        }
        return null;
    }

    ServerGroups.Group result() {
        ServerGroups.Group g = new ServerGroups.Group();
        g.name = name.getText().strip();
        g.members = new ArrayList<>(members.getItems());
        return g;
    }
}
