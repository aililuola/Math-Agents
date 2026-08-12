package io.github.aililuola.mathproofmesh.orchestration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SchedulerGraphSignalsParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_active_graph_and_inspiration_actions_are_schedulable",
        "test_shadow_modes_record_but_cannot_change_scheduling");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    OrchestrationParityScenarios.verify("SchedulerGraphSignalsParityTest", authorityFunction);
  }
}
