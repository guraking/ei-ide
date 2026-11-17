package local.bootservers;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.table.JBTable;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.UIUtil;
import local.bootservers.ui.ColumnShares;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 「저장한 API 요청 다시 보내기」 창(모달 아님: 열어 둔 채 비교 창 · 요청 탭을 볼 수 있다).
 * 체크한 요청만 SEND_THREADS 개씩 백그라운드로 다시 보내고 결과 칸을 채운다. 데이터를 바꿀 수 있는 방식(GET · HEAD · OPTIONS 밖)은 처음에 체크하지 않는다.
 * 줄을 두 번 누르면 저장한 응답과 이번 응답을 비교 창으로 연다.
 */
final class ApiResendDialog extends DialogWrapper {
    /** 동시에 보내는 요청 수(서버 · 보안 프로그램에 한꺼번에 몰리지 않게). */
    private static final Logger LOG = Logger.getInstance(ApiResendDialog.class);
    private static final int SEND_THREADS = 2;
    private static final double FIRST_WIDTH_RATIO = 0.5;
    private static final double FIRST_HEIGHT_RATIO = 0.45;
    private static final String DIMENSION_KEY = "moa.api.resend";
    private static final String COLUMNS_KEY = "moa.api.resend.columns";
    private static final int CHECK = 0;
    private static final int REQUEST = 1;
    private static final int METHOD = 2;
    private static final int BEFORE = 3;
    private static final int NOW = 4;
    private static final int RESULT = 5;
    private static final int[] SHARE_COLUMNS = {REQUEST, METHOD, BEFORE, NOW, RESULT};
    private static final double[] DEFAULT_SHARES = {0.46, 0.08, 0.1, 0.1, 0.26};

    private final Project project;
    private final List<Item> items = new ArrayList<>();
    private final Model model = new Model();
    private final JBTable table = new JBTable(model);
    private final Action sendAction;
    private final ExecutorService pool = AppExecutorUtil.createBoundedApplicationPoolExecutor("Moa API 다시 보내기", SEND_THREADS);
    /** 지금 보내기 묶음의 취소 표시. 보내지 않을 때는 null. */
    private ProgressIndicator running;
    private int left;

    private static final class Item {
        final String key;
        final String title;
        final String method;
        final String url;
        int beforeCode;
        final boolean changesData;
        final boolean droppedHeader;
        boolean checked;
        boolean sending;
        ApiReplay.Result result;

        Item(ApiStore.Entry e) {
            key = e.key;
            title = e.title;
            method = e.method;
            url = e.url;
            beforeCode = e.code;
            changesData = ApiSaved.changesData(e.method);
            droppedHeader = e.droppedHeader;
            checked = !changesData;
        }
    }

    ApiResendDialog(Project project) {
        super(project, null, true, IdeModalityType.MODELESS);
        this.project = project;
        ApiStore store = ApiStore.getInstance(project);
        for (String key : store.keys()) {
            ApiStore.Entry e = store.get(key);
            if (e != null) {
                items.add(new Item(e));
            }
        }
        sendAction = new AbstractAction(BootServersBundle.message("api.resend.send")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendOrCancel();
            }
        };
        sendAction.putValue(DEFAULT_ACTION, true);
        setTitle(BootServersBundle.message("api.resend.title"));
        init();
        refreshButtons();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        table.getEmptyText().setText(BootServersBundle.message("api.resend.empty"));
        table.setShowGrid(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(String.class, new Renderer());
        ColumnShares.fitCheck(table, CHECK);
        ColumnShares.remember(table, COLUMNS_KEY, SHARE_COLUMNS, DEFAULT_SHARES);
        new DoubleClickListener() {
            @Override
            protected boolean onDoubleClick(@NotNull MouseEvent event) {
                int row = table.rowAtPoint(event.getPoint());
                if (row < 0 || items.get(table.convertRowIndexToModel(row)).result == null) {
                    return false;
                }
                ApiReplay.showDiff(project, items.get(table.convertRowIndexToModel(row)).result);
                return true;
            }
        }.installOn(table);
        model.addTableModelListener(e -> refreshButtons());
        return ScrollPaneFactory.createScrollPane(table, true);
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return DIMENSION_KEY;
    }

    @Override
    public Dimension getInitialSize() {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        return new Dimension((int) (screen.width * FIRST_WIDTH_RATIO), (int) (screen.height * FIRST_HEIGHT_RATIO));
    }

