package ei.db.gen;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiJavaToken;
import com.intellij.psi.PsiMember;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.DbSettings;
import ei.db.EiDbBundle;
import ei.db.Notify;
import ei.db.QueryResult;
import ei.db.SqlGuard;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * 자바 편집기 우클릭 「enum 다시 맞추기」: EnumDialog 가 만든 enum(Javadoc 에 @ei-sql)의 SQL 을 그 접속에서 다시 실행해
 * 그 enum 의 상수 목록(여는 중괄호 뒤부터 상수를 끝내는 ; 까지)만 새로 만든 상수로 바꾼다.
 * Javadoc · import · 어노테이션 · 손으로 더한 메서드 · 바깥 클래스(클래스 안 enum 일 때)는 그대로 둔다.
 * 상수에 몸통이나 어노테이션이 있으면 지워지지 않게 바꾸지 않는다.
 */
public final class EnumSyncAction extends AnAction {

  private static final int CODE_COL = 0;
  private static final int LABEL_COL = 1;
  private static final int MIN_COLUMNS = 2;
  private static final String SEMICOLON = ";";

  private record Target(Document doc, SmartPsiElementPointer<PsiClass> cls, String name, EnumGen.Source src) {}

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
    e.getPresentation().setEnabledAndVisible(editor != null && javadocOf(enumAt(file, editor.getCaretModel().getOffset())) != null);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
    if (project == null || editor == null) return;
    PsiClass cls = enumAt(file, editor.getCaretModel().getOffset());
    String doc = javadocOf(cls);
    EnumGen.Source src = doc == null ? null : EnumGen.source(doc);
    if (src == null) {
      Notify.warning(project, EiDbBundle.message("enumsync.no.tags", EnumGen.SQL_TAG, EnumGen.CONNECTION_TAG));
      return;
    }
    if (SqlGuard.isWrite(src.sql())) {
      Notify.error(project, EiDbBundle.message("enumsync.not.query", src.sql()));
      return;
    }
    DbConfig c = connectionNamed(src.connection());
    if (c == null) {
      Notify.error(project, EiDbBundle.message("enumsync.no.connection", src.connection(), EnumGen.CONNECTION_TAG));
      return;
    }
    if (constantsRange(cls) == null) {
      Notify.error(project, EiDbBundle.message("enumsync.unsupported", cls.getName()));
      return;
    }
    Target t = new Target(editor.getDocument(), SmartPointerManager.createPointer(cls), cls.getName(), src);
    rerun(project, c, t);
  }

  /** 커서를 감싼 enum. 읽기 접근 안에서 부른다. */
  private static @Nullable PsiClass enumAt(@Nullable PsiFile file, int offset) {
    if (!(file instanceof PsiJavaFile)) return null;
    PsiClass c = PsiTreeUtil.getParentOfType(file.findElementAt(offset), PsiClass.class);
    return c != null && c.isEnum() && c.getName() != null ? c : null;
  }

  /** @ei-sql 이 든 클래스 Javadoc 글. 없으면 null. */
  private static @Nullable String javadocOf(@Nullable PsiClass c) {
    PsiDocComment doc = c == null ? null : c.getDocComment();
    if (doc == null) return null;
    String text = doc.getText();
    return text.contains(EnumGen.SQL_TAG) ? text : null;
  }

  /** 이름이 같은 첫 접속. 이름은 앞뒤 공백을 뺀 채 비교한다. */
  private static @Nullable DbConfig connectionNamed(String name) {
    for (DbConfig c : DbSettings.getInstance().connections()) {
      if (c.name != null && c.name.strip().equals(name)) return c;
    }
    return null;
  }

  private static void rerun(Project project, DbConfig c, Target t) {
    new Task.Backgroundable(project, EiDbBundle.message("enumsync.progress", t.name()), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        QueryResult r;
        try {
          // 상수가 빠지지 않게 조회 건수 제한 없이 끝까지 읽는다.
          r = DbSessions.get(project).run(c, t.src().sql(), List.of(), DbSessions.ALL_ROWS);
        } catch (ProcessCanceledException e) {
          throw e;
        } catch (Exception e) {
          Notify.error(project, EiDbBundle.message("enumsync.run.failed", t.name(), e.getMessage()));
          return;
        }
        String text = regenerate(project, t, r);
        if (text == null) return;
        ApplicationManager.getApplication().invokeLater(() -> replace(project, t, text), ModalityState.nonModal(), project.getDisposed());
      }
    }.queue();
  }

  /** 새 상수 목록 글({@link EnumGen#constants}). 쓸 수 없는 결과면 알리고 null. */
  private static @Nullable String regenerate(Project project, Target t, QueryResult r) {
    if (!r.isQuery() || r.columns().size() < MIN_COLUMNS) {
      Notify.error(project, EiDbBundle.message("enumsync.no.two.columns", t.name()));
      return null;
    }
    int code = CODE_COL;
    int label = LABEL_COL;
    if (t.src().columns() != null) {
      code = indexOf(r.columns(), t.src().columns().get(0));
      label = indexOf(r.columns(), t.src().columns().get(1));
      if (code < 0 || label < 0) {
        Notify.error(project, EiDbBundle.message("enumsync.no.columns", t.name(), String.join(" · ", t.src().columns())));
        return null;
      }
    }
    List<EnumGen.Item> items = EnumGen.items(r.rows(), code, label);
    if (items.isEmpty()) {
      Notify.warning(project, EiDbBundle.message("enumsync.no.rows", t.name()));
      return null;
    }
    return EnumGen.constants(items);
  }

  private static int indexOf(List<String> columns, String name) {
    for (int i = 0; i < columns.size(); i++) {
      if (columns.get(i).toUpperCase(Locale.ROOT).equals(name.toUpperCase(Locale.ROOT))) return i;
    }
    return -1;
  }

  /**
   * 여는 중괄호 끝부터 상수 목록을 끝내는 ; 끝까지. 상수에 몸통 · 어노테이션이 있거나 ; 가 없으면 null(바꾸면 손으로 쓴 것이 지워진다).
   * 읽기 접근 안에서 부른다.
   */
  private static @Nullable TextRange constantsRange(PsiClass cls) {
    PsiElement lBrace = cls.getLBrace();
    if (lBrace == null) return null;
    for (PsiElement el = lBrace.getNextSibling(); el != null; el = el.getNextSibling()) {
      if (el instanceof PsiEnumConstant c) {
        if (c.getInitializingClass() != null || c.getModifierList() != null && c.getModifierList().getAnnotations().length > 0) return null;
      } else if (el instanceof PsiJavaToken tok && SEMICOLON.equals(tok.getText())) {
        return new TextRange(lBrace.getTextRange().getEndOffset(), tok.getTextRange().getEndOffset());
      } else if (el instanceof PsiMember || el == cls.getRBrace()) {
        return null;
      }
    }
    return null;
  }

  private static void replace(Project project, Target t, String constants) {
    PsiDocumentManager.getInstance(project).commitDocument(t.doc());
    PsiClass cls = t.cls().getElement();
    if (cls == null || !cls.isValid()) {
      Notify.error(project, EiDbBundle.message("enumsync.gone", t.name()));
      return;
    }
    TextRange range = constantsRange(cls);
    if (range == null || range.getEndOffset() > t.doc().getTextLength()) {
      Notify.error(project, EiDbBundle.message("enumsync.unsupported", t.name()));
      return;
    }
    if (constants.equals(t.doc().getText(range))) {
      Notify.info(project, EiDbBundle.message("enumsync.up.to.date", t.name()));
      return;
    }
    if (!t.doc().isWritable()) {
      Notify.error(project, EiDbBundle.message("enumsync.read.only", t.name()));
      return;
    }
    WriteCommandAction.writeCommandAction(project).withName(EiDbBundle.message("action.ei.db.gen.EnumSync.text"))
        .run(() -> t.doc().replaceString(range.getStartOffset(), range.getEndOffset(), constants));
    Notify.info(project, EiDbBundle.message("enumsync.done", t.name()));
  }
}
