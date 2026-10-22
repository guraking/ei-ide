package local.bootservers.memo;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Window 메뉴 「보안 메모…」. 설정 폴더의 암호화된 메모 파일 하나를 비밀번호로 연다.
 * 파일이 없으면 새 비밀번호를 정하게 한다. 비밀번호를 잊으면 복구할 길은 없고, 「초기화」로 파일을 지우고 새로 시작한다.
 */
public final class SecureMemoAction extends DumbAwareAction {

    static final String TITLE = "보안 메모";
    private static final String FILE_NAME = "ei-secure-memo.bin";
    private static final int RETRY = 0;
    private static final int RESET = 1;

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        Path file = PathManager.getConfigDir().resolve(FILE_NAME);
        if (!Files.exists(file)) {
            startNew(project, file);
            return;
        }
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException ex) {
            Messages.showErrorDialog(project, "메모 파일을 읽지 못했습니다: " + ex.getMessage(), TITLE);
            return;
        }
        while (true) {
            String entered = Messages.showPasswordDialog(project, "비밀번호를 입력하세요.", TITLE, Messages.getQuestionIcon());
            if (entered == null) {
                return;
            }
            char[] password = entered.toCharArray();
            try {
                new SecureMemoDialog(project, file, password, SecureMemoCipher.decrypt(password, data)).show();
                return;
            } catch (SecureMemoCipher.WrongPasswordException ex) {
                Arrays.fill(password, '\0');
                int choice = Messages.showDialog(project,
                        ex.getMessage() + "\n비밀번호를 잊었다면 초기화로 메모를 지우고 새로 시작할 수 있습니다(내용은 되살릴 수 없습니다).",
                        TITLE, new String[]{"다시 입력", "초기화…", "취소"}, RETRY, Messages.getErrorIcon());
                if (choice == RESET) {
                    reset(project, file);
                    return;
                }
                if (choice != RETRY) {
                    return;
                }
            } catch (GeneralSecurityException ex) {
                Messages.showErrorDialog(project, "암호를 풀지 못했습니다: " + ex.getMessage(), TITLE);
                return;
            }
        }
    }

    private static void reset(@Nullable Project project, Path file) {
        if (Messages.showYesNoDialog(project, "보안 메모 내용이 영구히 지워집니다. 계속할까요?", TITLE,
                "지우기", "취소", Messages.getWarningIcon()) != Messages.YES) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            Messages.showErrorDialog(project, "메모 파일을 지우지 못했습니다: " + ex.getMessage(), TITLE);
            return;
        }
        startNew(project, file);
    }

    private static void startNew(@Nullable Project project, Path file) {
        char[] password = NewPasswordDialog.ask(project, "보안 메모 비밀번호 정하기");
        if (password != null) {
            new SecureMemoDialog(project, file, password, "").show();
        }
    }
}
