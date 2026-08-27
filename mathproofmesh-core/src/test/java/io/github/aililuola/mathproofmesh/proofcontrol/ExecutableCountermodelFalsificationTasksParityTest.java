package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ExecutableCountermodelFalsificationTasksParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_falsification_text_compiles_to_typed_contract",
        "test_non_automatable_task_is_not_assigned_without_an_executable_handler",
        "test_deferred_task_has_wake_condition",
        "test_task_wakes_after_provider_available",
        "test_no_task_remains_pending_without_wake_or_terminal_reason",
        "test_no_counterexample_does_not_verify_universal_claim");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("ExecutableCountermodelFalsificationTasksParityTest", authorityFunction);
  }
}
