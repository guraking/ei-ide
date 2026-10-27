package local.bootservers;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiConstantEvaluationHelper;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Navigate 메뉴의 「URL 찾기…」.
 * 프로젝트 소스에서 스프링 요청 매핑(@RequestMapping · @GetMapping 등)이 붙은 메서드를 모두 모아
 * 「HTTP 방식 · 전체 URL · 클래스.메서드」 목록 창을 연다.
 * 전체 URL 은 클래스(상위 클래스 포함)의 @RequestMapping 경로 + 메서드 매핑 경로. 경로 값은 상수 · 상수 덧셈까지 계산한다.
 */
public final class UrlMappingAction extends DumbAwareAction {

    static String title() {
        return BootServersBundle.message("url.title");
    }


    private static final String PACKAGE = "org.springframework.web.bind.annotation.";
    private static final String REQUEST_MAPPING = PACKAGE + "RequestMapping";
    private static final String ATTR_VALUE = "value";
    private static final String ATTR_PATH = "path";
    private static final String ATTR_METHOD = "method";
    /** method 를 적지 않은 @RequestMapping 은 모든 HTTP 방식을 받는다. */
    static final String ANY_METHOD = "모두";
    private static final String METHOD_SEPARATOR = "|";

    /** 매핑 주석 → 고정 HTTP 방식(@RequestMapping 은 null: method 속성에서 읽는다). */
    private static final Map<String, String> MAPPINGS = new LinkedHashMap<>();

    static {
        MAPPINGS.put(REQUEST_MAPPING, null);
        MAPPINGS.put(PACKAGE + "GetMapping", "GET");
        MAPPINGS.put(PACKAGE + "PostMapping", "POST");
        MAPPINGS.put(PACKAGE + "PutMapping", "PUT");
        MAPPINGS.put(PACKAGE + "DeleteMapping", "DELETE");
        MAPPINGS.put(PACKAGE + "PatchMapping", "PATCH");
    }

