package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class StrategyBlueprintBeforeAdmissionParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_blueprint_contains_non_main_subgoal_and_path",
        "test_blueprint_preserves_strategy_mechanism",
        "test_blueprint_compiler_runs_before_route_admission",
        "test_failed_blueprint_does_not_enter_core_graph");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("StrategyBlueprintBeforeAdmissionParityTest", authorityFunction);
  }
}
