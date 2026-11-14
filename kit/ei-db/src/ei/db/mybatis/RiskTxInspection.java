package ei.db.mybatis;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLoopStatement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.text.CharArrayUtil;
import ei.db.EiDbBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Transactional 없이 DB 를 두 번 이상 고치는 메서드. 반복문 · forEach 안에서 한 번 고치면 두 번으로 센다.
 * 「고침」은 매퍼 인터페이스 메서드 중 XML 문장이 insert · update · delete 이거나 @Insert · @Update · @Delete(Provider) 인 것,
 * MyBatis SqlSession · 전자정부 DAO 의 insert · update · delete, Spring JdbcTemplate 의 update · batchUpdate 다.
 * 트랜잭션으로 보는 것: 메서드 · 상위 메서드 · 클래스 · 상위 클래스 · 인터페이스의 @Transactional(이름만 보므로 jakarta · javax 도),
 * 그 어노테이션을 단 어노테이션(한 단계), 메서드 안에서 org.springframework.transaction 의 메서드를 부름(TransactionTemplate 등).
 * 프로젝트에 XML · 자바 설정 트랜잭션(tx:advice · TransactionInterceptor · TransactionProxyFactoryBean)이 있으면 아무것도 표시하지 않는다.
 * 그 설정은 프로젝트 루트 · 파일 구조가 바뀔 때 다시 찾는다(이미 있던 파일에 설정을 새로 쓰면 프로젝트를 다시 열 때 반영).
 * private · static 메서드와 테스트 소스는 보지 않는다(프록시 트랜잭션이 걸리지 않는 자리, 부르는 쪽이 정한다).
 */
public final class RiskTxInspection extends LocalInspectionTool {
  private static final String TRANSACTIONAL = "Transactional";
  private static final String SPRING_TX = "org.springframework.transaction.";
  private static final String SPRING_JDBC = "org.springframework.jdbc.";
  private static final Set<String> WRITE_ANNOTATIONS = Set.of("Insert", "Update", "Delete", "InsertProvider", "UpdateProvider", "DeleteProvider");
  private static final List<String> WRITE_TAGS = List.of("insert", "update", "delete");
  private static final Set<String> SESSION_WRITES = Set.of("insert", "update", "delete");
  private static final List<String> SESSION_PACKAGES = List.of("org.apache.ibatis.", "org.mybatis.", "egovframework.");
  private static final Set<String> JDBC_WRITES = Set.of("update", "batchUpdate");
  private static final String FOR_EACH = "forEach";
  private static final int MIN_WRITES = 2;
  private static final int LOOP_WEIGHT = 2;
  /** 알림에 이름을 보여 줄 고침 호출 수. */
  private static final int NAMES_SHOWN = 3;
  /** 단어 색인으로 후보 파일을 고르는 낱말과, 그 파일에 있어야 하는 글. */
  private static final List<String> TX_CONFIG_WORDS = List.of("advice", "TransactionInterceptor", "TransactionProxyFactoryBean");
  private static final List<String> TX_CONFIG_MARKS = List.of("tx:advice", "TransactionInterceptor", "TransactionProxyFactoryBean");
  private static final Key<CachedValue<Boolean>> TX_CONFIG = Key.create("ei.db.mybatis.RiskTxInspection.txConfig");

