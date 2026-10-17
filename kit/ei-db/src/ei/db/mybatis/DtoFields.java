package ei.db.mybatis;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** resultType 이름을 프로젝트 클래스로 찾고 필드 이름(상위 클래스 포함, static 제외)을 준다. 읽기 접근 안에서 부른다. */
final class DtoFields {
  private DtoFields() {}

  /** PsiModifier.STATIC 과 같은 값(PsiModifier 가 클래스패스에 없다). */
  private static final String STATIC = "static";

  /** MyBatis 기본 별칭 중 필드 비교가 뜻 없는 것(원시형 · 문자열 · 날짜 · 숫자 · Map · 컬렉션). */
  private static final Set<String> BUILTIN = Set.of("string", "byte", "long", "short", "int", "integer", "double",
      "float", "boolean", "char", "character", "date", "decimal", "bigdecimal", "biginteger", "object", "map",
      "hashmap", "list", "arraylist", "collection", "iterator", "resultset");

  /** 비교할 수 없는 형식이거나 찾지 못하면 null. */
  static PsiClass find(Project project, String resultType) {
    if (resultType == null || resultType.isBlank() || DumbService.isDumb(project)) return null;
    String t = resultType.trim();
    if (t.startsWith("_") || BUILTIN.contains(t.toLowerCase(Locale.ROOT))) return null;
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
}
