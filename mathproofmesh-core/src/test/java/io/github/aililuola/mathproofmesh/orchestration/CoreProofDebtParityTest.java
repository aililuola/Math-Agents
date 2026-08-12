package io.github.aililuola.mathproofmesh.orchestration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CoreProofDebtParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_core_closure_excludes_unrelated_auxiliary_obligations",
        "test_core_debt_rewards_core_closure_and_penalizes_open_control_risk");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    OrchestrationParityScenarios.verify("CoreProofDebtParityTest", authorityFunction);
  }
}
