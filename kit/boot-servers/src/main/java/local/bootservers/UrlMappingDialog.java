package local.bootservers;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.psi.PsiMethod;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollingUtil;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 스프링 요청 매핑 목록 창. 검색 창에 적는 대로 거른다(글 포함, 또는 /api/order/123 같은 실제 URL 이 매핑 경로에 맞음).
 * Enter · 두 번 누르기로 그 메서드로 가고 창을 닫는다.
 */
final class UrlMappingDialog extends DialogWrapper {

    /** HTTP 방식 칸 너비(글자 수). 「DELETE」 와 여러 방식을 이은 것의 앞부분이 들어갈 만큼. */
    private static final int METHOD_COLUMN_CHARS = 8;
    private static final String COLUMN_GAP = "  ";

    private final Project project;
    private final List<UrlMappingAction.Endpoint> all;
    private final JBTextField queryField = new JBTextField();
    private final JBLabel status = new JBLabel();
    private final JBList<UrlMappingAction.Endpoint> list = new JBList<>();

    UrlMappingDialog(Project project, List<UrlMappingAction.Endpoint> endpoints) {
        super(project, false);
        this.project = project;
        this.all = endpoints;
        setModal(false);
        setTitle(UrlMappingAction.TITLE);
        setOKButtonText("이동");
        getCancelAction().putValue(Action.NAME, "닫기");
        init();
        refresh();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        queryField.getEmptyText().setText("URL · 클래스 · 메서드 (예: /api/order/123)");
        queryField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                refresh();
            }
        });
        ScrollingUtil.redirectExpandSelection(list, queryField);

        list.setFont(EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN));
        list.setCellRenderer(new EndpointRenderer());
        list.getEmptyText().setText(all.isEmpty() ? "프로젝트에 스프링 요청 매핑이 없습니다" : "맞는 URL 이 없습니다");
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    doOKAction();
                }
            }
        });

        JPanel north = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        north.add(queryField, BorderLayout.NORTH);
        north.add(status, BorderLayout.SOUTH);
        north.setBorder(JBUI.Borders.emptyBottom(4));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(north, BorderLayout.NORTH);
        panel.add(new JBScrollPane(list), BorderLayout.CENTER);
        panel.setPreferredSize(JBUI.size(900, 500));
        return panel;
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return "KingGura.UrlMapping";
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return queryField;
    }

    /** 고른 줄(없으면 첫 줄)의 메서드로 간다. 코드가 바뀌어 메서드가 사라졌으면 알리고 창은 둔다. */
    @Override
    protected void doOKAction() {
        UrlMappingAction.Endpoint chosen = list.getSelectedValue();
        if (chosen == null && list.getModel().getSize() > 0) {
            chosen = list.getModel().getElementAt(0);
        }
        if (chosen == null) {
            return;
        }
        UrlMappingAction.Endpoint target = chosen;
        PsiMethod method = ReadAction.compute(() -> target.target().getElement());
        if (method == null || !method.isValid()) {
            status.setText("코드가 바뀌어 메서드를 찾지 못했습니다. 창을 닫고 다시 여세요.");
            status.setForeground(JBColor.RED);
            return;
        }
        super.doOKAction();
        method.navigate(true);
    }

    private void refresh() {
        String query = queryField.getText();
        List<UrlMappingAction.Endpoint> shown = new ArrayList<>();
        for (UrlMappingAction.Endpoint e : all) {
            if (UrlPattern.accepts(e.url(), shownText(e), query)) {
                shown.add(e);
            }
        }
        list.setModel(new CollectionListModel<>(shown));
        if (!shown.isEmpty()) {
            list.setSelectedIndex(0);
        }
        status.setText("URL " + shown.size() + "개 / 전체 " + all.size() + "개");
        status.setForeground(JBColor.foreground());
    }

    private static String shownText(UrlMappingAction.Endpoint e) {
        return e.httpMethod() + " " + e.url() + " " + e.owner();
    }

    private static final class EndpointRenderer extends ColoredListCellRenderer<UrlMappingAction.Endpoint> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends UrlMappingAction.Endpoint> list,
                                             UrlMappingAction.Endpoint value, int index, boolean selected, boolean hasFocus) {
            setFont(list.getFont());
            append(String.format("%-" + METHOD_COLUMN_CHARS + "s", value.httpMethod()), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            append(COLUMN_GAP + value.url(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            append(COLUMN_GAP + value.owner(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
    }
}
