package local.bootservers.memo;

import com.intellij.icons.AllIcons;
import com.intellij.markdown.utils.MarkdownToHtmlConverterKt;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.BrowserHyperlinkListener;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import local.bootservers.BootServersBundle;
import org.jetbrains.annotations.NotNull;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
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

    static String title() {
        return BootServersBundle.message("memo.title");
    }

    private static final String FILE_NAME = "ei-secure-memo.bin";
    private static final String LOCKED = "locked";
    private static final String OPEN = "open";
    private static final String EDIT = "edit";
    private static final String PREVIEW = "preview";
    private static final int FIELD_COLUMNS = 20;
    private static final String TOOLBAR_PLACE = "EI.SecureMemo.Toolbar";

    private final Project project;
    private final Path file = PathManager.getConfigDir().resolve(FILE_NAME);
    private final CardLayout cards = new CardLayout();

    private final JBLabel prompt = new JBLabel();
    private final JBPasswordField passwordField = new JBPasswordField();
    private final JButton unlockButton = new JButton(BootServersBundle.message("common.open"));
    private final JButton newButton = new JButton(BootServersBundle.message("memo.new.password"));
    private final JButton resetButton = new JButton(BootServersBundle.message("memo.reset"));

    private final JBTextArea area = new JBTextArea();
    // 메모 글을 Markdown(GFM)으로 그려 보여 주는 읽기 전용 칸. 저장 형식은 글 그대로다.
    private final CardLayout bodyCards = new CardLayout();
    private final JPanel body = new JPanel(bodyCards);
    private final JEditorPane preview = new JEditorPane();
    // 미리보기 켜짐. 도구 막대의 미리보기 단추가 이 값을 보인다.
    private boolean previewOn;
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
        // 왼쪽 끝은 글 칸과 맞추고 단추 사이만 띄운다.
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.add(unlockButton);
        buttons.add(newButton);
        buttons.add(Box.createHorizontalStrut(JBUI.scale(UIUtil.DEFAULT_HGAP)));
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
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(DumbAwareAction.create(BootServersBundle.message("common.save"), AllIcons.Actions.MenuSaveall, e -> save()));
        group.add(DumbAwareAction.create(BootServersBundle.message("memo.lock"), AllIcons.Nodes.Padlock, e -> lock()));
        group.add(DumbAwareAction.create(BootServersBundle.message("memo.change.password"), AllIcons.Actions.Edit, e -> changePassword()));
        group.addSeparator();
        group.add(new DumbAwareToggleAction(BootServersBundle.message("memo.preview"), null, AllIcons.Actions.Preview) {
            @Override
            public boolean isSelected(@NotNull AnActionEvent e) {
                return previewOn;
            }

            @Override
            public void setSelected(@NotNull AnActionEvent e, boolean state) {
                showPreview(state);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        preview.setEditorKit(HTMLEditorKitBuilder.simple());
        preview.setEditable(false);
        preview.addHyperlinkListener(BrowserHyperlinkListener.INSTANCE);
        area.setBorder(JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP));
        preview.setBorder(JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP));
        // 창 머리 선 위에 테두리가 겹치지 않게 테두리 없는 스크롤을 쓴다.
        body.add(ScrollPaneFactory.createScrollPane(area, true), EDIT);
        body.add(ScrollPaneFactory.createScrollPane(preview, true), PREVIEW);
        SimpleToolWindowPanel view = new SimpleToolWindowPanel(true, true);
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true);
        toolbar.setTargetComponent(view);
        view.setToolbar(toolbar.getComponent());
        view.setContent(body);
        return view;
    }

    /** @param error 앞서 실패한 까닭. 없으면 null. */
    private void showLocked(String error) {
        boolean exists = Files.exists(file);
        prompt.setText("<html>" + (!exists ? BootServersBundle.message("memo.prompt.none")
                : error != null ? BootServersBundle.message("memo.prompt.error", error)
                : BootServersBundle.message("memo.prompt.enter")) + "</html>");
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
            showLocked(BootServersBundle.message("memo.open.failed", e.getMessage()));
        }
    }

    private void startNew() {
        char[] next = NewPasswordDialog.ask(project, BootServersBundle.message("memo.new.password.title"));
        if (next != null) {
            showOpen(next, "");
            save();
        }
    }

    private void reset() {
        if (Messages.showYesNoDialog(project, BootServersBundle.message("memo.reset.confirm"), title(),
                BootServersBundle.message("memo.reset.ok"), BootServersBundle.message("common.cancel"), Messages.getWarningIcon()) != Messages.YES) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            Messages.showErrorDialog(project, BootServersBundle.message("memo.delete.failed", e.getMessage()), title());
            return;
        }
        showLocked(null);
    }

    private void changePassword() {
        char[] next = NewPasswordDialog.ask(project, BootServersBundle.message("memo.change.password.title"));
        if (next == null) {
            return;
        }
        char[] old = password;
        password = next;
        if (write(area.getText())) {
            Arrays.fill(old, '\0');
            Messages.showInfoMessage(project, BootServersBundle.message("memo.changed"), title());
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
            Messages.showErrorDialog(project, BootServersBundle.message("common.save.failed", e.getMessage()), title());
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
        previewOn = on;
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
