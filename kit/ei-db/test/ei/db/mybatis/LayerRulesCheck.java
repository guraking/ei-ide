package ei.db.mybatis;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 계층 이동 · 빠진 짝 판정 자체 점검: java -cp out:test-out ei.db.mybatis.LayerRulesCheck */
public class LayerRulesCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  public static void main(String[] args) {
    eq(LayerRules.simpleName("org.apache.ibatis.annotations.Select"), "Select");
    eq(LayerRules.simpleName("Select"), "Select");
    eq(LayerRules.simpleName(null), null);

    // 컨트롤러는 매핑 어노테이션이 있는 메서드만
    eq(LayerRules.kind(List.of("RestController"), List.of("GetMapping")), LayerRules.Kind.CONTROLLER);
    eq(LayerRules.kind(List.of("org.springframework.stereotype.Controller"),
        List.of("org.springframework.web.bind.annotation.RequestMapping")), LayerRules.Kind.CONTROLLER);
    eq(LayerRules.kind(List.of("RestController"), List.of()), null);
    eq(LayerRules.kind(List.of("Controller"), List.of("Transactional")), null);
    // 서비스는 클래스 어노테이션만
    eq(LayerRules.kind(List.of("Service"), List.of()), LayerRules.Kind.SERVICE);
    eq(LayerRules.kind(List.of("Service", "Transactional"), List.of("Override")), LayerRules.Kind.SERVICE);
    eq(LayerRules.kind(List.of("Component"), List.of("GetMapping")), null);
    eq(LayerRules.kind(List.of(), List.of()), null);

    eq(LayerRules.isBean(true, List.of()), true);
    eq(LayerRules.isBean(false, List.of("Repository")), true);
    eq(LayerRules.isBean(false, List.of("lombok.Data")), false);

    // XML 문장이 필요한 매퍼 메서드
    eq(LayerRules.needsStatement(false, false, List.of()), true);
    eq(LayerRules.needsStatement(false, false, List.of("Param", "Options")), true);
    eq(LayerRules.needsStatement(true, false, List.of()), false);
    eq(LayerRules.needsStatement(false, true, List.of()), false);
    eq(LayerRules.needsStatement(false, false, List.of("Select")), false);
    eq(LayerRules.needsStatement(false, false, List.of("org.apache.ibatis.annotations.SelectProvider")), false);
    eq(LayerRules.needsStatement(false, false, List.of("Flush")), false);

    // 짝 없는 이름
    eq(LayerRules.withoutPair(List.of("find", "insert", "count"), Set.of("find", "count", "other")), List.of("insert"));
    eq(LayerRules.withoutPair(List.of(), Set.of("find")), List.of());
    eq(LayerRules.withoutPair(java.util.Arrays.asList("a", null), Set.of()), List.of("a"));

    System.out.println("LayerRules 통과");
  }
}
