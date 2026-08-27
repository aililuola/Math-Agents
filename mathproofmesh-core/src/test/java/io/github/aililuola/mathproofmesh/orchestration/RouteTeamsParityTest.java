package io.github.aililuola.mathproofmesh.orchestration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RouteTeamsParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_high_risk_computation_uses_skeptic_tool_and_independent_referee",
        "test_low_risk_route_does_not_spend_a_skeptic_call",
        "test_salvaged_partial_delta_requires_an_independent_skeptic");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    OrchestrationParityScenarios.verify("RouteTeamsParityTest", authorityFunction);
  }
}
