package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MetaPivotStateMachineParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_pending_meta_pivot_blocks_hard_stagnation_stop",
        "test_meta_pivot_executes_before_evaluation",
        "test_meta_pivot_resume_exactly_once",
        "test_failed_meta_pivot_has_explicit_reason",
        "test_stop_allowed_after_empty_pivot",
        "test_pivot_grace_survives_checkpoint_resume");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("MetaPivotStateMachineParityTest", authorityFunction);
  }
}
