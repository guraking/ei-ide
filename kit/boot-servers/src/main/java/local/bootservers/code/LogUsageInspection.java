package local.bootservers.code;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiCatchSection;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiMethodUtil;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 빈 catch 블록 · 인자 없는 printStackTrace() · System.out/err.print* 검사.
 * 테스트 소스 폴더 파일과 main 메서드 안은 건너뛴다. 로거(org.slf4j.Logger 필드, 또는 @Slf4j 의 log)가
 * 있을 때만 빠른 수정을 단다. 바꿀 문장은 {@link LogFix}.
 */
public final class LogUsageInspection extends LocalInspectionTool {

    /** catch 매개변수 이름이 이것으로 시작하면 일부러 비운 것으로 본다(ignored 는 ignore 로 걸린다). */
    private static final String[] IGNORED_PREFIXES = {"ignore", "expected"};
    private static final String UNNAMED = "_";
    private static final String THROWABLE = "java.lang.Throwable";
    private static final String SYSTEM = "java.lang.System";
    private static final String OUT = "out";
    private static final String ERR = "err";
    private static final String PRINT_PREFIX = "print";
    private static final String PRINT = "print";
    private static final String PRINTLN = "println";
    private static final String PRINT_STACK_TRACE = "printStackTrace";
    private static final String SLF4J_LOGGER = "org.slf4j.Logger";
    private static final String LOMBOK_SLF4J = "lombok.extern.slf4j.Slf4j";
    private static final String LOMBOK_LOG = "log";
    private static final String NO_LOGGER = " (로거 필드나 @Slf4j 가 없어 자동 수정이 없습니다)";

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        PsiFile file = holder.getFile();
        VirtualFile vf = file.getVirtualFile();
        if (!(file instanceof PsiJavaFile) || vf == null
                || ProjectFileIndex.getInstance(file.getProject()).isInTestSourceContent(vf)) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }
        return new JavaElementVisitor() {
            @Override
            public void visitCatchSection(@NotNull PsiCatchSection section) {
                PsiCodeBlock block = section.getCatchBlock();
                PsiParameter parameter = section.getParameter();
                if (block == null || parameter == null || block.getStatements().length > 0 || isIgnored(parameter.getName())) {
                    return;
                }
                PsiMethod method = PsiTreeUtil.getParentOfType(section, PsiMethod.class);
                if (isMain(method)) {
                    return;
                }
                String logger = loggerName(section);
                String name = parameter.getName();
                String statement = method == null || UNNAMED.equals(name)
                        ? null : LogFix.statement(LogFix.Kind.EMPTY_CATCH, name, false, logger, method.getName());
                register(holder, section.getFirstChild(), "빈 catch 블록입니다", logger, statement, "로그 넣기: ");
            }

            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                String name = call.getMethodExpression().getReferenceName();
                PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
                if (name == null || qualifier == null) {
                    return;
                }
                PsiExpression[] args = call.getArgumentList().getExpressions();
                LogFix.Kind kind;
                String arg;
                boolean literal = false;
                String message;
                if (PRINT_STACK_TRACE.equals(name)) {
                    if (args.length != 0 || !InheritanceUtil.isInheritor(qualifier.getType(), THROWABLE)) {
                        return;
                    }
                    kind = LogFix.Kind.PRINT_STACK_TRACE;
                    arg = qualifier.getText();
                    message = "printStackTrace() 대신 로거를 쓰세요";
                } else if (name.startsWith(PRINT_PREFIX)) {
                    String stream = systemStream(qualifier);
                    if (stream == null) {
                        return;
                    }
                    kind = OUT.equals(stream) ? LogFix.Kind.OUT : LogFix.Kind.ERR;
                    // printf · format 처럼 인자 형식이 다른 출력은 수정 없이 알리기만 한다.
                    boolean single = args.length == 1 && (PRINT.equals(name) || PRINTLN.equals(name));
                    arg = single ? args[0].getText() : null;
                    literal = single && args[0] instanceof PsiLiteralExpression lit && lit.getValue() instanceof String;
                    message = "System." + stream + " 출력 대신 로거를 쓰세요";
                } else {
                    return;
                }
                PsiMethod method = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
                if (isMain(method)) {
                    return;
                }
                String logger = loggerName(call);
                String statement = !(call.getParent() instanceof PsiExpressionStatement)
                        ? null : LogFix.statement(kind, arg, literal, logger, method == null ? null : method.getName());
                register(holder, call, message, logger, statement, "로거로 바꾸기: ");
            }
        };
    }

    private static void register(ProblemsHolder holder, PsiElement at, String message, @Nullable String logger,
                                 @Nullable String statement, String fixPrefix) {
        if (at == null) {
            return;
        }
        if (statement != null) {
            holder.registerProblem(at, message, new ToLogFix(fixPrefix + statement, statement));
        } else {
            holder.registerProblem(at, logger == null ? message + NO_LOGGER : message);
        }
    }

    private static boolean isIgnored(String name) {
        for (String prefix : IGNORED_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMain(@Nullable PsiMethod method) {
        return method != null && PsiMethodUtil.isMainMethod(method);
    }

    /** System.out · System.err 이면 out / err, 아니면 null. */
    private static @Nullable String systemStream(PsiExpression qualifier) {
        if (!(qualifier instanceof PsiReferenceExpression ref) || !(ref.resolve() instanceof PsiField field)) {
            return null;
        }
        PsiClass owner = field.getContainingClass();
        String name = field.getName();
        if (owner == null || !SYSTEM.equals(owner.getQualifiedName()) || !(OUT.equals(name) || ERR.equals(name))) {
            return null;
        }
        return name;
    }

    /** 안쪽 클래스부터 바깥으로: @Slf4j 면 log, 자기 필드 중 org.slf4j.Logger 형이 있으면 그 이름. 없으면 null. */
    private static @Nullable String loggerName(PsiElement element) {
        for (PsiClass c = PsiTreeUtil.getParentOfType(element, PsiClass.class); c != null;
             c = PsiTreeUtil.getParentOfType(c, PsiClass.class)) {
            if (c.hasAnnotation(LOMBOK_SLF4J)) {
                return LOMBOK_LOG;
            }
            for (PsiField f : c.getFields()) {
                if (SLF4J_LOGGER.equals(f.getType().getCanonicalText())) {
                    return f.getName();
                }
            }
        }
        return null;
    }

    /** 문제 자리가 호출식이면 그 문장을 바꾸고, catch 키워드면 그 catch 블록 끝에 넣는다. */
    private static final class ToLogFix implements LocalQuickFix {

        private final String name;
        private final String statement;

        ToLogFix(String name, String statement) {
            this.name = name;
            this.statement = statement;
        }

        @Override
        public @NotNull String getName() {
            return name;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "로거 호출로 바꾸기";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (element == null) {
                return;
            }
            PsiStatement replacement = JavaPsiFacade.getElementFactory(project).createStatementFromText(statement, element);
            if (element instanceof PsiMethodCallExpression call) {
                if (call.getParent() instanceof PsiExpressionStatement old) {
                    CodeStyleManager.getInstance(project).reformat(old.replace(replacement));
                }
                return;
            }
            PsiCatchSection section = PsiTreeUtil.getParentOfType(element, PsiCatchSection.class, false);
            PsiCodeBlock block = section == null ? null : section.getCatchBlock();
            if (block != null) {
                CodeStyleManager.getInstance(project).reformat(block.add(replacement));
            }
        }
    }
}
