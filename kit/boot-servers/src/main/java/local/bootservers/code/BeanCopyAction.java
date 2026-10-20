package local.bootservers.code;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.ResolveState;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.scope.ElementClassHint;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.scope.util.PsiScopesUtil;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 자바 편집기 우클릭 「옮겨 담기 코드 만들기…」. 커서가 메서드 몸통 안일 때만 보인다.
 * 커서에서 보이는 클래스 형 변수 둘(꺼낼 · 담을)을 고르면 setter 마다 {@link BeanCopy} 줄을 커서 줄 다음에 넣고
 * 넣은 범위만 서식 정리한다. 한 번의 Ctrl+Z 로 되돌아간다.
 */
public final class BeanCopyAction extends AnAction {

    static final String TITLE = "옮겨 담기 코드 만들기";
    private static final int MIN_CANDIDATES = 2;
    private static final String OBJECT = "java.lang.Object";
    private static final String STRING = "java.lang.String";
    private static final String[] COLLECTION_TYPES = {"java.util.Collection", "java.util.Map"};
    private static final String LABEL_SEPARATOR = " : ";

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
                && bodyAt(file, editor.getCaretModel().getOffset()) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        if (project == null || editor == null || !(file instanceof PsiJavaFile)) {
            return;
        }
        Document document = editor.getDocument();
        PsiDocumentManager.getInstance(project).commitDocument(document);
        int offset = editor.getCaretModel().getOffset();
        PsiCodeBlock body = bodyAt(file, offset);
        PsiElement place = elementAt(file, offset);
        if (body == null || place == null) {
            return;
        }
        List<PsiVariable> candidates = candidates(place);
        if (candidates.size() < MIN_CANDIDATES) {
            Messages.showInfoMessage(project, "커서 위치에서 보이는 객체(클래스 형 변수)가 둘 이상 있어야 합니다.", TITLE);
            return;
        }
        List<String> labels = new ArrayList<>();
        for (PsiVariable v : candidates) {
            labels.add(v.getName() + LABEL_SEPARATOR + v.getType().getPresentableText());
        }
        BeanCopyDialog dialog = new BeanCopyDialog(project, labels);
        if (!dialog.showAndGet()) {
            return;
        }
        PsiVariable from = candidates.get(dialog.fromIndex());
        PsiVariable to = candidates.get(dialog.toIndex());
        if (!from.isValid() || !to.isValid() || !body.isValid()) {
            return;
        }
        PsiClass fromClass = classOf(from.getType());
        PsiClass toClass = classOf(to.getType());
        if (fromClass == null || toClass == null) {
            Messages.showWarningDialog(project, "고른 객체의 클래스를 찾을 수 없습니다.", TITLE);
            return;
        }
        List<String> lines = BeanCopy.lines(from.getName(), to.getName(), fieldOrder(toClass),
                accessors(toClass, true), accessors(fromClass, false));
        if (lines.isEmpty()) {
            Messages.showInfoMessage(project, "담을 객체(" + to.getType().getPresentableText() + ")에 setter 가 없습니다.", TITLE);
            return;
        }
        insert(project, file, document, body, offset, String.join("\n", lines));
    }

    /** 커서 줄 끝 다음에 넣는다. 커서 줄이 몸통을 닫는 } 줄이면 } 바로 앞에 넣는다. */
    private static void insert(Project project, PsiFile file, Document document, PsiCodeBlock body, int offset, String joined) {
        int lineEnd = document.getLineEndOffset(document.getLineNumber(offset));
        int rBrace = body.getTextRange().getEndOffset() - 1;
        int at = lineEnd <= rBrace ? lineEnd : rBrace;
        String text = lineEnd <= rBrace ? "\n" + joined : joined + "\n";
        WriteCommandAction.runWriteCommandAction(project, TITLE, null, () -> {
            document.insertString(at, text);
            PsiDocumentManager.getInstance(project).commitDocument(document);
            CodeStyleManager.getInstance(project).reformatText(file, at, at + text.length());
        }, file);
    }

    private static @Nullable PsiElement elementAt(PsiFile file, int offset) {
        PsiElement element = file.findElementAt(offset);
        return element != null || offset == 0 ? element : file.findElementAt(offset - 1);
    }

    /** 커서를 감싼 메서드 몸통. 여는 { 와 닫는 } 사이에 커서가 있을 때만. */
    private static @Nullable PsiCodeBlock bodyAt(PsiFile file, int offset) {
        PsiMethod method = PsiTreeUtil.getParentOfType(elementAt(file, offset), PsiMethod.class);
        PsiCodeBlock body = method == null ? null : method.getBody();
        if (body == null) {
            return null;
        }
        int start = body.getTextRange().getStartOffset();
        int end = body.getTextRange().getEndOffset();
        return offset > start && offset < end ? body : null;
    }

    /** 커서에서 보이는 변수(안쪽부터, 가려진 같은 이름은 뺌) 중 객체 형인 것. */
    private static List<PsiVariable> candidates(PsiElement place) {
        Map<String, PsiVariable> visible = new LinkedHashMap<>();
        ElementClassHint variablesOnly = kind -> kind == ElementClassHint.DeclarationKind.VARIABLE
                || kind == ElementClassHint.DeclarationKind.FIELD;
        PsiScopesUtil.treeWalkUp(new PsiScopeProcessor() {
            @Override
            public boolean execute(@NotNull PsiElement element, @NotNull ResolveState state) {
                if (element instanceof PsiVariable v && v.getName() != null) {
                    visible.putIfAbsent(v.getName(), v);
                }
                return true;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T getHint(@NotNull Key<T> hintKey) {
                return hintKey == ElementClassHint.KEY ? (T) variablesOnly : null;
            }
        }, place, null);
        List<PsiVariable> out = new ArrayList<>();
        for (PsiVariable v : visible.values()) {
            if (classOf(v.getType()) != null) {
                out.add(v);
            }
        }
        return out;
    }

    /** 객체 형이면 그 클래스. 원시형 · String · 박싱형 · 컬렉션 · Map · 배열 · 형 매개변수는 null. */
    private static @Nullable PsiClass classOf(PsiType type) {
        if (!(type instanceof PsiClassType classType) || PsiPrimitiveType.getUnboxedType(type) != null) {
            return null;
        }
        PsiClass c = classType.resolve();
        if (c == null || c instanceof PsiTypeParameter || STRING.equals(c.getQualifiedName())) {
            return null;
        }
        for (String collection : COLLECTION_TYPES) {
            if (InheritanceUtil.isInheritor(c, collection)) {
                return null;
            }
        }
        return c;
    }

    /** 인스턴스 필드 이름. 상위 클래스 먼저, 클래스 안은 선언 순서. */
    private static List<String> fieldOrder(PsiClass c) {
        Deque<PsiClass> chain = new ArrayDeque<>();
        for (PsiClass k = c; k != null && !OBJECT.equals(k.getQualifiedName()); k = k.getSuperClass()) {
            chain.addFirst(k);
        }
        List<String> names = new ArrayList<>();
        for (PsiClass k : chain) {
            for (PsiField f : k.getFields()) {
                if (!f.hasModifierProperty(PsiModifier.STATIC)) {
                    names.add(f.getName());
                }
            }
        }
        return names;
    }

    /**
     * public 인스턴스 메서드 중 setter 후보(매개변수 하나) 또는 getter 후보(매개변수 없음, void 아님).
     * getAllMethods 라 상속 · Lombok 이 만든 메서드도 들어온다.
     * shortcut: 제네릭 상위 클래스의 형 매개변수(T)는 치환하지 않아 「형 다름」으로 나올 수 있다. 자주 걸리면 getAllMethodsAndTheirSubstitutors 로 바꾼다.
     */
    private static List<BeanCopy.Accessor> accessors(PsiClass c, boolean setters) {
        List<BeanCopy.Accessor> out = new ArrayList<>();
        for (PsiMethod m : c.getAllMethods()) {
            if (m.isConstructor() || m.hasModifierProperty(PsiModifier.STATIC) || !m.hasModifierProperty(PsiModifier.PUBLIC)) {
                continue;
            }
            PsiParameter[] params = m.getParameterList().getParameters();
            if (setters) {
                if (params.length == 1) {
                    out.add(new BeanCopy.Accessor(m.getName(), params[0].getType().getCanonicalText()));
                }
                continue;
            }
            PsiType returnType = m.getReturnType();
            if (params.length == 0 && returnType != null && !PsiTypes.voidType().equals(returnType)) {
                out.add(new BeanCopy.Accessor(m.getName(), returnType.getCanonicalText()));
            }
        }
        return out;
    }
}
