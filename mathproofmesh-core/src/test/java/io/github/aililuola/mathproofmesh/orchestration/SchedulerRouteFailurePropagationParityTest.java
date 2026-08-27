package io.github.aililuola.mathproofmesh.orchestration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SchedulerRouteFailurePropagationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_checkpoint_rejection_is_persisted_at_route_scope",
        "test_authoritative_meta_stop_cools_route_and_requires_revision",
        "test_low_confidence_meta_stop_does_not_mutate_route_control");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    OrchestrationParityScenarios.verify("SchedulerRouteFailurePropagationParityTest", authorityFunction);
  }
}
