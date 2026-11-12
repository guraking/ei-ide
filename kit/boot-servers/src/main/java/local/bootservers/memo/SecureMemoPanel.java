package local.bootservers.memo;

import com.intellij.icons.AllIcons;
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI;
import com.intellij.markdown.utils.MarkdownToHtmlConverterKt;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.ui.BrowserHyperlinkListener;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.util.IconUtil;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;
import local.bootservers.BootServersBundle;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * 「보안 메모」 창 내용. 잠긴 면(비밀번호 입력 · 처음이면 새 비밀번호 정하기)과 메모 면 둘을 오간다.
 * 메모는 설정 폴더 파일 하나이고 {@link SecureMemoCipher} 로 암호화한다. 비밀번호는 키를 만든 뒤 바로 지우고,
 * 키는 풀려 있는 동안만 메모리에 두고 잠그면 지운다. 느린 키 만들기는 열 때 · 비밀번호를 정할 때 한 번만 모달 진행 창에서 하고, 저장은 그 키를 쓴다.
 * 저장은 임시 파일에 쓴 뒤 원본과 바꿔치기한다(쓰다 끊겨도 원본이 남음). 「잠그기」와 창이 닫힐 때(IDE 종료 포함)는 바뀐 내용을 먼저 저장한다.
 * 메모 파일은 IDE 하나에 하나이므로 한 번에 한 프로젝트 창에서만 풀린다. 다른 창에서 풀려 있으면 열기 · 새로 정하기 · 처음으로를 막아
 * 두 창이 서로의 글을 덮어쓰지 않게 한다(설정 폴더는 IDE 한 프로세스만 쓰므로 프로세스 안 표시로 충분하다).
 */
final class SecureMemoPanel extends JPanel implements Disposable {

    static String title() {
        return BootServersBundle.message("memo.title");
    }

    private static final String FILE_NAME = "ei-secure-memo.bin";
    /** 닫을 때 저장하지 못한 글의 암호화 사본 꼬리. 이름을 메모 파일 이름으로 바꾸면 그 글로 열린다. */
    private static final String UNSAVED_SUFFIX = ".unsaved";
    private static final String LOCKED = "locked";
    private static final String OPEN = "open";
    private static final String EDIT = "edit";
    private static final String PREVIEW = "preview";
    private static final String TOOLBAR_PLACE = "EI.SecureMemo.Toolbar";
    // 잠긴 면 자물쇠 배율(16px → 32px)과 색
    private static final float LOCK_ICON_SCALE = 2f;
    private static final Color LOCK_COLOR = JBColor.namedColor("EIMemo.lockForeground", new JBColor(0xD6264A, 0xFF375F));
    private static final String DIRTY_DOT = "● ";
    /** 지금 메모가 풀려 있는 창. 없으면 null. 화면 스레드에서만 읽고 쓴다. */
    private static SecureMemoPanel openPanel;

    private final Project project;
    private final Path file = PathManager.getConfigDir().resolve(FILE_NAME);
    private final CardLayout cards = new CardLayout();

    // 잠긴 면: 굵은 제목 · 회색 설명 두 줄 · 오류 줄
    private final JBLabel lockTitle = new JBLabel("", SwingConstants.CENTER);
    private final JBLabel prompt = new JBLabel("", SwingConstants.CENTER);
    private final JBLabel promptNote = new JBLabel(BootServersBundle.message("memo.locked.note"), SwingConstants.CENTER);
    private final JBLabel errorLabel = new JBLabel("", AllIcons.General.Error, SwingConstants.CENTER);
    // 메모 면 아래 저장 상태 줄
    private final JBLabel saveState = new JBLabel();
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
    // 풀려 있는 동안의 키. 잠겨 있으면 null.
    private SecureMemoCipher.Key key;
    private String saved;

    SecureMemoPanel(Project project) {
        this.project = project;
        errorLabel.setAllowAutoWrapping(true);
        setLayout(cards);
        add(lockedView(), LOCKED);
        add(openView(), OPEN);
        showLocked(null);
    }

