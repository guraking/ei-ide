package local.bootservers;

import com.intellij.ide.actions.RevealFileAction;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.javadoc.PsiDocComment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.java.JavaModuleSourceRootTypes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Project 메뉴 · 프로젝트 창 우클릭의 「프로그램 목록 엑셀로 내보내기…」.
 * 모듈의 소스 · 리소스 루트(테스트 제외)와 웹 폴더의 모든 파일을 「프로그램 목록」 장에,
 * 스프링 요청 매핑을 「URL 목록」 장에 담아 .xlsx 하나로 저장한다.
 * 프로젝트 창 우클릭에서 부르면 고른 줄(폴더 · 파일, 여러 줄 가능) 안의 것만 담고, Project 메뉴에서 부르거나 프로젝트 폴더를 고르면 전체를 담는다.
 */
public final class ProgramListAction extends DumbAwareAction {

    /** plugin.xml 의 notificationGroup id. */
    static final String NOTIFICATION_GROUP = "프로그램 목록";
    static final String SHEET_PROGRAMS = "프로그램 목록";
    static final String SHEET_URLS = "URL 목록";
    private static final String EXTENSION = "xlsx";
    private static final String FILE_INFIX = "-프로그램목록-";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 모듈 콘텐츠 루트 아래 웹 폴더(소스 루트로 잡히지 않는 JSP · 정적 파일 자리). */
    private static final List<String> WEB_DIRS = List.of("src/main/webapp", "WebContent", "WebRoot", "web");
    private static final char CLASS_METHOD_SEPARATOR = '.';
    private static final Logger LOG = Logger.getInstance(ProgramListAction.class);

    /** 훑은 파일 하나. 글은 목록 줄을 만들 때 하나씩 읽고 버린다. */
    private record Source(String module, String path, VirtualFile file) {
    }

