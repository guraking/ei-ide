package local.bootservers.ai;

import com.intellij.execution.impl.ConsoleViewUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * 편집기 · 콘솔 우클릭 「AI」 묶음. 고른 코드 · 로그로 프롬프트 파일을 만들고, IDE 터미널 새 탭에서
 * 프로젝트 폴더를 작업 폴더로 Claude Code 를 그 파일과 함께 시작한다.
 */
public final class AiActions {

    private static final String TAB_NAME = "Claude";
    private static final String NOTIFICATION_GROUP = "AI";
    private static final String TEMP_PREFIX = "ei-ai-";
    private static final String TEMP_SUFFIX = ".md";
    /** 이보다 오래된 프롬프트 파일은 새 파일을 만들 때 지운다. */
    private static final Duration TEMP_KEEP = Duration.ofDays(1);
    private static final Logger LOG = Logger.getInstance(AiActions.class);

    private AiActions() {
    }

    /** 편집기에서 고른 글. 고른 글이 없으면 캐럿 줄. */
    private record Snippet(String text, int startLine, int endLine) {
    }

    /** 편집기 우클릭 동작의 공통 부분. 일반 편집기(콘솔 아님)의 파일에서만 켜진다. */
    private abstract static class EditorAction extends DumbAwareAction {

        /** 프롬프트 맨 앞 지시. null 이면 그만둔다. */
        abstract String instruction(Project project);

