package io.github.aililuola.mathproofmesh.workflow;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ProofControlResumeParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_proof_control_sidecar_round_trips_without_changing_v07_state",
        "test_active_resume_migrates_a_v07_checkpoint_exactly_once");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    TemporalParityScenarios.verify("ProofControlResumeParityTest", authorityFunction);
  }
}
