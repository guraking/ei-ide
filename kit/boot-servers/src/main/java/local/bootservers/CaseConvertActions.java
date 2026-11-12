package local.bootservers;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;

/**
 * 편집기 우클릭 「표기법 바꾸기」 묶음. 고른 글(커서마다의 선택 영역) 안의 식별자를 한 표기법으로 바꾼다.
 * 바꾸는 규칙은 {@link CaseConvert}. 한 번의 Ctrl+Z 로 되돌아간다.
 */
public final class CaseConvertActions {


    private CaseConvertActions() {
    }

    private abstract static class ConvertAction extends DumbAwareAction {

        private final CaseConvert.Style style;

        ConvertAction(CaseConvert.Style style) {
            this.style = style;
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(AnActionEvent e) {
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            e.getPresentation().setEnabledAndVisible(e.getProject() != null && editor != null && !editor.isViewer()
                    && editor.getDocument().isWritable() && editor.getSelectionModel().hasSelection(true));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            Project project = e.getProject();
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            if (project == null || editor == null || !editor.getDocument().isWritable()) {
                return;
            }
            boolean changes = false;
            for (Caret caret : editor.getCaretModel().getAllCarets()) {
                String text = caret.getSelectedText();
                if (text != null && !text.isEmpty() && !CaseConvert.convert(text, style).equals(text)) {
                    changes = true;
                    break;
                }
            }
            if (!changes) {
                // 이미 그 표기법이면 바꿀 것이 없다고 편집기 위 안내로 알린다(IntelliJ 편집기 동작의 「할 일 없음」 표시와 같다).
                HintManager.getInstance().showInformationHint(editor, BootServersBundle.message("case.unchanged"));
                return;
            }
            Document document = editor.getDocument();
            // 다중 커서(Alt+Shift)면 커서마다 고른 글을 바꾼다. 뒤 커서부터 바꿔 앞 커서의 위치가 밀리지 않게 한다.
            WriteCommandAction.runWriteCommandAction(project, BootServersBundle.message("case.command"), null,
                    () -> editor.getCaretModel().runForEachCaret(caret -> convertCaret(document, caret), true));
        }

        private void convertCaret(Document document, Caret caret) {
            String text = caret.getSelectedText();
            if (text == null || text.isEmpty()) {
                return;
            }
            String converted = CaseConvert.convert(text, style);
            if (converted.equals(text)) {
                return;
            }
            int start = caret.getSelectionStart();
            document.replaceString(start, caret.getSelectionEnd(), converted);
            caret.setSelection(start, start + converted.length());
        }
    }

    public static final class Camel extends ConvertAction {
        public Camel() {
            super(CaseConvert.Style.CAMEL);
        }
    }

    public static final class Pascal extends ConvertAction {
        public Pascal() {
            super(CaseConvert.Style.PASCAL);
        }
    }

    public static final class Snake extends ConvertAction {
        public Snake() {
            super(CaseConvert.Style.SNAKE);
        }
    }

    public static final class UpperSnake extends ConvertAction {
        public UpperSnake() {
            super(CaseConvert.Style.UPPER_SNAKE);
        }
    }
}
