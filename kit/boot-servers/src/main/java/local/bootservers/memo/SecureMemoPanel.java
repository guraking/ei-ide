package local.bootservers.memo;

import com.intellij.markdown.utils.MarkdownToHtmlConverterKt;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.BrowserHyperlinkListener;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * 「보안 메모」 창 내용. 잠긴 면(비밀번호 입력 · 처음이면 새 비밀번호 정하기)과 메모 면 둘을 오간다.
 * 메모는 설정 폴더 파일 하나이고 {@link SecureMemoCipher} 로 암호화한다. 비밀번호는 풀려 있는 동안만 메모리에 두고 잠그면 지운다.
 * 저장은 임시 파일에 쓴 뒤 원본과 바꿔치기한다(쓰다 끊겨도 원본이 남음). 「잠그기」와 창이 닫힐 때(IDE 종료 포함)는 바뀐 내용을 먼저 저장한다.
 * shortcut: 프로젝트 창마다 따로 열리므로 두 창에서 동시에 고치면 나중에 저장한 쪽이 이긴다. 여러 창에서 쓰게 되면 하나만 풀리게 막는다.
 */
final class SecureMemoPanel extends JPanel implements Disposable {

    static final String TITLE = "보안 메모";
    private static final String FILE_NAME = "ei-secure-memo.bin";
    private static final String LOCKED = "locked";
    private static final String OPEN = "open";
    private static final String EDIT = "edit";
    private static final String PREVIEW = "preview";
    private static final int FIELD_COLUMNS = 20;

    private final Project project;
    private final Path file = PathManager.getConfigDir().resolve(FILE_NAME);
    private final CardLayout cards = new CardLayout();

    private final JBLabel prompt = new JBLabel();
    private final JBPasswordField passwordField = new JBPasswordField();
    private final JButton unlockButton = new JButton("열기");
    private final JButton newButton = new JButton("새 비밀번호 정하기…");
    private final JButton resetButton = new JButton("초기화…");

    private final JBTextArea area = new JBTextArea();
    // 메모 글을 Markdown(GFM)으로 그려 보여 주는 읽기 전용 칸. 저장 형식은 글 그대로다.
    private final CardLayout bodyCards = new CardLayout();
    private final JPanel body = new JPanel(bodyCards);
    private final JEditorPane preview = new JEditorPane();
    private final JToggleButton previewButton = new JToggleButton("미리보기");
    private char[] password;
    private String saved;

    SecureMemoPanel(Project project) {
        this.project = project;
        prompt.setAllowAutoWrapping(true);
        setLayout(cards);
        add(lockedView(), LOCKED);
        add(openView(), OPEN);
        showLocked(null);
    }

    private JPanel lockedView() {
        passwordField.setColumns(FIELD_COLUMNS);
        passwordField.addActionListener(e -> unlock());
        unlockButton.addActionListener(e -> unlock());
        newButton.addActionListener(e -> startNew());
        resetButton.addActionListener(e -> reset());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.add(unlockButton);
        buttons.add(newButton);
        buttons.add(resetButton);
        JPanel form = FormBuilder.createFormBuilder()
                .addComponent(prompt)
                .addComponent(passwordField)
                .addComponent(buttons)
                .getPanel();
        form.setBorder(JBUI.Borders.empty(UIUtil.DEFAULT_VGAP * 2, UIUtil.DEFAULT_HGAP * 2));
        JPanel view = new JPanel(new BorderLayout());
        view.add(form, BorderLayout.NORTH);
        return view;
    }

    private JPanel openView() {
        JButton save = new JButton("저장");
        save.addActionListener(e -> save());
        JButton lock = new JButton("잠그기");
        lock.addActionListener(e -> lock());
        JButton change = new JButton("비밀번호 바꾸기…");
        change.addActionListener(e -> changePassword());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(save);
        buttons.add(lock);
        buttons.add(change);
        previewButton.addActionListener(e -> showPreview(previewButton.isSelected()));
        buttons.add(previewButton);
        preview.setEditorKit(HTMLEditorKitBuilder.simple());
        preview.setEditable(false);
        preview.addHyperlinkListener(BrowserHyperlinkListener.INSTANCE);
        body.add(new JBScrollPane(area), EDIT);
        body.add(new JBScrollPane(preview), PREVIEW);
        JPanel view = new JPanel(new BorderLayout());
        view.add(buttons, BorderLayout.NORTH);
        view.add(body, BorderLayout.CENTER);
        return view;
    }

