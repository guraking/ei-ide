package ei.db.mybatis;

import com.intellij.codeInsight.FileModificationService;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.util.IncorrectOperationException;
import ei.db.EiDbBundle;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 편집기 오른쪽 클릭: 매퍼 XML 의 select 문장 아래에 Oracle ROWNUM 페이징 문장(&lt;id&gt;Page)과 건수 문장(&lt;id&gt;Count)을 더한다.
 * 매퍼 인터페이스와 원래 메서드를 찾으면 그 아래에 두 메서드도 더한다. 같은 id 가 이미 있으면 아무것도 바꾸지 않는다.
 */
public final class PagingAction extends AnAction {
  private static final String SELECT = "select";
  private static final String OBJECT = "java.lang.Object";

  /** 인터페이스에 더할 두 메서드와 넣을 자리. anchor 가 null 이면 인터페이스 끝. */
  private record JavaPart(PsiClass mapper, @Nullable PsiMethod anchor, PsiMethod page, PsiMethod count) {}

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    boolean ok = false;
    if (editor != null && e.getProject() != null) {
      CharSequence text = editor.getDocument().getCharsSequence();
      if (StringUtil.contains(text, "<mapper")) {
        MapperDoc.Node s = MapperDocs.of(e.getProject(), editor).statementAt(editor.getCaretModel().getOffset());
        ok = s != null && SELECT.equals(s.name);
      }
    }
    e.getPresentation().setEnabledAndVisible(ok);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    Project project = e.getProject();
    if (editor == null || project == null) return;
    if (DumbService.isDumb(project)) {
      Notify.warning(project, EiDbBundle.message("paging.dumb"));
      return;
    }
    Document document = editor.getDocument();
    String text = document.getText();
    MapperDoc doc = MapperDoc.parse(text);
    MapperDoc.Node s = doc.statementAt(editor.getCaretModel().getOffset());
    if (s == null || !SELECT.equals(s.name)) {
      Notify.error(project, EiDbBundle.message("paging.no.select"));
      return;
    }
    PagingGen.Result r;
    try {
      r = PagingGen.generate(text, doc, s);
    } catch (IllegalArgumentException ex) {
      Notify.warning(project, ex.getMessage());
      return;
    }
    for (MapperIndex.Entry entry : MapperIndex.find(project, doc.namespace)) {
      for (String id : new String[]{r.pageId(), r.countId()}) {
        if (entry.ids().containsKey(id)) {
          Notify.warning(project, EiDbBundle.message("paging.exists", entry.file().getName(), id));
          return;
        }
      }
    }
    if (!FileDocumentManager.getInstance().requestWriting(document, project)) {
      Notify.error(project, EiDbBundle.message("paging.read.only"));
      return;
    }

    String id = s.attr("id");
    PsiClass mapper = LayerCode.findClass(project, doc.namespace);
    PsiMethod original = LayerCode.mapperMethod(project, doc.namespace, id);
    String javaNote;
    JavaPart part = null;
    if (mapper == null || !mapper.isInterface()) {
      javaNote = EiDbBundle.message("paging.note.no.mapper", doc.namespace);
    } else if (original == null) {
      javaNote = EiDbBundle.message("paging.note.no.method", mapper.getName(), id);
    } else if (mapper.findMethodsByName(r.pageId(), false).length > 0 || mapper.findMethodsByName(r.countId(), false).length > 0) {
      javaNote = EiDbBundle.message("paging.note.method.exists", mapper.getName(), r.pageId(), r.countId());
    } else if (!FileModificationService.getInstance().preparePsiElementForWrite(mapper)) {
      javaNote = EiDbBundle.message("paging.note.read.only", mapper.getName());
    } else {
      try {
        part = javaPart(project, mapper, original, r);
        javaNote = EiDbBundle.message("paging.note.added", mapper.getName());
      } catch (IncorrectOperationException ex) {
        javaNote = EiDbBundle.message("paging.note.failed", ex.getMessage());
      }
    }

    PsiDocumentManager.getInstance(project).commitAllDocuments();
    JavaPart java = part;
    try {
      WriteCommandAction.writeCommandAction(project).withName(EiDbBundle.message("action.EiDb.MyBatis.Paging.text")).run(() -> {
        document.insertString(r.offset(), r.text());
        if (java != null) addMethods(project, java);
      });
    } catch (RuntimeException ex) {
      Notify.error(project, EiDbBundle.message("paging.insert.failed", ex.getMessage()));
      return;
    }
    editor.getCaretModel().moveToOffset(r.offset());
    Notify.info(project, EiDbBundle.message("paging.done", r.pageId(), r.countId(), PagingGen.START_ROW, PagingGen.END_ROW, javaNote));
  }

  /** 원래 메서드의 반환 원소 형식 · 매개변수 글로 두 메서드를 만든다(아직 넣지 않는다). 읽기 접근 안에서 부른다. */
  private static JavaPart javaPart(Project project, PsiClass mapper, PsiMethod original, PagingGen.Result r) {
    PsiType rt = original.getReturnType();
    String canonical = rt == null ? OBJECT : rt instanceof PsiPrimitiveType p ? p.getBoxedTypeName() : rt.getCanonicalText();
    String element = ServiceCode.elementType(canonical);
    String params = original.getParameterList().getText();
    PsiElementFactory f = JavaPsiFacade.getElementFactory(project);
    PsiMethod page = f.createMethodFromText(PagingGen.pageMethod(element != null ? element : canonical, r.pageId(), params), mapper);
    PsiMethod count = f.createMethodFromText(PagingGen.countMethod(r.countId(), params), mapper);
    return new JavaPart(mapper, original.getContainingClass() == mapper ? original : null, page, count);
  }

  /** 쓰기 동작 안에서 부른다. 원래 메서드 바로 아래(상위 인터페이스 메서드면 인터페이스 끝)에 넣고 이름을 줄여 들여온다. */
  private static void addMethods(Project project, JavaPart part) {
    PsiElement page = part.anchor() != null ? part.mapper().addAfter(part.page(), part.anchor()) : part.mapper().add(part.page());
    PsiElement count = part.mapper().addAfter(part.count(), page);
    JavaCodeStyleManager styles = JavaCodeStyleManager.getInstance(project);
    CodeStyleManager.getInstance(project).reformat(styles.shortenClassReferences(page));
    CodeStyleManager.getInstance(project).reformat(styles.shortenClassReferences(count));
  }
}