    /** 목록 한 줄. target 은 코드가 바뀌어도 따라가는 메서드 포인터. */
    record Endpoint(String httpMethod, String url, String owner, SmartPsiElementPointer<PsiMethod> target) {
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        if (DumbService.isDumb(project)) {
            Messages.showWarningDialog(project, BootServersBundle.message("url.indexing"), title());
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(project, BootServersBundle.message("url.progress"), true) {
            private List<Endpoint> found;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                found = ReadAction.nonBlocking(() -> collect(project))
                        .inSmartMode(project)
                        .wrapProgress(indicator)
                        .executeSynchronously();
            }

            @Override
            public void onSuccess() {
                if (found == null) {
                    Messages.showInfoMessage(project,
                            BootServersBundle.message("spring.web.missing"), title());
                    return;
                }
                new UrlMappingDialog(project, found).show();
            }
        });
    }

    /** 읽기 동작 안에서 부른다. 스프링 매핑 주석 클래스가 하나도 없으면 null. */
    static List<Endpoint> collect(Project project) {
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        GlobalSearchScope libraries = GlobalSearchScope.allScope(project);
        GlobalSearchScope sources = GlobalSearchScope.projectScope(project);
        PsiConstantEvaluationHelper evaluator = facade.getConstantEvaluationHelper();
        SmartPointerManager pointers = SmartPointerManager.getInstance(project);
        boolean anyAnnotation = false;
        List<Endpoint> result = new ArrayList<>();
        for (Map.Entry<String, String> mapping : MAPPINGS.entrySet()) {
            PsiClass annotationClass = facade.findClass(mapping.getKey(), libraries);
            if (annotationClass == null) {
                continue;
            }
            anyAnnotation = true;
            for (PsiMethod method : AnnotatedElementsSearch.searchPsiMethods(annotationClass, sources).findAll()) {
                ProgressManager.checkCanceled();
                PsiAnnotation annotation = method.getModifierList().findAnnotation(mapping.getKey());
                PsiClass owner = method.getContainingClass();
                if (annotation == null || owner == null) {
                    continue;
                }
                String httpMethod = mapping.getValue() != null ? mapping.getValue() : requestMethods(annotation);
                String ownerName = owner.getName() + "." + method.getName();
                SmartPsiElementPointer<PsiMethod> target = pointers.createSmartPsiElementPointer(method);
                for (String classPath : classPaths(owner, evaluator)) {
                    for (String methodPath : paths(annotation, evaluator)) {
                        result.add(new Endpoint(httpMethod, UrlPattern.join(classPath, methodPath), ownerName, target));
                    }
                }
            }
        }
        if (!anyAnnotation) {
            return null;
        }
        result.sort(Comparator.comparing(Endpoint::url).thenComparing(Endpoint::httpMethod));
        return result;
    }

    /** 읽기 동작 안에서 부른다. 메서드 하나의 매핑 줄들(collect 와 같은 규칙). 매핑 주석이 없으면 빈 목록. */
    static List<Endpoint> of(PsiMethod method) {
        List<Endpoint> result = new ArrayList<>();
        PsiClass owner = method.getContainingClass();
        if (owner == null) {
            return result;
        }
        PsiConstantEvaluationHelper evaluator = JavaPsiFacade.getInstance(method.getProject()).getConstantEvaluationHelper();
        SmartPsiElementPointer<PsiMethod> target = null;
        for (Map.Entry<String, String> mapping : MAPPINGS.entrySet()) {
            PsiAnnotation annotation = method.getModifierList().findAnnotation(mapping.getKey());
            if (annotation == null) {
                continue;
            }
            if (target == null) {
                target = SmartPointerManager.getInstance(method.getProject()).createSmartPsiElementPointer(method);
            }
            String httpMethod = mapping.getValue() != null ? mapping.getValue() : requestMethods(annotation);
            for (String classPath : classPaths(owner, evaluator)) {
                for (String methodPath : paths(annotation, evaluator)) {
                    result.add(new Endpoint(httpMethod, UrlPattern.join(classPath, methodPath), owner.getName() + "." + method.getName(), target));
                }
            }
        }
        return result;
    }

    /** 클래스(또는 상위 클래스 · 인터페이스)의 @RequestMapping 경로들. 없으면 빈 경로 하나. */
    private static Set<String> classPaths(PsiClass owner, PsiConstantEvaluationHelper evaluator) {
        PsiAnnotation annotation = AnnotationUtil.findAnnotationInHierarchy(owner, Set.of(REQUEST_MAPPING));
        return annotation == null ? Set.of("") : paths(annotation, evaluator);
    }

    /** value · path 속성의 경로들(배열이면 각각). 하나도 없으면 빈 경로 하나. 계산이 안 되는 값은 적힌 글 그대로. */
    private static Set<String> paths(PsiAnnotation annotation, PsiConstantEvaluationHelper evaluator) {
        Set<String> result = new LinkedHashSet<>();
        for (String attribute : new String[]{ATTR_VALUE, ATTR_PATH}) {
            for (PsiAnnotationMemberValue value : flatten(annotation.findDeclaredAttributeValue(attribute))) {
                Object constant = evaluator.computeConstantExpression(value);
                result.add(constant instanceof String s ? s : value.getText());
            }
        }
        if (result.isEmpty()) {
            result.add("");
        }
        return result;
    }

    /** @RequestMapping 의 method 속성(RequestMethod.X)들을 「|」 로 잇는다. 없으면 ANY_METHOD. */
    private static String requestMethods(PsiAnnotation annotation) {
        List<String> names = new ArrayList<>();
        for (PsiAnnotationMemberValue value : flatten(annotation.findDeclaredAttributeValue(ATTR_METHOD))) {
            names.add(value instanceof PsiReferenceExpression ref && ref.getReferenceName() != null
                    ? ref.getReferenceName() : value.getText());
        }
        return names.isEmpty() ? ANY_METHOD : String.join(METHOD_SEPARATOR, names);
    }

    private static List<PsiAnnotationMemberValue> flatten(PsiAnnotationMemberValue value) {
        List<PsiAnnotationMemberValue> result = new ArrayList<>();
        if (value instanceof PsiArrayInitializerMemberValue array) {
            for (PsiAnnotationMemberValue item : array.getInitializers()) {
                result.add(item);
            }
        } else if (value != null) {
            result.add(value);
        }
        return result;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
