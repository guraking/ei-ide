package ei.db.mybatis;

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
import com.intellij.psi.PsiClass;
import ei.db.EiDbBundle;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 편집기 오른쪽 클릭: 매퍼 XML 의 select 문장 위에 SELECT 칼럼 ↔ resultType 필드 짝으로 &lt;resultMap&gt;(&lt;id&gt;ResultMap)을 넣고
 * 문장의 resultType 을 그 resultMap 으로 바꾼다. 만들 수 없으면(resultType 이 프로젝트 클래스가 아님, SELECT *, 같은 id 있음) 아무것도 바꾸지 않는다.
 */
public final class ResultMapAction extends AnAction {
  private static final String SELECT = "select";

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    boolean ok = false;
    if (editor != null && e.getProject() != null && StringUtil.contains(editor.getDocument().getCharsSequence(), "<mapper")) {
      MapperDoc.Node s = MapperDocs.of(e.getProject(), editor).statementAt(editor.getCaretModel().getOffset());
      ok = s != null && SELECT.equals(s.name);
    }
    e.getPresentation().setEnabledAndVisible(ok);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    Project project = e.getProject();
    if (editor == null || project == null) return;
    if (DumbService.isDumb(project)) {
      Notify.warning(project, EiDbBundle.message("resultmap.dumb"));
      return;
    }
    Document document = editor.getDocument();
    String text = document.getText();
    MapperDoc doc = MapperDoc.parse(text);
    MapperDoc.Node s = doc.statementAt(editor.getCaretModel().getOffset());
    if (s == null || !SELECT.equals(s.name)) {
      Notify.error(project, EiDbBundle.message("resultmap.no.select"));
      return;
    }
    PsiClass dto = DtoFields.find(project, s.attr("resultType"));
    // resultMap 을 쓰는 문장은 dto 가 없어도 ResultMapGen 이 알맞은 문구로 거절한다.
    if (dto == null && s.attr("resultMap") == null) {
      Notify.warning(project, EiDbBundle.message("resultmap.no.class", String.valueOf(s.attr("resultType"))));
      return;
    }
    ResultMapGen.Result r;
    try {
      r = ResultMapGen.generate(text, doc, s, dto == null ? List.of() : DtoFields.of(dto));
    } catch (IllegalArgumentException ex) {
      Notify.warning(project, ex.getMessage());
      return;
    }
    if (!FileDocumentManager.getInstance().requestWriting(document, project)) {
      Notify.error(project, EiDbBundle.message("resultmap.read.only"));
      return;
    }
    try {
      WriteCommandAction.writeCommandAction(project).withName(EiDbBundle.message("action.EiDb.MyBatis.ResultMap.text")).run(() -> {
        document.replaceString(r.attrStart(), r.attrEnd(), r.attrText());
        document.insertString(r.insertAt(), r.insertText());
      });
    } catch (RuntimeException ex) {
      Notify.error(project, EiDbBundle.message("resultmap.insert.failed", ex.getMessage()));
      return;
    }
    editor.getCaretModel().moveToOffset(r.insertAt());
    String done = EiDbBundle.message("resultmap.done", r.id(), r.paired());
    if (r.unmatched().isEmpty()) Notify.info(project, done);
    else Notify.warning(project, done + " " + EiDbBundle.message("resultmap.unmatched", String.join(", ", r.unmatched())));
  }
}
