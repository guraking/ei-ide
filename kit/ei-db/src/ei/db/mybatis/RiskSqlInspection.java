package ei.db.mybatis;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClassInitializer;
import com.intellij.psi.PsiConstantEvaluationHelper;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import ei.db.EiDbBundle;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 자바에서 SQL 에 문자열 값을 바로 이어 붙인 곳(SQL 주입 위험)과 MyBatis 어노테이션 SQL 의 ${}.
 * 이어 붙이기는 + 식 하나, 또는 StringBuilder · StringBuffer 지역 변수의 초기값과 같은 메서드 안 append(…) 를 소스 순서대로 본다.
 * 식 바로 앞 글이 SQL 의 값 · 정렬 자리일 때({@link RiskRules#concatSpot})만, 식의 형식이 String 일 때만 표시한다
 * (숫자 · 날짜는 따옴표를 깰 수 없다). 이름에 escape · quote · sanitize 가 들어간 메서드를 거친 값은 건너뛴다.
 */
public final class RiskSqlInspection extends LocalInspectionTool {
  private static final String APPEND = "append";
  private static final String STRING_BUILDER = "java.lang.StringBuilder";
  private static final String STRING_BUFFER = "java.lang.StringBuffer";
  private static final String MYBATIS_ANNOTATIONS = "org.apache.ibatis.annotations.";
  private static final Set<String> SQL_ANNOTATIONS = Set.of("Select", "Insert", "Update", "Delete");
  private static final List<String> CLEANERS = List.of("escape", "quote", "sanitize");

  /** 조각 하나: 글(text != null) 또는 이어 붙인 식. */
  private record Piece(String text, PsiExpression expr) {}

  @Override
  public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
    PsiConstantEvaluationHelper constants = JavaPsiFacade.getInstance(holder.getProject()).getConstantEvaluationHelper();
    Set<PsiElement> reported = new HashSet<>();
    return new JavaElementVisitor() {
      @Override
      public void visitPolyadicExpression(@NotNull PsiPolyadicExpression e) {
        if (!isConcat(e) || isConcat(PsiUtil.skipParenthesizedExprUp(e.getParent()))) return;
        check(flatten(e, constants), holder, reported);
      }

      @Override
      public void visitLocalVariable(@NotNull PsiLocalVariable v) {
        if (isBuilder(v.getType())) check(builderPieces(v, constants), holder, reported);
      }

      @Override
      public void visitAnnotation(@NotNull PsiAnnotation a) {
        String q = a.getQualifiedName();
        if (q == null || !q.startsWith(MYBATIS_ANNOTATIONS) || !SQL_ANNOTATIONS.contains(q.substring(MYBATIS_ANNOTATIONS.length()))) return;
        for (PsiLiteralExpression lit : PsiTreeUtil.findChildrenOfType(a, PsiLiteralExpression.class)) {
          if (lit.getValue() instanceof String) dollarsIn(lit, holder);
        }
      }
    };
  }

  /** 어노테이션 SQL 글 하나의 ${}. 위치는 소스 글(따옴표 포함)에서 찾는다. */
  private static void dollarsIn(PsiLiteralExpression lit, ProblemsHolder holder) {
    String raw = lit.getText();
    StringBuilder before = new StringBuilder();
    int last = 0;
    for (RiskRules.Dollar d : RiskRules.dollars(raw, 0, raw.length())) {
      before.append(raw, last, d.start());
      holder.registerProblem(lit, RiskDollarInspection.message(RiskRules.dollarSpot(before.toString()), d.name()),
          ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new TextRange(d.start(), d.end()));
      before.append(RiskRules.PLACEHOLDER);
      last = d.end();
    }
  }

  private static void check(List<Piece> pieces, ProblemsHolder holder, Set<PsiElement> reported) {
    StringBuilder before = new StringBuilder();
    for (Piece p : pieces) {
      if (p.text() != null) {
        before.append(p.text());
        continue;
      }
      RiskRules.Spot spot = isString(p.expr().getType()) && !cleaned(p.expr()) ? RiskRules.concatSpot(before.toString()) : null;
      if (spot != null && reported.add(p.expr())) {
        String key = spot == RiskRules.Spot.ORDER ? "risk.sql.order" : "risk.sql.value";
        holder.registerProblem(p.expr(), EiDbBundle.message(key, p.expr().getText()));
      }
      before.append(RiskRules.PLACEHOLDER);
    }
  }

  /** escape · quote · sanitize 가 이름에 든 메서드의 결과. */
  private static boolean cleaned(PsiExpression e) {
    if (!(PsiUtil.skipParenthesizedExprDown(e) instanceof PsiMethodCallExpression call)) return false;
    String name = call.getMethodExpression().getReferenceName();
    if (name == null) return false;
    String lower = name.toLowerCase(Locale.ROOT);
    for (String c : CLEANERS) {
      if (lower.contains(c)) return true;
    }
    return false;
  }

  private static boolean isConcat(PsiElement e) {
    return e instanceof PsiPolyadicExpression pe && pe.getOperationTokenType() == JavaTokenType.PLUS && isString(pe.getType());
  }

  private static boolean isString(PsiType t) {
    return t != null && t.equalsToText(CommonClassNames.JAVA_LANG_STRING);
  }

  private static boolean isBuilder(PsiType t) {
    return t.equalsToText(STRING_BUILDER) || t.equalsToText(STRING_BUFFER);
  }

  /** 컴파일 때 정해지는 값은 글로, 문자열 + 식은 항마다 나누고, 나머지는 식으로. */
  private static List<Piece> flatten(PsiExpression expr, PsiConstantEvaluationHelper constants) {
    List<Piece> out = new ArrayList<>();
    PsiExpression e = PsiUtil.skipParenthesizedExprDown(expr);
    if (e == null) return out;
    Object value = constants.computeConstantExpression(e);
    if (value != null) {
      out.add(new Piece(String.valueOf(value), null));
    } else if (isConcat(e)) {
      for (PsiExpression operand : ((PsiPolyadicExpression) e).getOperands()) out.addAll(flatten(operand, constants));
    } else {
      out.add(new Piece(null, e));
    }
    return out;
  }

  /**
   * new StringBuilder("…") 의 글 인자 + 같은 메서드 안 v.append(x) · v.append(a).append(b) 의 인자를 인자 자리 순서대로.
   * shortcut: if 갈래 · setLength(0) 은 따지지 않고 소스 순서대로 잇는다. 갈래마다 다른 SQL 을 만드는 코드가 잘못 표시되면 갈래를 나눈다.
   */
  private static List<Piece> builderPieces(PsiLocalVariable v, PsiConstantEvaluationHelper constants) {
    List<Piece> out = new ArrayList<>();
    PsiExpression init = PsiUtil.skipParenthesizedExprDown(v.getInitializer());
    if (init instanceof PsiNewExpression n && n.getArgumentList() != null) {
      PsiExpression[] args = n.getArgumentList().getExpressions();
      if (args.length == 1 && isString(args[0].getType())) out.addAll(flatten(args[0], constants));
    }
    PsiElement scope = PsiTreeUtil.getParentOfType(v, PsiMethod.class, PsiLambdaExpression.class, PsiClassInitializer.class);
    if (scope == null) return out;
    List<PsiMethodCallExpression> appends = new ArrayList<>();
    for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(scope, PsiMethodCallExpression.class)) {
      if (APPEND.equals(call.getMethodExpression().getReferenceName()) && call.getArgumentList().getExpressions().length == 1
          && appendsTo(call, v)) {
        appends.add(call);
      }
    }
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
    return q instanceof PsiReferenceExpression ref && ref.isReferenceTo(v);
  }
}
