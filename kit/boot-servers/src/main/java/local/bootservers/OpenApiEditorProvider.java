package local.bootservers;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileEditor.TextEditorWithPreview;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * openapi · swagger 사양 파일(.yaml · .yml · .json)을 열면 편집기 옆에 Swagger UI 미리보기를 붙인다.
 * 글을 고치면 잠시 뒤 미리보기를 다시 그린다. 「Try it out」 은 이 파일이 있는 모듈의 개발 서버로 보낸다(사양에 servers 가 없을 때).
 */
public final class OpenApiEditorProvider implements FileEditorProvider, DumbAware {

    private static final Set<String> EXTENSIONS = Set.of("yaml", "yml", "json");
    /** 마지막으로 고친 뒤 미리보기를 다시 그리기까지 기다리는 시간. */
    private static final int REFRESH_DELAY_MS = 800;
    private static final Logger LOG = Logger.getInstance(OpenApiEditorProvider.class);

    @Override
    public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return isSpec(file);
    }

    /** 확장자가 맞고 앞부분에 최상위 openapi · swagger 판이 적힌 파일인가. 읽을 수 없으면 아님. */
    static boolean isSpec(VirtualFile file) {
        return specVersion(file) != null;
    }

    /**
     * 사양 파일이면 그 판(OpenApiFiles.SWAGGER_2 · OPENAPI_30 · OPENAPI_31), 아니거나 읽을 수 없으면 null.
     * 파일 앞 OpenApiFiles.HEAD_CHARS 바이트만 읽는다.
     */
    static String specVersion(VirtualFile file) {
        String ext = file.getExtension();
        if (file.isDirectory() || ext == null || !EXTENSIONS.contains(ext.toLowerCase())) {
            return null;
        }
        try {
            return OpenApiFiles.version(new String(VfsUtilCore.loadNBytes(file, OpenApiFiles.HEAD_CHARS), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        TextEditor text = (TextEditor) TextEditorProvider.getInstance().createEditor(project, file);
        return new TextEditorWithPreview(text, new Preview(project, file), "OpenAPI");
    }

    @Override
    public @NotNull String getEditorTypeId() {
        return "ei-openapi";
    }

    @Override
    public @NotNull FileEditorPolicy getPolicy() {
        return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
    }

    /** 미리보기 쪽 편집기. 문서가 바뀌면 사양을 다시 읽어 Swagger 화면을 다시 연다. */
    private static final class Preview extends UserDataHolderBase implements FileEditor {
        private final Project project;
        private final VirtualFile file;
        private final Disposable lifetime = Disposer.newDisposable("OpenAPI 미리보기");
        private final SwaggerView view = new SwaggerView(lifetime);
        private final Alarm alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, lifetime);

        Preview(Project project, VirtualFile file) {
            this.project = project;
            this.file = file;
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document != null) {
                document.addDocumentListener(new DocumentListener() {
                    @Override
                    public void documentChanged(@NotNull DocumentEvent event) {
                        alarm.cancelAllRequests();
                        alarm.addRequest(Preview.this::refresh, REFRESH_DELAY_MS);
                    }
                }, lifetime);
            }
            refresh();
        }

        private void refresh() {
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document == null) {
                return;
            }
            String text = document.getText();
            ReadAction.nonBlocking(() -> convert(ServerBase.resolve(project, ModuleUtilCore.findModuleForFile(file, project)), text))
                    .inSmartMode(project)
                    .expireWith(lifetime)
                    .finishOnUiThread(ModalityState.defaultModalityState(), shown -> {
                        if (shown != null) {
                            view.show(shown.base(), shown.json(), null);
                        }
                    })
                    .submit(AppExecutorUtil.getAppExecutorService());
        }

        /** 미리보기에 넘길 개발 서버 기본 주소와 Swagger UI 용 JSON. */
        private record Shown(String base, String json) {
        }

        /** 사양 글을 Swagger UI 용 JSON 으로 바꾼다. 읽을 수 없는 글이면 null. 화면 스레드 밖에서 부른다. */
        private static Shown convert(String base, String text) {
            try {
                return new Shown(base, OpenApiFiles.toJson(text, base));
            } catch (IllegalArgumentException e) {
                // 고치는 중의 깨진 글은 앞 미리보기를 그대로 둔다. 오류 위치는 편집기의 스키마 검사가 보인다.
                LOG.debug(e);
                return null;
            }
        }

        @Override
        public @NotNull JComponent getComponent() {
            return view.component();
        }

        @Override
        public @Nullable JComponent getPreferredFocusedComponent() {
            return view.component();
        }

        @Override
        public @NotNull String getName() {
            return "Swagger";
        }

        @Override
        public void setState(@NotNull FileEditorState state) {
        }

        @Override
        public boolean isModified() {
            return false;
        }

        @Override
        public boolean isValid() {
            return file.isValid();
        }

        @Override
        public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
        }

        @Override
        public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
        }

        @Override
        public @NotNull VirtualFile getFile() {
            return file;
        }

        @Override
        public void dispose() {
            Disposer.dispose(lifetime);
        }
    }
}