        @Override
        public void update(AnActionEvent e) {
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            e.getPresentation().setEnabledAndVisible(e.getProject() != null && editor != null
                    && !ConsoleViewUtil.isConsoleViewEditor(editor) && e.getData(CommonDataKeys.VIRTUAL_FILE) != null);
            if (e.getPresentation().isEnabled()) {
                e.getPresentation().setEnabled(snippet(editor) != null);
            }
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            Project project = e.getProject();
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
            if (project == null || editor == null || file == null) {
                return;
            }
            Snippet snippet = snippet(editor);
            if (snippet == null) {
                return;
            }
            String instruction = instruction(project);
            if (instruction == null) {
                return;
            }
            VirtualFile base = ProjectUtil.guessProjectDir(project);
            String relative = base == null ? null : VfsUtilCore.getRelativePath(file, base);
            String location = AiText.location(relative != null ? relative : file.getPath(), snippet.startLine(), snippet.endLine());
            start(project, AiText.prompt(instruction, location, snippet.text()));
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    /** 「설명해 줘」. */
    public static final class Explain extends EditorAction {
        @Override
        String instruction(Project project) {
            return "아래 코드가 무엇을 하는지 한국어로 설명해 줘. 위치의 실제 파일을 열어 앞뒤 맥락도 확인해 줘.";
        }
    }

    /** 「고쳐 줘」. */
    public static final class Fix extends EditorAction {
        @Override
        String instruction(Project project) {
            return "아래 코드에서 버그를 찾아 고쳐 줘. 위치의 실제 파일을 열어 확인하고, 고칠 곳과 이유를 먼저 한국어로 보여 준 뒤 파일을 고쳐 줘.";
        }
    }

    /** 「질문하기…」. 질문을 먼저 받는다. */
    public static final class Ask extends EditorAction {
        @Override
        String instruction(Project project) {
            String question = Messages.showInputDialog(project, "고른 코드에 대해 물을 내용을 적으세요.", "AI 에 질문", null);
            if (question == null || question.isBlank()) {
                return null;
            }
            return "아래 코드에 대한 질문에 한국어로 답해 줘. 위치의 실제 파일을 열어 앞뒤 맥락도 확인해 줘.\n\n질문: " + question.strip();
        }
    }

    /** 콘솔 우클릭 「이 오류 원인 찾기」. 콘솔에서 고른 글이 있어야 켜진다. */
    public static final class ConsoleError extends DumbAwareAction {

        @Override
        public void update(AnActionEvent e) {
            Editor editor = consoleEditor(e);
            e.getPresentation().setVisible(editor != null && e.getProject() != null);
            e.getPresentation().setEnabled(editor != null && e.getProject() != null && editor.getSelectionModel().hasSelection());
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            Editor editor = consoleEditor(e);
            Project project = e.getProject();
            String text = editor == null ? null : editor.getSelectionModel().getSelectedText();
            if (project == null || text == null || text.isBlank()) {
                return;
            }
            start(project, AiText.prompt("아래는 실행 · 서버 로그의 일부야. 이 오류의 원인을 프로젝트 소스에서 찾아 한국어로 설명하고 고치는 방법을 알려 줘.",
                    null, text));
        }

        private static Editor consoleEditor(AnActionEvent e) {
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            return editor != null && ConsoleViewUtil.isConsoleViewEditor(editor) ? editor : null;
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    /** 고른 글. 없으면 캐럿 줄. 글이 비었으면 null. */
    private static Snippet snippet(Editor editor) {
        Document doc = editor.getDocument();
        SelectionModel selection = editor.getSelectionModel();
        int start;
        int end;
        if (selection.hasSelection()) {
            start = selection.getSelectionStart();
            end = selection.getSelectionEnd();
        } else {
            int line = doc.getLineNumber(editor.getCaretModel().getOffset());
            start = doc.getLineStartOffset(line);
            end = doc.getLineEndOffset(line);
        }
        String text = doc.getText(new TextRange(start, end));
        if (text.isBlank()) {
            return null;
        }
        // 다음 줄 맨 앞에서 끝난 고르기는 그 줄을 넣지 않는다.
        int last = end > start && end == doc.getLineStartOffset(doc.getLineNumber(end)) ? end - 1 : end;
        return new Snippet(text, doc.getLineNumber(start) + 1, doc.getLineNumber(last) + 1);
    }

    /** 프롬프트 파일을 만들고 터미널 새 탭에서 Claude Code 를 시작한다. 프롬프트 글은 기록에 남기지 않는다. */
    private static void start(Project project, String prompt) {
        if (!AiText.claudeOnPath(System.getenv("PATH"), File.pathSeparator)) {
            notify(project, "Claude Code 를 찾지 못했습니다",
                    "PATH 에 claude 가 없습니다. Claude Code 를 설치한 뒤 IDE 를 다시 시작하세요.", NotificationType.WARNING);
            return;
        }
        Path dir = PathManager.getTempDir();
        String command;
        try {
            Files.createDirectories(dir);
            deleteOld(dir);
            Path file = Files.createTempFile(dir, TEMP_PREFIX, TEMP_SUFFIX);
            Files.writeString(file, prompt, StandardCharsets.UTF_8);
            command = AiText.command(file.toString());
        } catch (IOException | IllegalArgumentException ex) {
            LOG.warn("AI 프롬프트 파일을 만들지 못함: " + dir, ex);
            notify(project, "Claude Code 를 시작하지 못했습니다", ex.getMessage(), NotificationType.ERROR);
            return;
        }
        TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
                .workingDirectory(project.getBasePath())
                .tabName(TAB_NAME)
                .requestFocus(true)
                .createTab()
                .getView()
                .createSendTextBuilder()
                .shouldExecute()
                .send(command);
    }

    /** 하루 넘은 ei-ai- 파일을 지운다. 지우지 못하면 기록만 남긴다. */
    private static void deleteOld(Path dir) {
        Instant limit = Instant.now().minus(TEMP_KEEP);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, TEMP_PREFIX + "*" + TEMP_SUFFIX)) {
            for (Path file : files) {
                try {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(limit)) {
                        Files.delete(file);
                    }
                } catch (IOException ex) {
                    LOG.info("옛 AI 프롬프트 파일을 지우지 못함: " + file, ex);
                }
            }
        } catch (IOException ex) {
            LOG.info("옛 AI 프롬프트 파일 목록을 읽지 못함: " + dir, ex);
        }
    }

    private static void notify(Project project, String title, String content, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(title, content, type)
                .notify(project);
    }
}
