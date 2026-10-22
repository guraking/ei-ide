package local.bootservers.code;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiEllipsisType;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.IncorrectOperationException;
import local.bootservers.BootServersBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 자바 편집기 우클릭 「요청 값을 DTO 로 묶기…」. 커서가 @RequestMapping 계열 메서드 안일 때만 보인다.
 * 대상은 @RequestParam 이 붙은 매개변수(Map 형 빼고)와 스프링 웹 주석 없는 단순형 · 열거형 매개변수.
 * 고른 매개변수로 같은 폴더에 DTO 를 만들고({@link ParamBundle}), 매개변수를 @ModelAttribute DTO 하나로,
 * 본문 참조를 getter 호출로 바꾼다. 메서드 호출부는 바꿀 필요가 없다(스프링이 부른다).
 */
public final class ParamBundleAction extends AnAction {

    static String title() {
        return BootServersBundle.message("params.title");
    }

    private static final String NOTIFICATION_GROUP = "요청 값 묶기";
    private static final int MIN_CANDIDATES = 2;
    private static final String JAVA_FILE_SUFFIX = ".java";
    private static final String LABEL_SEPARATOR = " ";
    private static final String HTML_BREAK = "<br>";
    private static final Logger LOG = Logger.getInstance(ParamBundleAction.class);

