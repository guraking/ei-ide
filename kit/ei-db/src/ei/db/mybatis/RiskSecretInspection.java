package ei.db.mybatis;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.util.PsiUtil;
import ei.db.EiDbBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 코드에 박힌 비밀번호 · 키 · 토큰 · 접속 주소. 문자열 리터럴 하나만 본다(이어 붙인 값 · 상수 계산은 보지 않는다).
 * 값만 보고 정하는 것: 호스트가 든 JDBC 주소, 「아이디:비밀번호@」 가 든 URL, 개인 키 머리글, AWS 접근 키.
 * 이름을 함께 보는 것: 비밀 이름({@link RiskRules#secretName})의 변수 초기값 · 대입, setPassword("…") 같은 setter 인자,
 * put · setProperty("db.password", "…") 의 값, DriverManager.getConnection(주소, 아이디, "…") 의 비밀번호.
 * 테스트 소스는 보지 않는다. PSI 만 읽어 색인 중에도 돈다.
 */
public final class RiskSecretInspection extends LocalInspectionTool implements DumbAware {
  private static final String SETTER = "set";
  private static final String GET_CONNECTION = "getConnection";
  private static final String PASSWORD = "password";
  /** getConnection(url, user, password) 의 비밀번호 자리. */
  private static final int CONNECTION_PASSWORD_ARG = 2;

  @Override
  public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
    if (RiskTxInspection.inTests(holder.getFile())) return PsiElementVisitor.EMPTY_VISITOR;
    return new JavaElementVisitor() {
      @Override
      public void visitLiteralExpression(@NotNull PsiLiteralExpression lit) {
        if (!(lit.getValue() instanceof String value)) return;
        RiskRules.Secret kind = RiskRules.secretLiteral(value);
        if (kind != null) {
          holder.registerProblem(lit, EiDbBundle.message(kind == RiskRules.Secret.JDBC ? "risk.secret.jdbc" : "risk.secret.value"));
          return;
        }
        String name = nameOf(lit);
        if (name != null && RiskRules.secretName(name) && RiskRules.secretValue(name, value)) {
          holder.registerProblem(lit, EiDbBundle.message("risk.secret.value"));
        }
      }
    };
  }

  /** 리터럴이 담기는 이름. 변수 · 대입 왼쪽 · setter 이름 · 키 리터럴 · getConnection 의 비밀번호 자리, 그 밖은 null. */
  private static @Nullable String nameOf(PsiLiteralExpression lit) {
    PsiElement parent = PsiUtil.skipParenthesizedExprUp(lit.getParent());
    if (parent instanceof PsiVariable v && v.getInitializer() != null && PsiUtil.skipParenthesizedExprDown(v.getInitializer()) == lit) {
      return v.getName();
    }
    if (parent instanceof PsiAssignmentExpression a && a.getRExpression() != null && PsiUtil.skipParenthesizedExprDown(a.getRExpression()) == lit) {
      return PsiUtil.skipParenthesizedExprDown(a.getLExpression()) instanceof PsiReferenceExpression ref ? ref.getReferenceName() : null;
    }
    if (!(parent instanceof PsiExpressionList list) || !(list.getParent() instanceof PsiMethodCallExpression call)) return null;
    String method = call.getMethodExpression().getReferenceName();
    PsiExpression[] args = list.getExpressions();
    if (method == null || args.length == 0) return null;
    if (args.length == 1 && args[0] == lit && method.length() > SETTER.length() && method.startsWith(SETTER)) {
      return method.substring(SETTER.length());
    }
    if (args.length == CONNECTION_PASSWORD_ARG + 1 && args[CONNECTION_PASSWORD_ARG] == lit && GET_CONNECTION.equals(method)) return PASSWORD;
    if (args.length == 2 && args[1] == lit && PsiUtil.skipParenthesizedExprDown(args[0]) instanceof PsiLiteralExpression key
        && key.getValue() instanceof String k) {
      return RiskRules.lastSegment(k);
    }
    return null;
  }
}
