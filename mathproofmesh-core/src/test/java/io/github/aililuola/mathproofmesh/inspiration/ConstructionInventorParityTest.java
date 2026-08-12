package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ConstructionInventorParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_construction_has_definition_target_and_falsification_test",
        "test_invariant_remains_a_falsifiable_hypothesis",
        "test_reverse_goal_materializes_only_explicit_bridge_gaps");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("ConstructionInventorParityTest", authorityFunction);
  }
}
