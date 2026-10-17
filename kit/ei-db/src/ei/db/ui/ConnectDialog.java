package ei.db.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.DbKind;
import ei.db.DbSessions;
import ei.db.DbSettings;
import ei.db.Drivers;
import ei.db.Passwords;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 접속 추가 · 편집 창. 비밀번호는 설정 파일이 아닌 IDE 암호 저장소에 둔다. */
public final class ConnectDialog extends DialogWrapper {

  private static final int MIN_PORT = 1;
  private static final int MAX_PORT = 65535;
  private static final int PORT_COLUMNS = 6;
  /** 연결 시험 오류는 첫 줄만 이만큼 보이고 전체는 풍선 도움말로 둔다. */
  private static final int ERROR_PREVIEW = 80;
  private static final Color OK_COLOR = JBColor.namedColor("EiDatabase.connected", new JBColor(0x3E9B47, 0x5FB865));
  private static final Color ERROR_COLOR = JBColor.namedColor("EiDatabase.production", new JBColor(0xD9363E, 0xF75464));

  private final Project project;
  private final DbConfig base;
  private DbKind kind;
  private String driverJar;
  private DbConfig saved;

  private final Map<DbKind, JBRadioButton> kinds = new EnumMap<>(DbKind.class);
  private final JBTextField name = new JBTextField();
  private final JBTextField host = new JBTextField();
  private final JBTextField port = new JBTextField(PORT_COLUMNS);
  private final JBLabel serviceLabel = new JBLabel();
  private final JBTextField service = new JBTextField();
  private final JBRadioButton serviceName = new JBRadioButton("서비스 이름");
  private final JBRadioButton sid = new JBRadioButton("SID");
  private final JBTextField user = new JBTextField();
  private final JBPasswordField password = new JBPasswordField();
  private final JBCheckBox savePassword = new JBCheckBox("IDE 암호 저장소에 저장", true);
  private final JBTextField schema = new JBTextField();
  private final JBLabel driverName = new JBLabel();
  private final JBLabel driverStatus = new JBLabel("", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);
  private final JBLabel url = new JBLabel();
  private final JBCheckBox autoCommit = new JBCheckBox("자동 커밋");
  private final JBCheckBox readOnly = new JBCheckBox("읽기 전용 (UPDATE · DELETE 막음)");
  private final JBCheckBox production = new JBCheckBox("운영 표시 (빨간 띠)");
  private final JBLabel testResult = new JBLabel();

  /** existing 이 null 이면 새 접속. 저장하면 설정에 넣고 그 접속을, 취소하면 null. */
  public static DbConfig edit(Project project, DbConfig existing) {
    ConnectDialog d = new ConnectDialog(project, existing);
    return d.showAndGet() ? d.saved : null;
  }

