package ei.db.mybatis;

import com.intellij.codeInsight.FileModificationService;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportHolder;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PropertyUtilBase;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import ei.db.EiDbBundle;
import ei.db.Notify;
import ei.db.gen.Names;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 계층 코드 만들기 · 서비스 메서드 자동 완성이 함께 쓰는 PSI 쪽 도움. 따로 적지 않으면 읽기 접근 안에서 부른다. */
final class LayerCode {
  private LayerCode() {}

  private static final String OBJECT = "java.lang.Object";
  private static final String VOID = "void";
  private static final String LOMBOK_DATA = "lombok.Data";
  private static final String LOMBOK_GETTER = "lombok.Getter";
  private static final String LOMBOK_SETTER = "lombok.Setter";

  /** type 형식의 필드를 가진 클래스와 그 필드 이름. */
  record Holder(PsiClass cls, String field) {}

  /** 매퍼 XML 안 문장 하나. */
  record Located(VirtualFile file, MapperDoc doc, MapperDoc.Node statement) {}

  /** 인터페이스 메서드의 실제 모양. */
  static ServiceCode.Sig sigOf(PsiMethod m) {
    PsiType rt = m.getReturnType();
    List<ServiceCode.Param> params = new ArrayList<>();
    for (PsiParameter p : m.getParameterList().getParameters()) {
      params.add(new ServiceCode.Param(p.getType().getCanonicalText(), p.getName()));
    }
    return new ServiceCode.Sig(rt == null ? VOID : rt.getCanonicalText(), params, false);
  }

  /** 매퍼 인터페이스에 같은 이름 메서드가 있으면 그 모양, 없으면 XML 로 짐작한 모양. */
  static ServiceCode.Sig sigFor(Project project, MapperDoc doc, MapperDoc.Node statement) {
    PsiMethod m = mapperMethod(project, doc.namespace, statement.attr("id"));
    return m != null ? sigOf(m) : ServiceCode.fromXml(doc, statement, alias -> resolveType(project, alias));
  }

  static @Nullable PsiClass findClass(Project project, @Nullable String fqn) {
    if (fqn == null || fqn.isBlank()) return null;
    return JavaPsiFacade.getInstance(project).findClass(fqn.trim(), GlobalSearchScope.allScope(project));
  }

  static @Nullable PsiMethod mapperMethod(Project project, @Nullable String namespace, @Nullable String id) {
    PsiClass c = findClass(project, namespace);
    if (c == null || id == null) return null;
    PsiMethod[] ms = c.findMethodsByName(id, true);
    return ms.length > 0 ? ms[0] : null;
  }

  /** resultType · parameterType 별칭을 프로젝트 클래스의 정규 이름으로. 찾지 못하면 받은 값. */
  static String resolveType(Project project, String alias) {
    PsiClass c = DtoFields.find(project, alias);
    return c == null || c.getQualifiedName() == null ? alias : c.getQualifiedName();
  }

  /** namespace 매퍼 XML 들 가운데 id 문장이 있는 첫 것. 없거나 읽지 못하면 null. */
  static @Nullable Located locate(Project project, @Nullable String namespace, String id) {
    for (MapperIndex.Entry e : MapperIndex.find(project, namespace)) {
      if (!e.ids().containsKey(id)) continue;
      MapperDoc doc = load(e.file());
      MapperDoc.Node s = doc == null ? null : doc.statement(id);
      if (s != null) return new Located(e.file(), doc, s);
    }
    return null;
  }

  /** 열린 문서가 있으면 그 글, 아니면 파일 글. 읽지 못하면 null. */
  static @Nullable MapperDoc load(VirtualFile f) {
    if (f == null || !f.isValid()) return null;
    Document d = FileDocumentManager.getInstance().getCachedDocument(f);
    try {
      return MapperDoc.parse(d != null ? d.getImmutableCharSequence() : VfsUtilCore.loadText(f));
    } catch (IOException ex) {
      return null; // 읽지 못한 파일은 후보에서 빠진다.
    }
  }

  /**
   * type 형식의 필드를 가진 프로젝트 클래스(인터페이스 · type 자신 제외) 가운데 처음 찾은 것. 없으면 null.
   * 참조 찾기라 색인 중이 아닐 때 부른다.
   */
  static @Nullable Holder holderOf(Project project, @Nullable PsiClass type) {
    if (type == null) return null;
    for (PsiReference ref : ReferencesSearch.search(type, GlobalSearchScope.projectScope(project)).findAll()) {
      ProgressManager.checkCanceled();
      PsiElement el = ref.getElement();
      PsiField f = PsiTreeUtil.getParentOfType(el, PsiField.class);
      PsiTypeElement te = f == null ? null : f.getTypeElement();
      if (te == null || !PsiTreeUtil.isAncestor(te, el, false)) continue;
      PsiClass c = f.getContainingClass();
      if (c != null && !c.isInterface() && c != type && c.getQualifiedName() != null) return new Holder(c, f.getName());
    }
    return null;
  }

