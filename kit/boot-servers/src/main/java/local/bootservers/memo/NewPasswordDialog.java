package local.bootservers.memo;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.util.ui.FormBuilder;
import local.bootservers.BootServersBundle;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.Arrays;

/** 새 비밀번호를 두 번 받는 창. 최소 길이를 넘고 두 칸이 같아야 확인된다. */
final class NewPasswordDialog extends DialogWrapper {

    private static final int MIN_LENGTH = 4;
    private static final int FIELD_COLUMNS = 24;

    private final JBPasswordField first = new JBPasswordField();
    private final JBPasswordField second = new JBPasswordField();

    private NewPasswordDialog(@Nullable Project project, String title) {
        super(project, true);
        setTitle(title);
        first.setColumns(FIELD_COLUMNS);
        second.setColumns(FIELD_COLUMNS);
        init();
    }

    /** 취소하면 null. */
    static char @Nullable [] ask(@Nullable Project project, String title) {
        NewPasswordDialog dialog = new NewPasswordDialog(project, title);
        return dialog.showAndGet() ? dialog.first.getPassword() : null;
    }

    @Override
    protected JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent(BootServersBundle.message("memo.password.new"), first)
                .addLabeledComponent(BootServersBundle.message("memo.password.again"), second)
                .addTooltip(BootServersBundle.message("memo.password.tooltip"))
                .getPanel();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return first;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        char[] a = first.getPassword();
        char[] b = second.getPassword();
        try {
            if (a.length < MIN_LENGTH) {
                return new ValidationInfo(BootServersBundle.message("memo.password.short", MIN_LENGTH), first);
            }
            if (!Arrays.equals(a, b)) {
                return new ValidationInfo(BootServersBundle.message("memo.password.mismatch"), second);
            }
            return null;
        } finally {
            Arrays.fill(a, '\0');
            Arrays.fill(b, '\0');
        }
    }
}
