package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class InductionMeasureActivationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_induction_candidate_binds_triggering_obligation",
        "test_accepted_induction_enters_route_prompt",
        "test_induction_activation_requires_independent_review_evidence",
        "test_non_well_founded_measure_is_rejected",
        "test_induction_not_bound_to_main_goal_by_default",
        "test_induction_activation_resume_is_exactly_once");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("InductionMeasureActivationParityTest", authorityFunction);
  }
}
