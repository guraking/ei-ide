package local.bootservers.memo;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * 풀린 메모를 고치는 창. 「저장」은 지금 비밀번호로 암호화해 임시 파일에 쓴 뒤 원본과 바꿔치기한다(쓰다 끊겨도 원본이 남음).
 * 「비밀번호 바꾸기」는 지금 글을 새 비밀번호로 바로 다시 저장한다. 저장 안 한 채 닫으면 버릴지 묻는다.
 */
final class SecureMemoDialog extends DialogWrapper {

    private static final int WIDTH = 640;
    private static final int HEIGHT = 420;

    private final @Nullable Project project;
    private final Path file;
    private final JBTextArea area = new JBTextArea();
    private char[] password;
    private String saved;
    private boolean passwordChanged;

    SecureMemoDialog(@Nullable Project project, Path file, char[] password, String text) {
        super(project, true);
        this.project = project;
        this.file = file;
        this.password = password;
        this.saved = text;
        area.setText(text);
        area.setCaretPosition(0);
        setTitle(SecureMemoAction.TITLE);
        setOKButtonText("저장");
        setCancelButtonText("닫기");
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JBScrollPane scroll = new JBScrollPane(area);
        scroll.setPreferredSize(JBUI.size(WIDTH, HEIGHT));
        return scroll;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return area;
    }

    @Override
    protected Action[] createLeftSideActions() {
        return new Action[]{new DialogWrapperAction("비밀번호 바꾸기…") {
            @Override
            protected void doAction(ActionEvent e) {
                char[] next = NewPasswordDialog.ask(project, "보안 메모 비밀번호 바꾸기");
                if (next != null) {
                    Arrays.fill(password, '\0');
                    password = next;
                    passwordChanged = true;
                    if (save()) {
                        Messages.showInfoMessage(area, "새 비밀번호로 저장했습니다.", SecureMemoAction.TITLE);
                    }
                }
            }
        }};
    }

    @Override
    protected void doOKAction() {
        if (save()) {
            super.doOKAction();
        }
    }

    @Override
    public void doCancelAction() {
        if (!area.getText().equals(saved) || passwordChanged) {
            if (Messages.showYesNoDialog(area, "저장하지 않은 내용이 있습니다. 버리고 닫을까요?", SecureMemoAction.TITLE,
                    "버리고 닫기", "돌아가기", Messages.getWarningIcon()) != Messages.YES) {
                return;
            }
        }
        super.doCancelAction();
    }

    @Override
    protected void dispose() {
        Arrays.fill(password, '\0');
        super.dispose();
    }

    private boolean save() {
        String text = area.getText();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.write(tmp, SecureMemoCipher.encrypt(password, text));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | GeneralSecurityException ex) {
            Messages.showErrorDialog(area, "저장하지 못했습니다: " + ex.getMessage(), SecureMemoAction.TITLE);
            return false;
        }
        saved = text;
        passwordChanged = false;
        return true;
    }
}
