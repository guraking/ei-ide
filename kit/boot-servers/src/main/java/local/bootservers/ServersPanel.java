package local.bootservers;

import com.intellij.execution.Executor;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunManagerListener;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.impl.RunDialog;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.Disposable;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.task.ProjectTaskContext;
import com.intellij.task.ProjectTaskListener;
import com.intellij.task.ProjectTaskManager;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;
import local.bootservers.was.WasSettings;
import local.bootservers.was.WasLogFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.border.Border;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 아래쪽 「서버」 창. 스프링 부트 실행 설정과 추가한 WAS 를 한 목록에 보인다.
 * 줄 종류는 셋이다: 스프링 부트 실행 설정(Boot), WAS(WasSettings.Server), WAS 에 올린 프로젝트(Deploy).
 * 한 모듈을 프로필만 달리한 실행 설정마다 한 줄씩 보이고, 실행 설정이 아직 없는 모듈은 모듈 이름의 자리 줄 하나로 보인다.
 * 고른 줄들을 한꺼번에 시작 · 중지하며, 오른쪽 칸에서 고른 줄의 설정을 고친다.
 */
final class ServersPanel extends SimpleToolWindowPanel {

    // 표 머리글 글 키
    private static final String[] COLUMN_KEYS = {"servers.column.server", "servers.column.state", "servers.column.port", "servers.column.context", "servers.column.uptime"};
    private static final String PROFILE_KEY = "spring.profiles.active";
    private static final String PORT_KEY = "server.port";
    private static final int TICK_MILLIS = 1000;
    // 글자가 칸 왼쪽 끝 · 머리글에 붙지 않게 두는 칸 안쪽 여백
    private static final int CELL_PAD = 8;
    private static final int ROW_HEIGHT = 24;

    /** 스프링 부트 줄. settings 가 null 이면 실행 설정이 아직 없는 모듈의 자리 줄이다(시작 · 적용하면 모듈 이름으로 만든다). */
    private record Boot(Module module, RunnerAndConfigurationSettings settings) {
        String name() {
            return settings == null ? module.getName() : settings.getName();
        }
    }

    /** WAS 아래 줄. 시작 · 중지 같은 동작은 그 WAS 에 적용한다. */
    private record Deploy(WasSettings.Server server, WasSettings.Deployment deployment) {
    }

    private final Project project;
    private final Disposable parent;
    private Map<Module, Set<String>> bootModules = Map.of();
    private List<Object> rows = List.of();
    private final Map<Object, ServerLauncher.State> lastStates = new HashMap<>();
    // 마지막 tick 에서 구한 줄별 상태. 표 칸은 여기서 읽고, 없는 줄(tick 전에 생긴 줄)만 그 자리에서 구한다.
    private Map<Object, ServerLauncher.State> shownStates = Map.of();

    private final Model model = new Model();
    private final JBTable table = new JBTable(model);

    private final CardLayout cards = new CardLayout();
    private final JPanel detail = new JPanel(cards);
    // 스프링 부트 칸
    private final JBLabel bootTitle = new JBLabel();
    private final JBLabel bootStatus = new JBLabel();
    private final JBTextField bootName = new JBTextField();
    private final JComboBox<String> profile = new JComboBox<>(new String[]{"", "local", "dev", "prod"});
    private final JBTextField vmOptions = new JBTextField();
    private final JBTextField port = new JBTextField();
    private Boot detailBoot;
    // WAS 칸
    private final JBLabel wasTitle = new JBLabel();
    private final JBLabel wasStatus = new JBLabel();
    private final JBLabel wasSummary = new JBLabel();
    private WasSettings.Server detailServer;

