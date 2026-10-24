package ei.db.mybatis;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PropertyUtilBase;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 매퍼 문장의 #{} 에 쓸 수 있는 이름들. 읽기 접근 안에서 부른다. 색인 중이면 bind · foreach 이름만.
 * parameterType 이 프로젝트 클래스면 그 필드 · getter 속성, 아니면 매퍼 인터페이스 메서드의 매개변수(@Param 이름 우선,
 * @Param 없는 DTO 하나뿐이면 그 필드 · 속성)를 낸다. Map 매개변수 하나뿐이면 키를 알 수 없어 내지 않는다.
 */
final class StatementParams {
  private StatementParams() {}

  private static final String PARAM_ANNOTATION = "org.apache.ibatis.annotations.Param";
  private static final String MAP = "java.util.Map";
  private static final String STATIC = "static";

  /** target 은 Ctrl+클릭으로 갈 곳(bind · foreach 이름은 null), type 은 목록에 보일 형식(모르면 null). */
  record Param(String name, @Nullable PsiElement target, @Nullable String type, Icon icon) {}

  static List<Param> of(Project project, MapperDoc doc, MapperDoc.Node statement) {
    List<Param> out = new ArrayList<>();
    if (!DumbService.isDumb(project)) {
      String type = MapperParams.checkedType(statement);
      PsiClass dto = type == null ? null : DtoFields.find(project, type);
      if (dto != null) {
        addProperties(dto, out);
      } else if (type == null) {
        PsiMethod m = LayerCode.mapperMethod(project, doc.namespace, statement.attr("id"));
        if (m != null) addMethodParams(m, out);
      }
    }
    for (String n : MapperParams.localNames(statement)) out.add(new Param(n, null, null, AllIcons.Nodes.Variable));
    return out;
  }

  private static void addMethodParams(PsiMethod m, List<Param> out) {
    PsiParameter[] ps = m.getParameterList().getParameters();
    if (ps.length == 1 && paramName(ps[0]) == null) {
      PsiType t = ps[0].getType();
      if (InheritanceUtil.isInheritor(t, MAP)) return;
      PsiClass c = t instanceof PsiClassType ct ? ct.resolve() : null;
      if (c != null && c.getQualifiedName() != null && !MapperParams.isBuiltinType(c.getQualifiedName())) {
        addProperties(c, out);
        return;
      }
    }
    for (PsiParameter p : ps) {
      String name = paramName(p);
      out.add(new Param(name != null ? name : p.getName(), p, p.getType().getPresentableText(), AllIcons.Nodes.Parameter));
    }
  }

  /** 필드(상위 포함, static 제외) 다음에 필드 없는 getter 속성. */
  private static void addProperties(PsiClass c, List<Param> out) {
    Set<String> seen = new LinkedHashSet<>();
    for (PsiField f : c.getAllFields()) {
      if (f.hasModifierProperty(STATIC) || !seen.add(f.getName())) continue;
      out.add(new Param(f.getName(), f, f.getType().getPresentableText(), AllIcons.Nodes.Field));
    }
    for (PsiMethod m : c.getAllMethods()) {
      if (!PropertyUtilBase.isSimplePropertyGetter(m)) continue;
      String name = PropertyUtilBase.getPropertyNameByGetter(m);
      PsiType rt = m.getReturnType();
      if (seen.add(name)) out.add(new Param(name, m, rt == null ? null : rt.getPresentableText(), AllIcons.Nodes.Property));
    }
  }

  /** @Param("이름") 의 이름. 없거나 글자 상수가 아니면 null. */
  private static @Nullable String paramName(PsiParameter p) {
    PsiAnnotation a = p.getAnnotation(PARAM_ANNOTATION);
    if (a == null) return null;
    PsiAnnotationMemberValue v = a.findAttributeValue("value");
    return v instanceof PsiLiteralExpression l && l.getValue() instanceof String s ? s : null;
  }
}
