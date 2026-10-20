package ei.db.mybatis;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiConstantEvaluationHelper;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import ei.db.Notify;
import ei.db.console.SqlFormatter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 자바 편집기 오른쪽 클릭 「문자열 SQL 정리…」: 커서 자리의 SQL 조각을 모아 정리한 SQL 과 매퍼 XML 문장을 보여 준다.
 * 커서가 (가) StringBuilder · StringBuffer 지역 변수를 쓰는 줄이면 그 변수의 초기값과 같은 메서드 안 append(…) 를,
 * (나) String 지역 변수의 선언 · += 줄이면 초기값과 같은 메서드 안 += 를, (다) 그 밖에 + 로 이은 문자열 식 안이면 가장 바깥 식을 모은다.
 * 모은 조각에 글(리터럴 · 상수)이 하나도 없으면 SQL 로 보지 않는다.
 * shortcut: 중간의 sb.setLength(0) · sql = … 다시 대입 · if 갈래는 따지지 않고 소스 순서대로 모두 잇는다.
 */
public final class StringSqlAction extends AnAction {

  private static final String APPEND = "append";
  private static final String STRING_BUILDER = "java.lang.StringBuilder";
  private static final String STRING_BUFFER = "java.lang.StringBuffer";

  /** 모은 조각과 감싸는 메서드 이름(메서드 밖이면 null). */
  record Found(List<SqlConcat.Piece> pieces, @Nullable String methodName) {}

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    Project project = e.getProject();
    PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
    boolean ok = editor != null && project != null && file instanceof PsiJavaFile && !DumbService.isDumb(project)
                 && find(file, editor.getCaretModel().getOffset()) != null;
    e.getPresentation().setEnabledAndVisible(ok);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    Project project = e.getProject();
    PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
    if (editor == null || project == null || !(file instanceof PsiJavaFile)) return;
    if (DumbService.isDumb(project)) {
      Notify.warning(project, "색인 중에는 문자열 SQL 을 정리할 수 없습니다. 색인이 끝난 뒤 다시 해 주세요.");
      return;
    }
    int offset = editor.getCaretModel().getOffset();
    Found found = ReadAction.compute(() -> find(file, offset));
    if (found == null) {
      Notify.warning(project, "커서 자리에서 + 로 이은 문자열 · StringBuilder · += 로 만든 SQL 을 찾지 못했습니다.");
      return;
    }
    String raw = SqlConcat.sql(found.pieces());
    String sql;
    try {
      sql = SqlFormatter.format(raw);
    } catch (RuntimeException ex) {
      Notify.warning(project, "SQL 정렬에 실패했습니다(" + ex + "). 정렬하지 않은 SQL 을 보여 줍니다.");
      sql = raw.strip();
    }
    new StringSqlDialog(project, sql, SqlConcat.xml(sql, found.methodName())).show();
  }

  /** read action 안에서 부른다. 못 찾으면 null. */
  static @Nullable Found find(PsiFile file, int offset) {
    PsiElement at = file.findElementAt(offset);
    if (at == null && offset > 0) at = file.findElementAt(offset - 1);
    if (at == null) return null;
    PsiConstantEvaluationHelper constants = JavaPsiFacade.getInstance(file.getProject()).getConstantEvaluationHelper();
    List<SqlConcat.Piece> pieces = null;
    PsiLocalVariable v = variableAt(at);
    if (v != null) pieces = isBuilder(v.getType()) ? builderPieces(v, constants) : plusEqPieces(v, constants);
    if (!hasText(pieces)) {
      PsiPolyadicExpression concat = outermostConcat(at);
      pieces = concat == null ? null : flatten(concat, constants);
    }
    if (!hasText(pieces)) return null;
    PsiMethod method = PsiTreeUtil.getParentOfType(at, PsiMethod.class);
    return new Found(pieces, method == null ? null : method.getName());
  }

  private static boolean hasText(@Nullable List<SqlConcat.Piece> pieces) {
    if (pieces == null) return false;
    for (SqlConcat.Piece p : pieces) {
      if (p.literal() && !p.text().isBlank()) return true;
    }
    return false;
  }

  // ---- 커서 자리의 변수 ----

  /** 선언 줄의 StringBuilder · String 변수, sql += … 줄의 String 변수, 그 밖에 줄 안에서 쓰는 StringBuilder 변수. */
  private static @Nullable PsiLocalVariable variableAt(PsiElement at) {
    PsiStatement st = PsiTreeUtil.getParentOfType(at, PsiStatement.class, false);
    if (st == null) return null;
    if (st instanceof PsiDeclarationStatement d) {
      for (PsiElement de : d.getDeclaredElements()) {
        if (de instanceof PsiLocalVariable v && (isBuilder(v.getType()) || isString(v.getType()))) return v;
      }
    }
    if (st instanceof PsiExpressionStatement es && es.getExpression() instanceof PsiAssignmentExpression a) {
      PsiLocalVariable v = plusEqTarget(a);
      if (v != null) return v;
    }
    for (PsiReferenceExpression ref : PsiTreeUtil.findChildrenOfType(st, PsiReferenceExpression.class)) {
      if (ref.resolve() instanceof PsiLocalVariable v && isBuilder(v.getType())) return v;
    }
    return null;
  }

  /** v += … 이고 v 가 String 지역 변수면 v. */
  private static @Nullable PsiLocalVariable plusEqTarget(PsiAssignmentExpression a) {
    if (a.getOperationTokenType() != JavaTokenType.PLUSEQ) return null;
    PsiExpression left = PsiUtil.skipParenthesizedExprDown(a.getLExpression());
    return left instanceof PsiReferenceExpression ref && ref.resolve() instanceof PsiLocalVariable v && isString(v.getType()) ? v : null;
  }

  private static boolean isBuilder(PsiType t) {
    return t.equalsToText(STRING_BUILDER) || t.equalsToText(STRING_BUFFER);
  }

  private static boolean isString(@Nullable PsiType t) {
    return t != null && t.equalsToText(CommonClassNames.JAVA_LANG_STRING);
  }

  /** 변수가 사는 메서드(없으면 감싸는 코드 블록). */
  private static @Nullable PsiElement scopeOf(PsiLocalVariable v) {
    PsiMethod m = PsiTreeUtil.getParentOfType(v, PsiMethod.class);
    return m != null ? m : PsiTreeUtil.getParentOfType(v, PsiCodeBlock.class);
  }

  // ---- 조각 모으기 ----

  /** new StringBuilder("…") 의 글 인자 + 같은 메서드 안 v.append(x) · v.append(a).append(b) 의 인자를 소스 순서대로. */
  private static List<SqlConcat.Piece> builderPieces(PsiLocalVariable v, PsiConstantEvaluationHelper constants) {
    List<SqlConcat.Piece> out = new ArrayList<>();
    PsiExpression init = PsiUtil.skipParenthesizedExprDown(v.getInitializer());
    if (init instanceof PsiNewExpression n && n.getArgumentList() != null) {
      PsiExpression[] args = n.getArgumentList().getExpressions();
      if (args.length == 1 && isString(args[0].getType())) out.addAll(flatten(args[0], constants));
    }
    PsiElement scope = scopeOf(v);
    if (scope == null) return out;
    List<PsiMethodCallExpression> appends = new ArrayList<>();
    for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(scope, PsiMethodCallExpression.class)) {
      if (APPEND.equals(call.getMethodExpression().getReferenceName()) && appendsTo(call, v)
          && call.getArgumentList().getExpressions().length == 1) {
        appends.add(call);
      }
    }
    // 이어 쓴 append 는 바깥 호출이 같은 자리에서 시작하므로 인자 자리로 줄 세운다.
    appends.sort(Comparator.comparingInt(c -> c.getArgumentList().getTextRange().getStartOffset()));
    for (PsiMethodCallExpression call : appends) out.addAll(flatten(call.getArgumentList().getExpressions()[0], constants));
    return out;
  }

  /** append 연쇄의 맨 앞 받는 쪽이 v 인지. */
  private static boolean appendsTo(PsiMethodCallExpression call, PsiLocalVariable v) {
    PsiExpression q = PsiUtil.skipParenthesizedExprDown(call.getMethodExpression().getQualifierExpression());
    while (q instanceof PsiMethodCallExpression inner && APPEND.equals(inner.getMethodExpression().getReferenceName())) {
      q = PsiUtil.skipParenthesizedExprDown(inner.getMethodExpression().getQualifierExpression());
    }
    return q instanceof PsiReferenceExpression ref && ref.resolve() == v;
  }

  /** String v 의 초기값 + 같은 메서드 안 v += … 를 소스 순서대로. */
  private static List<SqlConcat.Piece> plusEqPieces(PsiLocalVariable v, PsiConstantEvaluationHelper constants) {
    List<SqlConcat.Piece> out = new ArrayList<>();
    if (v.getInitializer() != null) out.addAll(flatten(v.getInitializer(), constants));
    PsiElement scope = scopeOf(v);
    if (scope == null) return out;
    for (PsiAssignmentExpression a : PsiTreeUtil.findChildrenOfType(scope, PsiAssignmentExpression.class)) {
      if (plusEqTarget(a) == v && a.getRExpression() != null) out.addAll(flatten(a.getRExpression(), constants));
    }
    return out;
  }

  /** at 을 감싸는 문자열 + 식 중 가장 바깥 것. 괄호는 건너 올라간다. */
  private static @Nullable PsiPolyadicExpression outermostConcat(PsiElement at) {
    PsiPolyadicExpression found = null;
    for (PsiElement p = at; p != null && !(p instanceof PsiStatement) && !(p instanceof PsiCodeBlock); p = p.getParent()) {
      if (p instanceof PsiPolyadicExpression pe && isStringConcat(pe)) found = pe;
      else if (found != null && !(p instanceof PsiParenthesizedExpression)) break;
    }
    return found;
  }

  private static boolean isStringConcat(PsiPolyadicExpression e) {
    return e.getOperationTokenType() == JavaTokenType.PLUS && isString(e.getType());
  }

  /**
   * 식 하나 → 조각. 컴파일 때 정해지는 값(리터럴 · 텍스트 블록 · static final 상수 · 그 이음)은 글로, 문자열 + 식은 항마다 나누고,
   * 나머지는 바인드할 식으로.
   * shortcut: 1 + 2 + "a" 처럼 글 앞의 숫자 덧셈이 상수가 아니면(변수 섞임) 항마다 따로 본다. 그런 SQL 이 나오면 앞 항을 묶어 본다.
   */
  private static List<SqlConcat.Piece> flatten(PsiExpression expr, PsiConstantEvaluationHelper constants) {
    List<SqlConcat.Piece> out = new ArrayList<>();
    PsiExpression e = PsiUtil.skipParenthesizedExprDown(expr);
    if (e == null) return out;
    Object value = constants.computeConstantExpression(e);
    if (value != null) {
      out.add(SqlConcat.Piece.literal(String.valueOf(value)));
    } else if (e instanceof PsiPolyadicExpression pe && isStringConcat(pe)) {
      for (PsiExpression operand : pe.getOperands()) out.addAll(flatten(operand, constants));
    } else {
      out.add(SqlConcat.Piece.expr(e.getText()));
    }
    return out;
  }
}
