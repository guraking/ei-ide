package ei.db.mybatis;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PropertyUtilBase;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** resultType 이름을 프로젝트 클래스로 찾고 필드 이름(상위 클래스 포함, static 제외)을 준다. 읽기 접근 안에서 부른다. */
final class DtoFields {
  private DtoFields() {}

  /** PsiModifier.STATIC 과 같은 값(PsiModifier 가 클래스패스에 없다). */
  private static final String STATIC = "static";
  private static final String JAVA_STRING = "java.lang.String";
  /** MyBatis 의 문자열 형식 이름(별칭은 대소문자를 가리지 않는다). */
  private static final Set<String> STRING_TYPES = Set.of("string", "java.lang.string");
  private static final String MAP = "java.util.Map";
  private static final String ALIAS_ANNOTATION = "org.apache.ibatis.type.Alias";

  /**
   * 실행 창이 쓰는 클래스 정보. result 는 견줄 resultType 클래스(없으면 null), textParams 는 문자열로 받을 매개변수 이름,
   * mappers 는 문장이 다른 매퍼의 조각을 include 할 때 쓸 네임스페이스 → 매퍼 문서(찾지 못한 것은 null 값).
   */
  record ForRun(PsiClass result, List<String> resultFields, Set<String> textParams, Map<String, MapperDoc> mappers) {}

  /** 읽기 접근 안에서 부른다. resultMap 을 쓰거나 select 가 아니면 resultType 은 견주지 않는다. */
  static ForRun forRun(Project project, MapperDoc doc, MapperDoc.Node statement) {
    Map<String, MapperDoc> mappers = new HashMap<>();
    Function<String, MapperDoc> load = ns -> {
      if (!mappers.containsKey(ns)) mappers.put(ns, loadMapper(project, ns));
      return mappers.get(ns);
    };
    // 이름을 모으며 include 를 모두(조건과 상관없이) 따라가므로 쓰일 수 있는 다른 매퍼 문서가 mappers 에 다 담긴다.
    List<String> names = DynamicSql.paramNames(doc, statement, load);
    boolean select = "select".equals(statement.name) && statement.attr("resultMap") == null;
    PsiClass found = select ? find(project, statement.attr("resultType")) : null;
    // Map 을 이어받은 결과 형식(EgovMap 등)은 칼럼을 키로 받으므로 필드와 견주지 않는다.
    PsiClass result = found == null || isMap(found) ? null : found;
    return new ForRun(result, result == null ? List.of() : of(result), textParams(project, doc, statement, names), mappers);
  }

  /** namespace 매퍼 XML 들 가운데 처음 읽힌 것. 없으면 null. */
  private static @Nullable MapperDoc loadMapper(Project project, String namespace) {
    for (MapperIndex.Entry e : MapperIndex.find(project, namespace)) {
      MapperDoc d = LayerCode.load(e.file());
      if (d != null) return d;
    }
    return null;
  }

  /**
   * parameterType 이 문자열이면 모든 매개변수, DTO 면 그 String 필드 이름(Map 을 이어받은 클래스면 빈 집합).
   * parameterType 이 없으면 매퍼 인터페이스 메서드의 String 매개변수(@Param 이름) · DTO 하나면 그 String 필드 · 속성. 모르면 빈 집합.
   */
  private static Set<String> textParams(Project project, MapperDoc doc, MapperDoc.Node statement, List<String> names) {
    String t = statement.attr("parameterType");
    if (t == null || t.isBlank()) return methodTextParams(project, doc, statement);
    if (STRING_TYPES.contains(t.trim().toLowerCase(Locale.ROOT))) return new HashSet<>(names);
    PsiClass c = find(project, t);
    if (c == null || isMap(c)) return Set.of();
    Set<String> out = new HashSet<>();
    for (PsiField f : c.getAllFields()) {
      if (!f.hasModifierProperty(STATIC) && f.getType().equalsToText(JAVA_STRING)) out.add(f.getName());
    }
    return out;
  }

  private static Set<String> methodTextParams(Project project, MapperDoc doc, MapperDoc.Node statement) {
    Set<String> out = new HashSet<>();
    for (StatementParams.Param p : StatementParams.of(project, doc, statement, null)) {
      PsiType type = p.target() instanceof PsiVariable v ? v.getType() : p.target() instanceof PsiMethod m ? m.getReturnType() : null;
      if (type != null && type.equalsToText(JAVA_STRING)) out.add(p.name());
    }
    return out;
  }

  /** java.util.Map 을 이어받은 클래스(CommonMap · EgovMap 등). 필드가 아닌 키로 값을 받으므로 DTO 로 보지 않는다. */
  static boolean isMap(PsiClass c) {
    return InheritanceUtil.isInheritor(c, MAP);
  }

  static PsiClass find(Project project, String resultType) {
    return find(project, resultType, null);
  }

  /**
   * 비교할 수 없는 형식이거나 찾지 못하면 null. 같은 단순 이름 클래스가 여럿이면 @Alias 값이 같은 것, 그다음 from(매퍼 XML)과 같은 모듈의 것이
   * 하나뿐일 때 그것이고, 그래도 가릴 수 없으면 null(엉뚱한 클래스로 가지 않게).
   */
  static PsiClass find(Project project, String resultType, @Nullable VirtualFile from) {
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
      found = all.length == 1 ? all[0] : pick(project, all, t, from);
    }
    if (found == null || found.getQualifiedName() == null || found.getQualifiedName().startsWith("java.")) return null;
    return found;
  }

  private static @Nullable PsiClass pick(Project project, PsiClass[] all, String alias, @Nullable VirtualFile from) {
    List<PsiClass> aliased = new ArrayList<>();
    for (PsiClass c : all) {
      if (alias.equalsIgnoreCase(aliasOf(c))) aliased.add(c);
    }
    if (aliased.size() == 1) return aliased.get(0);
    Module module = from == null ? null : ModuleUtilCore.findModuleForFile(from, project);
    if (module == null) return null;
    PsiClass same = null;
    for (PsiClass c : aliased.isEmpty() ? List.of(all) : aliased) {
      if (module.equals(ModuleUtilCore.findModuleForPsiElement(c))) {
        if (same != null) return null;
        same = c;
      }
    }
    return same;
  }

  /** @Alias("이름") 의 이름. 없으면 null. */
  private static @Nullable String aliasOf(PsiClass c) {
    PsiAnnotation a = c.getAnnotation(ALIAS_ANNOTATION);
    PsiAnnotationMemberValue v = a == null ? null : a.findAttributeValue("value");
    return v instanceof PsiLiteralExpression l && l.getValue() instanceof String s ? s : null;
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