    private record Collected(List<List<Object>> programs, List<List<Object>> urls) {
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        if (DumbService.isDumb(project)) {
            Messages.showWarningDialog(project, BootServersBundle.message("programs.indexing"), BootServersBundle.message("programs.title"));
            return;
        }
        FileSaverDescriptor descriptor = new FileSaverDescriptor(BootServersBundle.message("programs.title"), BootServersBundle.message("programs.save.description"), EXTENSION);
        List<VirtualFile> picked = picked(e, project);
        List<String> scope = new ArrayList<>();
        for (VirtualFile file : picked) {
            scope.add(file.getPath());
        }
        VirtualFile only = picked.size() == 1 ? picked.get(0) : null;
        String defaultName = (only == null ? project.getName() : only.isDirectory() ? only.getName() : only.getNameWithoutExtension()) + FILE_INFIX + LocalDate.now().format(FILE_DATE) + "." + EXTENSION;
        VirtualFileWrapper chosen = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                .save(ProjectUtil.guessProjectDir(project), defaultName);
        if (chosen == null) {
            return;
        }
        Path target = chosen.getFile().toPath();
        ProgressManager.getInstance().run(new Task.Backgroundable(project, BootServersBundle.message("programs.progress"), true) {
            private Collected collected;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                collected = collect(project, scope, indicator);
                indicator.setText2(target.toString());
                try {
                    XlsxBook.write(target, List.of(
                            new XlsxBook.Sheet(SHEET_PROGRAMS, ProgramList.PROGRAM_COLUMNS, ProgramList.PROGRAM_WIDTHS, collected.programs()),
                            new XlsxBook.Sheet(SHEET_URLS, ProgramList.URL_COLUMNS, ProgramList.URL_WIDTHS, collected.urls())));
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target);
            }

            @Override
            public void onSuccess() {
                NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                        .createNotification(BootServersBundle.message("programs.saved"),
                                BootServersBundle.message("programs.saved.content", collected.programs().size(), collected.urls().size()) + "<br>" + target,
                                NotificationType.INFORMATION)
                        .addAction(NotificationAction.createSimple(BootServersBundle.message("common.open"), () -> RevealFileAction.openFile(target)))
                        .addAction(NotificationAction.createSimple(BootServersBundle.message("programs.open.folder"), () -> RevealFileAction.openDirectory(target.getParent())))
                        .notify(project);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                LOG.warn("프로그램 목록 저장 실패: " + target, error);
                Throwable cause = error instanceof UncheckedIOException io ? io.getCause() : error;
                NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                        .createNotification(BootServersBundle.message("programs.save.failed"),
                                target + "<br>" + cause.getMessage() + "<br>" + BootServersBundle.message("programs.save.failed.hint"),
                                NotificationType.ERROR)
                        .notify(project);
            }
        });
    }

    /**
     * 작업 스레드에서 부른다. 파일 목록 · URL 매핑은 짧은 읽기 동작으로 모으고(편집이 끼면 그 동작만 다시 돈다),
     * 파일 글은 읽기 동작 밖에서 하나씩 읽어 목록 줄을 만든 뒤 버린다(프로젝트 전체 글을 메모리에 쥐지 않는다).
     */
    private static Collected collect(Project project, List<String> scope, ProgressIndicator indicator) {
        List<Source> sources = ReadAction.nonBlocking(() -> scan(project, scope, indicator))
                .inSmartMode(project)
                .wrapProgress(indicator)
                .executeSynchronously();
        sources.sort(Comparator.comparing(Source::module).thenComparing(Source::path));
        List<List<Object>> programs = new ArrayList<>();
        for (Source source : sources) {
            indicator.checkCanceled();
            indicator.setText2(source.path());
            VirtualFile file = source.file();
            if (!file.isValid()) {
                continue;
            }
            programs.add(ProgramList.programRow(programs.size() + 1, source.module(), source.path(), file.getName(),
                    text(file), file.getTimeStamp()));
        }
        List<List<Object>> urls = ReadAction.nonBlocking(() -> urlRows(project, scope))
                .inSmartMode(project)
                .wrapProgress(indicator)
                .executeSynchronously();
        return new Collected(programs, urls);
    }

    /** 읽기 동작 안에서 부른다. */
    private static List<List<Object>> urlRows(Project project, List<String> scope) {
        List<List<Object>> urls = new ArrayList<>();
        List<UrlMappingAction.Endpoint> endpoints = UrlMappingAction.collect(project);
        if (endpoints != null) {
            for (UrlMappingAction.Endpoint endpoint : endpoints) {
                ProgressManager.checkCanceled();
                if (!scope.isEmpty()) {
                    // 고른 범위가 있으면 그 안 파일의 매핑만 담는다. 메서드를 더는 찾을 수 없는 매핑은 어느 파일인지 몰라 뺀다.
                    PsiMethod method = endpoint.target().getElement();
                    VirtualFile file = method == null || method.getContainingFile() == null ? null : method.getContainingFile().getVirtualFile();
                    if (file == null || !ProgramList.inScope(file.getPath(), scope)) {
                        continue;
                    }
                }
                urls.add(urlRow(urls.size() + 1, endpoint));
            }
        }
        return urls;
    }

    private static List<Object> urlRow(int no, UrlMappingAction.Endpoint endpoint) {
        PsiMethod method = endpoint.target().getElement();
        String owner = endpoint.owner();
        int dot = owner.lastIndexOf(CLASS_METHOD_SEPARATOR);
        String className = dot < 0 ? owner : owner.substring(0, dot);
        String methodName = dot < 0 ? "" : owner.substring(dot + 1);
        String description = "";
        if (method != null) {
            PsiClass containing = method.getContainingClass();
            if (containing != null && containing.getQualifiedName() != null) {
                className = containing.getQualifiedName();
            }
            PsiDocComment doc = method.getDocComment();
            description = doc == null ? "" : ProgramList.firstSentence(doc.getText());
        }
        return ProgramList.urlRow(no, endpoint.httpMethod(), endpoint.url(), className, methodName, description);
    }

    /**
     * 프로젝트 창 우클릭에서 고른 줄. 다른 곳(Project 메뉴)에서 불렀거나, 고른 것이 없거나, 프로젝트 폴더를 골랐으면 빈 목록(전체를 담는다).
     */
    private static List<VirtualFile> picked(AnActionEvent e, Project project) {
        VirtualFile[] files = ActionPlaces.PROJECT_VIEW_POPUP.equals(e.getPlace()) ? e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) : null;
        if (files == null) {
            return List.of();
        }
        VirtualFile base = ProjectUtil.guessProjectDir(project);
        for (VirtualFile file : files) {
            if (file.equals(base)) {
                return List.of();
            }
        }
        return List.of(files);
    }

    private static List<Source> scan(Project project, List<String> scope, ProgressIndicator indicator) {
        VirtualFile base = ProjectUtil.guessProjectDir(project);
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        Set<VirtualFile> seen = new LinkedHashSet<>();
        List<Source> result = new ArrayList<>();
        Module[] modules = ModuleManager.getInstance(project).getModules();
        Arrays.sort(modules, Comparator.comparing(Module::getName));
        for (Module module : modules) {
            for (VirtualFile root : roots(module)) {
                if (!ProgramList.reachesScope(root.getPath(), scope)) {
                    continue;
                }
                VfsUtilCore.visitChildrenRecursively(root, new VirtualFileVisitor<Void>() {
                    @Override
                    public boolean visitFile(@NotNull VirtualFile file) {
                        ProgressManager.checkCanceled();
                        if (file.isDirectory()) {
                            return !ProgramList.isExcludedDir(file.getName()) && !index.isExcluded(file);
                        }
                        if (ProgramList.inScope(file.getPath(), scope) && seen.add(file)) {
                            String path = relativePath(file, base);
                            indicator.setText2(path);
                            result.add(new Source(module.getName(), path, file));
                        }
                        return false;
                    }
                });
            }
        }
        return result;
    }

    /** 소스 · 리소스 루트(테스트 제외) + 콘텐츠 루트 아래 웹 폴더. 이름이 제외 폴더인 루트는 뺀다. */
    private static List<VirtualFile> roots(Module module) {
        ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
        List<VirtualFile> roots = new ArrayList<>(rootManager.getSourceRoots(JavaModuleSourceRootTypes.PRODUCTION));
        for (VirtualFile contentRoot : rootManager.getContentRoots()) {
            for (String webDir : WEB_DIRS) {
                VirtualFile web = contentRoot.findFileByRelativePath(webDir);
                if (web != null && web.isDirectory()) {
                    roots.add(web);
                }
            }
        }
        List<VirtualFile> result = new ArrayList<>();
        for (VirtualFile root : roots) {
            if (!ProgramList.isExcludedDir(root.getName())) {
                result.add(root);
            }
        }
        return result;
    }

    private static String relativePath(VirtualFile file, VirtualFile base) {
        String relative = base == null ? null : VfsUtilCore.getRelativePath(file, base);
        return relative != null ? relative : file.getPath();
    }

    /** 글 파일의 내용. 이진 · 너무 큰 파일은 null, 못 읽으면 경고 기록 후 null. */
    private static String text(VirtualFile file) {
        if (file.getFileType().isBinary() || FileUtilRt.isTooLarge(file.getLength())) {
            return null;
        }
        try {
            return VfsUtilCore.loadText(file);
        } catch (IOException e) {
            LOG.warn("파일을 읽지 못해 설명 · 줄 수를 비웁니다: " + file.getPath(), e);
            return null;
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
