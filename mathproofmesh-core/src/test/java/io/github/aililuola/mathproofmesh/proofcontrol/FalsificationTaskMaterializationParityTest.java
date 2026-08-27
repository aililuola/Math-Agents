package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class FalsificationTaskMaterializationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_strategy_falsification_materializes_fast_lane_task",
        "test_fast_lane_counterexample_refutes_claim",
        "test_no_counterexample_does_not_verify_universal_claim",
        "test_unsupported_falsification_has_agent_or_wakeable_task",
        "test_materialized_falsification_runs_before_route_turn");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("FalsificationTaskMaterializationParityTest", authorityFunction);
  }
}
