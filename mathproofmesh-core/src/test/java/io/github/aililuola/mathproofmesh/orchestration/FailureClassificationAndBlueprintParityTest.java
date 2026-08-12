package io.github.aililuola.mathproofmesh.orchestration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class FailureClassificationAndBlueprintParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_four_failure_classes_map_to_existing_actions",
        "test_problem_integrity_failure_forces_framing_reanchor",
        "test_blueprint_rewrite_preserves_verified_artifacts_and_route_history");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    OrchestrationParityScenarios.verify("FailureClassificationAndBlueprintParityTest", authorityFunction);
  }
}
