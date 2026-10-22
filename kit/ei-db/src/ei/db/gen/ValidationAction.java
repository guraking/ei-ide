package ei.db.gen;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import ei.db.DbConfig;
import ei.db.DbKind;
import ei.db.DbSessions;
import ei.db.DbSettings;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 자바 편집기 우클릭 「입력 검증 붙이기…」: 커서가 있는 클래스의 필드에 표 칼럼 메타(NOT NULL · 길이 · 자릿수)로
 * Bean Validation 주석을 붙인다. 필드와 칼럼은 Names.camel(칼럼) == 필드 이름으로 짝짓는다. 정적 필드는 보지 않는다.
 */
public final class ValidationAction extends AnAction {

  private static final String JAKARTA = "jakarta.validation.constraints";
  private static final String JAVAX = "javax.validation.constraints";
  private static final String PROBE = "NotNull";
  /** 미리보기에 보이는 필드 줄 수. 넘으면 나머지는 개수만 적는다. */
  private static final int PREVIEW_LINES = 30;

  /** 필드 하나와 붙일 주석. */
  private record Plan(PsiField field, List<ValidationGen.Ann> anns) {}

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
    e.getPresentation().setEnabledAndVisible(editor != null && classAt(file, editor.getCaretModel().getOffset()) != null);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
    if (project == null || editor == null) return;
    if (DumbService.isDumb(project)) {
      Notify.warning(project, EiDbBundle.message("validation.dumb"));
      return;
    }
    PsiClass cls = classAt(file, editor.getCaretModel().getOffset());
    if (cls == null || cls.getName() == null) {
      Notify.warning(project, EiDbBundle.message("validation.no.class"));
      return;
    }
    String pkg = validationPackage(project);
    if (pkg == null) {
      Notify.error(project, EiDbBundle.message("validation.no.library"));
      return;
    }
    List<DbConfig> conns = DbSettings.getInstance().connections();
    if (conns.isEmpty()) {
      Notify.warning(project, EiDbBundle.message("validation.no.connection"));
      return;
    }
    Picker picker = new Picker(project, conns, ValidationGen.guessTable(cls.getName()));
    if (!picker.showAndGet()) return;
    DbConfig c = picker.config();
    // Oracle 은 따옴표 없이 만든 이름을 대문자로 저장하므로 소문자로 적어도 찾게 한다.
    boolean upper = c.kind == DbKind.ORACLE;
    readColumns(project, cls, pkg, c, upper ? picker.schema().toUpperCase(Locale.ROOT) : picker.schema(),
                upper ? picker.table().toUpperCase(Locale.ROOT) : picker.table());
  }

  /** 커서를 감싼 클래스(익명 · 인터페이스 · enum · 주석형 제외). 읽기 접근 안에서 부른다. */
  private static @Nullable PsiClass classAt(@Nullable PsiFile file, int offset) {
    if (!(file instanceof PsiJavaFile)) return null;
    PsiClass c = PsiTreeUtil.getParentOfType(file.findElementAt(offset), PsiClass.class);
    if (c == null || c.getName() == null || c.isInterface() || c.isEnum() || c.isAnnotationType()) return null;
    return c;
  }

  /** jakarta 판이 있으면 jakarta, 없고 javax 판이 있으면 javax, 둘 다 없으면 null. */
  private static @Nullable String validationPackage(Project project) {
    JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
    GlobalSearchScope scope = GlobalSearchScope.allScope(project);
    if (facade.findClass(JAKARTA + "." + PROBE, scope) != null) return JAKARTA;
    if (facade.findClass(JAVAX + "." + PROBE, scope) != null) return JAVAX;
    return null;
  }

  private static void readColumns(Project project, PsiClass cls, String pkg, DbConfig c, String schema, String table) {
    new Task.Backgroundable(project, EiDbBundle.message("validation.progress", table), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        List<Meta.Column> cols;
        try {
          Connection conn = DbSessions.get(project).connection(c);
          cols = Meta.columns(conn, c, schema, table);
        } catch (ProcessCanceledException e) {
          throw e;
        } catch (Exception e) {
          Notify.error(project, EiDbBundle.message("validation.read.failed", table, e.getMessage()));
          return;
        }
        if (cols.isEmpty()) {
          Notify.warning(project, EiDbBundle.message("validation.no.table", schema + "." + table));
          return;
        }
        ApplicationManager.getApplication().invokeLater(() -> confirmAndApply(project, cls, pkg, cols), project.getDisposed());
      }
    }.queue();
  }

  private static void confirmAndApply(Project project, PsiClass cls, String pkg, List<Meta.Column> cols) {
    if (!ReadAction.compute(cls::isValid)) {
      Notify.warning(project, EiDbBundle.message("validation.class.changed"));
      return;
    }
    int[] unmatched = new int[1];
    List<Plan> plans = ReadAction.compute(() -> plan(cls, cols, unmatched));
    if (plans.isEmpty()) {
      Notify.info(project, EiDbBundle.message("validation.nothing", unmatched[0]));
      return;
    }
    String message = ReadAction.compute(() -> preview(plans, unmatched[0]));
    PsiFile file = ReadAction.compute(cls::getContainingFile);
    if (Messages.showYesNoDialog(project, message, EiDbBundle.message("validation.title"), EiDbBundle.message("validation.ok"), EiDbBundle.message("common.cancel"), Messages.getQuestionIcon()) != Messages.YES) {
      return;
    }
    int[] added = new int[2];
    WriteCommandAction.writeCommandAction(project, file).withName(EiDbBundle.message("validation.title")).run(() -> apply(project, pkg, plans, added));
    Notify.info(project, EiDbBundle.message("validation.done", added[0], added[1]));
  }

  /** 칼럼과 짝지은 필드마다 붙일 주석. 붙일 것이 없는 필드는 빼고, 짝 없는 필드 수를 unmatched[0] 에 둔다. */
  private static List<Plan> plan(PsiClass cls, List<Meta.Column> cols, int[] unmatched) {
    Map<String, Meta.Column> byField = new HashMap<>();
    for (Meta.Column col : cols) byField.putIfAbsent(Names.camel(col.name()), col);
    List<Plan> out = new ArrayList<>();
    for (PsiField f : cls.getFields()) {
      if (f.hasModifierProperty(PsiModifier.STATIC)) continue;
      Meta.Column col = byField.get(f.getName());
      if (col == null) {
        unmatched[0]++;
        continue;
      }
      List<ValidationGen.Ann> anns = ValidationGen.annotations(col, f.getType().getPresentableText(), annotationNames(f));
      if (!anns.isEmpty()) out.add(new Plan(f, anns));
    }
    return out;
  }

  /** 필드에 이미 있는 주석의 단순 이름. 풀리지 않는 주석은 쓴 이름 그대로. */
  private static Set<String> annotationNames(PsiField f) {
    Set<String> out = new HashSet<>();
    PsiModifierList ml = f.getModifierList();
    if (ml == null) return out;
    for (PsiAnnotation a : ml.getAnnotations()) {
      String fqn = a.getQualifiedName();
      PsiJavaCodeReferenceElement ref = a.getNameReferenceElement();
      if (fqn != null) out.add(Names.simple(fqn));
      else if (ref != null && ref.getReferenceName() != null) out.add(ref.getReferenceName());
    }
    return out;
  }

  private static String preview(List<Plan> plans, int unmatched) {
    StringBuilder b = new StringBuilder();
    int shown = 0;
    int total = 0;
    for (Plan p : plans) {
      total += p.anns().size();
      if (shown >= PREVIEW_LINES) continue;
      b.append(p.field().getName()).append(" :");
      for (ValidationGen.Ann a : p.anns()) b.append(' ').append(a.shortText());
      b.append('\n');
      shown++;
    }
    if (plans.size() > shown) b.append(EiDbBundle.message("validation.preview.more", plans.size() - shown)).append('\n');
    b.append('\n').append(EiDbBundle.message("validation.preview.summary", plans.size(), total, unmatched));
    return b.toString();
  }

  /** 쓰기 명령 안에서 부른다. added[0] 은 고친 필드 수, added[1] 은 붙인 주석 수. */
  private static void apply(Project project, String pkg, List<Plan> plans, int[] added) {
    PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
    JavaCodeStyleManager style = JavaCodeStyleManager.getInstance(project);
    for (Plan p : plans) {
      PsiField f = p.field();
      PsiModifierList ml = f.isValid() ? f.getModifierList() : null;
      if (ml == null) continue;
      List<ValidationGen.Ann> anns = p.anns();
      // addAnnotation 은 맨 앞에 넣으므로 거꾸로 넣어야 미리보기 순서대로 놓인다.
      for (int i = anns.size() - 1; i >= 0; i--) {
        ValidationGen.Ann ann = anns.get(i);
        PsiAnnotation slot = ml.addAnnotation(pkg + "." + ann.name());
        if (!ann.args().isEmpty()) slot.replace(factory.createAnnotationFromText(ann.text(pkg), f));
      }
      style.shortenClassReferences(ml);
      added[0]++;
      added[1] += anns.size();
    }
  }

  /** 접속 · 스키마 · 표 이름을 고르는 창. */
  private static final class Picker extends DialogWrapper {
    private final ComboBox<DbConfig> connection;
    private final JBTextField schemaField = new JBTextField();
    private final JBTextField tableField = new JBTextField();

    Picker(Project project, List<DbConfig> conns, String table) {
      super(project, true);
      connection = new ComboBox<>(conns.toArray(new DbConfig[0]));
      schemaField.setText(Meta.schemaOf(conns.get(0)));
      tableField.setText(table);
      connection.addActionListener(e -> {
        DbConfig c = config();
        if (c != null) schemaField.setText(Meta.schemaOf(c));
      });
      setTitle(EiDbBundle.message("validation.title"));
      setOKButtonText(EiDbBundle.message("validation.read.columns"));
      setCancelButtonText(EiDbBundle.message("common.cancel"));
      init();
    }

    @Override
    protected JComponent createCenterPanel() {
      return FormBuilder.createFormBuilder()
          .addLabeledComponent(EiDbBundle.message("common.connection"), connection)
          .addLabeledComponent(EiDbBundle.message("common.schema"), schemaField)
          .addLabeledComponent(EiDbBundle.message("guard.table.label"), tableField)
          .getPanel();
    }

    @Override
    public JComponent getPreferredFocusedComponent() { return tableField; }

    @Override
    protected ValidationInfo doValidate() {
      if (config() == null) return new ValidationInfo(EiDbBundle.message("common.connection.empty"), connection);
      if (schema().isEmpty()) return new ValidationInfo(EiDbBundle.message("common.schema.empty"), schemaField);
      if (table().isEmpty()) return new ValidationInfo(EiDbBundle.message("common.table.empty"), tableField);
      return null;
    }

    DbConfig config() { return (DbConfig) connection.getSelectedItem(); }

    String schema() { return schemaField.getText().strip(); }

    String table() { return tableField.getText().strip(); }
  }
}
