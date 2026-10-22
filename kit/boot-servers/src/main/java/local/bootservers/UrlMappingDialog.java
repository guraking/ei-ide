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
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollingUtil;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.openapi.ui.JBMenuItem;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 스프링 요청 매핑 목록 창. 검색 창에 적는 대로 거른다(글 포함, 또는 /api/order/123 같은 실제 URL 이 매핑 경로에 맞음).
 * Enter · 두 번 누르기로 그 메서드로 가고 창을 닫는다. 「요청 보내기」(버튼 · 우클릭)는 아래쪽 「API」 창(Swagger)에서 고른 API 를 펼쳐 연다.
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
        setTitle(UrlMappingAction.title());
        setOKButtonText(BootServersBundle.message("url.go"));
        getCancelAction().putValue(Action.NAME, BootServersBundle.message("common.close"));
        init();
        refresh();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        queryField.getEmptyText().setText(BootServersBundle.message("url.query.hint"));
        queryField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                refresh();
            }
        });
        ScrollingUtil.redirectExpandSelection(list, queryField);

        list.setFont(EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN));
        list.setCellRenderer(new EndpointRenderer());
        list.getEmptyText().setText(all.isEmpty() ? BootServersBundle.message("url.empty.none") : BootServersBundle.message("url.empty.no.match"));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    doOKAction();
                }
            }
        });
        list.addMouseListener(new PopupHandler() {
            @Override
            public void invokePopup(Component comp, int x, int y) {
                int row = list.locationToIndex(new Point(x, y));
                if (row < 0) {
                    return;
                }
                list.setSelectedIndex(row);
                JBPopupMenu menu = new JBPopupMenu();
                JBMenuItem go = new JBMenuItem(BootServersBundle.message("url.go.method"));
                go.addActionListener(event -> doOKAction());
                JBMenuItem send = new JBMenuItem(BootServersBundle.message("url.send"));
                send.addActionListener(event -> sendRequest());
                menu.add(go);
                menu.add(send);
                menu.show(comp, x, y);
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

    @Override
    protected Action @NotNull [] createLeftSideActions() {
        return new Action[]{new AbstractAction(BootServersBundle.message("url.send")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendRequest();
            }
        }};
    }

    /** 고른 줄(없으면 첫 줄)의 방식 · URL 로 HTTP 요청 창을 연다. 이 창은 그대로 둔다. */
    private void sendRequest() {
        UrlMappingAction.Endpoint chosen = chosen();
        if (chosen != null) {
            // 펼칠 API 는 OpenApiCollector 가 붙이는 「컨트롤러 클래스/메서드」(태그/operationId).
            close(OK_EXIT_CODE);
            SwaggerToolWindow.open(project, chosen.owner().replace('.', '/'), chosen.target());
        }
    }

    /** 고른 줄, 없으면 첫 줄, 목록이 비었으면 null. */
    private @Nullable UrlMappingAction.Endpoint chosen() {
        UrlMappingAction.Endpoint chosen = list.getSelectedValue();
        if (chosen == null && list.getModel().getSize() > 0) {
            chosen = list.getModel().getElementAt(0);
        }
        return chosen;
    }

    /** 고른 줄(없으면 첫 줄)의 메서드로 간다. 코드가 바뀌어 메서드가 사라졌으면 알리고 창은 둔다. */
    @Override
    protected void doOKAction() {
        UrlMappingAction.Endpoint chosen = chosen();
        if (chosen == null) {
            return;
        }
        UrlMappingAction.Endpoint target = chosen;
        PsiMethod method = ReadAction.compute(() -> target.target().getElement());
        if (method == null || !method.isValid()) {
            status.setText(BootServersBundle.message("url.method.gone"));
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
        status.setText(BootServersBundle.message("url.count", shown.size(), all.size()));
        status.setForeground(JBColor.foreground());
    }

    private static String shownText(UrlMappingAction.Endpoint e) {
        return shownMethod(e) + " " + e.url() + " " + e.owner();
    }

    /** 방식 글. 방식을 정하지 않은 매핑(UrlMappingAction.ANY_METHOD)은 화면 언어로 보인다. */
    private static String shownMethod(UrlMappingAction.Endpoint e) {
        return UrlMappingAction.ANY_METHOD.equals(e.httpMethod()) ? BootServersBundle.message("url.method.any") : e.httpMethod();
    }

    private static final class EndpointRenderer extends ColoredListCellRenderer<UrlMappingAction.Endpoint> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends UrlMappingAction.Endpoint> list,
                                             UrlMappingAction.Endpoint value, int index, boolean selected, boolean hasFocus) {
            setFont(list.getFont());
            append(String.format("%-" + METHOD_COLUMN_CHARS + "s", shownMethod(value)), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            append(COLUMN_GAP + value.url(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            append(COLUMN_GAP + value.owner(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
    }
}
