package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CommonModeExecutionClosureParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_shared_typed_dependency_clusters_different_route_mechanisms",
        "test_live_route_cutset_is_not_diluted_by_frozen_routes",
        "test_similar_theme_with_distinct_dependency_closures_is_not_merged",
        "test_verifier_premise_summary_enters_common_mode_detection",
        "test_transitive_dependency_closure_finds_a_shared_load_bearing_predecessor",
        "test_challenger_executes_once_survives_resume_and_never_closes_goal",
        "test_pending_challenger_blocks_hard_stop_until_it_reaches_a_terminal_state",
        "test_unreviewed_resolution_cannot_bypass_the_challenge_contract",
        "test_reworded_shared_dependency_is_not_admitted_as_an_independent_route",
        "test_orchestrator_dispatches_challenger_and_independent_review_without_api",
        "test_shadow_mode_records_detection_without_materializing_executable_work");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("CommonModeExecutionClosureParityTest", authorityFunction);
  }
}
