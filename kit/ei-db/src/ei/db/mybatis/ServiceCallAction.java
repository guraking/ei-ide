package ei.db.mybatis;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;

import java.util.function.UnaryOperator;

/**
 * 편집기 오른쪽 클릭 「호출 코드 복사」: 커서가 있는 매퍼 문장을 부르는 서비스 쪽 코드(매개변수 준비 + 호출 줄)를 클립보드에 넣는다.
 * 모양은 매퍼 인터페이스 메서드가 있으면 그것, 없으면 XML 속성으로 짐작한다(색인 중이면 XML 로만).
 */
public final class ServiceCallAction extends AnAction {
  private static final String MAPPER_MARK = "<mapper";
  /** namespace 가 없을 때 쓰는 매퍼 필드 이름. */
  private static final String FALLBACK_MAPPER_FIELD = "mapper";

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    boolean ok = false;
    if (editor != null && e.getProject() != null) {
      CharSequence text = editor.getDocument().getCharsSequence();
      ok = StringUtil.contains(text, MAPPER_MARK) && MapperDoc.parse(text).statementAt(editor.getCaretModel().getOffset()) != null;
    }
    e.getPresentation().setEnabledAndVisible(ok);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    Project project = e.getProject();
    if (editor == null || project == null) return;
    MapperDoc doc = MapperDoc.parse(editor.getDocument().getImmutableCharSequence());
    MapperDoc.Node s = doc.statementAt(editor.getCaretModel().getOffset());
    if (s == null) {
      Notify.error(project, "커서 위치에서 매퍼 문장(select · insert · update · delete)을 찾지 못했습니다.");
      return;
    }
    ServiceCode.Sig sig = DumbService.isDumb(project)
        ? ServiceCode.fromXml(doc, s, UnaryOperator.identity())
        : LayerCode.sigFor(project, doc, s);
    String field = doc.namespace == null ? FALLBACK_MAPPER_FIELD : ServiceCode.mapperField(doc.shortNamespace());
    String code = ServiceCall.code(s.name, s.attr("id"), sig, DynamicSql.paramNames(doc, s), field);
    CopyPasteManager.copyTextToClipboard(code);
    Notify.info(project, "호출 코드를 복사했습니다.");
  }
}
