package local.bootservers;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * 아래쪽 「API」 창: 프로젝트의 스프링 컨트롤러로 만든 OpenAPI 사양을 Swagger UI 로 보이고 「Try it out」 으로 요청을 보낸다.
 * 창을 열 때 · 새로 고칠 때마다 컨트롤러를 다시 읽는다.
 */
public final class SwaggerToolWindow implements ToolWindowFactory, DumbAware {

    static final String ID = "API";
    private static final String SAVE_NAME = "openapi.yaml";
    private static final Key<State> STATE = Key.create("ei.swagger.state");
    private static final Logger LOG = Logger.getInstance(SwaggerToolWindow.class);

    /** 프로젝트마다 창 하나의 화면과 마지막 사양. */
    private static final class State {
        final SwaggerView view;
        volatile String specJson;

        State(SwaggerView view) {
            this.view = view;
        }
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow window) {
        SwaggerView view = new SwaggerView(window.getDisposable());
        Content content = ContentFactory.getInstance().createContent(view.component(), "", false);
        window.getContentManager().addContent(content);
        project.putUserData(STATE, new State(view));
        window.setTitleActions(List.<AnAction>of(new Refresh(), new Save()));
        open(project, null, null);
    }

    /**
     * 창을 열고 컨트롤러를 다시 읽어 Swagger 화면을 보인다. anchor 는 펼칠 API(「태그/operationId」), 없으면 null.
     * from 은 어느 모듈의 요청인지 알려 주는 요소(서버 주소를 고를 때 그 모듈을 실행하는 서버를 우선한다), 없으면 null.
     */
    static void open(Project project, @Nullable String anchor, @Nullable SmartPsiElementPointer<? extends PsiElement> from) {
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(ID);
        if (window == null) {
            return;
        }
        State state = project.getUserData(STATE);
        if (state == null) {
            // 처음이면 창을 만들며 createToolWindowContent 가 다시 부른다. 펼칠 API 는 그 뒤에 한 번 더 연다.
            window.activate(anchor == null ? null : () -> open(project, anchor, from));
            return;
        }
        window.activate(null);
        ReadAction.nonBlocking(() -> {
                    PsiElement element = from == null ? null : from.getElement();
                    Module module = element == null ? null : ModuleUtilCore.findModuleForPsiElement(element);
                    String base = ServerBase.resolve(project, module);
                    return new String[]{base, OpenApiCollector.specJson(project, project.getName(), base)};
                })
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), r -> show(project, state, r[0], r[1], anchor))
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private static void show(Project project, State state, String base, @Nullable String specJson, @Nullable String anchor) {
        if (specJson == null) {
            Messages.showInfoMessage(project,
                    BootServersBundle.message("spring.web.missing"), BootServersBundle.message("swagger.title"));
            return;
        }
        state.specJson = specJson;
        state.view.show(base, specJson, anchor);
    }

    /** Run 메뉴 「API 테스트(Swagger)…」. */
    public static final class OpenAction extends DumbAwareAction {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (e.getProject() != null) {
                open(e.getProject(), null, null);
            }
        }
    }

    private static final class Refresh extends DumbAwareAction {
        Refresh() {
            super(BootServersBundle.message("swagger.refresh"), BootServersBundle.message("swagger.refresh.description"), AllIcons.Actions.Refresh);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (e.getProject() != null) {
                open(e.getProject(), null, null);
            }
        }
    }

    /** 지금 사양을 openapi.yaml 로 저장하고 편집기로 연다(편집기 옆에 같은 Swagger 미리보기가 붙는다). */
    private static final class Save extends DumbAwareAction {
        Save() {
            super(BootServersBundle.message("swagger.save"), BootServersBundle.message("swagger.save.description"), AllIcons.Actions.MenuSaveall);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            State state = project == null ? null : project.getUserData(STATE);
            if (state == null || state.specJson == null) {
                return;
            }
            VirtualFileWrapper target = FileChooserFactory.getInstance()
                    .createSaveFileDialog(new FileSaverDescriptor(BootServersBundle.message("swagger.save.title"), BootServersBundle.message("swagger.save.chooser"), "yaml"), project)
                    .save(project.getBasePath() == null ? null : LocalFileSystem.getInstance().findFileByPath(project.getBasePath()), SAVE_NAME);
            if (target == null) {
                return;
            }
            String yaml = OpenApiFiles.toYaml(state.specJson);
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    Files.writeString(target.getFile().toPath(), yaml, StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    LOG.warn("OpenAPI 사양을 저장하지 못했습니다: " + target.getFile(), ex);
                    ApplicationManager.getApplication().invokeLater(() ->
                            Messages.showErrorDialog(project, BootServersBundle.message("common.save.failed", ex.getMessage()), BootServersBundle.message("swagger.title")));
                    return;
                }
                VirtualFile saved = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.getFile().toPath());
                if (saved != null) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!project.isDisposed()) {
                            FileEditorManager.getInstance(project).openFile(saved, true);
                        }
                    });
                }
            });
        }
    }
}