    private static final String REQUEST_PARAM = "org.springframework.web.bind.annotation.RequestParam";
    private static final String[] MAPPING_ANNOTATIONS = {
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping"};
    /** 이 꾸러미의 주석(@PathVariable · @RequestBody · @AuthenticationPrincipal 등)이 붙은 매개변수는 그대로 둔다. */
    private static final String[] KEEP_ANNOTATION_PREFIXES = {"org.springframework.web.", "org.springframework.security."};
    private static final String MAP = "java.util.Map";
    private static final String[] REQUEST_NAME_ATTRIBUTES = {"value", "name"};
    private static final String DEFAULT_VALUE_ATTRIBUTE = "defaultValue";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        e.getPresentation().setEnabledAndVisible(e.getProject() != null && editor != null && !editor.isViewer()
                && file instanceof PsiJavaFile && editor.getDocument().isWritable()
                && mappingMethodAt(file, editor.getCaretModel().getOffset()) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        if (project == null || editor == null || !(file instanceof PsiJavaFile javaFile)) {
            return;
        }
        PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
        PsiMethod method = mappingMethodAt(file, editor.getCaretModel().getOffset());
        if (method == null) {
            return;
        }
        List<PsiParameter> candidates = new ArrayList<>();
        for (PsiParameter p : method.getParameterList().getParameters()) {
            if (isTarget(p)) {
                candidates.add(p);
            }
        }
        if (candidates.size() < MIN_CANDIDATES) {
            Messages.showInfoMessage(project, BootServersBundle.message("params.too.few"), title());
            return;
        }
        List<ParamBundle.Param> params = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<String> fieldNames = new ArrayList<>();
        for (PsiParameter p : candidates) {
            ParamBundle.Param param = toParam(project, p);
            params.add(param);
            labels.add(p.getType().getPresentableText() + LABEL_SEPARATOR + p.getName());
            fieldNames.add(ParamBundle.defaultFieldName(param));
        }
        ParamBundleDialog dialog = new ParamBundleDialog(project, ParamBundle.className(method.getName()), labels, fieldNames);
        if (!dialog.showAndGet() || !method.isValid()) {
            return;
        }
        List<PsiParameter> chosen = new ArrayList<>();
        List<ParamBundle.Field> fields = new ArrayList<>();
        for (int i : dialog.selectedIndexes()) {
            chosen.add(candidates.get(i));
            fields.add(new ParamBundle.Field(dialog.fieldName(i), params.get(i)));
        }
        List<List<PsiReference>> references = new ArrayList<>();
        List<String> written = new ArrayList<>();
        for (PsiParameter p : chosen) {
            if (!p.isValid()) {
                return;
            }
            Collection<PsiReference> found = ReferencesSearch.search(p, new LocalSearchScope(method)).findAll();
            for (PsiReference ref : found) {
                if (ref.getElement() instanceof PsiExpression expr && PsiUtil.isAccessedForWriting(expr)) {
                    written.add(p.getName());
                    break;
                }
            }
            references.add(new ArrayList<>(found));
        }
        if (!written.isEmpty()) {
            Messages.showWarningDialog(project, BootServersBundle.message("params.written", String.join(", ", written)), title());
            return;
        }
        PsiDirectory dir = file.getContainingDirectory();
        if (dir == null) {
            Messages.showWarningDialog(project, BootServersBundle.message("params.no.dir"), title());
            return;
        }
        String className = dialog.className();
        String fileName = className + JAVA_FILE_SUFFIX;
        PsiFile existing = dir.findFile(fileName);
        if (file.equals(existing)) {
            Messages.showWarningDialog(project, BootServersBundle.message("params.same.class", className), title());
            return;
        }
        if (existing != null && Messages.showYesNoDialog(project, BootServersBundle.message("params.overwrite", fileName), title(),
                Messages.getQuestionIcon()) != Messages.YES) {
            return;
        }
        String pkg = javaFile.getPackageName();
        String qualifiedName = pkg.isEmpty() ? className : pkg + "." + className;
        String variable = ParamBundle.variableName(takenNames(method));
        String text = ParamBundle.dto(pkg, className, fields);
        try {
            WriteCommandAction.writeCommandAction(project, file).withName(title()).run(() -> {
                writeDto(project, dir, existing, fileName, text);
                apply(project, method, chosen, fields, references, qualifiedName, variable);
            });
        } catch (IncorrectOperationException ex) {
            LOG.warn("요청 값 묶기 실패: " + qualifiedName, ex);
            notify(project, BootServersBundle.message("params.failed"), StringUtil.escapeXmlEntities(String.valueOf(ex.getMessage())), NotificationType.ERROR);
            return;
        }
        report(project, qualifiedName, fields);
    }

    /** 쓰기 동작 안에서 부른다. 있던 파일은 글을 바꿔 쓴다. */
    private static void writeDto(Project project, PsiDirectory dir, @Nullable PsiFile existing, String fileName, String text) {
        PsiFile dto;
        if (existing != null) {
            Document document = PsiDocumentManager.getInstance(project).getDocument(existing);
            if (document == null || !document.isWritable()) {
                throw new IncorrectOperationException(BootServersBundle.message("params.read.only", fileName));
            }
            document.setText(text);
            PsiDocumentManager.getInstance(project).commitDocument(document);
            dto = existing;
        } else {
            PsiFile draft = PsiFileFactory.getInstance(project).createFileFromText(fileName, JavaFileType.INSTANCE, text);
            dto = (PsiFile) dir.add(draft);
        }
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(dto);
    }

    /** 쓰기 동작 안에서 부른다. 참조를 먼저 바꾼 뒤 첫 매개변수 자리에 새 매개변수를 넣고 나머지를 지운다. */
    private static void apply(Project project, PsiMethod method, List<PsiParameter> chosen, List<ParamBundle.Field> fields,
                              List<List<PsiReference>> references, String qualifiedName, String variable) {
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        for (int i = 0; i < chosen.size(); i++) {
            String access = ParamBundle.access(variable, fields.get(i).name());
            for (PsiReference ref : references.get(i)) {
                PsiElement element = ref.getElement();
                if (element.isValid()) {
                    element.replace(factory.createExpressionFromText(access, element));
                }
            }
        }
        PsiParameter replacement = factory.createParameterFromText(ParamBundle.parameter(qualifiedName, variable), method);
        PsiElement added = chosen.get(0).replace(replacement);
        for (int i = 1; i < chosen.size(); i++) {
            chosen.get(i).delete();
        }
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(added);
    }

    private static void report(Project project, String qualifiedName, List<ParamBundle.Field> fields) {
        List<String> lines = new ArrayList<>();
        for (String w : ParamBundle.nameWarnings(fields)) {
            lines.add(BootServersBundle.message("params.name.warning", StringUtil.escapeXmlEntities(w)));
        }
        for (ParamBundle.Field f : fields) {
            ParamBundle.Param p = f.param();
            if (p.defaultValue() != null && ParamBundle.initializer(p.type(), p.defaultValue()) == null) {
                lines.add(BootServersBundle.message("params.default.lost", StringUtil.escapeXmlEntities(f.name())));
            }
        }
        NotificationType type = lines.isEmpty() ? NotificationType.INFORMATION : NotificationType.WARNING;
        lines.add(0, BootServersBundle.message("params.done", StringUtil.escapeXmlEntities(qualifiedName), fields.size()));
        notify(project, title(), String.join(HTML_BREAK, lines), type);
    }

    private static @Nullable PsiMethod mappingMethodAt(PsiFile file, int offset) {
        PsiElement element = file.findElementAt(offset);
        if (element == null && offset > 0) {
            element = file.findElementAt(offset - 1);
        }
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (method == null) {
            return null;
        }
        for (String annotation : MAPPING_ANNOTATIONS) {
            if (method.hasAnnotation(annotation)) {
                return method;
            }
        }
        return null;
    }

    private static boolean isTarget(PsiParameter p) {
        PsiType type = p.getType();
        if (p.hasAnnotation(REQUEST_PARAM)) {
            return !InheritanceUtil.isInheritor(type, MAP);
        }
        for (PsiAnnotation annotation : annotations(p)) {
            String name = annotation.getQualifiedName();
            for (String prefix : KEEP_ANNOTATION_PREFIXES) {
                if (name != null && name.startsWith(prefix)) {
                    return false;
                }
            }
        }
        if (type instanceof PsiPrimitiveType || ParamBundle.isSimpleType(type.getCanonicalText())) {
            return true;
        }
        PsiClass c = PsiUtil.resolveClassInType(type);
        return c != null && c.isEnum();
    }

    /** @RequestParam 의 이름 · 기본값은 상수로 풀리는 글만 쓴다. 다른 주석은 정규 이름으로 필드에 옮긴다. */
    private static ParamBundle.Param toParam(Project project, PsiParameter p) {
        PsiType type = p.getType() instanceof PsiEllipsisType ellipsis ? ellipsis.toArrayType() : p.getType();
        PsiAnnotation requestParam = p.getAnnotation(REQUEST_PARAM);
        String requestName = null;
        String defaultValue = null;
        if (requestParam != null) {
            for (String attribute : REQUEST_NAME_ATTRIBUTES) {
                String value = constant(project, requestParam, attribute);
                if (value != null && !value.isBlank()) {
                    requestName = value;
                    break;
                }
            }
            defaultValue = constant(project, requestParam, DEFAULT_VALUE_ATTRIBUTE);
        }
        List<String> annotations = new ArrayList<>();
        for (PsiAnnotation annotation : annotations(p)) {
            String name = annotation.getQualifiedName();
            if (REQUEST_PARAM.equals(name)) {
                continue;
            }
            annotations.add(name == null ? annotation.getText() : "@" + name + annotation.getParameterList().getText());
        }
        return new ParamBundle.Param(p.getName(), type.getCanonicalText(), requestName, defaultValue, annotations);
    }

    private static PsiAnnotation[] annotations(PsiParameter p) {
        PsiModifierList modifiers = p.getModifierList();
        return modifiers == null ? PsiAnnotation.EMPTY_ARRAY : modifiers.getAnnotations();
    }

    private static @Nullable String constant(Project project, PsiAnnotation annotation, String attribute) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        if (value == null) {
            return null;
        }
        Object c = JavaPsiFacade.getInstance(project).getConstantEvaluationHelper().computeConstantExpression(value);
        return c instanceof String s ? s : null;
    }

    /** 메서드 안 변수 · 매개변수와 클래스 필드(상속 포함) 이름. 새 변수가 이것들을 가리지 않게 한다. */
    private static Set<String> takenNames(PsiMethod method) {
        Set<String> names = new HashSet<>();
        for (PsiVariable v : PsiTreeUtil.findChildrenOfType(method, PsiVariable.class)) {
            names.add(v.getName());
        }
        PsiClass owner = method.getContainingClass();
        if (owner != null) {
            for (PsiField f : owner.getAllFields()) {
                names.add(f.getName());
            }
        }
        return names;
    }

    private static void notify(Project project, String title, String content, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(title, content, type)
                .notify(project);
    }
}
