package local.bootservers;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.Executor;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunManagerListener;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.impl.RunDialog;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI;
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
import com.intellij.openapi.actionSystem.Separator;
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
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.task.ProjectTaskContext;
import com.intellij.task.ProjectTaskListener;
import com.intellij.task.ProjectTaskManager;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.InlineBanner;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanelWithEmptyText;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;
import local.bootservers.was.WasSettings;
import local.bootservers.was.WasLogFiles;
import local.bootservers.ui.Pill;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 아래쪽 「서버」 창. 왼쪽은 종류별(스프링 부트 · Tomcat · JEUS …) 트리, 오른쪽은 고른 서버의 머리줄 · 설정과 그 아래 콘솔 · 로그 파일 탭.
 * 줄 종류는 셋이다: 스프링 부트 실행 설정(Boot), WAS(WasSettings.Server), WAS 에 올린 프로젝트(Deploy, WAS 줄 아래).
 * 한 모듈을 프로필만 달리한 실행 설정마다 한 줄씩 보이고, 실행 설정이 아직 없는 모듈은 모듈 이름의 자리 줄 하나로 보인다.
 * 고른 줄들을 한꺼번에 시작 · 중지하며, 오른쪽 칸에서 고른 줄의 설정을 고친다.
 */
final class ServersPanel extends SimpleToolWindowPanel {

    private static final String PROFILE_KEY = "spring.profiles.active";
    private static final String PORT_KEY = "server.port";
    private static final int TICK_MILLIS = 1000;
    // 트리 · 오른쪽 칸, 설정 · 콘솔 나눔 비율을 기억하는 키와 처음 비율
    private static final String TREE_SPLIT_KEY = "BootServers.Servers.TreeSplit";
    private static final String DETAIL_SPLIT_KEY = "BootServers.Servers.DetailSplit";
    private static final float TREE_PROPORTION = 0.22f;
    private static final float DETAIL_PROPORTION = 0.4f;
    // 머리줄 이름이 기본 라벨 글자보다 큰 만큼
    private static final float TITLE_FONT_BIGGER = 2f;
    // 아래 탭 차례
    private static final int CONSOLE_TAB = 0;
    private static final int LOG_TAB = 1;
    // 실행 설정 이름 끝의 「 (local)」 같은 덧붙임. 회색으로 보인다.
    private static final String SUFFIX_OPEN = " (";
    private static final String SUFFIX_CLOSE = ")";
    // 상태 꼬리표 앞 점
    private static final String STATE_DOT = "● ";
    private static final String JOIN = " · ";
    private static final String PORT_PREFIX = ":";
    // 상태 꼬리표 바탕(글 색은 테마의 성공 · 경고 · 오류 글 색)
    private static final Color RUNNING_BG = JBColor.namedColor("EIServers.runningBackground", new JBColor(0xDDF3E2, 0x213E2C));
    private static final Color STARTING_BG = JBColor.namedColor("EIServers.startingBackground", new JBColor(0xFBEFD9, 0x463927));
    private static final Color FAILED_BG = JBColor.namedColor("EIServers.failedBackground", new JBColor(0xFADEDF, 0x462C2D));

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
    private final ToolWindow toolWindow;
    private final Disposable parent;
    // 창 제목에 마지막으로 보인 실행 중 · 실패 개수 글
    private String shownTitle = "";
    private Map<Module, Set<String>> bootModules = Map.of();
    // 트리 줄 순서대로 늘어놓은 줄(Boot · Server · Deploy). 묶음 줄은 트리에만 있다.
    private List<Object> rows = List.of();
    private final Map<Object, ServerLauncher.State> lastStates = new HashMap<>();
    // 마지막 tick 에서 구한 줄별 상태. 트리 줄은 여기서 읽고, 없는 줄(tick 전에 생긴 줄)만 그 자리에서 구한다.
    private Map<Object, ServerLauncher.State> shownStates = Map.of();

