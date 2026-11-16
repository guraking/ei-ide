package ei.db.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.panel.ComponentPanelBuilder;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.DbKind;
import ei.db.DbSessions;
import ei.db.EiDbBundle;
import ei.db.FileTypes;
import ei.db.Notify;
import ei.db.SqlRunner;
import ei.db.console.ConsoleOpener;
import ei.db.gen.Ui;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 탐색기 「시노님 · 권한 주기」: 고른 표 · 뷰(또는 Oracle 프로그램 하나)에 GRANT · REVOKE 와 Oracle 시노님 문을 만들어 미리 보이고 실행한다.
 * 받는 사용자는 칸에 쓰거나(쉼표로 여럿) 「…」 단추로 DB 사용자 목록에서 고른다. 목록은 창을 열 때 한 번 백그라운드에서 읽는다.
 * 이름 꼴이 아닌 글은 SQL 에 넣지 않고 아래 줄에 알린다(GrantSql.readGrantees).
 * 「실행」은 SqlRunner.runAll 로: 읽기 전용 거절 · 운영 접속 확인 · DROP 확인 · Oracle 암묵 커밋 확인이 그대로 걸린다. 앞 문장이 실패하면 멈추고 알린다.
 * MySQL 은 사용자 이름에 호스트가 붙어 창을 열지 않고 알린다. 창은 모달이 아니다.
 */
final class GrantDialog extends DialogWrapper {

  /** 처음 열 때 화면 폭 · 높이에 대한 창 크기 비율. 사용자가 바꾼 크기는 IntelliJ 가 기억한다. */
  private static final double FIRST_WIDTH_RATIO = 0.5;
  private static final double FIRST_HEIGHT_RATIO = 0.6;
  private static final String DIMENSION_KEY = "ei.db.ui.GrantDialog";
  private static final int GAP = 6;
  private static final String NAME_SEPARATOR = ", ";

  private final Project project;
  private final DbConfig c;
  private final String schema;
  private final List<String> objects;
  private final boolean program;

  private final JBRadioButton grant = new JBRadioButton(EiDbBundle.message("grant.mode.grant"), true);
  private final JBRadioButton revoke = new JBRadioButton(EiDbBundle.message("grant.mode.revoke"));
  private final TextFieldWithBrowseButton grantees = new TextFieldWithBrowseButton();
  /** 권한 고르기와 그 SQL 낱말(같은 순서). */
  private final List<JBCheckBox> privilegeBoxes = new ArrayList<>();
  private final List<String> privilegeWords = new ArrayList<>();
  private final JBCheckBox grantOption = new JBCheckBox(EiDbBundle.message("grant.option"));
  private final JBRadioButton synonymNone = new JBRadioButton(EiDbBundle.message("grant.synonym.none"), true);
  private final JBRadioButton synonymGrantee = new JBRadioButton(EiDbBundle.message("grant.synonym.grantee"));
  private final JBRadioButton synonymPublic = new JBRadioButton(EiDbBundle.message("grant.synonym.public"));
  private final JBCheckBox schemaUsage = new JBCheckBox(EiDbBundle.message("grant.usage"), true);
  private final EditorTextField preview;
  private final JBLabel status = new JBLabel(" ");
  /** DB 사용자 이름(DB 에 저장된 그대로). 읽기 전 · 실패면 null. 화면 스레드에서만 바꾼다. */
  private List<String> users;
  private String usersError;
  /** 실행하는 동안 참: 같은 문장을 두 번 보내지 않게 단추를 끈다. */
  private boolean running;

  private final Action runAction = new DialogWrapperAction(EiDbBundle.message("grant.run")) {
    @Override
    protected void doAction(ActionEvent e) { run(); }
  };
  private final Action consoleAction = new DialogWrapperAction(EiDbBundle.message("querypick.send")) {
    @Override
    protected void doAction(ActionEvent e) { ConsoleOpener.open(project, c, GrantSql.script(statements())); }
  };

  /** objects 는 schema 안 메타 이름 그대로, 하나 이상. program 이면 실행 권한(EXECUTE)만 고른다. 화면 스레드에서 부른다. */
  static void show(Project project, DbConfig c, String schema, List<String> objects, boolean program) {
    if (c.kind == DbKind.MYSQL) {
      Notify.warning(project, EiDbBundle.message("grant.mysql.unsupported"));
      return;
    }
    new GrantDialog(project, c, schema, List.copyOf(objects), program).show();
  }

