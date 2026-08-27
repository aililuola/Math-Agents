package io.github.aililuola.mathproofmesh.orchestration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DeepExplorationPolicyParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_three_tiers_use_separate_recovery_budgets_and_transport_timeouts",
        "test_legacy_elapsed_time_fields_load_but_are_discarded",
        "test_checkpoint_segment_index_no_longer_selects_route_tier",
        "test_new_route_starts_at_96k_instead_of_the_bounded_repair_tier",
        "test_distinct_high_tier_signatures_run_in_parallel",
        "test_same_signature_has_one_atomic_running_lease",
        "test_high_tier_no_progress_locks_only_that_mathematical_signature",
        "test_post_failure_lineage_gets_one_bounded_repair_across_reworded_targets",
        "test_same_domain_subdirections_and_local_pivot_are_allowed",
        "test_128k_requires_96k_verified_progress_meta_approval_and_reserve",
        "test_new_mechanism_does_not_inherit_old_128k_progression",
        "test_resume_persists_strikes_pivots_and_route_usage");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    OrchestrationParityScenarios.verify("DeepExplorationPolicyParityTest", authorityFunction);
  }
}