  private ConnectDialog(Project project, @Nullable DbConfig existing) {
    super(project, true);
    this.project = project;
    this.base = existing == null ? new DbConfig() : existing.copy();
    setTitle(existing == null ? "접속 추가" : "접속 편집");
    setOKButtonText("저장");
    setCancelButtonText("취소");

    kind = base.kind;
    driverJar = base.driverJar == null ? "" : base.driverJar;
    name.setText(base.name);
    host.setText(base.host);
    port.setText(String.valueOf(base.port));
    service.setText(base.service);
    (base.sid ? sid : serviceName).setSelected(true);
    user.setText(base.user);
    schema.setText(base.schema);
    autoCommit.setSelected(base.autoCommit);
    readOnly.setSelected(base.readOnly);
    production.setSelected(base.production);
    url.setCopyable(true);
    if (existing != null) loadPassword(existing);

    init();
    applyKind(false);
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JPanel kindRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    ButtonGroup kindGroup = new ButtonGroup();
    for (DbKind k : DbKind.values()) {
      JBRadioButton b = new JBRadioButton(k.label, k == kind);
      b.addActionListener(e -> {
        kind = k;
        applyKind(true);
      });
      kindGroup.add(b);
      kinds.put(k, b);
      kindRow.add(b);
    }

    ButtonGroup serviceGroup = new ButtonGroup();
    serviceGroup.add(serviceName);
    serviceGroup.add(sid);
    serviceName.addActionListener(e -> updateUrl());
    sid.addActionListener(e -> updateUrl());
    JPanel serviceRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
    serviceRow.add(service, BorderLayout.CENTER);
    JPanel serviceKinds = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    serviceKinds.add(serviceName);
    serviceKinds.add(sid);
    serviceRow.add(serviceKinds, BorderLayout.EAST);

    JPanel hostRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
    hostRow.add(host, BorderLayout.CENTER);
    hostRow.add(port, BorderLayout.EAST);

    JPanel passwordRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
    passwordRow.add(password, BorderLayout.CENTER);
    passwordRow.add(savePassword, BorderLayout.EAST);

    JPanel driverRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
    driverRow.add(driverName);
    driverRow.add(driverStatus);
    driverRow.add(new ActionLink("다른 jar 고르기", (ActionListener) e -> chooseJar()));

    JPanel safety = new JPanel();
    safety.setLayout(new BoxLayout(safety, BoxLayout.Y_AXIS));
    safety.add(autoCommit);
    safety.add(readOnly);
    safety.add(production);

    DocumentAdapter preview = new DocumentAdapter() {
      @Override protected void textChanged(@NotNull DocumentEvent e) { updateUrl(); }
    };
    host.getDocument().addDocumentListener(preview);
    port.getDocument().addDocumentListener(preview);
    service.getDocument().addDocumentListener(preview);

    JPanel form = FormBuilder.createFormBuilder()
        .addLabeledComponent("종류", kindRow)
        .addLabeledComponent("이름", name)
        .addLabeledComponent("호스트 · 포트", hostRow)
        .addLabeledComponent(serviceLabel, serviceRow)
        .addLabeledComponent("사용자", user)
        .addLabeledComponent("비밀번호", passwordRow)
        .addLabeledComponent("기본 스키마", schema)
        .addLabeledComponent("드라이버", driverRow)
        .addLabeledComponent("JDBC 주소", url)
        .addLabeledComponent("안전", safety)
        .getPanel();
    form.setPreferredSize(JBUI.size(600, -1).withHeight(form.getPreferredSize().height));
    return form;
  }

  @Override
  protected JPanel createSouthAdditionalPanel() {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
    JButton test = new JButton("연결 시험");
    test.addActionListener(e -> testConnection());
    p.add(test);
    p.add(testResult);
    return p;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return name;
  }

  /** 종류를 바꾸면(사용자가 고른 때만) 기본 포트로 맞추고, 서비스 칸 이름 · 드라이버 · 주소를 다시 보인다. */
  private void applyKind(boolean byUser) {
    if (byUser) port.setText(String.valueOf(kind.defaultPort));
    boolean oracle = kind == DbKind.ORACLE;
    serviceLabel.setText(oracle ? "서비스" : "데이터베이스");
    serviceName.setVisible(oracle);
    sid.setVisible(oracle);
    refreshDriver();
    updateUrl();
  }

  private void updateUrl() {
    url.setText(current().url());
  }

  /** 입력 칸으로 만든 설정. 포트가 숫자가 아니면 0(검증에서 막는다). */
  private DbConfig current() {
    DbConfig c = base.copy();
    c.kind = kind;
    c.name = name.getText().trim();
    c.host = host.getText().trim();
    c.port = parsePort();
    c.service = service.getText().trim();
    c.sid = kind == DbKind.ORACLE && sid.isSelected();
    c.user = user.getText().trim();
    c.schema = schema.getText().trim();
    c.driverJar = driverJar;
    c.autoCommit = autoCommit.isSelected();
    c.readOnly = readOnly.isSelected();
    c.production = production.isSelected();
    return c;
  }

