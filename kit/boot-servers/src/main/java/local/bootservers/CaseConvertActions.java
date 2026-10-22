package local.bootservers;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;

/**
 * 편집기 우클릭 「표기법 바꾸기」 묶음. 고른 글(주 캐럿의 선택 영역) 안의 식별자를 한 표기법으로 바꾼다.
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
                    && editor.getDocument().isWritable() && editor.getSelectionModel().hasSelection());
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            Project project = e.getProject();
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            if (project == null || editor == null || !editor.getDocument().isWritable()) {
                return;
            }
            SelectionModel selection = editor.getSelectionModel();
            String text = selection.getSelectedText();
            if (text == null || text.isEmpty()) {
                return;
            }
            String converted = CaseConvert.convert(text, style);
            if (converted.equals(text)) {
                return;
            }
            int start = selection.getSelectionStart();
            int end = selection.getSelectionEnd();
            Document document = editor.getDocument();
            WriteCommandAction.runWriteCommandAction(project, BootServersBundle.message("case.command"), null, () -> {
                document.replaceString(start, end, converted);
                selection.setSelection(start, start + converted.length());
            });
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
