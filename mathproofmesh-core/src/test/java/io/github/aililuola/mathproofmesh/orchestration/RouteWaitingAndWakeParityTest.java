package io.github.aililuola.mathproofmesh.orchestration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RouteWaitingAndWakeParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_deferred_task_with_wake_sets_route_waiting",
        "test_waiting_route_wakes_when_condition_satisfied",
        "test_route_cannot_freeze_with_automatic_wake_condition",
        "test_frozen_route_requires_explicit_intervention",
        "test_route_waiting_resume_roundtrip");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    OrchestrationParityScenarios.verify("RouteWaitingAndWakeParityTest", authorityFunction);
  }
}
