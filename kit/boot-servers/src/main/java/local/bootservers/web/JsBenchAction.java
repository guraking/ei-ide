package local.bootservers.web;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;

/**
 * 도구 메뉴 · 편집기 우클릭 「작업대」 의 「자바스크립트 작업대…」. 편집기에서 고른 글이 있으면 코드 칸에 넣고,
 * 편집 중인 파일이 TypeScript(TS_EXTENSIONS)면 TypeScript, 다른 편집기면 JavaScript, 편집기가 없으면 지난번 언어로 연다.
 */
public final class JsBenchAction extends DumbAwareAction {

    private static final Set<String> TS_EXTENSIONS = Set.of("ts", "tsx", "mts", "cts");

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
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        String selected = editor != null ? editor.getSelectionModel().getSelectedText() : null;
        Boolean typescript = null;
        if (editor != null) {
            VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
            String extension = file != null ? file.getExtension() : null;
            typescript = extension != null && TS_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
        }
        JsBenchDialog.open(project, editor, selected, typescript);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
