package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ContinueDeepeningGateParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_two_same_error_segments_without_core_progress_block_active_deepening",
        "test_changed_first_error_or_verified_bridge_resets_stagnation",
        "test_shadow_continue_gate_reports_shadow_block_only");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("ContinueDeepeningGateParityTest", authorityFunction);
  }
}
