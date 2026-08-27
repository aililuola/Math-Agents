package io.github.aililuola.mathproofmesh.orchestration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CrossRoutePhaseParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_global_share_gate_uses_the_claims_source_delta_review");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    OrchestrationParityScenarios.verify("CrossRoutePhaseParityTest", authorityFunction);
  }
}