    /** @param error 앞서 실패한 까닭. 없으면 null. */
    private void showLocked(String error) {
        boolean exists = Files.exists(file);
        prompt.setText("<html>" + (!exists ? "아직 메모가 없습니다. 비밀번호를 정하면 시작합니다."
                : error != null ? error + " 잊었다면 초기화로 지우고 새로 시작합니다(내용은 되살릴 수 없음)."
                : "비밀번호를 입력하세요.") + "</html>");
        prompt.setForeground(error != null ? UIUtil.getErrorForeground() : UIUtil.getLabelForeground());
        passwordField.setText("");
        passwordField.setVisible(exists);
        unlockButton.setVisible(exists);
        newButton.setVisible(!exists);
        resetButton.setVisible(exists && error != null);
        cards.show(this, LOCKED);
    }

    private void showOpen(char[] password, String text) {
        this.password = password;
        this.saved = text;
        area.setText(text);
        area.setCaretPosition(0);
        showPreview(false);
        cards.show(this, OPEN);
        area.requestFocusInWindow();
    }

    private void unlock() {
        char[] entered = passwordField.getPassword();
        try {
            showOpen(entered, SecureMemoCipher.decrypt(entered, Files.readAllBytes(file)));
        } catch (SecureMemoCipher.WrongPasswordException e) {
            Arrays.fill(entered, '\0');
            showLocked(e.getMessage());
        } catch (IOException | GeneralSecurityException e) {
            Arrays.fill(entered, '\0');
            showLocked("메모 파일을 열지 못했습니다: " + e.getMessage());
        }
    }

    private void startNew() {
        char[] next = NewPasswordDialog.ask(project, "보안 메모 비밀번호 정하기");
        if (next != null) {
            showOpen(next, "");
            save();
        }
    }

    private void reset() {
        if (Messages.showYesNoDialog(project, "보안 메모 내용이 영구히 지워집니다. 계속할까요?", TITLE,
                "지우기", "취소", Messages.getWarningIcon()) != Messages.YES) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            Messages.showErrorDialog(project, "메모 파일을 지우지 못했습니다: " + e.getMessage(), TITLE);
            return;
        }
        showLocked(null);
    }

    private void changePassword() {
        char[] next = NewPasswordDialog.ask(project, "보안 메모 비밀번호 바꾸기");
        if (next == null) {
            return;
        }
        char[] old = password;
        password = next;
        if (write(area.getText())) {
            Arrays.fill(old, '\0');
            Messages.showInfoMessage(project, "새 비밀번호로 저장했습니다.", TITLE);
        } else {
            Arrays.fill(next, '\0');
            password = old;
        }
    }

    private void lock() {
        if (isOpen() && (area.getText().equals(saved) || save())) {
            clear();
            showLocked(null);
        }
    }

    private boolean save() {
        return write(area.getText());
    }

    private boolean write(String text) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.write(tmp, SecureMemoCipher.encrypt(password, text));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | GeneralSecurityException e) {
            Messages.showErrorDialog(project, "저장하지 못했습니다: " + e.getMessage(), TITLE);
            return false;
        }
        saved = text;
        return true;
    }

    private boolean isOpen() {
        return password != null;
    }

    private void clear() {
        Arrays.fill(password, '\0');
        password = null;
        saved = null;
        area.setText("");
        preview.setText("");
    }

    /** 켜면 지금 글을 Markdown 으로 그려 보여 주고, 끄면 편집 칸으로 돌아간다. */
    private void showPreview(boolean on) {
        previewButton.setSelected(on);
        if (on) {
            preview.setText(MarkdownToHtmlConverterKt.convertMarkdownToHtml(area.getText()));
            preview.setCaretPosition(0);
        }
        bodyCards.show(body, on ? PREVIEW : EDIT);
        (on ? preview : area).requestFocusInWindow();
    }

    @Override
    public void dispose() {
        if (isOpen()) {
            if (!area.getText().equals(saved)) {
                save();
            }
            clear();
        }
    }
}
