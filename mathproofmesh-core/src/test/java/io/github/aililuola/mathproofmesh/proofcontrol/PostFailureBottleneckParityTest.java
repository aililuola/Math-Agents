package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PostFailureBottleneckParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_only_explicit_no_artifact_failures_are_classified",
        "test_extractor_uses_public_checkpoint_once_and_filters_invented_ids",
        "test_segmented_route_failure_invokes_bottleneck_recovery",
        "test_diagnostic_becomes_route_local_obligation_and_manual_inspiration_trigger");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("PostFailureBottleneckParityTest", authorityFunction);
  }
}
