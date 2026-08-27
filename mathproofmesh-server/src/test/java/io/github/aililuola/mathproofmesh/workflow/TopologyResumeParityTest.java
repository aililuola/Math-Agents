package io.github.aililuola.mathproofmesh.workflow;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TopologyResumeParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_inspiration_resume_does_not_materialize_proposal_twice",
        "test_inspiration_shadow_records_decision_without_state_mutation",
        "test_active_surprise_can_create_one_novel_route_within_budget");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    TemporalParityScenarios.verify("TopologyResumeParityTest", authorityFunction);
  }
}
