package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MetaDirectiveControlParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_meta_strategy_becomes_audited_control_task_not_an_insight",
        "test_audited_cooldown_mutates_route_registry_and_resumes_exactly_once",
        "test_shadow_meta_directive_never_mutates_route_control");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("MetaDirectiveControlParityTest", authorityFunction);
  }
}