    /** 가운데 정렬: 큰 자물쇠 · 굵은 제목 · 회색 설명, 그 아래 창 폭의 비밀번호 칸 · 단추 · 오류 줄. */
    private JPanel lockedView() {
        passwordField.getEmptyText().setText(BootServersBundle.message("memo.password.hint"));
        passwordField.addActionListener(e -> unlock());
        unlockButton.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true);
        newButton.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true);
        unlockButton.addActionListener(e -> unlock());
        newButton.addActionListener(e -> startNew());
        resetButton.addActionListener(e -> reset());
        lockTitle.setFont(JBUI.Fonts.label().asBold());
        prompt.setForeground(NamedColorUtil.getInactiveTextColor());
        promptNote.setForeground(NamedColorUtil.getInactiveTextColor());
        errorLabel.setForeground(UIUtil.getErrorForeground());
        JBLabel lockIcon = new JBLabel(IconUtil.colorize(IconUtil.scale(AllIcons.Nodes.Padlock, null, LOCK_ICON_SCALE), LOCK_COLOR), SwingConstants.CENTER);
        JPanel form = new JPanel(new VerticalLayout(UIUtil.DEFAULT_VGAP, VerticalLayout.FILL));
        form.add(lockIcon);
        form.add(lockTitle);
        form.add(prompt);
        form.add(promptNote);
        form.add(passwordField);
        form.add(unlockButton);
        form.add(newButton);
        form.add(resetButton);
        form.add(errorLabel);
        // 세로 가운데 · 가로는 창 폭(좌우 여백만)
        JPanel view = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = JBUI.insets(UIUtil.DEFAULT_VGAP * 2, UIUtil.DEFAULT_HGAP * 2);
        view.add(form, c);
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
        area.setFont(EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN));
        area.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                updateSaveState();
            }
        });
        preview.setBorder(JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP));
        // 창 머리 선 위에 테두리가 겹치지 않게 테두리 없는 스크롤을 쓴다.
        body.add(ScrollPaneFactory.createScrollPane(area, true), EDIT);
        body.add(ScrollPaneFactory.createScrollPane(preview, true), PREVIEW);
        SimpleToolWindowPanel view = new SimpleToolWindowPanel(true, true);
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true);
        toolbar.setTargetComponent(view);
        view.setToolbar(toolbar.getComponent());
        saveState.setFont(JBUI.Fonts.smallFont());
        saveState.setBorder(JBUI.Borders.empty(UIUtil.DEFAULT_VGAP / 2, UIUtil.DEFAULT_HGAP));
        JPanel stateRow = new JPanel(new BorderLayout());
        stateRow.setBorder(JBUI.Borders.customLineTop(JBColor.border()));
        stateRow.add(saveState, BorderLayout.CENTER);
        JPanel content = new JPanel(new BorderLayout());
        content.add(body, BorderLayout.CENTER);
        content.add(stateRow, BorderLayout.SOUTH);
        view.setContent(content);
        return view;
    }

    /** 아래 상태 줄: 저장한 글과 같으면 회색 「저장됨」, 다르면 주황 「바뀐 내용 있음」과 잠글 때 일어나는 일. */
    private void updateSaveState() {
        if (saved == null) {
            saveState.setText("");
            return;
        }
        boolean dirty = !area.getText().equals(saved);
        saveState.setText(dirty ? DIRTY_DOT + BootServersBundle.message("memo.state.dirty") : BootServersBundle.message("memo.state.saved"));
        saveState.setForeground(dirty ? JBUI.CurrentTheme.Label.warningForeground() : NamedColorUtil.getInactiveTextColor());
    }

    /** @param error 앞서 실패한 까닭. 없으면 null. 비밀번호를 잊었을 때의 「처음으로」 안내와 단추를 함께 보인다. */
    private void showLocked(String error) {
        showLocked(error == null ? null : BootServersBundle.message("memo.prompt.error", error), error != null);
    }

    /** @param message 오류 줄에 그대로 보일 글. 없으면 null. @param offerReset 「처음으로」 단추를 보일지 */
    private void showLocked(String message, boolean offerReset) {
        boolean exists = Files.exists(file);
        lockTitle.setText(BootServersBundle.message(exists ? "memo.locked.title" : "memo.locked.none"));
        prompt.setText(BootServersBundle.message(exists ? "memo.prompt.enter" : "memo.prompt.none"));
        errorLabel.setText(message != null ? "<html>" + message + "</html>" : "");
        errorLabel.setVisible(message != null);
        passwordField.setText("");
        passwordField.setVisible(exists);
        unlockButton.setVisible(exists);
        newButton.setVisible(!exists);
        resetButton.setVisible(exists && offerReset);
        cards.show(this, LOCKED);
    }

    private void showOpen(SecureMemoCipher.Key key, String text) {
        openPanel = this;
        this.key = key;
        this.saved = text;
        area.setText(text);
        updateSaveState();
        area.setCaretPosition(0);
        showPreview(false);
        cards.show(this, OPEN);
        area.requestFocusInWindow();
    }

    /** 다른 창에서 풀려 있으면 그렇다고 잠긴 면에 보이고 true. */
    private boolean openElsewhere() {
        if (openPanel == null || openPanel == this) {
            return false;
        }
        showLocked(BootServersBundle.message("memo.open.elsewhere"), false);
        return true;
    }

    private void unlock() {
        if (openElsewhere()) {
            return;
        }
        char[] entered = passwordField.getPassword();
        SecureMemoCipher.Key opened = null;
        try {
            byte[] data = Files.readAllBytes(file);
            byte[] salt = SecureMemoCipher.saltOf(data);
            opened = deriveKey(entered, salt);
            showOpen(opened, SecureMemoCipher.decrypt(opened, data));
        } catch (SecureMemoCipher.WrongPasswordException e) {
            wipe(opened);
            showLocked(e.getMessage());
        } catch (IOException | GeneralSecurityException e) {
            wipe(opened);
            showLocked(BootServersBundle.message("memo.open.failed", e.getMessage()));
        } finally {
            Arrays.fill(entered, '\0');
        }
    }

    /** 비밀번호와 salt 에서 키를 만든다. 반복이 많아 느리므로 모달 진행 창 안에서 돌린다. 비밀번호 배열은 부른 쪽이 지운다. */
    private SecureMemoCipher.Key deriveKey(char[] password, byte[] salt) throws GeneralSecurityException {
        return ProgressManager.getInstance().runProcessWithProgressSynchronously(
                (ThrowableComputable<SecureMemoCipher.Key, GeneralSecurityException>) () -> SecureMemoCipher.deriveKey(password, salt),
                BootServersBundle.message("memo.progress.key"), false, project);
    }

    /** 새 비밀번호로 새 salt 의 키를 만들고 비밀번호 배열을 지운다. 실패하면 알리고 null. */
    private SecureMemoCipher.Key newKey(char[] password) {
        try {
            return deriveKey(password, SecureMemoCipher.newSalt());
        } catch (GeneralSecurityException e) {
            Messages.showErrorDialog(project, BootServersBundle.message("common.save.failed", e.getMessage()), title());
            return null;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static void wipe(SecureMemoCipher.Key key) {
        if (key != null) {
            key.wipe();
        }
    }

    private void startNew() {
        // 잠긴 면을 띄운 뒤 다른 창이 메모를 만들었으면 그 파일을 덮어쓰지 않고 「잠겨 있음」 면으로 바꾼다.
        if (openElsewhere()) {
            return;
        }
        if (Files.exists(file)) {
            showLocked(null);
            return;
        }
        char[] next = NewPasswordDialog.ask(project, BootServersBundle.message("memo.new.password.title"));
        SecureMemoCipher.Key fresh = next == null ? null : newKey(next);
        if (fresh != null) {
            showOpen(fresh, "");
            save();
        }
    }

    private void reset() {
        if (openElsewhere()) {
            return;
        }
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
        SecureMemoCipher.Key fresh = newKey(next);
        if (fresh == null) {
            return;
        }
        SecureMemoCipher.Key old = key;
        key = fresh;
        if (write(area.getText())) {
            old.wipe();
            Messages.showInfoMessage(project, BootServersBundle.message("memo.changed"), title());
        } else {
            fresh.wipe();
            key = old;
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
            Files.write(tmp, SecureMemoCipher.encrypt(key, text));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | GeneralSecurityException e) {
            Messages.showErrorDialog(project, BootServersBundle.message("common.save.failed", e.getMessage()), title());
            return false;
        }
        saved = text;
        updateSaveState();
        return true;
    }

    /**
     * 닫을 때 저장이 실패한 글을 같은 비밀번호로 암호화해 메모 파일 옆 「이름.unsaved」에 남기고 그 경로를 알린다.
     * 평문으로는 남기지 않는다. 이것도 실패하면 글을 잃었다고 알린다.
     */
    private void keepUnsaved(String text) {
        Path copy = file.resolveSibling(file.getFileName() + UNSAVED_SUFFIX);
        try {
            Files.write(copy, SecureMemoCipher.encrypt(key, text));
            Messages.showErrorDialog(project, BootServersBundle.message("memo.unsaved.kept", copy), title());
        } catch (IOException | GeneralSecurityException e) {
            Messages.showErrorDialog(project, BootServersBundle.message("memo.unsaved.lost", e.getMessage()), title());
        }
    }

    private boolean isOpen() {
        return key != null;
    }

    private void clear() {
        key.wipe();
        key = null;
        if (openPanel == this) {
            openPanel = null;
        }
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
            if (!area.getText().equals(saved) && !save()) {
                keepUnsaved(area.getText());
            }
            clear();
        }
    }
}
