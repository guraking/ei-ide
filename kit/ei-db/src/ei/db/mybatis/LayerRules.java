package ei.db.mybatis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 계층 이동 · 빠진 짝 표시의 판정 규칙. 어노테이션은 짧은 이름(패키지 없이)으로 견준다.
 * IntelliJ 클래스를 쓰지 않는다.
 */
public final class LayerRules {
  private LayerRules() {}

  /** 메서드에 SQL 을 직접 단 MyBatis 어노테이션. 이런 메서드는 XML 문장이 없어도 된다. */
  static final Set<String> STATEMENT_ANNOTATIONS = Set.of(
      "Select", "Insert", "Update", "Delete",
      "SelectProvider", "InsertProvider", "UpdateProvider", "DeleteProvider", "Flush");

  static final Set<String> CONTROLLER_ANNOTATIONS = Set.of("Controller", "RestController");

  static final Set<String> MAPPING_ANNOTATIONS = Set.of(
      "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");

  static final Set<String> SERVICE_ANNOTATIONS = Set.of("Service");

  /** 필드 형식이 이 어노테이션을 단 클래스면 다음 계층(빈)으로 본다. */
  static final Set<String> BEAN_ANNOTATIONS = Set.of("Service", "Component", "Repository");

  /** 계층 이동 아이콘을 달 메서드 종류. */
  enum Kind { CONTROLLER, SERVICE }

  /** 정규 이름이면 마지막 마디, 짧은 이름이면 그대로. null 이면 null. */
  static String simpleName(String name) {
    if (name == null) return null;
    return name.substring(name.lastIndexOf('.') + 1);
  }

  /** names 중 하나라도 wanted 에 있으면 true. names 는 짧은 이름이나 정규 이름. */
  static boolean any(Collection<String> names, Set<String> wanted) {
    for (String n : names) {
      if (wanted.contains(simpleName(n))) return true;
    }
    return false;
  }

  /**
   * 계층 이동 아이콘을 달 메서드면 그 종류, 아니면 null.
   * 컨트롤러: 클래스에 @Controller · @RestController, 메서드에 매핑 어노테이션. 서비스: 클래스에 @Service.
   */
  static Kind kind(Collection<String> classAnnotations, Collection<String> methodAnnotations) {
    if (any(classAnnotations, CONTROLLER_ANNOTATIONS)) {
      return any(methodAnnotations, MAPPING_ANNOTATIONS) ? Kind.CONTROLLER : null;
    }
    return any(classAnnotations, SERVICE_ANNOTATIONS) ? Kind.SERVICE : null;
  }

  /** 필드 형식이 다음 계층(인터페이스이거나 빈 어노테이션을 단 클래스)이면 true. */
  static boolean isBean(boolean isInterface, Collection<String> classAnnotations) {
    return isInterface || any(classAnnotations, BEAN_ANNOTATIONS);
  }

  /** 매퍼 인터페이스 메서드가 XML 문장을 가져야 하면 true. default · static · SQL 어노테이션 메서드는 아니다. */
  static boolean needsStatement(boolean isDefault, boolean isStatic, Collection<String> annotations) {
    return !isDefault && !isStatic && !any(annotations, STATEMENT_ANNOTATIONS);
  }

  /** 짝 없는 이름들(names 순서). names 중 others 에 없는 것. null 이름은 건너뛴다. */
  static List<String> withoutPair(Collection<String> names, Collection<String> others) {
    List<String> out = new ArrayList<>();
    for (String n : names) {
      if (n != null && !others.contains(n)) out.add(n);
    }
    return out;
  }
}
