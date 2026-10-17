package ei.db.mybatis;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PropertyUtilBase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** resultType 이름을 프로젝트 클래스로 찾고 필드 이름(상위 클래스 포함, static 제외)을 준다. 읽기 접근 안에서 부른다. */
final class DtoFields {
  private DtoFields() {}

  /** PsiModifier.STATIC 과 같은 값(PsiModifier 가 클래스패스에 없다). */
  private static final String STATIC = "static";
  private static final String JAVA_STRING = "java.lang.String";
  /** MyBatis 의 문자열 형식 이름(별칭은 대소문자를 가리지 않는다). */
  private static final Set<String> STRING_TYPES = Set.of("string", "java.lang.string");

  /**
   * 실행 창이 쓰는 클래스 정보. result 는 견줄 resultType 클래스(없으면 null), textParams 는 문자열로 받을 매개변수 이름.
   */
  record ForRun(PsiClass result, List<String> resultFields, Set<String> textParams) {}

  /** 읽기 접근 안에서 부른다. resultMap 을 쓰거나 select 가 아니면 resultType 은 견주지 않는다. */
  static ForRun forRun(Project project, MapperDoc doc, MapperDoc.Node statement) {
    boolean select = "select".equals(statement.name) && statement.attr("resultMap") == null;
    PsiClass result = select ? find(project, statement.attr("resultType")) : null;
    return new ForRun(result, result == null ? List.of() : of(result), textParams(project, doc, statement));
  }

  /** parameterType 이 문자열이면 모든 매개변수, DTO 면 그 String 필드 이름. 모르면 빈 집합. */
  private static Set<String> textParams(Project project, MapperDoc doc, MapperDoc.Node statement) {
    String t = statement.attr("parameterType");
    if (t == null || t.isBlank()) return Set.of();
    if (STRING_TYPES.contains(t.trim().toLowerCase(Locale.ROOT))) return new HashSet<>(DynamicSql.paramNames(doc, statement));
    PsiClass c = find(project, t);
    if (c == null) return Set.of();
    Set<String> out = new HashSet<>();
    for (PsiField f : c.getAllFields()) {
      if (!f.hasModifierProperty(STATIC) && f.getType().equalsToText(JAVA_STRING)) out.add(f.getName());
    }
    return out;
  }

  /** 비교할 수 없는 형식이거나 찾지 못하면 null. */
  static PsiClass find(Project project, String resultType) {
    if (resultType == null || resultType.isBlank() || DumbService.isDumb(project)) return null;
    String t = resultType.trim();
    if (MapperParams.isBuiltinType(t)) return null;
    PsiClass found;
    if (t.contains(".")) {
      found = JavaPsiFacade.getInstance(project).findClass(t, GlobalSearchScope.allScope(project));
    } else {
      // typeAliases 는 보통 클래스 단순 이름(첫 글자만 소문자일 수 있음)이다.
      String simple = Character.toUpperCase(t.charAt(0)) + t.substring(1);
      PsiClass[] all = PsiShortNamesCache.getInstance(project).getClassesByName(simple, GlobalSearchScope.projectScope(project));
      found = all.length > 0 ? all[0] : null;
    }
    if (found == null || found.getQualifiedName() == null || found.getQualifiedName().startsWith("java.")) return null;
    return found;
  }

  static List<String> of(PsiClass c) {
    List<String> out = new ArrayList<>();
    for (PsiField f : c.getAllFields()) {
      if (!f.hasModifierProperty(STATIC) && !out.contains(f.getName())) out.add(f.getName());
    }
    return out;
  }

  /** 필드 이름에 getter 속성 이름(getXxx · isXxx, 상위 클래스 포함)을 더한 것. */
  static List<String> properties(PsiClass c) {
    List<String> out = of(c);
    for (PsiMethod m : c.getAllMethods()) {
      if (!PropertyUtilBase.isSimplePropertyGetter(m)) continue;
      String name = PropertyUtilBase.getPropertyNameByGetter(m);
      if (!out.contains(name)) out.add(name);
    }
    return out;
  }
}
