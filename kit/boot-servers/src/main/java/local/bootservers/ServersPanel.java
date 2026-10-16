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
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import local.bootservers.was.WasSettings;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import java.awt.CardLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 아래쪽 「서버」 창. 스프링 부트 실행 설정과 추가한 WAS 를 한 목록에 보인다.
 * 줄 종류는 셋이다: 스프링 부트 실행 설정(Boot), WAS(WasSettings.Server), WAS 에 올린 프로젝트(Deploy).
 * 한 모듈을 프로필만 달리한 실행 설정마다 한 줄씩 보이고, 실행 설정이 아직 없는 모듈은 모듈 이름의 자리 줄 하나로 보인다.
 * 고른 줄들을 한꺼번에 시작 · 중지하며, 오른쪽 칸에서 고른 줄의 설정을 고친다.
 */
final class ServersPanel extends SimpleToolWindowPanel {

    private static final String[] COLUMNS = {"서버", "상태", "포트", "컨텍스트 · 프로필", "실행 시간"};
    private static final String PROFILE_KEY = "spring.profiles.active";
    private static final String PORT_KEY = "server.port";
    private static final int TICK_MILLIS = 1000;
    // 글자가 칸 왼쪽 끝 · 머리글에 붙지 않게 두는 칸 안쪽 여백
    private static final int CELL_PAD = 8;
    private static final int ROW_HEIGHT = 24;
    private static final String COPY_SUFFIX = " (복제)";

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
                    for (Object target : targets()) {
                        showConsoleOrStart(target);
                    }
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
        Timer timer = new Timer(TICK_MILLIS, e -> tick());
        timer.start();
        Disposer.register(parent, timer::stop);
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
        table.getEmptyText().setText("서버를 찾는 중…");
        ReadAction.nonBlocking(() -> ServerLauncher.serverModules(project))
                .inSmartMode(project)
                .expireWith(parent)
                .finishOnUiThread(ModalityState.any(), found -> {
                    bootModules = found;
                    rebuildRows();
                    table.getEmptyText().setText("스프링 부트 모듈이 없습니다. 「WAS 추가」 로 Tomcat 을 추가할 수 있습니다.");
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
        for (Object row : rows) {
            if (row instanceof Boot b) {
                ServerLauncher.State state = stateOf(b);
                changed |= lastStates.put(row, state) != state;
            }
        }
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

    private void start(Object target, boolean debug) {
        Executor executor = debug ? DefaultDebugExecutor.getDebugExecutorInstance() : DefaultRunExecutor.getRunExecutorInstance();
        if (target instanceof Boot b) {
            if (b.settings() != null) {
                ServerLauncher.start(b.settings(), executor);
            }
            else {
                // 자리 줄: 모듈 이름으로 실행 설정이 만들어지므로 줄을 다시 맞춘다.
                ServerLauncher.start(b.module(), executor);
                rebuildRows();
            }
        }
        else {
            WasRunner.getInstance(project).start((WasSettings.Server) target, debug);
        }
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
        g.add(new AnAction("WAS 추가…", "Tomcat 같은 WAS 를 추가합니다", AllIcons.General.Add) {
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
        g.add(action("시작", AllIcons.Actions.Execute, t -> isStartable(stateOf(t)), t -> start(t, false)));
        g.add(action("디버그로 시작", AllIcons.Actions.StartDebugger, t -> isStartable(stateOf(t)), t -> start(t, true)));
        g.add(action("재시작", AllIcons.Actions.Restart, t -> isLive(stateOf(t)), this::restart));
        g.add(action("중지", AllIcons.Actions.Suspend, t -> isLive(stateOf(t)), this::stop));
        g.add(new AnAction("모두 중지", "실행 중인 서버와 WAS 를 모두 중지합니다", AllIcons.Actions.Cancel) {
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
        g.addSeparator();
        g.add(action("서버 복제", AllIcons.Actions.Copy, t -> t instanceof Boot b && b.settings() != null, this::duplicate));
        g.add(action("반영 (다시 빌드)", AllIcons.Actions.Compile, t -> t instanceof WasSettings.Server && isLive(stateOf(t)), this::publish));
        g.add(action("브라우저로 열기", AllIcons.General.Web, t -> stateOf(t) == ServerLauncher.State.RUNNING, this::openBrowser));
        g.add(action("콘솔 보기", AllIcons.Debugger.Console, t -> isLive(stateOf(t)), this::showConsoleOrStart));
        g.add(action("설정 편집…", AllIcons.Actions.EditSource, t -> true, this::editSettings));
        g.add(action("WAS 삭제", AllIcons.General.Remove, t -> t instanceof WasSettings.Server && !isLive(stateOf(t)), this::removeWas));
        g.addSeparator();
        g.add(new AnAction("서버 목록 새로 찾기", null, AllIcons.Actions.Refresh) {
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
        copy.setName(runManager.suggestUniqueName(source.getName() + COPY_SUFFIX, source.getType()));
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
                Messages.showInfoMessage(project, "콘솔 출력에서 포트를 아직 찾지 못했습니다.", "브라우저로 열기");
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
            if (settings != null && RunDialog.editConfiguration(project, settings, settings.getName() + " 실행 설정")) {
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
        if (Messages.showYesNoDialog(project, "'" + s.name + "' 을(를) 서버 목록에서 뺄까요? 설치 폴더는 지우지 않습니다.", "WAS 삭제",
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

    // ---- 오른쪽 칸 ----

    private static JComponent emptyCard() {
        JPanel p = FormBuilder.createFormBuilder().addComponent(new JBLabel("서버를 고르세요")).addComponentFillVertically(new JPanel(), 0).getPanel();
        p.setBorder(JBUI.Borders.empty(8, 10));
        return p;
    }

    private JComponent bootCard() {
        bootName.getEmptyText().setText("실행 설정 이름");
        profile.setEditable(true);
        vmOptions.getEmptyText().setText("-Dkey=value 띄어쓰기로 구분");
        port.getEmptyText().setText("비우면 application 설정 값");
        bootTitle.setFont(JBUI.Fonts.label().asBold());
        bootStatus.setForeground(JBColor.GRAY);
        JButton apply = new JButton("적용");
        apply.addActionListener(e -> applyBoot());
        JPanel p = FormBuilder.createFormBuilder()
                .addComponent(bootTitle)
                .addComponent(bootStatus)
                .addLabeledComponent("이름", bootName)
                .addLabeledComponent("프로필", profile)
                .addLabeledComponent("VM 옵션", vmOptions)
                .addLabeledComponent("포트", port)
                .addComponent(apply)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        p.setBorder(JBUI.Borders.empty(8, 10));
        return p;
    }

    private JComponent wasCard() {
        wasTitle.setFont(JBUI.Fonts.label().asBold());
        wasStatus.setForeground(JBColor.GRAY);
        wasSummary.setVerticalAlignment(JBLabel.TOP);
        JButton edit = new JButton("설정 편집…");
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
        p.setBorder(JBUI.Borders.empty(8, 10));
        return p;
    }

    private void showDetail() {
        Object row = selectedRow();
        detailBoot = row instanceof Boot b ? b : null;
        detailServer = row instanceof Deploy d ? d.server() : row instanceof WasSettings.Server s ? s : null;
        if (detailBoot != null) {
            bootTitle.setText(detailBoot.module().getName() + " 모듈 실행 설정");
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
                    .append("HTTP ").append(s.httpPort).append(" · 종료 ").append(s.shutdownPort).append(" · 디버그 ").append(s.debugPort).append("<br>")
                    .append("VM 옵션: ").append(s.vmOptions.isBlank() ? "없음" : escape(s.vmOptions)).append("<br>")
                    .append(s.reloadable ? "클래스가 바뀌면 자동으로 다시 올림" : "자동으로 다시 올리지 않음").append("<br><br>올린 프로젝트<br>");
            if (s.deployments.isEmpty()) {
                html.append("· 없음 (설정 편집에서 고르세요)");
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
            String text = state.label;
            if (state == ServerLauncher.State.FAILED && run != null && run.error != null) {
                text += " · " + run.error;
            }
            else if (detailBoot.settings() == null) {
                text += " · 처음 시작하거나 적용하면 실행 설정이 만들어집니다";
            }
            bootStatus.setText(text);
            bootStatus.setToolTipText(text);
        }
        if (detailServer != null) {
            ServerRegistry.Run run = ServerRegistry.getInstance(project).get(WasRunner.registryKey(detailServer));
            ServerLauncher.State state = WasRunner.getInstance(project).state(detailServer);
            String text = state.label + (state == ServerLauncher.State.FAILED && run != null && run.error != null ? " · " + run.error : "");
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
            Messages.showErrorDialog(project, "실행 설정 이름을 입력하세요.", "서버");
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
                Messages.showErrorDialog(project, "포트는 1 ~ 65535 사이 숫자여야 합니다.", "서버");
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
            return sec + "초";
        }
        if (sec < 3600) {
            return (sec / 60) + "분";
        }
        return (sec / 3600) + "시간 " + (sec % 3600 / 60) + "분";
    }

    // ---- 표 ----

    private final class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
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
                    case 1 -> stateOf(d) == ServerLauncher.State.RUNNING ? "올라감" : "-";
                    case 3 -> d.deployment().contextPath;
                    default -> "";
                };
            }
            WasSettings.Server s = (WasSettings.Server) r;
            ServerLauncher.State state = stateOf(s);
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
            ServerLauncher.State state = stateOf(b);
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
        private static final JBColor RUN = new JBColor(0x3E9C46, 0x5FB865);
        private static final JBColor STARTING = new JBColor(0xB7791F, 0xE0A64F);
        private static final JBColor FAILED = new JBColor(0xC94F4F, 0xE5605A);

        @Override
        protected void customizeCellRenderer(@NotNull JTable table, Object value, boolean selected, boolean hasFocus, int row, int column) {
            setIpad(JBUI.insets(0, CELL_PAD));
            if (!(value instanceof ServerLauncher.State state)) {
                append(value == null ? "" : value.toString(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                return;
            }
            JBColor color = switch (state) {
                case RUNNING -> RUN;
                case STARTING -> STARTING;
                case FAILED -> FAILED;
                case STOPPED -> JBColor.GRAY;
            };
            append("● ", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color));
            append(state.label);
        }
    }
}
