package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CountermodelExecutionClosureParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_legacy_assigned_countermodel_executes_once_after_resume",
        "test_orchestrator_runs_countermodel_and_prevents_premature_no_action_stop");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("CountermodelExecutionClosureParityTest", authorityFunction);
  }
}
