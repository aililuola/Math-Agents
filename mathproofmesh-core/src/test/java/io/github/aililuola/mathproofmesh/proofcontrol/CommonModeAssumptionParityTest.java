package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CommonModeAssumptionParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_wording_variants_share_one_unverified_common_mode_assumption",
        "test_chinese_particle_variants_share_the_same_assumption_family",
        "test_chinese_and_english_assumptions_share_a_conservative_family",
        "test_cross_language_matching_fails_closed_on_semantic_conflicts",
        "test_transport_wrappers_do_not_make_unrelated_chinese_gaps_equivalent",
        "test_route_votes_never_upgrade_evidence_status",
        "test_independently_verified_fact_clears_common_mode_risk",
        "test_main_goal_hypotheses_are_given_not_unverified_route_votes");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("CommonModeAssumptionParityTest", authorityFunction);
  }
}
