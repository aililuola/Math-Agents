package io.github.aililuola.mathproofmesh.orchestration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ActiveRouteTeamParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_active_route_uses_real_prover_skeptic_referee_pipeline",
        "test_active_tool_specialist_is_called_as_an_independent_role");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    OrchestrationParityScenarios.verify("ActiveRouteTeamParityTest", authorityFunction);
  }
}
