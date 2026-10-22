package local.bootservers.memo;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.util.ui.FormBuilder;
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
                .addLabeledComponent("새 비밀번호:", first)
                .addLabeledComponent("한 번 더:", second)
                .addTooltip("잊으면 메모를 되살릴 수 없습니다. 비밀번호는 어디에도 저장되지 않습니다.")
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
                return new ValidationInfo("비밀번호는 " + MIN_LENGTH + "자 이상이어야 합니다.", first);
            }
            if (!Arrays.equals(a, b)) {
                return new ValidationInfo("두 비밀번호가 다릅니다.", second);
            }
            return null;
        } finally {
            Arrays.fill(a, '\0');
            Arrays.fill(b, '\0');
        }
    }
}