    @Override
    protected Action @NotNull [] createActions() {
        getCancelAction().putValue(Action.NAME, BootServersBundle.message("api.resend.close"));
        return new Action[]{sendAction, getCancelAction()};
    }

    @Override
    protected Action @NotNull [] createLeftSideActions() {
        return new Action[]{
                new AbstractAction(BootServersBundle.message("api.resend.accept")) {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        acceptChecked();
                    }
                },
                new AbstractAction(BootServersBundle.message("api.resend.open")) {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        openSelected();
                    }
                },
                new AbstractAction(BootServersBundle.message("api.resend.remove")) {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        removeSelected();
                    }
                }};
    }

    private void refreshButtons() {
        boolean any = false;
        for (Item item : items) {
            any |= item.checked;
        }
        sendAction.putValue(Action.NAME, BootServersBundle.message(running != null ? "api.resend.cancel" : "api.resend.send"));
        sendAction.setEnabled(running != null || any);
    }

    /** 체크한 요청을 다시 보낸다. 보내는 중이면 남은 것을 멈춘다(보내는 중인 요청도 끊는다). */
    private void sendOrCancel() {
        if (running != null) {
            running.cancel();
            return;
        }
        List<Item> todo = new ArrayList<>();
        for (Item item : items) {
            if (item.checked) {
                item.result = null;
                item.sending = true;
                todo.add(item);
            }
        }
        if (todo.isEmpty()) {
            return;
        }
        ProgressIndicator indicator = new EmptyProgressIndicator(ModalityState.stateForComponent(table));
        running = indicator;
        left = todo.size();
        model.fireTableDataChanged();
        refreshButtons();
        for (Item item : todo) {
            pool.execute(() -> {
                ApiReplay.Result result = null;
                try {
                    if (!indicator.isCanceled() && !project.isDisposed()) {
                        result = ProgressManager.getInstance().runProcess(() -> ApiReplay.resend(project, item.key), indicator);
                    }
                } catch (ProcessCanceledException cancelled) {
                    // 취소: 결과 없이 다음으로(남은 것도 곧 취소를 본다).
                } catch (RuntimeException unexpected) {
                    // 보내기 밖의 예외(프로젝트 닫는 중 등)도 이 줄의 실패로 보여 「보내는 중」에 멈추지 않게 한다.
                    LOG.warn("API 다시 보내기 실패", unexpected);
                    result = ApiReplay.failed(item.key, item.title, item.method,
                            BootServersBundle.message("api.resend.error.unexpected", String.valueOf(unexpected.getMessage())));
                } finally {
                    ApiReplay.Result done = result;
                    ApplicationManager.getApplication().invokeLater(() -> finished(indicator, item, done), ModalityState.any(),
                            o -> isDisposed() || project.isDisposed());
                }
            });
        }
    }

    private void finished(ProgressIndicator indicator, Item item, @Nullable ApiReplay.Result result) {
        if (indicator != running) {
            return;
        }
        item.sending = false;
        item.result = result;
        left--;
        if (left == 0) {
            running = null;
        }
        int row = items.indexOf(item);
        if (row >= 0) {
            model.fireTableRowsUpdated(row, row);
        }
        refreshButtons();
    }

    /** 체크한 줄 가운데 이번 응답이 있는 것을 확인받은 뒤 기준으로 저장하고, 그 줄의 지난 상태 · 결과를 바꾼다. */
    private void acceptChecked() {
        List<Item> ready = new ArrayList<>();
        for (Item item : items) {
            if (item.checked && item.result != null && item.result.error() == null && item.result.nowBody() != null) {
                ready.add(item);
            }
        }
        if (ready.isEmpty()) {
            Messages.showInfoMessage(getContentPanel(), BootServersBundle.message("api.resend.accept.none"), getTitle());
            return;
        }
        if (Messages.showYesNoDialog(getContentPanel(), BootServersBundle.message("api.resend.accept.confirm", ready.size()), getTitle(),
                Messages.getQuestionIcon()) != Messages.YES) {
            return;
        }
        for (Item item : ready) {
            ApiReplay.accept(project, item.result);
            item.result = ApiReplay.accepted(item.result);
            item.beforeCode = item.result.beforeCode();
        }
        model.fireTableDataChanged();
        Messages.showInfoMessage(getContentPanel(), BootServersBundle.message("api.resend.accept.done", ready.size()), getTitle());
    }

    private void openSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            Messages.showInfoMessage(getContentPanel(), BootServersBundle.message("api.resend.pick"), getTitle());
            return;
        }
        String key = items.get(table.convertRowIndexToModel(row)).key;
        // 열린 탭이 있으면 그 탭의 코드 시험 값은 두고 저장한 값으로 다시 채운다.
        if (ApiRequestEditor.focus(project, key)) {
            return;
        }
        ApiStore.Entry e = ApiStore.getInstance(project).get(key);
        if (e == null) {
            return;
        }
        // 새 탭은 저장한 값으로 연다(비밀 값은 탭이 비밀번호 저장소에서 되살림). 코드의 시험 값을 모르므로 이 탭의 「처음 시험 값으로」는 저장한 값(비밀은 빈 칸)이다.
        String source = BootServersBundle.message("api.source.saved");
        ApiRequestEditor.open(project, new ApiRequestEditor.Request(e.title, e.key, e.method, e.url,
                rows(e.query, source), rows(e.headers, source), e.body));
    }

    private static List<ApiRequestEditor.Row> rows(List<ApiStore.LineBean> lines, String source) {
        List<ApiRequestEditor.Row> out = new ArrayList<>();
        for (ApiStore.LineBean l : lines) {
            out.add(new ApiRequestEditor.Row(l.name, l.value, source));
        }
        return out;
    }

    private void removeSelected() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            Messages.showInfoMessage(getContentPanel(), BootServersBundle.message("api.resend.pick"), getTitle());
            return;
        }
        if (Messages.showYesNoDialog(getContentPanel(), BootServersBundle.message("api.resend.remove.confirm", rows.length), getTitle(),
                Messages.getQuestionIcon()) != Messages.YES) {
            return;
        }
        List<Item> gone = new ArrayList<>();
        for (int row : rows) {
            Item item = items.get(table.convertRowIndexToModel(row));
            if (!item.sending) {
                gone.add(item);
                ApiStore.getInstance(project).remove(item.key);
            }
        }
        items.removeAll(gone);
        model.fireTableDataChanged();
    }

    @Override
    protected void dispose() {
        if (running != null) {
            running.cancel();
        }
        pool.shutdown();
        super.dispose();
    }

    private final class Model extends AbstractTableModel {
        private final String[] names = {"", BootServersBundle.message("api.resend.col.request"), BootServersBundle.message("api.resend.col.method"),
                BootServersBundle.message("api.resend.col.before"), BootServersBundle.message("api.resend.col.now"),
                BootServersBundle.message("api.resend.col.result")};

        @Override
        public int getRowCount() {
            return items.size();
        }

        @Override
        public int getColumnCount() {
            return names.length;
        }

        @Override
        public String getColumnName(int column) {
            return names[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == CHECK ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == CHECK && running == null;
        }

        @Override
        public Object getValueAt(int row, int column) {
            Item item = items.get(row);
            return switch (column) {
                case CHECK -> item.checked;
                case REQUEST -> item.title + " · " + item.url;
                case METHOD -> item.method;
                case BEFORE -> item.beforeCode == ApiSaved.NO_CODE ? "" : String.valueOf(item.beforeCode);
                case NOW -> item.result == null || item.result.error() != null ? "" : String.valueOf(item.result.nowCode());
                default -> resultText(item);
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == CHECK) {
                items.get(row).checked = Boolean.TRUE.equals(value);
                fireTableCellUpdated(row, column);
            }
        }
    }

    private static String resultText(Item item) {
        String text;
        if (item.sending) {
            text = BootServersBundle.message("api.resend.sending");
        } else if (item.result != null) {
            text = item.result.verdictText();
        } else {
            text = item.changesData ? BootServersBundle.message("api.resend.changes.data") : "";
        }
        if (!item.droppedHeader) {
            return text;
        }
        String note = BootServersBundle.message("api.resend.header.dropped");
        return text.isEmpty() ? note : text + " · " + note;
    }

    /** 결과가 같음이 아니면 경고 색, 보내기 전 안내 · 보내는 중은 흐린 색. */
    private final class Renderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focused, int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, focused, row, column);
            Item item = items.get(t.convertRowIndexToModel(row));
            setToolTipText(column == REQUEST ? item.title + " · " + item.url : null);
            if (!selected && column == RESULT) {
                if (item.result == null) {
                    setForeground(UIUtil.getContextHelpForeground());
                } else if (!item.result.same()) {
                    setForeground(SimpleTextAttributes.ERROR_ATTRIBUTES.getFgColor());
                } else {
                    setForeground(t.getForeground());
                }
            } else if (!selected) {
                setForeground(t.getForeground());
            }
            return this;
        }
    }
}
