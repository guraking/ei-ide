package local.bootservers;

import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.execution.ui.RunnerLayoutUi;
import com.intellij.icons.AllIcons;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.JBColor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.content.Content;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.HyperlinkAdapter;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultListModel;
import javax.swing.JEditorPane;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.event.HyperlinkEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 서버 콘솔 옆 「오류」 칸: 왼쪽 오류 목록(같은 오류는 한 줄 · 횟수, 새것이 위), 오른쪽 고른 오류 설명.
 * 설명 = 한국어 설명과 예 · 가장 안쪽 원인 · 우리 코드 줄(누르면 그 줄로) · 요청(API 요청 보내기로 다시 보내기) ·
 * 값을 채운 SQL(DB 콘솔로 · 복사) · 시작 실패 안내 · 우리 코드만 남긴 스택(라이브러리 줄은 접음).
 * 위 도구줄은 콘솔에서 보기 · 오류 복사 · 목록 비우기. 칸 사이는 끌 수 있고 비율을 기억한다. 화면 스레드에서만 쓴다.
 */
final class ErrorPanel extends JPanel {

    private static final Logger LOG = Logger.getInstance(ErrorPanel.class);
    private static final String TAB_ID = "MoaErrors";
    private static final int FIND_TRIES = 10;
    private static final int FIND_DELAY_MS = 300;
    private static final String SPLIT_KEY = "Moa.ErrorPanel.split";
    private static final float SPLIT_PROPORTION = 0.38f;
    /** ei-db 의 「쿼리 콘솔로 보내기」(선택 글을 고른 DB 콘솔에 붙인다). ei-db 가 없으면 링크를 숨긴다. */
    private static final String DB_CONSOLE_ACTION = "EiDb.MyBatis.LogSend";
    private static final String TOOLBAR_PLACE = "MoaErrorPanel";
    /** 목록 한 줄에 보일 원인 글자 수. */
    private static final int MAX_CAUSE_CHARS = 200;
    /** 복사할 때 붙일 스택 줄 수. */
    private static final int COPY_STACK_LINES = 40;
    /** plugin.xml 의 notificationGroup id. 설정 → 알림에서 끌 수 있다. */
    private static final String NOTIFICATION_GROUP = "서버 오류";
    /** 오류 알림 사이의 최소 간격. 오류가 쏟아져도 이 사이에는 알림을 하나만 띄운다. */
    private static final long NOTIFY_GAP_MILLIS = 30_000;
    private static final String LINK_CODE = "code";
    private static final String LINK_FRAME = "frame:";
    private static final String LINK_LOGGER = "logger";
    private static final String LINK_MAPPER = "mapper";
    private static final String XML_EXTENSION = "xml";
    /** 매퍼를 찾을 때 이보다 큰 XML 은 읽지 않는다(자료 파일). */
    private static final long MAX_MAPPER_XML_BYTES = 2_000_000;
    private static final String LINK_API = "api";
    private static final String LINK_SQL_CONSOLE = "sqlConsole";
    private static final String LINK_SQL_COPY = "sqlCopy";
    private static final String LINK_CONSOLE = "console";
    // 「{id:[7], name:[a, b]}」 의 한 칸(스프링 DispatcherServlet DEBUG 의 parameters)
    private static final Pattern SPRING_PARAM = Pattern.compile("([^\\s,{}:\\[\\]]+):\\[([^\\]]*)]");
    private static final String API_SOURCE = "로그";

    private final Project project;
    private final ErrorWatcher watcher;
    private final Supplier<Editor> consoleEditor;
    private final Runnable showConsole;
    private final ServerRegistry.Run run;
    private final IntConsumer countShown;
    private final String serverName;
    private final Runnable reveal;
    // 지난 목록의 오류 횟수 합. 늘었으면 새 오류가 난 것이다.
    private int lastTotal;
    private long lastNotifiedMillis;
    private Notification lastNotification;
    private final DefaultListModel<ErrorScan.Item> model = new DefaultListModel<>();
    private final JBList<ErrorScan.Item> list = new JBList<>(model);
    private final JEditorPane detail = new JEditorPane();
    private final JBLabel status = new JBLabel();
    // 목록을 다시 채우는 중이면 참(고르기 알림으로 설명을 여러 번 그리지 않는다).
    private boolean updating;
    // 지금 설명에 그린 오류. 없으면 null.
    private ErrorScan.Item shownItem;
    // 프로젝트 소스의 클래스로 확인된 로거 이름. 이 로거의 오류에만 「로그를 찍은 곳」 줄을 보인다.
    private final Set<String> projectLoggers = new HashSet<>();

