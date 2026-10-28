package kinggura.sync;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.FlowLayout;

/**
 * 서버 하나의 로그인을 넣는 창. IntelliJ GitHub 계정의 「토큰으로 로그인」 창처럼 칸은 고른 방식에 필요한 것만 보인다.
 * 아이디 · 비밀번호: 서버 · 아이디 · 비밀번호. 토큰: 서버 · 토큰(Basic 아이디는 GitLogins 가 채운다).
 * 고치기에서는 비밀번호 · 토큰 칸이 비어 있고, 비워 두면 저장된 값을 그대로 쓴다(result 의 secret 이 null).
 */
final class GitLoginDialog extends DialogWrapper {
  private final JBTextField server = new JBTextField();
  private final JBRadioButton password = new JBRadioButton(SyncBundle.message("gitlogin.kind.password"));
  private final JBRadioButton token = new JBRadioButton(SyncBundle.message("gitlogin.kind.token"));
  private final JBTextField user = new JBTextField();
  private final JBPasswordField secret = new JBPasswordField();
  private final JBLabel userLabel = new JBLabel(SyncBundle.message("gitlogin.user"));
  private final JBLabel secretLabel = new JBLabel();
  private final boolean editing;
  /** 고치기 전 서버 주소. 주소를 바꾸면 저장된 비밀번호가 옛 주소에 묶여 있어 새로 넣어야 한다. */
  private final @Nullable String oldServer;

  /** old 가 null 이면 새로 넣기. */
  GitLoginDialog(@Nullable GitLogins.Login old) {
    super(true);
    editing = old != null;
    oldServer = old == null ? null : old.server();
    ButtonGroup kinds = new ButtonGroup();
    kinds.add(password);
    kinds.add(token);
    if (old == null) {
      password.setSelected(true);
    }
    else {
      server.setText(old.server());
      (old.kind() == GitLogins.Kind.TOKEN ? token : password).setSelected(true);
      user.setText(old.user());
      secret.getEmptyText().setText(SyncBundle.message("gitlogin.secret.keep"));
    }
    server.getEmptyText().setText(SyncBundle.message("gitlogin.server.hint"));
    password.addActionListener(e -> updateFields());
    token.addActionListener(e -> updateFields());
    setTitle(SyncBundle.message(old == null ? "gitlogin.add.title" : "gitlogin.edit.title"));
    init();
    updateFields();
  }

  @Override
  protected JComponent createCenterPanel() {
    JPanel kinds = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    kinds.add(password);
    kinds.add(token);
    return FormBuilder.createFormBuilder()
      .addLabeledComponent(SyncBundle.message("gitlogin.server"), server)
      .addLabeledComponent(SyncBundle.message("gitlogin.kind"), kinds)
      .addLabeledComponent(userLabel, user)
      .addLabeledComponent(secretLabel, secret)
      .getPanel();
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return editing ? secret : server;
  }

  /** 고른 방식에 맞춰 아이디 줄을 보이거나 숨기고, 값 칸 이름을 비밀번호 · 토큰으로 바꾼 뒤 창 크기를 맞춘다. */
  private void updateFields() {
    boolean byPassword = password.isSelected();
    userLabel.setVisible(byPassword);
    user.setVisible(byPassword);
    secretLabel.setText(SyncBundle.message(byPassword ? "gitlogin.secret.password" : "gitlogin.secret.token"));
    pack();
  }

  @Override
  protected @Nullable ValidationInfo doValidate() {
    try {
      GitLogins.serverOf(server.getText());
    }
    catch (IllegalArgumentException e) {
      return new ValidationInfo(e.getMessage(), server);
    }
    String name = password.isSelected() ? user.getText().trim() : "";
    // Basic 인증은 아이디:비밀번호로 묶으므로 아이디에 「:」가 있으면 안 되고, 탭 · 줄바꿈은 저장 형식을 깬다.
    if (name.contains(":") || name.contains("\t") || name.contains("\n")) return new ValidationInfo(SyncBundle.message("gitlogin.error.user"), user);
    if (password.isSelected() && name.isEmpty()) return new ValidationInfo(SyncBundle.message("gitlogin.error.user.empty"), user);
    if (secret.getPassword().length == 0 && !GitLogins.serverOf(server.getText()).equals(oldServer)) {
      return new ValidationInfo(SyncBundle.message("gitlogin.error.secret"), secret);
    }
    return null;
  }

  GitLogins.Login result() {
    char[] typed = secret.getPassword();
    return new GitLogins.Login(GitLogins.serverOf(server.getText()), token.isSelected() ? GitLogins.Kind.TOKEN : GitLogins.Kind.PASSWORD,
                               password.isSelected() ? user.getText().trim() : "", typed.length == 0 ? null : new String(typed));
  }
}