  private GrantDialog(Project project, DbConfig c, String schema, List<String> objects, boolean program) {
    super(project, false);
    this.project = project;
    this.c = c;
    this.schema = schema;
    this.objects = objects;
    this.program = program;
    preview = new EditorTextField(EditorFactory.getInstance().createDocument(""), project, FileTypes.byExtension("sql"), true, false);
    preview.addSettingsProvider(e -> {
      e.setVerticalScrollbarVisible(true);
      e.setHorizontalScrollbarVisible(true);
    });
    setModal(false);
    String what = objects.size() == 1 ? schema + "." + objects.get(0) : EiDbBundle.message("grant.title.many", schema, objects.size());
    setTitle(EiDbBundle.message("grant.title", c.name, what));
    setCancelButtonText(EiDbBundle.message("common.close"));
    init();
    refresh();
    loadUsers();
  }

  @Override
  protected @Nullable String getDimensionServiceKey() { return DIMENSION_KEY; }

  @Override
  public Dimension getInitialSize() { return Ui.firstSize(FIRST_WIDTH_RATIO, FIRST_HEIGHT_RATIO); }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[] {runAction, consoleAction, getCancelAction()};
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() { return grantees.getTextField(); }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JBTextField target = new JBTextField(schema + "." + String.join(NAME_SEPARATOR, objects));
    target.setEditable(false);

    if (program) {
      addPrivilege("grant.priv.execute", GrantSql.EXECUTE, true);
    } else {
      addPrivilege("grant.priv.select", GrantSql.SELECT, true);
      addPrivilege("grant.priv.insert", GrantSql.INSERT, false);
      addPrivilege("grant.priv.update", GrantSql.UPDATE, false);
      addPrivilege("grant.priv.delete", GrantSql.DELETE, false);
    }