    /**
     * @param consoleEditor 콘솔 편집기(닫혔으면 null)
     * @param showConsole   콘솔을 앞에 보이게 한다(부트 실행 창은 콘솔 탭을 고른다). 콘솔이 옆에 있으면 아무것도 안 한다
     * @param run           포트를 읽을 실행 기록. 없으면 null(API 다시 보내기를 못 한다)
     * @param countShown    오류 종류 수가 바뀌면 불린다(탭 이름 등)
     * @param serverName    알림 제목에 쓸 서버 이름
     * @param reveal        이 칸을 화면 앞에 보이게 한다(알림의 「오류 보기」)
     */
    ErrorPanel(Project project, ErrorWatcher watcher, Supplier<Editor> consoleEditor, Runnable showConsole,
               ServerRegistry.Run run, IntConsumer countShown, String serverName, Runnable reveal) {
        super(new BorderLayout());
        this.project = project;
        this.watcher = watcher;
        this.consoleEditor = consoleEditor;
        this.showConsole = showConsole;
        this.run = run;
        this.countShown = countShown;
        this.serverName = serverName;
        this.reveal = reveal;

        list.getEmptyText().setText(BootServersBundle.message("errors.empty"));
        list.setCellRenderer(new ItemRenderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !updating) {
                showDetail();
            }
        });

        detail.setEditorKit(HTMLEditorKitBuilder.simple());
        detail.setEditable(false);
        detail.setBorder(JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP));
        detail.addHyperlinkListener(new HyperlinkAdapter() {
            @Override
            protected void hyperlinkActivated(@NotNull HyperlinkEvent e) {
                linkClicked(e.getDescription());
            }
        });

        OnePixelSplitter split = new OnePixelSplitter(false, SPLIT_KEY, SPLIT_PROPORTION);
        split.setHonorComponentsMinimumSize(false);
        split.setFirstComponent(ScrollPaneFactory.createScrollPane(list, true));
        split.setSecondComponent(ScrollPaneFactory.createScrollPane(detail, true));

        DefaultActionGroup actions = new DefaultActionGroup(
                action("errors.action.console", AllIcons.Actions.Find, this::jumpToConsole),
                action("errors.action.copy", AllIcons.Actions.Copy, this::copyAll),
                action("errors.action.clear", AllIcons.Actions.GC, watcher::clear));
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, actions, true);
        toolbar.setTargetComponent(this);
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(JBUI.Borders.customLineBottom(JBColor.border()));
        top.add(toolbar.getComponent(), BorderLayout.WEST);
        status.setForeground(NamedColorUtil.getInactiveTextColor());
        status.setBorder(JBUI.Borders.emptyRight(UIUtil.DEFAULT_HGAP));
        top.add(status, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        showDetail();
        watcher.listen(this::update);
    }

    /**
     * 애플리케이션 실행(스프링 부트) 창의 콘솔 탭 옆에 「오류」 탭을 붙인다. 출력은 지금부터 읽고, 실행 창은 화면 스레드에서 찾는다.
     * 실행 창(RunnerLayoutUi)을 못 찾으면 탭 없이 읽기만 멈춘다. 탭은 실행 창과 함께 닫힌다.
     */
    static void attachToRun(Project project, ProcessHandler handler, ServerRegistry.Run run, String serverName) {
        attachLater(project, handler, run, serverName, new ErrorWatcher(handler), FIND_TRIES);
    }

    /** 실행 창이 아직 실행 창 목록에 없을 수 있어 FIND_DELAY_MS 간격으로 tries 번까지 찾는다. 끝내 없으면 읽기를 멈춘다. */
    private static void attachLater(Project project, ProcessHandler handler, ServerRegistry.Run run, String serverName,
                                    ErrorWatcher watcher, int tries) {
        ApplicationManager.getApplication().invokeLater(() -> {
            RunContentDescriptor descriptor = null;
            for (RunContentDescriptor d : RunContentManager.getInstance(project).getAllDescriptors()) {
                if (d.getProcessHandler() == handler) {
                    descriptor = d;
                }
            }
            if (descriptor == null && tries > 1) {
                AppExecutorUtil.getAppScheduledExecutorService().schedule(
                        () -> attachLater(project, handler, run, serverName, watcher, tries - 1), FIND_DELAY_MS, TimeUnit.MILLISECONDS);
                return;
            }
            RunContentDescriptor shown = descriptor;
            RunnerLayoutUi ui = descriptor == null ? null : descriptor.getRunnerLayoutUi();
            if (ui == null || Disposer.isDisposed(descriptor)) {
                LOG.info("오류 탭을 붙일 실행 창을 찾지 못함: " + (descriptor == null ? "없음" : descriptor.getDisplayName()));
                Disposer.dispose(watcher);
                return;
            }
            Disposer.register(descriptor, watcher);
            ExecutionConsole console = descriptor.getExecutionConsole();
            String title = BootServersBundle.message("errors.tab");
            Content[] tab = new Content[1];
            ErrorPanel panel = new ErrorPanel(project, watcher,
                    () -> console instanceof ConsoleViewImpl impl ? impl.getEditor() : null,
                    () -> {
                        Content consoleTab = ui.findContent(ExecutionConsole.CONSOLE_CONTENT_ID);
                        if (consoleTab != null) {
                            ui.selectAndFocus(consoleTab, true, false);
                        }
                    },
                    run,
                    count -> tab[0].setDisplayName(count == 0 ? title : BootServersBundle.message("errors.tab.count", count)),
                    serverName,
                    () -> {
                        if (revealRun(project, shown)) {
                            ui.selectAndFocus(tab[0], true, false);
                        }
                    });
            tab[0] = ui.createContent(TAB_ID, panel, title, null, panel.list);
            tab[0].setCloseable(false);
            ui.addContent(tab[0]);
        }, ModalityState.nonModal(), o -> project.isDisposed() && disposeWatcher(watcher));
    }

    /**
     * 실행 창(실행 · 디버그 도구 창)을 열고 그 실행의 탭을 고른다. 화면 스레드에서 부른다.
     * @return 열었으면 참. 이미 닫힌 실행이면 아무것도 안 하고 거짓
     */
    static boolean revealRun(Project project, RunContentDescriptor descriptor) {
        if (project.isDisposed() || descriptor == null || Disposer.isDisposed(descriptor)) {
            return false;
        }
        RunContentManager manager = RunContentManager.getInstance(project);
        ToolWindow window = manager.getToolWindowByDescriptor(descriptor);
        if (window == null) {
            return false;
        }
        window.activate(null);
        manager.selectRunContent(descriptor);
        return true;
    }

    /**
     * 이 칸이 화면에 안 보일 때 새 오류가 나면 알림을 띄운다(제목 = 서버 이름 · 오류 횟수 합, 글 = 가장 새 오류, 「오류 보기」 로 이 칸을 연다).
     * 알림은 NOTIFY_GAP_MILLIS 에 하나만 띄우고 앞 알림은 지운다.
     */
    private void notifyNew(List<ErrorScan.Item> items) {
        int total = 0;
        for (ErrorScan.Item item : items) {
            total += item.count();
        }
        boolean more = total > lastTotal;
        lastTotal = total;
        long now = System.currentTimeMillis();
        if (!more || isShowing() || now - lastNotifiedMillis < NOTIFY_GAP_MILLIS || project.isDisposed()) {
            return;
        }
        lastNotifiedMillis = now;
        if (lastNotification != null) {
            lastNotification.expire();
        }
        ErrorReport newest = items.get(items.size() - 1).last();
        String title = newest.explain() != null ? newest.explain().title() + " · " : "";
        lastNotification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(BootServersBundle.message("errors.notify.title", esc(serverName), total),
                        esc(title + StringUtil.first(newest.cause().replace('\n', ' '), MAX_CAUSE_CHARS, true)), NotificationType.ERROR)
                .addAction(NotificationAction.createSimpleExpiring(BootServersBundle.message("errors.notify.show"), reveal));
        lastNotification.notify(project);
    }

    /** 프로젝트가 닫혀 실행 창을 찾지 않게 되면 읽기도 멈춘다(invokeLater 의 그만두기 조건으로 쓴다). */
    private static boolean disposeWatcher(ErrorWatcher watcher) {
        Disposer.dispose(watcher);
        return true;
    }

    /** 고른 오류가 있을 때만 켜지는 도구줄 단추. */
    private AnAction action(String key, javax.swing.Icon icon, Runnable run) {
        return new DumbAwareAction(BootServersBundle.message(key), null, icon) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                run.run();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(!model.isEmpty());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
    }

    /** 새 목록으로 바꾼다. 새것이 위다. 고른 오류(열쇠)는 그대로 두고, 고른 것이 없으면 맨 위를 고른다. */
    private void update(List<ErrorScan.Item> items) {
        ErrorScan.Item selected = list.getSelectedValue();
        String selectedKey = selected == null ? null : selected.key();
        int select = items.isEmpty() ? -1 : 0;
        updating = true;
        try {
            model.clear();
            for (int i = items.size() - 1; i >= 0; i--) {
                ErrorScan.Item item = items.get(i);
                model.addElement(item);
                if (item.key().equals(selectedKey)) {
                    select = model.size() - 1;
                }
            }
            if (select >= 0) {
                list.setSelectedIndex(select);
                list.ensureIndexIsVisible(select);
            }
        }
        finally {
            updating = false;
        }
        showDetail();
        countShown.accept(items.size());
        notifyNew(items);
    }

    /** 고른 오류의 설명을 그린다. 같은 오류가 그대로면(새로 나지 않았으면) 다시 그리지 않아 읽던 자리를 지킨다. */
    private void showDetail() {
        ErrorScan.Item item = list.getSelectedValue();
        if (shownItem != null && item != null && shownItem.last() == item.last() && shownItem.count() == item.count()) {
            return;
        }
        shownItem = item;
        drawDetail();
        resolveLogger(item);
    }

    private void drawDetail() {
        ErrorScan.Item item = shownItem;
        detail.setText(item == null ? html(esc(BootServersBundle.message(model.isEmpty() ? "errors.empty" : "errors.pick"))) : detailHtml(item));
        detail.setCaretPosition(0);
    }

    /**
     * 스택 없는 오류의 로거가 프로젝트 소스의 클래스인지 뒤에서 확인하고, 맞으면 「로그를 찍은 곳」 줄을 넣어 다시 그린다.
     * 클래스가 아닌 로거 이름(「audit」) · 라이브러리 로거(줄여 찍힌 「o.s.w.s.DispatcherServlet」 포함)는 줄이 나오지 않는다.
     */
    private void resolveLogger(ErrorScan.Item item) {
        String logger = item == null || item.last().frame() != null ? null : item.last().logger();
        if (logger == null || projectLoggers.contains(logger) || ErrorRules.isFramework(logger)) {
            return;
        }
        ReadAction.nonBlocking(() -> loggerFile(logger) != null)
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), (Boolean found) -> {
                    if (found && shownItem == item) {
                        projectLoggers.add(logger);
                        drawDetail();
                    }
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /** 로거 이름의 클래스가 프로젝트 소스에 있으면 그 파일, 아니면 null. 읽기 동작 안에서 부른다. */
    private VirtualFile loggerFile(String logger) {
        VirtualFile file = classFile(logger, true);
        return file != null && ProjectFileIndex.getInstance(project).isInSourceContent(file) ? file : null;
    }

    /**
     * 제목 줄 아래에 「이름 칸 · 내용 칸」 두 칸 표로 그린다(이름 칸은 회색, 내용 칸 왼쪽이 한 줄로 맞는다). 내용이 없는 줄은 넣지 않는다.
     */
    private String detailHtml(ErrorScan.Item item) {
        ErrorReport r = item.last();
        StringBuilder b = new StringBuilder();
        Color error = NamedColorUtil.getErrorForeground();
        String title = r.explain() != null ? r.explain().title()
                : r.startup() ? BootServersBundle.message("errors.startup") : BootServersBundle.message("errors.unknown");
        b.append("<p><font size='+1' color='").append(hex(error)).append("'>").append(esc(title)).append("</font>");
        if (item.count() > 1) {
            b.append(gray("  " + BootServersBundle.message("errors.count", item.count(), StringUtil.notNullize(item.firstTime()),
                    StringUtil.notNullize(r.time()))));
        }
        b.append("</p><table cellpadding='0' cellspacing='0'>");
        if (r.explain() != null) {
            String example = r.explain().example().isEmpty() ? ""
                    : "<br>" + gray(BootServersBundle.message("errors.example")) + ' ' + code(r.explain().example());
            section(b, "errors.explain", esc(r.explain().text()) + example);
        }
        section(b, "errors.cause", esc(r.cause()));
        if (r.startup() && r.action() != null) {
            section(b, "errors.spring.action", esc(r.action()));
        }
        if (r.frame() != null) {
            section(b, "errors.our.code", link(LINK_CODE, r.frame().text()));
        }
        else if (r.logger() != null && projectLoggers.contains(r.logger())) {
            section(b, "errors.logged.at", link(LINK_LOGGER, r.logger()));
        }
        if (r.mapperId() != null) {
            section(b, "errors.mapper", link(LINK_MAPPER, r.mapperId()));
        }
        if (r.path() != null) {
            String request = (r.method() == null ? "" : r.method() + " ") + r.path() + (r.params() == null ? "" : "  " + r.params());
            String send = run == null || run.port == null ? gray(BootServersBundle.message("errors.api.no.port"))
                    : link(LINK_API, BootServersBundle.message("errors.api.send"));
            String where = r.thread() == null ? "" : gray(BootServersBundle.message("errors.request.thread", r.thread())) + "<br>";
            section(b, "errors.request", code(request) + "<br>" + where + send);
        }
        if (r.sql() != null) {
            String filled = SqlFill.fill(r.sql(), r.sqlParams());
            StringBuilder sql = new StringBuilder("<pre>").append(esc(filled != null ? filled : r.sql())).append("</pre>");
            if (filled == null && r.sqlParams() != null) {
                sql.append(gray(BootServersBundle.message("errors.sql.unfilled"))).append("<br>");
            }
            if (ActionManager.getInstance().getAction(DB_CONSOLE_ACTION) != null) {
                sql.append(link(LINK_SQL_CONSOLE, BootServersBundle.message("errors.sql.console"))).append(" &nbsp; ");
            }
            sql.append(link(LINK_SQL_COPY, BootServersBundle.message("errors.sql.copy")));
            section(b, filled != null ? "errors.sql.filled" : "errors.sql", sql.toString());
        }
        // 한 줄뿐인 오류(스택 없음)는 위 「원인」 과 같은 글이라 스택 줄을 넣지 않는다.
        if (r.lines().size() > 1) {
            section(b, "errors.stack", stackHtml(r));
        }
        return html(b.append("</table>").append(link(LINK_CONSOLE, BootServersBundle.message("errors.action.console"))).toString());
    }

    /** 예외 · 원인 줄과 우리 코드 줄만 남기고, 이어진 라이브러리 줄은 「… 라이브러리 n줄」 로 접는다. 우리 코드 줄은 누르면 간다. */
    private static String stackHtml(ErrorReport r) {
        StringBuilder b = new StringBuilder("<pre>");
        int hidden = 0;
        List<String> lines = r.lines();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            ErrorReport.Frame f = ErrorReport.frame(l);
            if (f != null && ErrorRules.isFramework(f.className())) {
                hidden++;
                continue;
            }
            if (hidden > 0) {
                b.append(esc("    … " + BootServersBundle.message("errors.stack.hidden", hidden))).append('\n');
                hidden = 0;
            }
            b.append(f != null ? link(LINK_FRAME + i, l.replace("\t", "    ")) : esc(l.replace("\t", "    "))).append('\n');
        }
        if (hidden > 0) {
            b.append(esc("    … " + BootServersBundle.message("errors.stack.hidden", hidden))).append('\n');
        }
        return b.append("</pre>").toString();
    }

    /** 표 한 줄: 왼쪽 회색 이름(줄 바꿈 없음), 오른쪽 내용. */
    private static void section(StringBuilder b, String key, String body) {
        b.append("<tr><td valign='top' nowrap>").append(gray(BootServersBundle.message(key))).append("&nbsp;&nbsp;&nbsp;</td><td valign='top'>")
                .append(body).append("<br>&nbsp;</td></tr>");
    }

    private void linkClicked(String href) {
        ErrorScan.Item item = list.getSelectedValue();
        if (item == null || href == null) {
            return;
        }
        ErrorReport r = item.last();
        if (href.equals(LINK_CODE) && r.frame() != null) {
            openFrame(r.frame());
        }
        else if (href.equals(LINK_LOGGER) && r.logger() != null) {
            openLogLine(r);
        }
        else if (href.equals(LINK_MAPPER) && r.mapperId() != null) {
            openMapper(r.mapperId());
        }
        else if (href.startsWith(LINK_FRAME)) {
            int index = StringUtil.parseInt(href.substring(LINK_FRAME.length()), -1);
            ErrorReport.Frame f = index >= 0 && index < r.lines().size() ? ErrorReport.frame(r.lines().get(index)) : null;
            if (f != null) {
                openFrame(f);
            }
        }
        else if (href.equals(LINK_API)) {
            resend(r);
        }
        else if (href.equals(LINK_SQL_CONSOLE)) {
            sendToDbConsole(sqlOf(r));
        }
        else if (href.equals(LINK_SQL_COPY)) {
            copy(sqlOf(r));
        }
        else if (href.equals(LINK_CONSOLE)) {
            jumpToConsole();
        }
    }

    private static String sqlOf(ErrorReport r) {
        String filled = SqlFill.fill(r.sql(), r.sqlParams());
        return filled != null ? filled : r.sql();
    }

    /** 스택 줄의 클래스를 프로젝트(없으면 라이브러리 소스)에서 찾아 그 줄을 연다. 색인 중이면 끝난 뒤에 찾는다. */
    private void openFrame(ErrorReport.Frame frame) {
        ReadAction.nonBlocking(() -> classFile(frame.className(), false))
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), (VirtualFile file) -> {
                    if (file == null) {
                        status.setText(BootServersBundle.message("errors.class.not.found", frame.className()));
                        return;
                    }
                    new OpenFileDescriptor(project, file, Math.max(0, frame.line() - 1), 0).navigate(true);
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /**
     * 클래스 이름(안쪽 클래스 포함)의 소스 파일. 읽기 동작 안에서 부른다. 못 찾으면 null.
     * @param abbreviated 참이면 이름이 「c.s.s.SuppApplication」 처럼 줄여 찍힌 로거 이름일 수 있다:
     *                    온 이름으로 못 찾으면 프로젝트에 그 짧은 이름의 클래스가 하나뿐일 때 그것을 쓴다
     */
    private VirtualFile classFile(String className, boolean abbreviated) {
        int inner = className.indexOf('$');
        String name = inner < 0 ? className : className.substring(0, inner);
        JavaPsiFacade psi = JavaPsiFacade.getInstance(project);
        PsiClass found = psi.findClass(name, GlobalSearchScope.projectScope(project));
        if (found == null) {
            found = psi.findClass(name, GlobalSearchScope.allScope(project));
        }
        if (found == null && abbreviated) {
            PsiClass[] byName = PsiShortNamesCache.getInstance(project)
                    .getClassesByName(name.substring(name.lastIndexOf('.') + 1), GlobalSearchScope.projectScope(project));
            found = byName.length == 1 ? byName[0] : null;
        }
        PsiElement nav = found == null ? null : found.getNavigationElement();
        PsiFile file = nav == null ? null : nav.getContainingFile();
        return file == null ? null : file.getVirtualFile();
    }

    /** 스택 없는 오류: 로그를 찍은 클래스에서 그 글을 찍는 줄을 찾아 연다. 줄을 못 찾으면 클래스 맨 위를 연다. */
    private void openLogLine(ErrorReport r) {
        String logger = r.logger();
        String message = r.rootMessage();
        ReadAction.nonBlocking(() -> {
                    VirtualFile file = loggerFile(logger);
                    Document doc = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
                    return file == null ? null : new OpenFileDescriptor(project, file,
                            Math.max(0, doc == null ? 0 : SourceFind.logLine(doc.getText(), message)), 0);
                })
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), (OpenFileDescriptor at) -> {
                    if (at == null) {
                        status.setText(BootServersBundle.message("errors.class.not.found", logger));
                        return;
                    }
                    at.navigate(true);
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /**
     * 매퍼 문장 id(「com.x.UserMapper.selectUser」)의 XML 문장을 연다. namespace 가 맞는 XML 을 이름이 같은 파일에서 먼저,
     * 없으면 프로젝트의 모든 XML 에서 찾는다. 문장이 없으면(Invalid bound statement) 그 XML 맨 위, XML 도 없으면 매퍼 인터페이스를 연다.
     */
    private void openMapper(String mapperId) {
        int dot = mapperId.lastIndexOf('.');
        String namespace = mapperId.substring(0, dot);
        String statement = mapperId.substring(dot + 1);
        ReadAction.nonBlocking(() -> {
                    GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
                    List<VirtualFile> candidates = new ArrayList<>(FilenameIndex.getVirtualFilesByName(
                            namespace.substring(namespace.lastIndexOf('.') + 1) + "." + XML_EXTENSION, scope));
                    candidates.addAll(FilenameIndex.getAllFilesByExt(project, XML_EXTENSION, scope));
                    for (VirtualFile file : candidates) {
                        ProgressManager.checkCanceled();
                        if (file.getLength() > MAX_MAPPER_XML_BYTES) {
                            continue;
                        }
                        Document doc = FileDocumentManager.getInstance().getDocument(file);
                        if (doc != null && SourceFind.isMapperOf(doc.getText(), namespace)) {
                            return new OpenFileDescriptor(project, file, Math.max(0, SourceFind.statementOffset(doc.getText(), statement)));
                        }
                    }
                    VirtualFile mapper = classFile(namespace, false);
                    return mapper == null ? null : new OpenFileDescriptor(project, mapper);
                })
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), (OpenFileDescriptor at) -> {
                    if (at == null) {
                        status.setText(BootServersBundle.message("errors.mapper.not.found", namespace));
                        return;
                    }
                    at.navigate(true);
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /** 오류 앞 로그의 요청을 「API 요청 보내기」 탭으로 연다. 주소의 쿼리 · 로그의 parameters 를 쿼리 칸으로 넣는다. 본문은 로그에 없어 비운다. */
    private void resend(ErrorReport r) {
        Integer port = run == null ? null : run.port;
        if (port == null) {
            status.setText(BootServersBundle.message("errors.api.no.port"));
            return;
        }
        String path = r.path();
        String query = "";
        int q = path.indexOf('?');
        if (q >= 0) {
            query = path.substring(q + 1);
            path = path.substring(0, q);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (!pair.isEmpty()) {
                int eq = pair.indexOf('=');
                values.put(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1));
            }
        }
        if (r.params() != null) {
            Matcher m = SPRING_PARAM.matcher(r.params());
            while (m.find()) {
                values.putIfAbsent(m.group(1), m.group(2).trim());
            }
        }
        List<ApiRequestEditor.Row> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            rows.add(new ApiRequestEditor.Row(e.getKey(), e.getValue(), API_SOURCE));
        }
        String method = r.method() == null ? "GET" : r.method();
        String url = path.startsWith("http") ? path : HttpText.baseUrl(port, "") + path;
        String title = BootServersBundle.message("errors.api.title", method, path);
        ApiRequestEditor.open(project, new ApiRequestEditor.Request(title, "errors " + method + " " + url, method, url, rows, List.of(), ""));
    }

    /** ei-db 「쿼리 콘솔로 보내기」 를 SQL 만 담은 임시 편집기(전체 선택)로 부른다. 고를 콘솔 · 접속은 ei-db 가 묻는다. */
    private void sendToDbConsole(String sql) {
        AnAction send = ActionManager.getInstance().getAction(DB_CONSOLE_ACTION);
        if (send == null || sql == null) {
            return;
        }
        EditorFactory factory = EditorFactory.getInstance();
        Document doc = factory.createDocument(sql);
        Editor viewer = factory.createViewer(doc, project);
        try {
            viewer.getSelectionModel().setSelection(0, doc.getTextLength());
            DataContext context = SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, project)
                    .add(CommonDataKeys.EDITOR, viewer)
                    .build();
            ActionUtil.invokeAction(send, context, ActionPlaces.UNKNOWN, null, null);
        }
        finally {
            factory.releaseEditor(viewer);
        }
    }

    /** 고른 오류의 첫 줄을 콘솔에서 끝부터 찾아 그 줄로 간다. 콘솔에서 이미 지워졌으면 알린다. */
    private void jumpToConsole() {
        ErrorScan.Item item = list.getSelectedValue();
        Editor editor = consoleEditor.get();
        if (item == null || editor == null || editor.isDisposed()) {
            return;
        }
        String head = item.last().lines().get(0);
        Document doc = editor.getDocument();
        int at = head.isEmpty() ? -1 : doc.getImmutableCharSequence().toString().lastIndexOf(head);
        if (at < 0) {
            status.setText(BootServersBundle.message("errors.console.gone"));
            return;
        }
        showConsole.run();
        int line = doc.getLineNumber(at);
        editor.getCaretModel().moveToOffset(at);
        editor.getSelectionModel().setSelection(doc.getLineStartOffset(line), doc.getLineEndOffset(line));
        editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
        status.setText("");
    }

    private void copyAll() {
        ErrorScan.Item item = list.getSelectedValue();
        if (item != null) {
            ErrorReport r = item.last();
            copy(r.copyText(r.sql() == null ? null : SqlFill.fill(r.sql(), r.sqlParams()), COPY_STACK_LINES));
        }
    }

    private void copy(String text) {
        if (text != null) {
            CopyPasteManager.getInstance().setContents(new StringSelection(text));
            status.setText(BootServersBundle.message("errors.copied"));
        }
    }

    private static String html(String body) {
        return "<html><body>" + body + "</body></html>";
    }

    private static String esc(String text) {
        return StringUtil.escapeXmlEntities(text);
    }

    private static String gray(String text) {
        return "<span style='color:" + hex(NamedColorUtil.getInactiveTextColor()) + "'>" + esc(text) + "</span>";
    }

    private static String code(String text) {
        return "<code>" + esc(text) + "</code>";
    }

    private static String link(String href, String text) {
        return "<a href='" + esc(href) + "'>" + esc(text) + "</a>";
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** 목록 한 줄: 회색 시각 · 오류 이름(설명 제목, 없으면 예외 이름) · 횟수 · 회색 원인. */
    private static final class ItemRenderer extends ColoredListCellRenderer<ErrorScan.Item> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends ErrorScan.Item> list, ErrorScan.Item item, int index,
                                             boolean selected, boolean hasFocus) {
            ErrorReport r = item.last();
            if (r.time() != null) {
                append(r.time() + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
            String name = r.explain() != null ? r.explain().title()
                    : r.rootClass() != null ? r.rootClass().substring(r.rootClass().lastIndexOf('.') + 1)
                    : BootServersBundle.message(r.startup() ? "errors.startup" : "errors.unknown");
            append(name, SimpleTextAttributes.ERROR_ATTRIBUTES);
            if (item.count() > 1) {
                append("  ×" + item.count(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            }
            append("  " + StringUtil.first(r.cause().replace('\n', ' '), MAX_CAUSE_CHARS, true), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            setToolTipText(r.headline());
        }
    }
}
