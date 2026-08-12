package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class InspirationProposerAssignmentParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_assignment_uses_dynamic_pool_and_distinct_agents",
        "test_assignment_does_not_repeat_when_two_agents_are_available",
        "test_single_agent_fallback_is_bounded_to_warm_and_cold",
        "test_cooled_down_agent_causes_task_deferral",
        "test_meta_replan_does_not_consume_generalist_proposers");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("InspirationProposerAssignmentParityTest", authorityFunction);
  }
}