  /** cls 의 필드 가운데 형식이 fqn 인 것의 이름. 없으면 null. */
  static @Nullable String fieldOfType(@Nullable PsiClass cls, String fqn) {
    if (cls == null) return null;
    for (PsiField f : cls.getAllFields()) {
      if (f.getType().equalsToText(fqn)) return f.getName();
    }
    return null;
  }

  /** 프로젝트 안 첫 인터페이스(java.* 제외). 없으면 null. */
  static @Nullable PsiClass projectInterface(@Nullable PsiClass cls) {
    if (cls == null) return null;
    for (PsiClass i : cls.getInterfaces()) {
      String q = i.getQualifiedName();
      if (q != null && !q.startsWith("java.") && i.getContainingFile() != null && i.getContainingFile().isWritable()) return i;
    }
    return null;
  }

  /** getter 로 읽을 수 있는 속성(상위 클래스 포함, Object 의 getClass 제외). */
  static List<ServiceCode.Prop> props(@Nullable PsiClass c) {
    List<ServiceCode.Prop> out = new ArrayList<>();
    if (c == null) return out;
    for (PsiMethod m : c.getAllMethods()) {
      PsiClass owner = m.getContainingClass();
      if (owner == null || OBJECT.equals(owner.getQualifiedName()) || !PropertyUtilBase.isSimplePropertyGetter(m)) continue;
      String name = PropertyUtilBase.getPropertyNameByGetter(m);
      PsiType t = m.getReturnType();
      boolean seen = false;
      for (ServiceCode.Prop p : out) seen |= p.name().equals(name);
      if (!seen && t != null) out.add(new ServiceCode.Prop(name, t.getCanonicalText()));
    }
    return out;
  }

  /** @Data, 또는 @Getter 와 @Setter 가 함께 붙어 getter · setter 를 따로 쓸 필요가 없는 클래스. */
  static boolean lombok(@Nullable PsiClass c) {
    if (c == null) return false;
    return c.hasAnnotation(LOMBOK_DATA) || (c.hasAnnotation(LOMBOK_GETTER) && c.hasAnnotation(LOMBOK_SETTER));
  }

  /** 단순 이름이 text 에 낱말로 나오는 정규 이름만 들여온다. java 파일이 아니거나 클래스를 찾지 못하면 건너뛴다. 쓰기 동작 안에서 부른다. */
  static void importAll(PsiFile file, String text, Set<String> imports) {
    if (!(file instanceof PsiImportHolder holder)) return;
    for (String fqn : imports) {
      String simple = Names.simple(fqn);
      if (!Pattern.compile("\\b" + Pattern.quote(simple) + "\\b").matcher(text).find()) continue;
      PsiClass c = JavaPsiFacade.getInstance(file.getProject()).findClass(fqn, file.getResolveScope());
      if (c != null) holder.importClass(c);
    }
  }

  /**
   * 화면 스레드에서 부른다. methodText 를 target 에 메서드로 넣고 들여올 이름을 넣은 뒤 그 메서드로 간다.
   * 같은 이름 메서드가 이미 있으면 넣지 않고 그리로 가서 알린다. 글을 읽지 못하거나 쓰지 못하면 알린다.
   */
  static void insert(Project project, @Nullable PsiClass target, String methodText, Set<String> imports) {
    if (target == null || !target.isValid()) {
      Notify.error(project, EiDbBundle.message("layercode.no.target.class"));
      return;
    }
    if (methodText == null || methodText.isBlank()) {
      Notify.error(project, EiDbBundle.message("layercode.empty.method"));
      return;
    }
    if (!FileModificationService.getInstance().preparePsiElementForWrite(target)) return;
    PsiMethod[] result = new PsiMethod[1];
    boolean[] existed = new boolean[1];
    try {
      WriteCommandAction.writeCommandAction(project, target.getContainingFile()).withName(EiDbBundle.message("layercode.command.insert")).run(() -> {
        PsiMethod m = JavaPsiFacade.getElementFactory(project).createMethodFromText(methodText.strip(), target);
        PsiMethod[] same = target.findMethodsByName(m.getName(), false);
        if (same.length > 0) {
          result[0] = same[0];
          existed[0] = true;
          return;
        }
        PsiElement added = target.add(m);
        importAll(target.getContainingFile(), methodText, imports);
        result[0] = (PsiMethod) CodeStyleManager.getInstance(project).reformat(added);
      });
    } catch (IncorrectOperationException ex) {
      Notify.error(project, EiDbBundle.message("layercode.parse.failed", ex.getMessage()));
      return;
    } catch (RuntimeException ex) {
      Notify.error(project, EiDbBundle.message("layercode.insert.failed", ex.getMessage()));
      return;
    }
    if (result[0] == null) return;
    result[0].navigate(true);
    if (existed[0]) {
      Notify.info(project, EiDbBundle.message("layercode.exists", target.getName(), result[0].getName()));
    }
  }
}
