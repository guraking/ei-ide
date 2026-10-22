package ei.db.mybatis;

import com.intellij.ide.util.TreeClassChooser;
import com.intellij.ide.util.TreeClassChooserFactory;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import ei.db.EiDbBundle;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 편집기 오른쪽 클릭: 매퍼 XML 의 select · update · delete 문장 안에서 DTO 필드로 &lt;where&gt; · &lt;if&gt; 조건을 만들어 넣는다.
 * DTO 는 parameterType(별칭 포함), 없으면 매퍼 인터페이스 메서드의 매개변수 형식, 둘 다 못 찾으면 사용자가 고른다.
 */
public final class DynamicWhereAction extends AnAction {
  private static final Set<String> TAGS = Set.of("select", "update", "delete");
  /** PsiModifier.STATIC 과 같은 값. */
  private static final String STATIC = "static";
  private static final String JAVA_PREFIX = "java.";

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setEnabledAndVisible(e.getProject() != null && statementAt(e.getProject(), e.getData(CommonDataKeys.EDITOR)) != null);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    Project project = e.getProject();
    if (editor == null || project == null) return;
    if (DumbService.isDumb(project)) {
      Notify.warning(project, EiDbBundle.message("dynamicwhere.dumb"));
      return;
    }
    Document document = editor.getDocument();
    int caret = editor.getCaretModel().getOffset();
    String text = document.getText();
    MapperDoc doc = MapperDoc.parse(text);
    MapperDoc.Node s = doc.statementAt(caret);
    if (s == null || !TAGS.contains(s.name)) {
      Notify.error(project, EiDbBundle.message("dynamicwhere.no.statement"));
      return;
    }
    PsiClass dto = dtoOf(project, doc, s);
    if (dto == null) {
      TreeClassChooser chooser = TreeClassChooserFactory.getInstance(project).createProjectScopeChooser(EiDbBundle.message("dynamicwhere.chooser.title"));
      chooser.showDialog();
      dto = chooser.getSelected();
      if (dto == null) return; // 고르는 창을 닫은 경우다.
    }
    List<WhereDialog.Field> fields = fields(dto);
    if (fields.isEmpty()) {
      Notify.warning(project, EiDbBundle.message("dynamicwhere.no.fields", dto.getName()));
      return;
    }
    int bodyStart = WhereGen.openEnd(text, s.start);
    int bodyEnd = WhereGen.closeStart(text, s);
    String alias = bodyStart < 0 || bodyEnd < 0 ? "" : WhereGen.guessAlias(text.substring(bodyStart, bodyEnd));
    WhereDialog dialog = new WhereDialog(project, dto.getName(), fields, alias);
    if (!dialog.showAndGet()) return;

    // 창이 열린 동안 문서가 바뀌었을 수 있어 지금 글로 다시 찾는다.
    String now = document.getText();
    MapperDoc.Node target = MapperDoc.parse(now).statementAt(caret);
    if (target == null || !TAGS.contains(target.name)) {
      Notify.error(project, EiDbBundle.message("dynamicwhere.document.changed"));
      return;
    }
    WhereGen.Insert in;
    try {
      in = WhereGen.insertion(now, target, caret, dialog.conds(), dialog.alias());
    } catch (IllegalArgumentException ex) {
      Notify.error(project, ex.getMessage());
      return;
    }
    if (!FileDocumentManager.getInstance().requestWriting(document, project)) {
      Notify.error(project, EiDbBundle.message("dynamicwhere.read.only"));
      return;
    }
    WriteCommandAction.runWriteCommandAction(project, EiDbBundle.message("action.EiDb.MyBatis.DynamicWhere.command"), null, () -> document.insertString(in.offset(), in.text()));
    editor.getCaretModel().moveToOffset(in.offset() + in.text().length());
    editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
  }

  /** 매퍼 XML 의 select · update · delete 문장. 아니면 null. */
  private static @Nullable MapperDoc.Node statementAt(Project project, @Nullable Editor editor) {
    if (editor == null) return null;
    CharSequence text = editor.getDocument().getCharsSequence();
    if (!StringUtil.contains(text, "<mapper")) return null;
    MapperDoc.Node s = MapperDocs.of(project, editor).statementAt(editor.getCaretModel().getOffset());
    return s != null && TAGS.contains(s.name) ? s : null;
  }

  // shortcut: #{필드} 꼴만 만든다. @Param 으로 이름 붙인 매개변수면 사용자가 앞말(dto.)을 붙인다.
  /** parameterType 의 클래스, 없으면 매퍼 인터페이스 메서드의 첫 프로젝트 클래스 매개변수 형식. 못 찾으면 null. */
  private static @Nullable PsiClass dtoOf(Project project, MapperDoc doc, MapperDoc.Node s) {
    PsiClass c = DtoFields.find(project, s.attr("parameterType"));
    if (c != null) return c;
    PsiMethod m = LayerCode.mapperMethod(project, doc.namespace, s.attr("id"));
    if (m == null) return null;
    for (PsiParameter p : m.getParameterList().getParameters()) {
      PsiClass pc = p.getType() instanceof PsiClassType ct ? ct.resolve() : null;
      String q = pc == null ? null : pc.getQualifiedName();
      if (q != null && !q.startsWith(JAVA_PREFIX)) return pc;
    }
    return null;
  }

  /** 필드(상위 클래스 포함, static 제외, 이름 겹침 제외)와 형식. */
  private static List<WhereDialog.Field> fields(PsiClass dto) {
    List<WhereDialog.Field> out = new ArrayList<>();
    List<String> seen = new ArrayList<>();
    for (PsiField f : dto.getAllFields()) {
      if (f.hasModifierProperty(STATIC) || seen.contains(f.getName())) continue;
      seen.add(f.getName());
      out.add(new WhereDialog.Field(f.getName(), f.getType().getCanonicalText()));
    }
    return out;
  }
}
