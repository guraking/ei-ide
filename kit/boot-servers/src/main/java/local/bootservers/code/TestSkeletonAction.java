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
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiEllipsisType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.java.JavaSourceRootType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 자바 편집기 · 프로젝트 트리 우클릭 「테스트 뼈대 만들기」.
 * 대상은 커서가 있는 최상위 클래스(트리에서는 고른 .java 파일의 첫 클래스). 글은 {@link TestSkeleton}.
 * 모듈의 첫 테스트 소스 폴더 · 같은 패키지에 <클래스>Test.java 를 만든다. 파일이 있으면 없는 테스트 메서드만 끝에 더한다.
 * 테스트 소스 폴더는 만들지 않는다.
 */
public final class TestSkeletonAction extends AnAction {

    private static final String TITLE = "테스트 뼈대 만들기";
    private static final String NOTIFICATION_GROUP = "테스트 뼈대";
    private static final String JAVA_EXTENSION = "java";
    private static final String JAVA_FILE_SUFFIX = "." + JAVA_EXTENSION;
    private static final String PACKAGE_SEPARATOR = ".";
    private static final String PATH_SEPARATOR = "/";
    private static final Logger LOG = Logger.getInstance(TestSkeletonAction.class);

    /** 이 주석이 붙은 인스턴스 필드는 주입받는 필드로 본다. */
    private static final String[] INJECT_ANNOTATIONS = {
            "org.springframework.beans.factory.annotation.Autowired",
            "jakarta.annotation.Resource", "javax.annotation.Resource",
            "jakarta.inject.Inject", "javax.inject.Inject"};

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        e.getPresentation().setEnabledAndVisible(e.getProject() != null && file != null && !file.isDirectory()
                && JAVA_EXTENSION.equalsIgnoreCase(file.getExtension()));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || file == null || !file.isValid()) {
            return;
        }
        PsiClass target = targetClass(project, file, e.getData(CommonDataKeys.EDITOR));
        if (target == null || target.getName() == null) {
            notify(project, "테스트할 클래스를 찾지 못했습니다", file.getName(), NotificationType.WARNING);
            return;
        }
        Module module = ModuleUtilCore.findModuleForPsiElement(target);
        if (module == null) {
            notify(project, "모듈을 찾지 못했습니다", file.getName() + " 은(는) 모듈에 속하지 않습니다.", NotificationType.WARNING);
            return;
        }
        List<VirtualFile> testRoots = ModuleRootManager.getInstance(module).getSourceRoots(JavaSourceRootType.TEST_SOURCE);
        if (testRoots.isEmpty()) {
            notify(project, "테스트 소스 폴더가 없습니다",
                    module.getName() + " 모듈에 테스트 소스 폴더(예: src/test/java)를 먼저 지정하세요.", NotificationType.WARNING);
            return;
        }
        String className = target.getName();
        String pkg = ((PsiJavaFile) target.getContainingFile()).getPackageName();
        TestSkeleton.Kind kind = TestSkeleton.isMapper(target.isInterface(), annotationNames(target), className)
                ? TestSkeleton.Kind.MAPPER : TestSkeleton.Kind.MOCK;
        List<TestSkeleton.Method> methods = methods(target, kind);
        String testFileName = TestSkeleton.testClassName(className) + JAVA_FILE_SUFFIX;
        VirtualFile root = testRoots.get(0);
        VirtualFile existingDir = pkg.isEmpty() ? root : root.findFileByRelativePath(pkg.replace(PACKAGE_SEPARATOR, PATH_SEPARATOR));
        VirtualFile existing = existingDir == null ? null : existingDir.findChild(testFileName);
        if (existing != null) {
            addMissing(project, existing, kind, methods);
            return;
        }
        String text = TestSkeleton.file(pkg, className, kind, kind == TestSkeleton.Kind.MOCK ? fields(target) : List.of(), methods);
        VirtualFile created;
        try {
            created = WriteCommandAction.writeCommandAction(project).withName(TITLE)
                    .compute(() -> create(project, root, pkg, testFileName, text));
        } catch (IOException ex) {
            LOG.warn("테스트 파일을 만들지 못함: " + testFileName, ex);
            notify(project, "테스트 파일을 만들지 못했습니다", ex.getMessage(), NotificationType.ERROR);
            return;
        }
        FileEditorManager.getInstance(project).openFile(created, true);
    }

    private static PsiClass targetClass(Project project, VirtualFile file, Editor editor) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (!(psiFile instanceof PsiJavaFile javaFile)) {
            return null;
        }
        if (editor != null && file.equals(FileDocumentManager.getInstance().getFile(editor.getDocument()))) {
            PsiElement at = javaFile.findElementAt(editor.getCaretModel().getOffset());
            PsiClass top = PsiTreeUtil.getTopmostParentOfType(at, PsiClass.class);
            if (top != null) {
                return top;
            }
        }
        PsiClass[] classes = javaFile.getClasses();
        return classes.length == 0 ? null : classes[0];
    }

    private static Set<String> annotationNames(PsiClass cls) {
        Set<String> names = new HashSet<>();
        for (PsiAnnotation annotation : cls.getAnnotations()) {
            String name = annotation.getQualifiedName();
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    /** 매퍼는 static 이 아닌 모든 메서드, 그 밖은 public 이고 static 이 아닌 메서드(생성자 빼고). */
    private static List<TestSkeleton.Method> methods(PsiClass cls, TestSkeleton.Kind kind) {
        List<TestSkeleton.Method> result = new ArrayList<>();
        for (PsiMethod method : cls.getMethods()) {
            if (method.isConstructor() || method.hasModifierProperty(PsiModifier.STATIC)) {
                continue;
            }
            if (kind == TestSkeleton.Kind.MOCK && !method.hasModifierProperty(PsiModifier.PUBLIC)) {
                continue;
            }
            List<String> params = new ArrayList<>();
            for (PsiParameter parameter : method.getParameterList().getParameters()) {
                PsiType type = parameter.getType();
                if (type instanceof PsiEllipsisType ellipsis) {
                    type = ellipsis.toArrayType();
                }
                params.add(TypeConversionUtil.erasure(type).getCanonicalText());
            }
            result.add(new TestSkeleton.Method(method.getName(), params, method.getThrowsList().getReferencedTypes().length > 0));
        }
        return result;
    }

    /** static 이 아니고, 초기값 없는 final 이거나 주입 주석이 붙은 필드. */
    private static List<TestSkeleton.Field> fields(PsiClass cls) {
        List<TestSkeleton.Field> result = new ArrayList<>();
        for (PsiField field : cls.getFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) {
                continue;
            }
            boolean injected = field.hasModifierProperty(PsiModifier.FINAL) && field.getInitializer() == null;
            for (String annotation : INJECT_ANNOTATIONS) {
                injected |= field.hasAnnotation(annotation);
            }
            if (injected) {
                result.add(new TestSkeleton.Field(field.getType().getCanonicalText(), field.getName()));
            }
        }
        return result;
    }

    /** 쓰기 동작 안에서 부른다. 패키지 폴더는 없으면 만든다. */
    private static VirtualFile create(Project project, VirtualFile root, String pkg, String fileName, String text) throws IOException {
        VirtualFile dir = pkg.isEmpty() ? root : VfsUtil.createDirectoryIfMissing(root, pkg.replace(PACKAGE_SEPARATOR, PATH_SEPARATOR));
        PsiDirectory psiDir = dir == null ? null : PsiManager.getInstance(project).findDirectory(dir);
        if (psiDir == null) {
            throw new IOException("패키지 폴더를 열 수 없습니다: " + root.getPath() + PATH_SEPARATOR + pkg);
        }
        PsiFile draft = PsiFileFactory.getInstance(project).createFileFromText(fileName, JavaFileType.INSTANCE, text);
        PsiFile added = (PsiFile) psiDir.add(draft);
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(added);
        return added.getVirtualFile();
    }

    private static void addMissing(Project project, VirtualFile existing, TestSkeleton.Kind kind, List<TestSkeleton.Method> methods) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(existing);
        PsiClass[] classes = psiFile instanceof PsiJavaFile javaFile ? javaFile.getClasses() : PsiClass.EMPTY_ARRAY;
        if (classes.length == 0) {
            notify(project, "테스트 클래스를 찾지 못했습니다", existing.getPath(), NotificationType.WARNING);
            FileEditorManager.getInstance(project).openFile(existing, true);
            return;
        }
        PsiClass testClass = classes[0];
        Set<String> names = new LinkedHashSet<>();
        for (PsiMethod method : testClass.getMethods()) {
            names.add(method.getName());
        }
        List<String> missing = TestSkeleton.missingMethods(kind, methods, names);
        if (missing.isEmpty()) {
            notify(project, "더할 테스트가 없습니다", existing.getName(), NotificationType.INFORMATION);
        } else if (existing.isWritable()) {
            WriteCommandAction.writeCommandAction(project).withName(TITLE).run(() -> {
                PsiElementFactory factory = PsiElementFactory.getInstance(project);
                JavaCodeStyleManager styleManager = JavaCodeStyleManager.getInstance(project);
                for (String text : missing) {
                    PsiElement added = testClass.add(factory.createMethodFromText(text, testClass));
                    styleManager.shortenClassReferences(added);
                }
            });
            notify(project, "테스트 " + missing.size() + "개를 더했습니다", existing.getName(), NotificationType.INFORMATION);
        } else {
            notify(project, "테스트 파일이 읽기 전용입니다", existing.getPath(), NotificationType.WARNING);
        }
        FileEditorManager.getInstance(project).openFile(existing, true);
    }

    private static void notify(Project project, String title, String content, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(title, content, type)
                .notify(project);
    }
}