  private int parsePort() {
    try {
      return Integer.parseInt(port.getText().trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** ~/.m2 검색은 파일을 훑으므로 백그라운드에서. 결과가 오기 전에 종류 · jar 가 바뀌었으면 버린다. */
  private void refreshDriver() {
    DbConfig probe = current();
    driverName.setText("찾는 중…");
    driverStatus.setText("");
    // 창이 뜨기 전(init)에도 불리므로 current() 를 쓰면 바깥 상태가 잡혀 창이 닫힐 때까지 갱신이 밀린다. 글자만 바꾸므로 any() 로 둔다.
    ModalityState modality = ModalityState.any();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      Path jar = Drivers.jarFor(probe);
      ApplicationManager.getApplication().invokeLater(() -> {
        if (kind != probe.kind || !Objects.equals(driverJar, probe.driverJar)) return;
        boolean chosen = !driverJar.isBlank();
        driverName.setText(jar != null ? jar.getFileName().toString() : chosen ? Path.of(driverJar).getFileName().toString() : kind.artifactPrefix + " jar");
        driverStatus.setText(jar == null ? "찾지 못함" : chosen ? "직접 고름" : ".m2 에서 찾음");
        driverStatus.setForeground(jar == null ? ERROR_COLOR : UIUtil.getContextHelpForeground());
      }, modality);
    });
  }

  private void chooseJar() {
    FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, true, true, false, false)
        .withTitle("JDBC 드라이버 jar")
        .withExtensionFilter("jar");
    VirtualFile file = FileChooser.chooseFile(descriptor, getContentPane(), project, null);
    if (file == null) return;
    driverJar = file.getPath();
    refreshDriver();
  }

  private void loadPassword(DbConfig existing) {
    // 창이 뜨기 전(init)에도 불리므로 current() 를 쓰면 바깥 상태가 잡혀 창이 닫힐 때까지 갱신이 밀린다. 글자만 바꾸므로 any() 로 둔다.
    ModalityState modality = ModalityState.any();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      String stored = Passwords.get(existing);
      if (stored == null) return;
      ApplicationManager.getApplication().invokeLater(() -> {
        if (password.getPassword().length == 0) password.setText(stored); // 그 사이 사용자가 친 값은 덮지 않는다.
      }, modality);
    });
  }

  private void testConnection() {
    DbConfig probe = current();
    String pw = new String(password.getPassword());
    testResult.setForeground(UIUtil.getContextHelpForeground());
    testResult.setToolTipText(null);
    testResult.setText("시험 중…");
    // 창이 뜨기 전(init)에도 불리므로 current() 를 쓰면 바깥 상태가 잡혀 창이 닫힐 때까지 갱신이 밀린다. 글자만 바꾸므로 any() 로 둔다.
    ModalityState modality = ModalityState.any();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      long start = System.currentTimeMillis();
      String text;
      String tooltip = null;
      Color color;
      try {
        String product = DbSessions.test(probe, pw);
        text = "연결됨 · " + product + " · " + (System.currentTimeMillis() - start) + " ms";
        color = OK_COLOR;
      } catch (Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String first = msg.lines().findFirst().orElse(msg);
        text = first.length() > ERROR_PREVIEW ? first.substring(0, ERROR_PREVIEW) + "…" : first;
        tooltip = msg;
        color = ERROR_COLOR;
      }
      String shown = text;
      String tip = tooltip;
      Color fg = color;
      ApplicationManager.getApplication().invokeLater(() -> {
        testResult.setText(shown);
        testResult.setToolTipText(tip);
        testResult.setForeground(fg);
      }, modality);
    });
  }

  @Override
  protected @Nullable ValidationInfo doValidate() {
    if (name.getText().isBlank()) return new ValidationInfo("이름을 넣으세요.", name);
    if (host.getText().isBlank()) return new ValidationInfo("호스트를 넣으세요.", host);
    int p = parsePort();
    if (p < MIN_PORT || p > MAX_PORT) return new ValidationInfo("포트는 " + MIN_PORT + "–" + MAX_PORT + " 사이 숫자여야 합니다.", port);
    if (user.getText().isBlank()) return new ValidationInfo("사용자를 넣으세요.", user);
    return null;
  }

  /** 열린 접속을 고치면 새 설정으로 다시 열도록 닫는다. 미반영 변경이 있으면 롤백된다고 먼저 묻는다. */
  @Override
  protected void doOKAction() {
    DbConfig c = current();
    DbSessions sessions = DbSessions.get(project);
    boolean reopen = sessions.isOpen(c.id);
    int pending = sessions.pending(c.id);
    if (reopen && pending > 0
        && Messages.showYesNoDialog(getContentPane(), "미반영 변경 " + pending + "건이 롤백됩니다. 저장할까요?", "접속 편집", Messages.getWarningIcon()) != Messages.YES) {
      return;
    }
    DbSettings.getInstance().put(c);
    if (savePassword.isSelected()) Passwords.set(c, new String(password.getPassword()));
    if (reopen) ApplicationManager.getApplication().executeOnPooledThread(() -> sessions.close(c.id));
    saved = c;
    super.doOKAction();
  }
}