    ServersPanel(Project project, ToolWindow toolWindow) {
        super(true, true);
        this.project = project;
        this.parent = toolWindow.getDisposable();

        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setShowGrid(false);
        table.setRowHeight(JBUI.scale(ROW_HEIGHT));
        table.setDefaultRenderer(Object.class, new CellRenderer());
        table.getColumnModel().getColumn(1).setCellRenderer(new StateRenderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetail();
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    // 2026.2 는 마우스 이벤트를 읽기 잠금 없이 처리한다. 설정이 없는 부트 줄은 PSI 로 메인 클래스를 찾으므로 잠금 안에서 돌린다.
                    WriteIntentReadAction.run(() -> {
                        for (Object target : targets()) {
                            showConsoleOrStart(target);
                        }
                    });
                }
            }
        });

        DefaultActionGroup group = actions();
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("BootServers.Toolbar", group, true);
        toolbar.setTargetComponent(table);
        setToolbar(toolbar.getComponent());
        PopupHandler.installPopupMenu(table, group, "BootServers.Popup");

        detail.add(emptyCard(), "none");
        detail.add(bootCard(), "boot");
        detail.add(wasCard(), "was");
        OnePixelSplitter splitter = new OnePixelSplitter(false, 0.66f);
        splitter.setFirstComponent(ScrollPaneFactory.createScrollPane(table, true));
        splitter.setSecondComponent(detail);
        setContent(splitter);

        // 실행 시간 · 상태를 1초마다 다시 그린다. 상태가 바뀐 줄이 있으면 프로젝트 트리 표시도 맞춘다.
        // 창이 보이는 동안만 돌고, 보이게 되는 순간 한 번 바로 맞춘다.
        Timer timer = new Timer(TICK_MILLIS, e -> tick());
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) {
                return;
            }
            if (isShowing()) {
                tick();
                timer.start();
            }
            else {
                timer.stop();
            }
        });
        Disposer.register(parent, timer::stop);
        Disposer.register(parent, this::cancelGroupRun);
        watchBuilds();
        // Gradle · Maven 가져오기가 끝나면 색인이 다시 돌므로, 색인이 끝날 때마다 서버 목록을 다시 찾는다.
        project.getMessageBus().connect(parent).subscribe(DumbService.DUMB_MODE, new DumbService.DumbModeListener() {
            @Override
            public void exitDumbMode() {
                reload();
            }
        });
        // 실행 설정 대화상자에서 설정을 더하거나 지우거나 이름을 바꾸면 줄을 다시 맞춘다. 알림은 아무 스레드에서나 오므로 화면 스레드로 넘긴다.
        project.getMessageBus().connect(parent).subscribe(RunManagerListener.TOPIC, new RunManagerListener() {
            @Override
            public void runConfigurationAdded(@NotNull RunnerAndConfigurationSettings settings) {
                rebuildLater();
            }

            @Override
            public void runConfigurationRemoved(@NotNull RunnerAndConfigurationSettings settings) {
                rebuildLater();
            }

            @Override
            public void runConfigurationChanged(@NotNull RunnerAndConfigurationSettings settings) {
                rebuildLater();
            }
        });
        reload();
    }

    private void reload() {
        table.getEmptyText().setText(BootServersBundle.message("servers.empty.loading"));
        ReadAction.nonBlocking(() -> ServerLauncher.serverModules(project))
                .inSmartMode(project)
                .expireWith(parent)
                .finishOnUiThread(ModalityState.any(), found -> {
                    bootModules = found;
                    rebuildRows();
                    table.getEmptyText().setText(BootServersBundle.message("servers.empty.none"));
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private void rebuildLater() {
        ApplicationManager.getApplication().invokeLater(this::rebuildRows, ModalityState.any(), project.getDisposed());
    }

    /** 모듈별 스프링 부트 실행 설정(없으면 자리 줄), 그 뒤에 WAS 와 그 아래 올린 프로젝트 순. 고른 줄은 그대로 둔다. */
    private void rebuildRows() {
        List<Object> selected = targets();
        List<Object> list = new ArrayList<>();
        for (Map.Entry<Module, Set<String>> entry : bootModules.entrySet()) {
            Module m = entry.getKey();
            if (m.isDisposed()) {
                continue;
            }
            int before = list.size();
            for (RunnerAndConfigurationSettings s : ServerLauncher.settingsOf(m)) {
                if (entry.getValue().contains(((ApplicationConfiguration) s.getConfiguration()).getMainClassName())) {
                    list.add(new Boot(m, s));
                }
            }
            if (list.size() == before) {
                list.add(new Boot(m, null));
            }
        }
        for (WasSettings.Server s : WasSettings.getInstance(project).servers()) {
            list.add(s);
            for (WasSettings.Deployment d : s.deployments) {
                list.add(new Deploy(s, d));
            }
        }
        rows = list;
        model.fireTableDataChanged();
        for (Object t : selected) {
            select(t);
        }
        showDetail();
    }

    /** 같은 실행 설정 · 모듈 · WAS 의 줄을 고른 줄에 더한다. */
    private void select(Object target) {
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            boolean same = row instanceof Boot b && target instanceof Boot t
                    ? (b.settings() == null ? t.settings() == null && b.module().equals(t.module()) : b.settings() == t.settings())
                    : row == target;
            if (same) {
                table.addRowSelectionInterval(i, i);
            }
        }
    }

    private void tick() {
        if (rows.isEmpty()) {
            return;
        }
        boolean changed = false;
        Map<Object, ServerLauncher.State> states = new HashMap<>();
        for (Object row : rows) {
            ServerLauncher.State state = stateOf(row);
            states.put(row, state);
            if (row instanceof Boot) {
                changed |= lastStates.put(row, state) != state;
            }
        }
        shownStates = states;
        model.fireTableRowsUpdated(0, rows.size() - 1);
        updateStatus();
        if (changed) {
            ProjectView.getInstance(project).refresh();
        }
    }

    // ---- 줄 종류별 공통 질의 ----

    private ServerLauncher.State stateOf(Object row) {
        if (row instanceof Boot b) {
            return b.settings() == null ? ServerLauncher.State.STOPPED : ServerLauncher.state(b.settings());
        }
        WasSettings.Server s = row instanceof Deploy d ? d.server() : (WasSettings.Server) row;
        return WasRunner.getInstance(project).state(s);
    }

    private ServerLauncher.State shownState(Object row) {
        ServerLauncher.State state = shownStates.get(row);
        return state != null ? state : stateOf(row);
    }

    private static boolean isLive(ServerLauncher.State s) {
        return s == ServerLauncher.State.RUNNING || s == ServerLauncher.State.STARTING;
    }

    private static boolean isStartable(ServerLauncher.State s) {
        return s == ServerLauncher.State.STOPPED || s == ServerLauncher.State.FAILED;
    }

    /** 고른 줄을 동작 대상으로: 올린 프로젝트 줄은 그 WAS 로 바꾸고 겹치면 하나로 친다. */
    private List<Object> targets() {
        Set<Object> result = new LinkedHashSet<>();
        for (int i : table.getSelectedRows()) {
            if (i >= rows.size()) {
                continue;
            }
            Object row = rows.get(i);
            if (row instanceof Boot b && b.module().isDisposed()) {
                continue;
            }
            result.add(row instanceof Deploy d ? d.server() : row);
        }
        return new ArrayList<>(result);
    }

    /** @return 시작(빌드 · 실행)을 넘겼으면 true, 알림 · 오류 창만 띄우고 그만뒀으면 false */
    private boolean start(Object target, boolean debug) {
        Executor executor = debug ? DefaultDebugExecutor.getDebugExecutorInstance() : DefaultRunExecutor.getRunExecutorInstance();
        if (target instanceof Boot b) {
            if (b.settings() != null) {
                ServerLauncher.start(b.settings(), executor);
                return true;
            }
            // 자리 줄: 모듈 이름으로 실행 설정이 만들어지므로 줄을 다시 맞춘다.
            boolean began = ServerLauncher.start(b.module(), executor);
            rebuildRows();
            return began;
        }
        return WasRunner.getInstance(project).start((WasSettings.Server) target, debug);
    }

    private void stop(Object target) {
        if (target instanceof Boot b) {
            if (b.settings() != null) {
                ServerLauncher.stop(b.settings());
            }
        }
        else {
            WasRunner.getInstance(project).stop((WasSettings.Server) target);
        }
    }

    private void restart(Object target) {
        if (target instanceof Boot b) {
            if (b.settings() != null) {
                ServerLauncher.restart(b.settings());
            }
        }
        else {
            WasRunner.getInstance(project).restart((WasSettings.Server) target);
        }
    }

    // ---- 동작 ----

    private interface Perform {
        void run(Object target);
    }

    private interface When {
        boolean test(Object target);
    }

    private DefaultActionGroup actions() {
        DefaultActionGroup g = new DefaultActionGroup();
        g.add(new AnAction(BootServersBundle.message("servers.action.add.was"), BootServersBundle.message("servers.action.add.was.description"), AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                editWas(null);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        g.addSeparator();
        g.add(action(BootServersBundle.message("servers.action.start"), AllIcons.Actions.Execute, t -> isStartable(stateOf(t)), t -> start(t, false)));
        g.add(action(BootServersBundle.message("servers.action.debug"), AllIcons.Actions.StartDebugger, t -> isStartable(stateOf(t)), t -> start(t, true)));
        g.add(action(BootServersBundle.message("servers.action.restart"), AllIcons.Actions.Restart, t -> isLive(stateOf(t)), this::restart));
        g.add(action(BootServersBundle.message("servers.action.stop"), AllIcons.Actions.Suspend, t -> isLive(stateOf(t)), this::stop));
        g.add(new AnAction(BootServersBundle.message("servers.action.stop.all"), BootServersBundle.message("servers.action.stop.all.description"), AllIcons.Actions.Cancel) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(rows.stream().anyMatch(r -> !(r instanceof Deploy) && isLive(stateOf(r))));
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                for (Object r : rows) {
                    if (!(r instanceof Deploy) && isLive(stateOf(r))) {
                        stop(r);
                    }
                }
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        g.add(groupMenu());
        g.addSeparator();
        g.add(action(BootServersBundle.message("servers.action.duplicate"), AllIcons.Actions.Copy, t -> t instanceof Boot b && b.settings() != null, this::duplicate));
        g.add(action(BootServersBundle.message("servers.action.publish"), AllIcons.Actions.Compile, t -> t instanceof WasSettings.Server && isLive(stateOf(t)), this::publish));
        g.add(action(BootServersBundle.message("servers.action.open.browser"), AllIcons.General.Web, t -> stateOf(t) == ServerLauncher.State.RUNNING, this::openBrowser));
        g.add(action(BootServersBundle.message("servers.action.console"), AllIcons.Debugger.Console, t -> isLive(stateOf(t)), this::showConsoleOrStart));
        g.add(action(BootServersBundle.message("servers.action.edit.settings"), AllIcons.Actions.EditSource, t -> true, this::editSettings));
        g.add(action(BootServersBundle.message("servers.action.remove.was"), AllIcons.General.Remove, t -> t instanceof WasSettings.Server && !isLive(stateOf(t)), this::removeWas));
        g.add(action(BootServersBundle.message("servers.action.log.files"), AllIcons.FileTypes.Text, t -> t instanceof WasSettings.Server, this::showLogFiles));
        g.addSeparator();
        g.add(new AnAction(BootServersBundle.message("servers.action.reload"), null, AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                reload();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        return g;
    }

    /** 고른 대상 가운데 조건에 맞는 것에만 적용되는 동작. 맞는 대상이 하나도 없으면 꺼진다. */
    private AnAction action(String text, Icon icon, When when, Perform perform) {
        return new AnAction(text, null, icon) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(targets().stream().anyMatch(when::test));
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                for (Object t : targets()) {
                    if (when.test(t)) {
                        perform.run(t);
                    }
                }
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
    }

    /** 실행 설정을 「이름 (복제)」 로 복사해 새 줄로 고른다. 프로필 · 포트는 오른쪽 칸에서 고쳐 적용한다. */
    private void duplicate(Object target) {
        RunnerAndConfigurationSettings source = ((Boot) target).settings();
        RunManager runManager = RunManager.getInstance(project);
        RunConfiguration copy = source.getConfiguration().clone();
        copy.setName(runManager.suggestUniqueName(source.getName() + BootServersBundle.message("servers.copy.suffix"), source.getType()));
        RunnerAndConfigurationSettings created = runManager.createConfiguration(copy, source.getFactory());
        runManager.addConfiguration(created);
        rebuildRows();
        table.clearSelection();
        select(new Boot(((Boot) target).module(), created));
        showDetail();
        bootName.requestFocusInWindow();
    }

    private void publish(Object target) {
        WasRunner.getInstance(project).publish((WasSettings.Server) target);
    }

    private void openBrowser(Object target) {
        Object row = selectedRow();
        if (target instanceof Boot b) {
            ServerRegistry.Run run = b.settings() == null ? null : ServerRegistry.getInstance(project).get(b.name());
            if (run == null || run.port == null) {
                Messages.showInfoMessage(project, BootServersBundle.message("servers.browser.no.port"), BootServersBundle.message("servers.action.open.browser"));
                return;
            }
            BrowserUtil.browse("http://localhost:" + run.port);
            return;
        }
        WasSettings.Server s = (WasSettings.Server) target;
        String path = row instanceof Deploy d && d.server() == s ? d.deployment().contextPath : "/";
        BrowserUtil.browse("http://localhost:" + s.httpPort + (path.startsWith("/") ? path : "/" + path));
    }

    private void showConsoleOrStart(Object target) {
        if (!(target instanceof Boot b)) {
            if (isLive(stateOf(target))) {
                ToolWindow run = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.RUN);
                if (run != null) {
                    run.activate(null);
                }
            }
            else {
                start(target, false);
            }
            return;
        }
        List<RunContentDescriptor> descriptors = b.settings() == null ? List.of() : ServerLauncher.runningDescriptors(b.settings());
        if (descriptors.isEmpty()) {
            start(b, false);
            return;
        }
        RunContentDescriptor descriptor = descriptors.get(0);
        RunContentManager manager = RunContentManager.getInstance(project);
        manager.selectRunContent(descriptor);
        ToolWindow window = manager.getToolWindowByDescriptor(descriptor);
        if (window != null) {
            window.activate(null);
        }
    }

    private void editSettings(Object target) {
        if (target instanceof Boot b) {
            RunnerAndConfigurationSettings settings = b.settings() == null ? ServerLauncher.ensureSettings(b.module()) : b.settings();
            if (settings != null && RunDialog.editConfiguration(project, settings, BootServersBundle.message("servers.run.config.title", settings.getName()))) {
                rebuildRows();
            }
        }
        else {
            editWas((WasSettings.Server) target);
        }
    }

    private void editWas(WasSettings.Server existing) {
        WasDialog dialog = new WasDialog(project, existing);
        if (dialog.showAndGet()) {
            WasSettings.getInstance(project).put(dialog.result());
            rebuildRows();
        }
    }

    private void removeWas(Object target) {
        WasSettings.Server s = (WasSettings.Server) target;
        if (Messages.showYesNoDialog(project, BootServersBundle.message("servers.remove.was.confirm", s.name), BootServersBundle.message("servers.action.remove.was"),
                Messages.getQuestionIcon()) == Messages.YES) {
            WasSettings.getInstance(project).remove(s);
            // 뺀 서버의 JEUS 비밀번호가 저장소에 남지 않게 한다.
            if (s.kind() == WasSettings.Kind.JEUS) {
                WasRunner.getInstance(project).forgetJeusPassword(s);
            }
            rebuildRows();
        }
    }

    private Object selectedRow() {
        int[] sel = table.getSelectedRows();
        return sel.length == 0 || sel[sel.length - 1] >= rows.size() ? null : rows.get(sel[sel.length - 1]);
    }

    // ---- 서버 묶음: 정한 순서대로 하나씩, 앞 서버가 실행 중이 된 뒤 다음 서버를 시작한다 ----

    // 서버 하나가 실행 중이 되기까지 기다리는 한도. WAS 는 시작 전 빌드까지 하므로 넉넉히 둔다.
    private static final long GROUP_START_TIMEOUT_MILLIS = 5 * 60 * 1000;
    // plugin.xml 의 알림 묶음 id.
    private static final String NOTIFICATION_GROUP = "서버 실행";
    private static final String BOOT_KEY_PREFIX = "boot:";
    // 시작을 지시한 뒤 빌드 · 실행 준비도 프로세스도 보이지 않으면 시작이 일어나지 않은 것(오류 창을 띄우고 그만둠)으로 보는 한도.
    // 빌드가 끝난 뒤 실행 창이 뜨기까지의 틈(화면 스레드 한두 차례 · 실행 전 작업 준비)보다 넉넉해야 한다.
    private static final long GROUP_START_GRACE_MILLIS = 15_000;

    private GroupSequence groupRun;
    private String groupRunName;
    private Timer groupTimer;
    // 도는 중인 프로젝트 빌드 수. 서버 시작 전 빌드(WAS 시작 · 실행 설정의 실행 전 빌드)도 여기에 잡힌다.
    private final AtomicInteger buildsRunning = new AtomicInteger();
    // start() 를 부르는 동안의 줄 키. 그 안의 모달 창(JEUS 비밀번호 등)이 떠 있는 동안 묶음 타이머가 돌아도 준비 중으로 본다.
    private String groupStartingKey;

    private void watchBuilds() {
        project.getMessageBus().connect(parent).subscribe(ProjectTaskListener.TOPIC, new ProjectTaskListener() {
            @Override
            public void started(@NotNull ProjectTaskContext context) {
                buildsRunning.incrementAndGet();
            }

            @Override
            public void finished(@NotNull ProjectTaskManager.Result result) {
                buildsRunning.updateAndGet(n -> Math.max(0, n - 1));
            }
        });
    }

    /** 묶음 안에 적는 줄 키. 올린 프로젝트 줄은 묶음에 넣지 않으므로 null. */
    private static String rowKey(Object row) {
        if (row instanceof Boot b) {
            return BOOT_KEY_PREFIX + b.name();
        }
        return row instanceof WasSettings.Server s ? WasRunner.registryKey(s) : null;
    }

    private Object rowByKey(String key) {
        for (Object row : rows) {
            if (key.equals(rowKey(row)) && !(row instanceof Boot b && b.module().isDisposed())) {
                return row;
            }
        }
        return null;
    }

    /** 줄 키 → 보일 이름. 서버 창 줄 순서. */
    private Map<String, String> rowLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (Object row : rows) {
            String key = rowKey(row);
            if (key != null) {
                labels.put(key, row instanceof Boot b ? b.name() : ((WasSettings.Server) row).name + "  [" + ((WasSettings.Server) row).kind().label + "]");
            }
        }
        return labels;
    }

    private String labelOf(String key) {
        Object row = rowByKey(key);
        if (row instanceof Boot b) {
            return b.name();
        }
        if (row instanceof WasSettings.Server s) {
            return s.name;
        }
        return key.startsWith(BOOT_KEY_PREFIX) ? key.substring(BOOT_KEY_PREFIX.length()) : key;
    }

    /** 도구 모음 · 우클릭의 「서버 묶음」: 새 묶음, 묶음마다 시작 · 중지 · 편집 · 삭제. */
    private ActionGroup groupMenu() {
        ActionGroup menu = new ActionGroup(BootServersBundle.message("servers.group.title"), BootServersBundle.message("servers.group.description"), AllIcons.Nodes.Folder) {
            @Override
            public AnAction @NotNull [] getChildren(@Nullable AnActionEvent e) {
                List<AnAction> children = new ArrayList<>();
                children.add(DumbAwareAction.create(BootServersBundle.message("servers.group.new"), ev -> newGroup()));
                for (ServerGroups.Group group : ServerGroups.getInstance(project).groups()) {
                    DefaultActionGroup sub = DefaultActionGroup.createPopupGroup(() -> group.name);
                    sub.add(DumbAwareAction.create(BootServersBundle.message("servers.group.start"), ev -> startGroup(group)));
                    sub.add(DumbAwareAction.create(BootServersBundle.message("servers.group.stop"), ev -> stopGroup(group)));
                    sub.add(DumbAwareAction.create(BootServersBundle.message("servers.group.edit"), ev -> editGroup(group)));
                    sub.add(DumbAwareAction.create(BootServersBundle.message("servers.group.delete"), ev -> deleteGroup(group)));
                    children.add(sub);
                }
                return children.toArray(AnAction[]::new);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
        menu.setPopup(true);
        return menu;
    }

    private void newGroup() {
        List<String> initial = new ArrayList<>();
        for (Object t : targets()) {
            String key = rowKey(t);
            if (key != null) {
                initial.add(key);
            }
        }
        ServerGroupDialog dialog = new ServerGroupDialog(project, null, initial, rowLabels(), groupNames(null));
        if (dialog.showAndGet()) {
            ServerGroups.getInstance(project).groups().add(dialog.result());
        }
    }

    private void editGroup(ServerGroups.Group group) {
        ServerGroupDialog dialog = new ServerGroupDialog(project, group, group.members, rowLabels(), groupNames(group));
        if (dialog.showAndGet()) {
            List<ServerGroups.Group> groups = ServerGroups.getInstance(project).groups();
            int i = groups.indexOf(group);
            if (i >= 0) {
                groups.set(i, dialog.result());
            }
        }
    }

    private void deleteGroup(ServerGroups.Group group) {
        if (Messages.showYesNoDialog(project, BootServersBundle.message("servers.group.delete.confirm", group.name), BootServersBundle.message("servers.group.title"),
                Messages.getQuestionIcon()) == Messages.YES) {
            ServerGroups.getInstance(project).groups().remove(group);
        }
    }

    /** except 를 뺀 묶음 이름들(이름 겹침 검사용). */
    private List<String> groupNames(ServerGroups.Group except) {
        List<String> names = new ArrayList<>();
        for (ServerGroups.Group g : ServerGroups.getInstance(project).groups()) {
            if (g != except) {
                names.add(g.name);
            }
        }
        return names;
    }

    /** 서버 창에 없는 서버가 묶음에 있으면 시작하지 않고 알린다. 다른 묶음이 시작 중이면 그 진행은 멈춘다. */
    private void startGroup(ServerGroups.Group group) {
        List<String> missing = new ArrayList<>();
        for (String key : group.members) {
            if (rowByKey(key) == null) {
                missing.add(labelOf(key));
            }
        }
        if (group.members.isEmpty() || !missing.isEmpty()) {
            Messages.showErrorDialog(project, group.members.isEmpty()
                    ? BootServersBundle.message("servers.group.empty", group.name)
                    : BootServersBundle.message("servers.group.missing", group.name, String.join(", ", missing)), BootServersBundle.message("servers.group.title"));
            return;
        }
        cancelGroupRun();
        groupRun = new GroupSequence(group.members, GROUP_START_TIMEOUT_MILLIS, GROUP_START_GRACE_MILLIS);
        groupRunName = group.name;
        groupTimer = new Timer(TICK_MILLIS, e -> groupTick());
        groupTimer.start();
        groupTick();
    }

    /** 시작 중인 같은 묶음 진행을 멈추고, 묶음의 서버를 거꾸로 중지한다. */
    private void stopGroup(ServerGroups.Group group) {
        if (group.name.equals(groupRunName)) {
            cancelGroupRun();
        }
        for (int i = group.members.size() - 1; i >= 0; i--) {
            Object row = rowByKey(group.members.get(i));
            if (row != null && isLive(stateOf(row))) {
                stop(row);
            }
        }
    }

    private void cancelGroupRun() {
        if (groupTimer != null) {
            groupTimer.stop();
        }
        groupTimer = null;
        groupRun = null;
        groupRunName = null;
    }

    private GroupSequence.Status groupStatus(String key) {
        Object row = rowByKey(key);
        if (row == null) {
            return GroupSequence.Status.FAILED;
        }
        ServerLauncher.State state = stateOf(row);
        if (isStartable(state) && (key.equals(groupStartingKey) || buildsRunning.get() > 0
                || row instanceof WasSettings.Server s && WasRunner.getInstance(project).isLaunching(s))) {
            return GroupSequence.Status.PREPARING;
        }
        return switch (state) {
            case RUNNING -> GroupSequence.Status.RUNNING;
            case STARTING -> GroupSequence.Status.STARTING;
            case FAILED -> GroupSequence.Status.FAILED;
            case STOPPED -> GroupSequence.Status.STOPPED;
        };
    }

    private void groupTick() {
        if (groupRun == null) {
            return;
        }
        String name = groupRunName;
        GroupSequence run = groupRun;
        GroupSequence.Step step = run.step(System.currentTimeMillis(), this::groupStatus);
        String order = BootServersBundle.message("servers.group.order", run.position(), run.size(), (step.member() == null ? "" : labelOf(step.member())));
        switch (step.kind()) {
            case WAIT -> {
            }
            case START -> {
                Object row = rowByKey(step.member());
                if (row == null) {
                    cancelGroupRun();
                    notifyGroup(BootServersBundle.message("servers.group.stopped.gone", name, order), NotificationType.ERROR);
                    return;
                }
                // 스윙 타이머는 잠금 없이 돌고, 실행 설정이 없는 부트 줄은 PSI 로 메인 클래스를 찾으므로 잠금 안에서 부른다.
                groupStartingKey = step.member();
                boolean[] began = {false};
                try {
                    WriteIntentReadAction.run(() -> began[0] = start(row, false));
                }
                finally {
                    groupStartingKey = null;
                }
                if (!began[0]) {
                    cancelGroupRun();
                    notifyGroup(BootServersBundle.message("servers.group.stopped.not.started", name, order), NotificationType.ERROR);
                }
            }
            case DONE -> {
                cancelGroupRun();
                notifyGroup(BootServersBundle.message("servers.group.done", name, run.size()), NotificationType.INFORMATION);
            }
            case FAILED -> {
                cancelGroupRun();
                notifyGroup(BootServersBundle.message("servers.group.stopped.failed", name, order), NotificationType.ERROR);
            }
            case NOT_STARTED -> {
                cancelGroupRun();
                notifyGroup(BootServersBundle.message("servers.group.stopped.not.started", name, order), NotificationType.ERROR);
            }
            case TIMEOUT -> {
                cancelGroupRun();
                notifyGroup(BootServersBundle.message("servers.group.stopped.timeout", name, order, GROUP_START_TIMEOUT_MILLIS / 60_000), NotificationType.ERROR);
            }
        }
    }

    private void notifyGroup(String text, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(BootServersBundle.message("servers.group.title"), text, type)
                .notify(project);
    }

    // ---- WAS 로그 파일 보기 ----

    // 로그 파일 고르기 창에서 로그 폴더 기준 하위 폴더를 보일 최대 글자 수. 넘으면 가운데를 줄인다(전체 경로는 풍선 도움말).
    private static final int LOG_FOLDER_MAX_CHARS = 40;

    /** WAS 의 로그 파일 목록을 띄우고, 고른 파일을 실행 창 콘솔 탭에서 끝을 따라가며 보인다. 폴더 읽기는 풀 스레드에서 한다. */
    private void showLogFiles(Object target) {
        WasSettings.Server s = (WasSettings.Server) target;
        List<Path> roots;
        try {
            roots = WasLogFiles.roots(s.kind(), WasRunner.getInstance(project).runDir(s), Path.of(s.home), s.jeusDomain, s.jeusServer);
        }
        catch (InvalidPathException e) {
            showLogFolderError(s, e);
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> findLogFiles(s, roots));
    }

    /** 풀 스레드에서 로그 파일을 찾아 화면 스레드에서 목록을 띄운다. */
    private void findLogFiles(WasSettings.Server s, List<Path> roots) {
        List<Path> files;
        try {
            files = WasLogFiles.find(roots);
        }
        catch (IOException | InvalidPathException e) {
            ApplicationManager.getApplication().invokeLater(() -> showLogFolderError(s, e), project.getDisposed());
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> chooseLogFile(s, roots, files), project.getDisposed());
    }

    private void showLogFolderError(WasSettings.Server s, Exception e) {
        Messages.showErrorDialog(project, BootServersBundle.message("servers.log.files.read.error", s.name, e.getMessage()), BootServersBundle.message("servers.log.files.title"));
    }

    private void chooseLogFile(WasSettings.Server s, List<Path> roots, List<Path> files) {
        if (files.isEmpty()) {
            StringBuilder where = new StringBuilder();
            for (Path root : roots) {
                where.append("\n· ").append(root);
            }
            Messages.showInfoMessage(project, BootServersBundle.message("servers.log.files.none", s.name, where), BootServersBundle.message("servers.log.files.title"));
            return;
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(files)
                .setTitle(BootServersBundle.message("servers.log.files.popup", s.name))
                .setRenderer(new ColoredListCellRenderer<>() {
                    @Override
                    protected void customizeCellRenderer(@NotNull JList<? extends Path> list, Path p, int index, boolean selected, boolean hasFocus) {
                        append(p.getFileName().toString());
                        String folder = WasLogFiles.folderLabel(roots, p);
                        if (!folder.isEmpty()) {
                            append("   " + StringUtil.shortenPathWithEllipsis(folder, LOG_FOLDER_MAX_CHARS), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                        }
                        setToolTipText(p.toString());
                    }
                })
                .setItemChosenCallback(p -> LogFileView.open(project, s.name, p))
                .createPopup()
                .showInFocusCenter();
    }

    // ---- 오른쪽 칸 ----

    /** 오른쪽 칸 안쪽 여백. 플랫폼 표준 간격(UIUtil.DEFAULT_VGAP · DEFAULT_HGAP)으로 맞춘다. */
    private static Border cardBorder() {
        return JBUI.Borders.empty(UIUtil.DEFAULT_VGAP * 2, UIUtil.DEFAULT_HGAP);
    }

    private static JComponent emptyCard() {
        JPanel p = FormBuilder.createFormBuilder().addComponent(new JBLabel(BootServersBundle.message("servers.detail.choose"))).addComponentFillVertically(new JPanel(), 0).getPanel();
        p.setBorder(cardBorder());
        return p;
    }

    private JComponent bootCard() {
        bootName.getEmptyText().setText(BootServersBundle.message("servers.boot.name.hint"));
        profile.setEditable(true);
        vmOptions.getEmptyText().setText(BootServersBundle.message("servers.boot.vm.hint"));
        port.getEmptyText().setText(BootServersBundle.message("servers.boot.port.hint"));
        bootTitle.setFont(JBUI.Fonts.label().asBold());
        bootStatus.setForeground(NamedColorUtil.getInactiveTextColor());
        JButton apply = new JButton(BootServersBundle.message("servers.boot.apply"));
        // 2026.2 는 버튼 클릭을 읽기 잠금 없이 처리한다. 설정이 없으면 PSI 로 메인 클래스를 찾으므로 잠금 안에서 돌린다.
        apply.addActionListener(e -> WriteIntentReadAction.run(() -> applyBoot()));
        JPanel p = FormBuilder.createFormBuilder()
                .addComponent(bootTitle)
                .addComponent(bootStatus)
                .addLabeledComponent(BootServersBundle.message("servers.boot.name"), bootName)
                .addLabeledComponent(BootServersBundle.message("servers.boot.profile"), profile)
                .addLabeledComponent(BootServersBundle.message("servers.boot.vm"), vmOptions)
                .addLabeledComponent(BootServersBundle.message("servers.boot.port"), port)
                .addComponent(apply)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        p.setBorder(cardBorder());
        return p;
    }

    private JComponent wasCard() {
        wasTitle.setFont(JBUI.Fonts.label().asBold());
        wasStatus.setForeground(NamedColorUtil.getInactiveTextColor());
        wasSummary.setVerticalAlignment(JBLabel.TOP);
        JButton edit = new JButton(BootServersBundle.message("servers.action.edit.settings"));
        edit.addActionListener(e -> {
            if (detailServer != null) {
                editWas(detailServer);
            }
        });
        JPanel p = FormBuilder.createFormBuilder()
                .addComponent(wasTitle)
                .addComponent(wasStatus)
                .addComponent(wasSummary)
                .addComponent(edit)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        p.setBorder(cardBorder());
        return p;
    }

    private void showDetail() {
        Object row = selectedRow();
        detailBoot = row instanceof Boot b ? b : null;
        detailServer = row instanceof Deploy d ? d.server() : row instanceof WasSettings.Server s ? s : null;
        if (detailBoot != null) {
            bootTitle.setText(BootServersBundle.message("servers.boot.title", detailBoot.module().getName()));
            bootName.setText(detailBoot.name());
            String vm = vmOf(detailBoot);
            profile.setSelectedItem(getProperty(vm, PROFILE_KEY));
            port.setText(getProperty(vm, PORT_KEY));
            vmOptions.setText(vm);
            cards.show(detail, "boot");
        }
        else if (detailServer != null) {
            WasSettings.Server s = detailServer;
            wasTitle.setText(s.name);
            StringBuilder html = new StringBuilder("<html>")
                    .append(s.kind().label).append(" · ").append(escape(s.home)).append("<br>")
                    .append(BootServersBundle.message("servers.was.ports", s.httpPort, s.shutdownPort, s.debugPort)).append("<br>")
                    .append(BootServersBundle.message("servers.was.vm", s.vmOptions.isBlank() ? BootServersBundle.message("servers.was.vm.none") : escape(s.vmOptions))).append("<br>")
                    .append(s.reloadable ? BootServersBundle.message("servers.was.reload.on") : BootServersBundle.message("servers.was.reload.off")).append("<br><br>").append(BootServersBundle.message("servers.was.deployments")).append("<br>");
            if (s.deployments.isEmpty()) {
                html.append("· ").append(BootServersBundle.message("servers.was.deployments.none"));
            }
            for (WasSettings.Deployment d : s.deployments) {
                html.append("· ").append(escape(d.module)).append(" → ").append(escape(d.contextPath)).append("<br>");
            }
            wasSummary.setText(html.append("</html>").toString());
            cards.show(detail, "was");
        }
        else {
            cards.show(detail, "none");
        }
        updateStatus();
    }

    private void updateStatus() {
        if (detailBoot != null) {
            ServerRegistry.Run run = ServerRegistry.getInstance(project).get(detailBoot.name());
            ServerLauncher.State state = stateOf(detailBoot);
            String text = state.label();
            if (state == ServerLauncher.State.FAILED && run != null && run.error != null) {
                text += " · " + run.error;
            }
            else if (detailBoot.settings() == null) {
                text += " · " + BootServersBundle.message("servers.boot.no.config");
            }
            bootStatus.setText(text);
            bootStatus.setToolTipText(text);
        }
        if (detailServer != null) {
            ServerRegistry.Run run = ServerRegistry.getInstance(project).get(WasRunner.registryKey(detailServer));
            ServerLauncher.State state = WasRunner.getInstance(project).state(detailServer);
            String text = state.label() + (state == ServerLauncher.State.FAILED && run != null && run.error != null ? " · " + run.error : "");
            wasStatus.setText(text);
            wasStatus.setToolTipText(text);
        }
    }

    /** 이름을 바꾸고, 프로필 · 포트 칸 값을 VM 옵션의 -D 값으로 넣어 실행 설정에 적는다. 다음 시작부터 적용된다. */
    private void applyBoot() {
        if (detailBoot == null) {
            return;
        }
        String name = bootName.getText().strip();
        if (name.isEmpty()) {
            Messages.showErrorDialog(project, BootServersBundle.message("servers.boot.name.required"), BootServersBundle.message("servers.column.server"));
            return;
        }
        String portText = port.getText().strip();
        if (!portText.isEmpty()) {
            int n;
            try {
                n = Integer.parseInt(portText);
            }
            catch (NumberFormatException ex) {
                n = -1;
            }
            if (n < 1 || n > 65535) {
                Messages.showErrorDialog(project, BootServersBundle.message("servers.boot.port.invalid"), BootServersBundle.message("servers.column.server"));
                return;
            }
        }
        RunnerAndConfigurationSettings settings = detailBoot.settings() == null ? ServerLauncher.ensureSettings(detailBoot.module()) : detailBoot.settings();
        if (settings == null) {
            return;
        }
        Object selectedProfile = profile.getEditor().getItem();
        String vm = vmOptions.getText();
        vm = setProperty(vm, PROFILE_KEY, selectedProfile == null ? "" : selectedProfile.toString());
        vm = setProperty(vm, PORT_KEY, portText);
        ((ApplicationConfiguration) settings.getConfiguration()).setVMParameters(vm);
        RunManager runManager = RunManager.getInstance(project);
        if (!name.equals(settings.getName())) {
            String oldName = settings.getName();
            settings.setName(runManager.suggestUniqueName(name, settings.getType()));
            // 이름이 바뀌면 실행 관리자의 색인 키도 바뀌므로 다시 등록해 맞춘다.
            runManager.addConfiguration(settings);
            ServerRegistry.getInstance(project).rename(oldName, settings.getName());
        }
        runManager.setSelectedConfiguration(settings);
        Boot applied = new Boot(detailBoot.module(), settings);
        rebuildRows();
        table.clearSelection();
        select(applied);
        showDetail();
    }

    private static String vmOf(Boot b) {
        String vm = b.settings() == null ? null : ((ApplicationConfiguration) b.settings().getConfiguration()).getVMParameters();
        return vm == null ? "" : vm;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---- VM 옵션의 -Dkey=value ----

    static String getProperty(String vm, String key) {
        String prefix = "-D" + key + "=";
        for (String p : ParametersListUtil.parse(vm)) {
            if (p.startsWith(prefix)) {
                return p.substring(prefix.length());
            }
        }
        return "";
    }

    /** -Dkey=value 를 바꾸거나 넣는다. 값이 비면 뺀다. 다른 옵션의 순서는 그대로 둔다. */
    static String setProperty(String vm, String key, String value) {
        String prefix = "-D" + key + "=";
        List<String> parts = new ArrayList<>(ParametersListUtil.parse(vm));
        parts.removeIf(p -> p.startsWith(prefix));
        if (!value.isBlank()) {
            parts.add(prefix + value.strip());
        }
        return ParametersListUtil.join(parts);
    }

    static String uptime(long startMillis) {
        long sec = Math.max(0, (System.currentTimeMillis() - startMillis) / 1000);
        if (sec < 60) {
            return BootServersBundle.message("servers.uptime.seconds", sec);
        }
        if (sec < 3600) {
            return BootServersBundle.message("servers.uptime.minutes", sec / 60);
        }
        return BootServersBundle.message("servers.uptime.hours", sec / 3600, sec % 3600 / 60);
    }

    // ---- 표 ----

    private final class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMN_KEYS.length;
        }

        @Override
        public String getColumnName(int column) {
            return BootServersBundle.message(COLUMN_KEYS[column]);
        }

        @Override
        public Object getValueAt(int row, int column) {
            Object r = rows.get(row);
            if (r instanceof Boot b) {
                return bootCell(b, column);
            }
            if (r instanceof Deploy d) {
                return switch (column) {
                    case 0 -> "    └ " + d.deployment().module;
                    case 1 -> shownState(d) == ServerLauncher.State.RUNNING ? BootServersBundle.message("servers.deploy.up") : "-";
                    case 3 -> d.deployment().contextPath;
                    default -> "";
                };
            }
            WasSettings.Server s = (WasSettings.Server) r;
            ServerLauncher.State state = shownState(s);
            ServerRegistry.Run run = ServerRegistry.getInstance(project).get(WasRunner.registryKey(s));
            return switch (column) {
                case 0 -> s.name + "  [" + s.kind().label + "]";
                case 1 -> state;
                case 2 -> ":" + s.httpPort;
                case 3 -> "-";
                default -> state == ServerLauncher.State.RUNNING && run != null ? uptime(run.startMillis) : "-";
            };
        }

        private Object bootCell(Boot b, int column) {
            ServerLauncher.State state = shownState(b);
            ServerRegistry.Run run = ServerRegistry.getInstance(project).get(b.name());
            String vm = vmOf(b);
            return switch (column) {
                case 0 -> b.name();
                case 1 -> state;
                case 2 -> state == ServerLauncher.State.RUNNING && run != null && run.port != null
                        ? ":" + run.port
                        : getProperty(vm, PORT_KEY).isEmpty() ? "-" : ":" + getProperty(vm, PORT_KEY);
                case 3 -> getProperty(vm, PROFILE_KEY).isEmpty() ? "-" : getProperty(vm, PROFILE_KEY);
                default -> state == ServerLauncher.State.RUNNING && run != null ? uptime(run.startMillis) : "-";
            };
        }
    }

    /** 모든 칸의 기본 그리기. 좌우 여백을 두고, 실행 설정이 없는 자리 줄의 이름은 회색으로 보인다. */
    private final class CellRenderer extends ColoredTableCellRenderer {
        @Override
        protected void customizeCellRenderer(@NotNull JTable table, Object value, boolean selected, boolean hasFocus, int row, int column) {
            setIpad(JBUI.insets(0, CELL_PAD));
            boolean placeholder = column == 0 && row < rows.size() && rows.get(row) instanceof Boot b && b.settings() == null;
            append(value == null ? "" : value.toString(), placeholder ? SimpleTextAttributes.GRAYED_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
        }
    }

    private static final class StateRenderer extends ColoredTableCellRenderer {

        @Override
        protected void customizeCellRenderer(@NotNull JTable table, Object value, boolean selected, boolean hasFocus, int row, int column) {
            setIpad(JBUI.insets(0, CELL_PAD));
            if (!(value instanceof ServerLauncher.State state)) {
                append(value == null ? "" : value.toString(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                return;
            }
            // 상태 점 색은 테마의 성공 · 경고 · 오류 · 흐린 글 색을 따른다.
            Color color = switch (state) {
                case RUNNING -> UIUtil.getLabelSuccessForeground();
                case STARTING -> JBUI.CurrentTheme.Label.warningForeground();
                case FAILED -> JBUI.CurrentTheme.Label.errorForeground();
                case STOPPED -> NamedColorUtil.getInactiveTextColor();
            };
            append("● ", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color));
            append(state.label());
        }
    }
}