    // 종류 묶음(userObject 는 묶음 이름 String) → 서버 줄 → WAS 는 올린 프로젝트 줄
    private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode());
    private final Tree tree = new Tree(treeModel);

    // 오른쪽 칸: 아무것도 안 골랐을 때 안내, 골랐을 때 머리줄 · 설정 · 탭
    private final CardLayout sideCards = new CardLayout();
    private final JPanel side = new JPanel(sideCards);
    private final JBLabel title = new JBLabel();
    private final Pill statePill = new Pill();
    private final JBLabel status = new JBLabel();
    private final CardLayout cards = new CardLayout();
    private final JPanel detail = new JPanel(cards);
    // 스프링 부트 설정 폼
    private final JBTextField bootName = new JBTextField();
    private final JComboBox<String> profile = new JComboBox<>(new String[]{"", "local", "dev", "prod"});
    private final JBTextField vmOptions = new JBTextField();
    private final JBTextField port = new JBTextField();
    private Boot detailBoot;
    // WAS 설정 요약
    private final JPanel wasSummary = new JPanel(new BorderLayout());
    private WasSettings.Server detailServer;
    // 고른 서버가 실패했을 때 오른쪽 칸 위에 보이는 오류 띠
    private final InlineBanner errorBanner = new InlineBanner("", EditorNotificationPanel.Status.Error);
    private final JPanel bannerRow = new JPanel(new BorderLayout());
    // 아래 탭: 고른 서버의 프로세스 출력 콘솔 · WAS 로그 파일
    private final JBTabbedPane tabs = new JBTabbedPane();
    private final JPanel consoleHolder = new JPanel(new BorderLayout());
    private ConsoleView console;
    private Object consoleTarget;
    private ProcessHandler consoleHandler;
    private final JPanel logHolder = new JPanel(new BorderLayout());
    private final JBLabel logFileName = new JBLabel();
    private LogFileView.View logView;
    private WasSettings.Server logServer;

    ServersPanel(Project project, ToolWindow toolWindow) {
        super(true, true);
        this.project = project;
        this.toolWindow = toolWindow;
        this.parent = toolWindow.getDisposable();

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new NodeRenderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.addTreeSelectionListener(e -> showDetail());
        tree.addMouseListener(new MouseAdapter() {
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

        // 창 머리 도구줄은 추가 · 새로고침 · 「더 보기」(나머지 동작), 우클릭 메뉴는 모두 펼친다. 머리줄 단추는 시작 · 디버그 · 재시작 · 중지 · 편집.
        AnAction add = addAction();
        AnAction reload = reloadAction();
        AnAction start = action(BootServersBundle.message("servers.action.start"), AllIcons.Actions.Execute, t -> isStartable(stateOf(t)), t -> start(t, false));
        AnAction debug = action(BootServersBundle.message("servers.action.debug"), AllIcons.Actions.StartDebugger, t -> isStartable(stateOf(t)), t -> start(t, true));
        AnAction restart = action(BootServersBundle.message("servers.action.restart"), AllIcons.Actions.Restart, t -> isLive(stateOf(t)), this::restart);
        AnAction stop = action(BootServersBundle.message("servers.action.stop"), AllIcons.Actions.Suspend, t -> isLive(stateOf(t)), this::stop);
        AnAction edit = action(BootServersBundle.message("servers.action.edit.settings"), AllIcons.Actions.EditSource, t -> true, this::editSettings);
        DefaultActionGroup serverActions = serverActions(start, debug, restart, stop, edit);
        DefaultActionGroup more = new DefaultActionGroup(BootServersBundle.message("servers.action.more"), true);
        more.getTemplatePresentation().setIcon(AllIcons.Actions.More);
        more.add(serverActions);
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("BootServers.Toolbar", new DefaultActionGroup(add, reload, more), true);
        toolbar.setTargetComponent(tree);
        setToolbar(toolbar.getComponent());
        PopupHandler.installPopupMenu(tree, new DefaultActionGroup(add, Separator.getInstance(), serverActions, Separator.getInstance(), reload), "BootServers.Popup");
        ActionToolbar detailToolbar = ActionManager.getInstance().createActionToolbar("BootServers.Detail", new DefaultActionGroup(start, debug, restart, stop, edit), true);
        detailToolbar.setTargetComponent(tree);
        detailToolbar.setReservePlaceAutoPopupIcon(false);

        side.add(emptyCard(), "none");
        side.add(serverCard(detailToolbar), "server");
        OnePixelSplitter splitter = new OnePixelSplitter(false, TREE_PROPORTION);
        splitter.setSplitterProportionKey(TREE_SPLIT_KEY);
        splitter.setFirstComponent(ScrollPaneFactory.createScrollPane(tree, true));
        splitter.setSecondComponent(side);
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
        // 스프링 부트 프로세스가 뜨면 tick 을 기다리지 않고 콘솔 탭을 붙인다.
        project.getMessageBus().connect(parent).subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            @Override
            public void processStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler) {
                ApplicationManager.getApplication().invokeLater(ServersPanel.this::syncConsole, ModalityState.any(), project.getDisposed());
            }
        });
        reload();
    }

    private void reload() {
        tree.getEmptyText().setText(BootServersBundle.message("servers.empty.loading"));
        ReadAction.nonBlocking(() -> ServerLauncher.serverModules(project))
                .inSmartMode(project)
                .expireWith(parent)
                .finishOnUiThread(ModalityState.any(), found -> {
                    bootModules = found;
                    rebuildRows();
                    tree.getEmptyText().setText(BootServersBundle.message("servers.empty.none"))
                            .appendSecondaryText(BootServersBundle.message("servers.action.add.was"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES, e -> editWas(null));
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private void rebuildLater() {
        ApplicationManager.getApplication().invokeLater(this::rebuildRows, ModalityState.any(), project.getDisposed());
    }

    /**
     * 트리를 다시 만든다: 「스프링 부트」 묶음에 모듈별 실행 설정(없으면 자리 줄), 그 뒤에 WAS 종류별 묶음에 WAS 와 그 아래 올린 프로젝트.
     * 서버가 있는 묶음만 보이고 모두 펼친다. 고른 줄은 그대로 둔다.
     */
    private void rebuildRows() {
        List<Object> selected = targets();
        List<Object> list = new ArrayList<>();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        DefaultMutableTreeNode bootGroup = new DefaultMutableTreeNode(BootServersBundle.message("servers.tree.boot"));
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
            for (int i = before; i < list.size(); i++) {
                bootGroup.add(new DefaultMutableTreeNode(list.get(i), false));
            }
        }
        if (bootGroup.getChildCount() > 0) {
            root.add(bootGroup);
        }
        Map<WasSettings.Kind, DefaultMutableTreeNode> kinds = new EnumMap<>(WasSettings.Kind.class);
        for (WasSettings.Server s : WasSettings.getInstance(project).servers()) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(s);
            kinds.computeIfAbsent(s.kind(), k -> new DefaultMutableTreeNode(k.label)).add(node);
            list.add(s);
            for (WasSettings.Deployment d : s.deployments) {
                Deploy deploy = new Deploy(s, d);
                list.add(deploy);
                node.add(new DefaultMutableTreeNode(deploy, false));
            }
        }
        for (DefaultMutableTreeNode group : kinds.values()) {
            root.add(group);
        }
        rows = list;
        treeModel.setRoot(root);
        for (TreeNode node : Collections.list(root.breadthFirstEnumeration())) {
            if (node != root && node.getChildCount() > 0) {
                tree.expandPath(new TreePath(((DefaultMutableTreeNode) node).getPath()));
            }
        }
        for (Object t : selected) {
            select(t);
        }
        showDetail();
    }

    /** 같은 실행 설정 · 모듈 · WAS 의 줄을 고른 줄에 더한다. */
    private void select(Object target) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        for (TreeNode node : Collections.list(root.breadthFirstEnumeration())) {
            if (same(((DefaultMutableTreeNode) node).getUserObject(), target)) {
                tree.addSelectionPath(new TreePath(((DefaultMutableTreeNode) node).getPath()));
            }
        }
    }

    /** 같은 줄인가. Boot 는 다시 만든 레코드라도 같은 실행 설정(자리 줄은 같은 모듈)이면 같다. */
    private static boolean same(Object row, Object target) {
        if (row instanceof Boot b && target instanceof Boot t) {
            return b.settings() == null ? t.settings() == null && b.module().equals(t.module()) : b.settings() == t.settings();
        }
        return row == target;
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
        tree.repaint();
        updateStatus();
        syncConsole();
        updateTitle(states);
        if (changed) {
            ProjectView.getInstance(project).refresh();
        }
    }

    /** 창 제목 옆에 실행 중 · 실패 개수를 보인다. 올린 프로젝트 줄은 세지 않고, 글이 바뀔 때만 고친다. */
    private void updateTitle(Map<Object, ServerLauncher.State> states) {
        int running = 0;
        int failed = 0;
        for (Map.Entry<Object, ServerLauncher.State> e : states.entrySet()) {
            if (e.getKey() instanceof Deploy) {
                continue;
            }
            if (e.getValue() == ServerLauncher.State.RUNNING) {
                running++;
            }
            else if (e.getValue() == ServerLauncher.State.FAILED) {
                failed++;
            }
        }
        List<String> parts = new ArrayList<>();
        if (running > 0) {
            parts.add(BootServersBundle.message("servers.title.count", ServerLauncher.State.RUNNING.label(), running));
        }
        if (failed > 0) {
            parts.add(BootServersBundle.message("servers.title.count", ServerLauncher.State.FAILED.label(), failed));
        }
        String title = String.join(JOIN, parts);
        if (!title.equals(shownTitle)) {
            shownTitle = title;
            toolWindow.setTitle(title);
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

    /** 고른 줄을 동작 대상으로: 묶음 줄은 빼고, 올린 프로젝트 줄은 그 WAS 로 바꾸고 겹치면 하나로 친다. */
    private List<Object> targets() {
        Set<Object> result = new LinkedHashSet<>();
        TreePath[] paths = tree.getSelectionPaths();
        for (TreePath path : paths == null ? new TreePath[0] : paths) {
            Object row = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            if (row instanceof String || row instanceof Boot b && b.module().isDisposed()) {
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

    private AnAction addAction() {
        return new AnAction(BootServersBundle.message("servers.action.add.was"), BootServersBundle.message("servers.action.add.was.description"), AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                editWas(null);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
    }

    /** 고른 서버에 하는 동작 전부(머리줄 단추와 같은 인스턴스를 받는다). 「더 보기」 와 우클릭 메뉴에 들어간다. */
    private DefaultActionGroup serverActions(AnAction start, AnAction debug, AnAction restart, AnAction stop, AnAction edit) {
        DefaultActionGroup g = new DefaultActionGroup();
        g.add(start);
        g.add(debug);
        g.add(restart);
        g.add(stop);
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
        g.add(action(BootServersBundle.message("servers.action.open.browser"), AllIcons.General.Web, t -> stateOf(t) == ServerLauncher.State.RUNNING, this::openBrowser));
        g.add(action(BootServersBundle.message("servers.action.console"), AllIcons.Debugger.Console, t -> isLive(stateOf(t)), this::showConsoleOrStart));
        g.add(edit);
        g.addSeparator();
        g.add(action(BootServersBundle.message("servers.action.duplicate"), AllIcons.Actions.Copy, t -> t instanceof Boot b && b.settings() != null, this::duplicate));
        g.add(action(BootServersBundle.message("servers.action.publish"), AllIcons.Actions.Compile, t -> t instanceof WasSettings.Server && isLive(stateOf(t)), this::publish));
        g.add(action(BootServersBundle.message("servers.action.remove.was"), AllIcons.General.Remove, t -> t instanceof WasSettings.Server && !isLive(stateOf(t)), this::removeWas));
        g.add(action(BootServersBundle.message("servers.action.log.files"), AllIcons.FileTypes.Text, t -> t instanceof WasSettings.Server, this::showLogFiles));
        return g;
    }

    private AnAction reloadAction() {
        return new AnAction(BootServersBundle.message("servers.action.reload"), null, AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                reload();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
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
        tree.clearSelection();
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

    /** 마지막으로 고른 줄(묶음 줄이면 null). 오른쪽 칸은 이 줄을 보인다. */
    private Object selectedRow() {
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null || paths.length == 0) {
            return null;
        }
        Object row = ((DefaultMutableTreeNode) paths[paths.length - 1].getLastPathComponent()).getUserObject();
        return row instanceof String ? null : row;
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

    /** 우클릭 · 「더 보기」 의 로그 파일 보기: 고른 파일을 실행 창 탭에서 보인다. */
    private void showLogFiles(Object target) {
        WasSettings.Server s = (WasSettings.Server) target;
        showLogFiles(s, p -> LogFileView.open(project, s.name, p));
    }

    /** WAS 의 로그 파일 목록을 띄우고, 고른 파일을 open 에 넘긴다. 폴더 읽기는 풀 스레드에서 한다. */
    private void showLogFiles(WasSettings.Server s, Consumer<Path> open) {
        List<Path> roots;
        try {
            roots = WasLogFiles.roots(s.kind(), WasRunner.getInstance(project).runDir(s), Path.of(s.home), s.jeusDomain, s.jeusServer);
        }
        catch (InvalidPathException e) {
            showLogFolderError(s, e);
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> findLogFiles(s, roots, open));
    }

    /** 풀 스레드에서 로그 파일을 찾아 화면 스레드에서 목록을 띄운다. */
    private void findLogFiles(WasSettings.Server s, List<Path> roots, Consumer<Path> open) {
        List<Path> files;
        try {
            files = WasLogFiles.find(roots);
        }
        catch (IOException | InvalidPathException e) {
            ApplicationManager.getApplication().invokeLater(() -> showLogFolderError(s, e), project.getDisposed());
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> chooseLogFile(s, roots, files, open), project.getDisposed());
    }

    private void showLogFolderError(WasSettings.Server s, Exception e) {
        Messages.showErrorDialog(project, BootServersBundle.message("servers.log.files.read.error", s.name, e.getMessage()), BootServersBundle.message("servers.log.files.title"));
    }

    private void chooseLogFile(WasSettings.Server s, List<Path> roots, List<Path> files, Consumer<Path> open) {
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
                .setItemChosenCallback(open::accept)
                .createPopup()
                .showInFocusCenter();
    }

    // ---- 오른쪽 칸 ----

    /** 아무 줄도 고르지 않았을 때: 가운데 회색 안내 두 줄. */
    private static JComponent emptyCard() {
        JBPanelWithEmptyText p = new JBPanelWithEmptyText();
        p.getEmptyText().setText(BootServersBundle.message("servers.detail.choose"))
                .appendLine(BootServersBundle.message("servers.detail.choose.hint"), SimpleTextAttributes.GRAYED_ATTRIBUTES, null);
        return p;
    }

    /** 고른 서버 칸: 위에 오류 띠 · 머리줄 · 설정(세로로 눌리면 스크롤), 아래에 콘솔 · 로그 파일 탭. 나눔 비율은 기억한다. */
    private JComponent serverCard(ActionToolbar detailToolbar) {
        errorBanner.showCloseButton(false);
        bannerRow.add(errorBanner, BorderLayout.CENTER);
        bannerRow.setBorder(JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP, 0, UIUtil.DEFAULT_HGAP));
        bannerRow.setVisible(false);
        detail.add(bootCard(), "boot");
        detail.add(wasCard(), "was");
        JPanel body = new JPanel(new BorderLayout());
        body.add(header(detailToolbar), BorderLayout.NORTH);
        body.add(detail, BorderLayout.CENTER);
        JPanel stack = new JPanel(new BorderLayout());
        stack.add(bannerRow, BorderLayout.NORTH);
        stack.add(body, BorderLayout.CENTER);
        JPanel top = new JPanel(new BorderLayout());
        top.add(stack, BorderLayout.NORTH);

        tabs.addTab(BootServersBundle.message("servers.tab.console"), consoleHolder);
        tabs.addTab(BootServersBundle.message("servers.tab.log"), logCard());
        OnePixelSplitter split = new OnePixelSplitter(true, DETAIL_PROPORTION);
        split.setSplitterProportionKey(DETAIL_SPLIT_KEY);
        split.setFirstComponent(ScrollPaneFactory.createScrollPane(top, true));
        split.setSecondComponent(tabs);
        return split;
    }

    /** 머리줄: 큰 굵은 이름 + 상태 꼬리표 + 흐린 보조 글(포트 · 프로필 · 실행 시간), 오른쪽 끝에 시작 · 중지 · 편집 단추. 보조 글은 좁으면 줄임표. */
    private JComponent header(ActionToolbar toolbar) {
        title.setFont(JBUI.Fonts.label().asBold().biggerOn(TITLE_FONT_BIGGER));
        status.setForeground(NamedColorUtil.getInactiveTextColor());
        status.setMinimumSize(new Dimension(0, 0));
        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
        head.setBorder(JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP, 0, UIUtil.DEFAULT_HGAP));
        head.add(title);
        head.add(Box.createHorizontalStrut(JBUI.scale(UIUtil.DEFAULT_HGAP)));
        head.add(statePill);
        head.add(Box.createHorizontalStrut(JBUI.scale(UIUtil.DEFAULT_HGAP)));
        head.add(status);
        head.add(Box.createHorizontalGlue());
        head.add(toolbar.getComponent());
        return head;
    }

    private static JBLabel grayLabel(String text) {
        JBLabel label = new JBLabel(text);
        label.setForeground(NamedColorUtil.getInactiveTextColor());
        return label;
    }

    /** 스프링 부트 설정 폼: 이름 · 프로필 · 포트 · VM 옵션, 「적용」. 다음 시작부터 적용된다. */
    private JComponent bootCard() {
        bootName.getEmptyText().setText(BootServersBundle.message("servers.boot.name.hint"));
        profile.setEditable(true);
        vmOptions.getEmptyText().setText(BootServersBundle.message("servers.boot.vm.hint"));
        port.getEmptyText().setText(BootServersBundle.message("servers.boot.port.hint"));
        JButton apply = new JButton(BootServersBundle.message("servers.boot.apply"));
        apply.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true);
        // 2026.2 는 버튼 클릭을 읽기 잠금 없이 처리한다. 설정이 없으면 PSI 로 메인 클래스를 찾으므로 잠금 안에서 돌린다.
        apply.addActionListener(e -> WriteIntentReadAction.run(() -> applyBoot()));
        JPanel applyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        applyRow.add(apply);
        applyRow.add(Box.createHorizontalStrut(JBUI.scale(UIUtil.DEFAULT_HGAP)));
        applyRow.add(grayLabel(BootServersBundle.message("servers.boot.apply.hint")));
        JPanel p = FormBuilder.createFormBuilder()
                .addLabeledComponent(BootServersBundle.message("servers.boot.name"), bootName)
                .addLabeledComponent(BootServersBundle.message("servers.boot.profile"), profile)
                .addLabeledComponent(BootServersBundle.message("servers.boot.port"), port)
                .addLabeledComponent(BootServersBundle.message("servers.boot.vm"), vmOptions)
                .addComponent(applyRow)
                .getPanel();
        p.setBorder(JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP));
        return p;
    }

    /** WAS 설정 요약(보기 전용). 고치기는 머리줄의 편집 단추가 WAS 편집 창을 연다. */
    private JComponent wasCard() {
        wasSummary.setBorder(JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP));
        return wasSummary;
    }

    /** WAS 설정 요약: 회색 이름표 + 값 두 줄 칸, 그 아래 올린 프로젝트를 아이콘 붙은 줄로. */
    private static JComponent wasSummaryOf(WasSettings.Server s) {
        FormBuilder form = FormBuilder.createFormBuilder()
                .addLabeledComponent(grayLabel(BootServersBundle.message("servers.was.kind")), new JBLabel(s.kind().label))
                .addLabeledComponent(grayLabel(BootServersBundle.message("servers.was.home")), valueLabel(s.home))
                .addLabeledComponent(grayLabel(BootServersBundle.message("servers.was.ports.label")),
                        new JBLabel(BootServersBundle.message("servers.was.ports", s.httpPort, s.shutdownPort, s.debugPort)))
                .addLabeledComponent(grayLabel(BootServersBundle.message("servers.boot.vm")),
                        valueLabel(s.vmOptions.isBlank() ? BootServersBundle.message("servers.was.vm.none") : s.vmOptions))
                .addLabeledComponent(grayLabel(BootServersBundle.message("servers.was.reload")),
                        new JBLabel(s.reloadable ? BootServersBundle.message("servers.was.reload.on") : BootServersBundle.message("servers.was.reload.off")))
                .addComponent(new TitledSeparator(BootServersBundle.message("servers.was.deployments")));
        if (s.deployments.isEmpty()) {
            form.addComponent(grayLabel(BootServersBundle.message("servers.was.deployments.none")));
        }
        for (WasSettings.Deployment d : s.deployments) {
            form.addComponent(new JBLabel(BootServersBundle.message("servers.was.deployment", d.module, d.contextPath), AllIcons.Nodes.WebFolder, SwingConstants.LEFT));
        }
        return form.getPanel();
    }

    /** 칸보다 길 수 있는 값(설치 폴더 · VM 옵션). 잘리면 말풍선으로 전체를 보인다. */
    private static JBLabel valueLabel(String text) {
        JBLabel label = new JBLabel(text);
        label.setToolTipText(text);
        return label;
    }

    private void showDetail() {
        Object row = selectedRow();
        detailBoot = row instanceof Boot b ? b : null;
        detailServer = row instanceof Deploy d ? d.server() : row instanceof WasSettings.Server s ? s : null;
        if (detailBoot != null) {
            title.setText(detailBoot.name());
            title.setIcon(bootIcon(detailBoot));
            bootName.setText(detailBoot.name());
            String vm = vmOf(detailBoot);
            profile.setSelectedItem(getProperty(vm, PROFILE_KEY));
            port.setText(getProperty(vm, PORT_KEY));
            vmOptions.setText(vm);
            cards.show(detail, "boot");
        }
        else if (detailServer != null) {
            title.setText(detailServer.name);
            title.setIcon(AllIcons.Webreferences.Server);
            wasSummary.removeAll();
            wasSummary.add(wasSummaryOf(detailServer), BorderLayout.CENTER);
            wasSummary.revalidate();
            wasSummary.repaint();
            cards.show(detail, "was");
        }
        sideCards.show(side, detailBoot == null && detailServer == null ? "none" : "server");
        // 로그 파일 탭은 WAS 만. 다른 줄로 옮기면 보던 파일은 닫는다.
        if (logServer != null && logServer != detailServer) {
            clearLog();
        }
        tabs.setEnabledAt(LOG_TAB, detailServer != null);
        if (detailServer == null && tabs.getSelectedIndex() == LOG_TAB) {
            tabs.setSelectedIndex(CONSOLE_TAB);
        }
        updateStatus();
        syncConsole();
    }

    /** 머리줄의 상태 꼬리표 · 흐린 보조 글(모듈 · 포트 · 프로필 · 실행 시간)과, 실패했으면 위쪽 오류 띠를 맞춘다. */
    private void updateStatus() {
        ServerLauncher.State state;
        ServerRegistry.Run run;
        List<String> parts = new ArrayList<>();
        if (detailBoot != null) {
            run = ServerRegistry.getInstance(project).get(detailBoot.name());
            state = stateOf(detailBoot);
            parts.add(BootServersBundle.message("servers.boot.module", detailBoot.module().getName()));
            if (detailBoot.settings() == null) {
                parts.add(BootServersBundle.message("servers.boot.no.config"));
            }
            else {
                addIfPresent(parts, portOf(detailBoot));
                addIfPresent(parts, profileOf(detailBoot));
                addIfPresent(parts, uptimeOf(detailBoot));
            }
        }
        else if (detailServer != null) {
            run = ServerRegistry.getInstance(project).get(WasRunner.registryKey(detailServer));
            state = stateOf(detailServer);
            parts.add(detailServer.kind().label);
            parts.add(PORT_PREFIX + detailServer.httpPort);
            addIfPresent(parts, uptimeOf(detailServer));
        }
        else {
            return;
        }
        showState(statePill, state);
        String text = String.join(JOIN, parts);
        status.setText(text);
        status.setToolTipText(text);
        String error = state == ServerLauncher.State.FAILED ? (run != null && run.error != null ? run.error : state.label()) : null;
        if (error != null) {
            errorBanner.setMessage(error);
        }
        if (bannerRow.isVisible() != (error != null)) {
            bannerRow.setVisible(error != null);
            bannerRow.revalidate();
        }
    }

    private static void addIfPresent(List<String> parts, String text) {
        if (!text.isEmpty()) {
            parts.add(text);
        }
    }

    /** 「:포트」. 실행 중이면 콘솔에서 읽은 포트, 아니면 VM 옵션의 server.port. 없으면 빈 글. */
    private String portOf(Boot b) {
        if (b.settings() == null) {
            return "";
        }
        ServerRegistry.Run run = ServerRegistry.getInstance(project).get(b.name());
        if (shownState(b) == ServerLauncher.State.RUNNING && run != null && run.port != null) {
            return PORT_PREFIX + run.port;
        }
        String configured = getProperty(vmOf(b), PORT_KEY);
        return configured.isEmpty() ? "" : PORT_PREFIX + configured;
    }

    /** VM 옵션의 spring.profiles.active. 없으면 빈 글. */
    private static String profileOf(Boot b) {
        return b.settings() == null ? "" : getProperty(vmOf(b), PROFILE_KEY);
    }

    /** 실행 중인 줄(Boot · WAS)의 실행 시간. 아니면 빈 글. */
    private String uptimeOf(Object row) {
        if (shownState(row) != ServerLauncher.State.RUNNING) {
            return "";
        }
        String key = row instanceof Boot b ? b.name() : WasRunner.registryKey((WasSettings.Server) row);
        ServerRegistry.Run run = ServerRegistry.getInstance(project).get(key);
        return run == null ? "" : uptime(run.startMillis);
    }

    // ---- 아래 탭: 콘솔 · 로그 파일 ----

    /** 고른 줄의 살아 있는 프로세스. 없거나 끝났으면 null. */
    private ProcessHandler handlerOf(Object target) {
        if (target instanceof Boot b) {
            List<RunContentDescriptor> descriptors = b.settings() == null ? List.of() : ServerLauncher.runningDescriptors(b.settings());
            return descriptors.isEmpty() ? null : descriptors.get(0).getProcessHandler();
        }
        return WasRunner.getInstance(project).handler((WasSettings.Server) target);
    }

    /**
     * 콘솔 탭을 고른 줄의 프로세스에 맞춘다. 줄이 바뀌거나 새 프로세스가 뜨면 콘솔을 새로 만들어 붙이고,
     * 프로세스가 끝나도 마지막 출력(실패 이유)은 남긴다. 실행 창 콘솔과 같은 프로세스에 따로 붙으므로 붙기 전 출력은 안 보인다.
     * shortcut: WAS 는 startNotify 뒤 다음 tick 에 붙어 첫 1초 출력을 놓친다, 거슬리면 WasRunner 가 시작 알림을 주게 한다.
     */
    private void syncConsole() {
        Object target = detailBoot != null ? detailBoot : detailServer;
        ProcessHandler handler = target == null ? null : handlerOf(target);
        boolean sameTarget = target == null ? consoleTarget == null : consoleTarget != null && same(target, consoleTarget);
        if (sameTarget && (handler == null || handler == consoleHandler)) {
            return;
        }
        if (console != null) {
            Disposer.dispose(console);
            console = null;
        }
        consoleHolder.removeAll();
        consoleTarget = target;
        consoleHandler = handler;
        if (target != null) {
            console = TextConsoleBuilderFactory.getInstance().createBuilder(project).filters(new LogLevelFilter()).getConsole();
            Disposer.register(parent, console);
            DefaultActionGroup actions = new DefaultActionGroup();
            JPanel panel = ConsolePanel.create(console, actions);
            ConsolePanel.addConsoleActions(console, actions);
            if (handler != null) {
                console.attachToProcess(handler);
            }
            else {
                console.print(BootServersBundle.message("servers.console.idle") + "\n", ConsoleViewContentType.SYSTEM_OUTPUT);
            }
            consoleHolder.add(panel, BorderLayout.CENTER);
        }
        consoleHolder.revalidate();
        consoleHolder.repaint();
    }

    /** 「로그 파일」 탭: 위에 고르기 링크 · 고른 파일 경로, 아래에 그 파일 끝을 따라가는 콘솔. */
    private JComponent logCard() {
        ActionLink choose = new ActionLink(BootServersBundle.message("servers.log.choose"), e -> {
            if (detailServer != null) {
                showLogFiles(detailServer, this::showLogInTab);
            }
        });
        logFileName.setForeground(NamedColorUtil.getInactiveTextColor());
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(UIUtil.DEFAULT_HGAP), 0));
        row.setBorder(JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP / 2));
        row.add(choose);
        row.add(logFileName);
        JPanel p = new JPanel(new BorderLayout());
        p.add(row, BorderLayout.NORTH);
        p.add(logHolder, BorderLayout.CENTER);
        clearLog();
        return p;
    }

    /** 보던 로그 파일을 닫고 안내만 보인다. */
    private void clearLog() {
        if (logView != null) {
            Disposer.dispose(logView);
            logView = null;
        }
        logServer = null;
        logFileName.setText("");
        logFileName.setToolTipText(null);
        logHolder.removeAll();
        JBPanelWithEmptyText hint = new JBPanelWithEmptyText();
        hint.getEmptyText().setText(BootServersBundle.message("servers.log.choose.hint"));
        logHolder.add(hint, BorderLayout.CENTER);
        logHolder.revalidate();
        logHolder.repaint();
    }

    /** 고른 로그 파일을 「로그 파일」 탭에서 따라간다. */
    private void showLogInTab(Path file) {
        if (detailServer == null) {
            return;
        }
        clearLog();
        logView = LogFileView.view(project, file);
        Disposer.register(parent, logView);
        logServer = detailServer;
        logFileName.setText(file.toString());
        logFileName.setToolTipText(file.toString());
        logHolder.removeAll();
        logHolder.add(logView.panel(), BorderLayout.CENTER);
        logHolder.revalidate();
        logHolder.repaint();
        tabs.setSelectedIndex(LOG_TAB);
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
        tree.clearSelection();
        select(applied);
        showDetail();
    }

    private static String vmOf(Boot b) {
        String vm = b.settings() == null ? null : ((ApplicationConfiguration) b.settings().getConfiguration()).getVMParameters();
        return vm == null ? "" : vm;
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

    // ---- 트리 ----

    /**
     * 트리 줄 그리기. 묶음 줄은 굵은 이름 + 회색 개수. 서버 줄은 상태 점(실행 초록 · 시작 중 노랑 · 실패 빨강 · 중지 회색) + 이름 + 회색 「:포트」.
     * 실행 설정 이름 끝 괄호는 회색, 자리 줄은 회색 기울임 + 「설정 없음」. 올린 프로젝트 줄은 폴더 아이콘 + 이름 + 회색 경로, WAS 가 실행 중이면 「올라감」.
     */
    private final class NodeRenderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object r = node.getUserObject();
            if (r instanceof String label) {
                append(label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                append(" " + node.getChildCount(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
            else if (r instanceof Deploy d) {
                setIcon(AllIcons.Nodes.WebFolder);
                append(d.deployment().module);
                append(" " + d.deployment().contextPath, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                if (shownState(d) == ServerLauncher.State.RUNNING) {
                    append(JOIN + BootServersBundle.message("servers.deploy.up"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
            }
            else if (r instanceof WasSettings.Server s) {
                setIcon(new DotIcon(stateColor(shownState(s))));
                append(s.name);
                append(" " + PORT_PREFIX + s.httpPort, SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
            else if (r instanceof Boot b) {
                setIcon(new DotIcon(stateColor(shownState(b))));
                if (b.settings() == null) {
                    append(b.name(), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                    append(" " + BootServersBundle.message("servers.boot.no.config.short"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                    return;
                }
                String name = b.name();
                int suffix = name.lastIndexOf(SUFFIX_OPEN);
                if (suffix > 0 && name.endsWith(SUFFIX_CLOSE)) {
                    append(name.substring(0, suffix));
                    append(name.substring(suffix), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
                else {
                    append(name);
                }
                String port = portOf(b);
                if (!port.isEmpty()) {
                    append(" " + port, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            }
        }
    }

    /** 상태 점 아이콘. 고른 줄에서도 색이 남게 글이 아니라 아이콘으로 그린다. */
    private static final class DotIcon implements Icon {
        private static final int SIZE = 16;
        private static final int DOT = 8;
        private final Color color;

        DotIcon(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                int d = JBUI.scale(DOT);
                int off = (getIconWidth() - d) / 2;
                g2.fillOval(x + off, y + off, d, d);
            }
            finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return JBUI.scale(SIZE);
        }

        @Override
        public int getIconHeight() {
            return JBUI.scale(SIZE);
        }
    }

    private static Icon bootIcon(Boot b) {
        return b.settings() == null ? IconLoader.getDisabledIcon(AllIcons.RunConfigurations.Application) : AllIcons.RunConfigurations.Application;
    }

    /** 상태 색: 실행 중 · 시작 중 · 실패는 테마의 성공 · 경고 · 오류 글 색, 중지됨은 흐린 글 색. */
    private static Color stateColor(ServerLauncher.State state) {
        return switch (state) {
            case RUNNING -> UIUtil.getLabelSuccessForeground();
            case STARTING -> JBUI.CurrentTheme.Label.warningForeground();
            case FAILED -> JBUI.CurrentTheme.Label.errorForeground();
            case STOPPED -> NamedColorUtil.getInactiveTextColor();
        };
    }

    /** 상태 꼬리표: 실행 중 · 시작 중 · 실패는 테마 바탕 위 상태 색, 중지됨은 바탕 없이 흐린 글 색. */
    private static void showState(Pill pill, ServerLauncher.State state) {
        String text = STATE_DOT + state.label();
        switch (state) {
            case RUNNING -> pill.show(text, stateColor(state), RUNNING_BG);
            case STARTING -> pill.show(text, stateColor(state), STARTING_BG);
            case FAILED -> pill.show(text, stateColor(state), FAILED_BG);
            case STOPPED -> pill.show(state.label(), stateColor(state), null);
        }
    }
}