  @Override
  public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
    Project project = holder.getProject();
    if (inTests(holder.getFile()) || hasTxConfig(project)) return PsiElementVisitor.EMPTY_VISITOR;
    Map<String, Boolean> statements = new HashMap<>();
    return new JavaElementVisitor() {
      @Override
      public void visitMethod(@NotNull PsiMethod m) {
        PsiCodeBlock body = m.getBody();
        PsiClass c = m.getContainingClass();
        PsiIdentifier name = m.getNameIdentifier();
        if (body == null || c == null || name == null || c.isInterface() || m.isConstructor()
            || m.hasModifierProperty(PsiModifier.PRIVATE) || m.hasModifierProperty(PsiModifier.STATIC)) return;
        int count = 0;
        boolean loop = false;
        List<String> names = new ArrayList<>();
        for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class)) {
          PsiMethod callee = call.resolveMethod();
          if (callee == null) continue;
          PsiClass owner = callee.getContainingClass();
          String q = owner == null ? null : owner.getQualifiedName();
          if (q != null && q.startsWith(SPRING_TX)) return;
          if (owner == null || !isWrite(callee, owner, q, call, project, statements)) continue;
          boolean inLoop = inLoop(call, body);
          loop |= inLoop;
          count += inLoop ? LOOP_WEIGHT : 1;
          if (names.size() < NAMES_SHOWN && !names.contains(callee.getName())) names.add(callee.getName());
        }
        if (count < MIN_WRITES || hasTx(m)) return;
        holder.registerProblem(name, EiDbBundle.message(loop ? "risk.tx.loop" : "risk.tx", String.join(", ", names)));
      }
    };
  }

  /** 테스트 소스 파일인지. 테스트 코드는 일부러 가짜 값 · 트랜잭션 없는 호출을 쓴다. */
  static boolean inTests(PsiFile file) {
    VirtualFile vf = file.getVirtualFile();
    return vf != null && ProjectFileIndex.getInstance(file.getProject()).isInTestSourceContent(vf);
  }

  private static boolean isWrite(PsiMethod callee, PsiClass owner, @Nullable String q, PsiMethodCallExpression call, Project project,
                                 Map<String, Boolean> statements) {
    for (String a : LayerNav.annotationNames(callee)) {
      if (WRITE_ANNOTATIONS.contains(a)) return true;
    }
    String name = callee.getName();
    if (q != null) {
      if (SESSION_WRITES.contains(name)) {
        for (String p : SESSION_PACKAGES) {
          if (q.startsWith(p)) return true;
        }
      }
      if (JDBC_WRITES.contains(name) && q.startsWith(SPRING_JDBC)) return true;
    }
    if (!owner.isInterface()) return false;
    // 공용 상위 인터페이스에 선언한 메서드는 받는 쪽 형식(하위 매퍼)의 namespace 로 찾는다.
    PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
    PsiClass receiver = qualifier != null && qualifier.getType() instanceof PsiClassType t ? t.resolve() : null;
    List<String> namespaces = new ArrayList<>();
    if (receiver != null && receiver.getQualifiedName() != null) namespaces.add(receiver.getQualifiedName());
    if (q != null && !namespaces.contains(q)) namespaces.add(q);
    for (String ns : namespaces) {
      Boolean write = statements.computeIfAbsent(ns + '#' + name, k -> statementWrites(project, ns, name));
      if (write != null) return write;
    }
    return false;
  }

  /** namespace 의 문장 id 가 insert · update · delete 면 true, select 면 false, 문장이 없으면 null. */
  private static @Nullable Boolean statementWrites(Project project, String namespace, String id) {
    for (MapperIndex.Entry e : MapperIndex.find(project, namespace)) {
      Integer at = e.ids().get(id);
      if (at == null) continue;
      PsiFile f = PsiManager.getInstance(project).findFile(e.file());
      if (f == null) continue;
      CharSequence text = f.getViewProvider().getContents();
      for (String tag : WRITE_TAGS) {
        if (at + 1 + tag.length() <= text.length() && CharArrayUtil.regionMatches(text, at + 1, tag)) return true;
      }
      return false;
    }
    return null;
  }

  /** 반복문 몸통 또는 forEach(…) 에 넘긴 람다 안인지(메서드 몸통까지만 올라간다). */
  private static boolean inLoop(PsiElement call, PsiCodeBlock body) {
    for (PsiElement p = call.getParent(); p != null && p != body; p = p.getParent()) {
      if (p instanceof PsiLoopStatement) return true;
      if (p instanceof PsiLambdaExpression && p.getParent() instanceof PsiExpressionList args
          && args.getParent() instanceof PsiMethodCallExpression mc && FOR_EACH.equals(mc.getMethodExpression().getReferenceName())) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasTx(PsiMethod m) {
    if (annotated(m)) return true;
    for (PsiMethod s : m.findSuperMethods()) {
      if (annotated(s)) return true;
    }
    Deque<PsiClass> todo = new ArrayDeque<>();
    Set<PsiClass> seen = new HashSet<>();
    todo.add(m.getContainingClass());
    while (!todo.isEmpty()) {
      PsiClass c = todo.poll();
      if (!seen.add(c)) continue;
      if (annotated(c)) return true;
      for (PsiClass s : c.getSupers()) todo.add(s);
    }
    return false;
  }

  /** @Transactional, 또는 @Transactional 을 단 어노테이션. */
  private static boolean annotated(PsiModifierListOwner owner) {
    for (PsiAnnotation a : owner.getAnnotations()) {
      String name = a.getNameReferenceElement() == null ? null : a.getNameReferenceElement().getReferenceName();
      if (TRANSACTIONAL.equals(name)) return true;
      PsiClass type = a.resolveAnnotationType();
      if (type != null && LayerNav.annotationNames(type).contains(TRANSACTIONAL)) return true;
    }
    return false;
  }

  /** 프로젝트 소스에 XML · 자바 설정 트랜잭션이 있는지. 단어 색인으로 후보만 열어 본다. */
  private static boolean hasTxConfig(Project project) {
    return CachedValuesManager.getManager(project).getCachedValue(project, TX_CONFIG, () -> CachedValueProvider.Result.create(
        findTxConfig(project), ProjectRootModificationTracker.getInstance(project), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS), false);
  }

  private static boolean findTxConfig(Project project) {
    PsiSearchHelper search = PsiSearchHelper.getInstance(project);
    GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
    boolean[] found = {false};
    for (String word : TX_CONFIG_WORDS) {
      search.processAllFilesWithWord(word, scope, f -> {
        CharSequence text = f.getViewProvider().getContents();
        for (String mark : TX_CONFIG_MARKS) {
          if (StringUtil.contains(text, mark)) {
            found[0] = true;
            return false;
          }
        }
        return true;
      }, true);
      if (found[0]) return true;
    }
    return false;
  }
}