    grantees.addActionListener(e -> pickUsers());
    grantees.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) { refresh(); }
    });
    List<AbstractButton> all = new ArrayList<>(List.of(grant, revoke, grantOption, synonymNone, synonymGrantee, synonymPublic, schemaUsage));
    all.addAll(privilegeBoxes);
    for (AbstractButton b : all) b.addActionListener(e -> refresh());

    FormBuilder form = FormBuilder.createFormBuilder()
        .addLabeledComponent(EiDbBundle.message("grant.target"), target)
        .addLabeledComponent(EiDbBundle.message("grant.mode"), row(group(grant, revoke)))
        .addLabeledComponent(EiDbBundle.message("grant.grantees"), grantees)
        .addLabeledComponent(EiDbBundle.message("grant.privileges"), row(privilegeBoxes.toArray(new AbstractButton[0])))
        .addLabeledComponent("", grantOption);
    if (c.kind == DbKind.ORACLE) {
      form.addLabeledComponent(EiDbBundle.message("grant.synonym"), row(group(synonymNone, synonymGrantee, synonymPublic)));
      form.addComponentToRightColumn(help(EiDbBundle.message("grant.help.oracle")));
    } else {
      form.addLabeledComponent("", schemaUsage);
      form.addComponentToRightColumn(help(EiDbBundle.message("grant.help.postgres")));
    }

    JPanel center = new JPanel(new BorderLayout(0, JBUI.scale(GAP)));
    center.add(new JBLabel(EiDbBundle.message("grant.preview")), BorderLayout.NORTH);
    center.add(preview, BorderLayout.CENTER);

    JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(GAP)));
    panel.add(form.getPanel(), BorderLayout.NORTH);
    panel.add(center, BorderLayout.CENTER);
    panel.add(status, BorderLayout.SOUTH);
    return panel;
  }

  private void addPrivilege(String key, String word, boolean selected) {
    privilegeBoxes.add(new JBCheckBox(EiDbBundle.message(key), selected));
    privilegeWords.add(word);
  }

  private static AbstractButton[] group(AbstractButton... buttons) {
    ButtonGroup g = new ButtonGroup();
    for (AbstractButton b : buttons) g.add(b);
    return buttons;
  }

  /** 단추 · 고르기 칸을 한 줄로(글 칸이 아니라 짧아도 된다). */
  private static JPanel row(AbstractButton... buttons) {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP * 2), 0));
    p.setBorder(JBUI.Borders.empty());
    for (AbstractButton b : buttons) p.add(b);
    return p;
  }

  /** 칸 아래 회색 설명(스톡 설정 화면과 같은 꼴, 창 폭에 맞춰 줄바꿈). */
  private static JComponent help(String text) {
    return ComponentPanelBuilder.createCommentComponent(text, true, -1, true);
  }

  // ---- 문장 ----

  private List<String> statements() {
    List<String> privileges = new ArrayList<>();
    for (int i = 0; i < privilegeBoxes.size(); i++) {
      if (privilegeBoxes.get(i).isSelected()) privileges.add(privilegeWords.get(i));
    }
    GrantSql.Synonym synonym = synonymGrantee.isSelected() ? GrantSql.Synonym.GRANTEE
                             : synonymPublic.isSelected() ? GrantSql.Synonym.PUBLIC_SYNONYM : GrantSql.Synonym.NONE;
    GrantSql.Grantees g = GrantSql.readGrantees(grantees.getText());
    // 이름 꼴이 아닌 조각이 하나라도 있으면 문장을 만들지 않는다: 뺀 채로 실행되면 사용자가 모르고 넘어간다.
    if (!g.invalid().isEmpty()) return List.of();
    return GrantSql.statements(new GrantSql.Request(c.kind, revoke.isSelected(), schema, objects, privileges, g.names(),
        grantOption.isSelected(), synonym, schemaUsage.isSelected()));
  }

  /** 미리보기 · 단추 · 아래 줄을 지금 화면 값에 맞춘다. */
  private void refresh() {
    grantOption.setEnabled(grant.isSelected());
    schemaUsage.setEnabled(grant.isSelected());
    String script = GrantSql.script(statements());
    preview.setText(script);
    runAction.setEnabled(!script.isEmpty() && !running);
    consoleAction.setEnabled(!script.isEmpty());
    List<String> invalid = GrantSql.readGrantees(grantees.getText()).invalid();
    if (!invalid.isEmpty()) status(EiDbBundle.message("grant.invalid", String.join(NAME_SEPARATOR, invalid)), true);
    else if (script.isEmpty()) status(EiDbBundle.message("grant.need"), false);
    else if (usersError != null) status(EiDbBundle.message("grant.users.failed", usersError), true);
    else status(" ", false);
  }

  private void status(String text, boolean error) {
    status.setText(text);
    status.setToolTipText(text.isBlank() ? null : text);
    status.setForeground(error ? UIUtil.getErrorForeground() : UIUtil.getContextHelpForeground());
  }

  // ---- 받는 사용자 목록 ----

  private void loadUsers() {
    ModalityState modality = ModalityState.current();
    DbSessions sessions = DbSessions.get(project);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      List<String> read = new ArrayList<>();
      String error = null;
      try {
        Connection conn = sessions.connection(c);
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(GrantSql.usersSql(c.kind))) {
          while (rs.next()) read.add(rs.getString(1));
        }
      } catch (Exception e) {
        error = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
      } finally {
        sessions.endReadTransaction(c);
      }
      String failed = error;
      ApplicationManager.getApplication().invokeLater(() -> {
        if (isDisposed()) return;
        usersError = failed;
        users = failed == null ? read : null;
        refresh();
      }, modality);
    });
  }

  /** 「…」 단추: 사용자 목록에서 여럿 골라 칸 끝에 덧붙인다(이미 있는 이름은 건너뜀). 목록이 아직 없으면 까닭을 아래 줄에 보인다. */
  private void pickUsers() {
    if (users == null) {
      status(usersError == null ? EiDbBundle.message("grant.users.loading") : EiDbBundle.message("grant.users.failed", usersError), usersError != null);
      return;
    }
    List<String> items = new ArrayList<>();
    items.add(GrantSql.PUBLIC);
    for (String u : users) {
      if (u != null && !u.equalsIgnoreCase(GrantSql.PUBLIC)) items.add(u);
    }
    JBPopupFactory.getInstance().createPopupChooserBuilder(items)
        .setTitle(EiDbBundle.message("grant.users.title"))
        .setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        .setNamerForFiltering(s -> s)
        .setItemsChosenCallback(chosen -> addGrantees(new ArrayList<>(chosen)))
        .createPopup()
        .showUnderneathOf(grantees);
  }

  private void addGrantees(List<String> chosen) {
    List<String> have = GrantSql.readGrantees(grantees.getText()).names();
    StringBuilder text = new StringBuilder(grantees.getText().strip());
    for (String u : chosen) {
      String name = GrantSql.granteeText(c.kind, u);
      if (have.contains(name)) continue;
      if (!text.isEmpty()) text.append(NAME_SEPARATOR);
      text.append(name);
    }
    grantees.setText(text.toString());
    refresh();
  }

  // ---- 실행 ----

  private void run() {
    if (running) return;
    List<String> sqls = statements();
    if (sqls.isEmpty()) return;
    running = SqlRunner.runAll(project, c, sqls, (done, error) -> ran(sqls.size(), done, error));
    if (running) status(EiDbBundle.message("grant.running", sqls.size()), false);
    runAction.setEnabled(!running);
  }

  /** 창이 그사이 닫혔어도 결과는 알린다. */
  private void ran(int total, int done, String error) {
    running = false;
    String text;
    if (error != null) {
      text = EiDbBundle.message("grant.stopped", done, total, error);
      Notify.error(project, text);
    } else {
      boolean uncommitted = DbSessions.get(project).pending(c.id) > 0;
      text = EiDbBundle.message(uncommitted ? "grant.done.uncommitted" : "grant.done", done);
      Notify.info(project, text);
    }
    if (isDisposed()) return;
    refresh();
    status(text, error != null);
  }
}
