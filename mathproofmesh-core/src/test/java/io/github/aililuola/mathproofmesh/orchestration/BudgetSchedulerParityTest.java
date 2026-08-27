package io.github.aililuola.mathproofmesh.orchestration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class BudgetSchedulerParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_difficulty_scaling_and_extra_compute_are_opt_in",
        "test_difficulty_budget_scaling_is_idempotent_and_audited",
        "test_all_failed_paths_force_configurable_widen_and_use_max_capacity",
        "test_structural_and_strategy_failures_reduce_false_progress",
        "test_execution_failure_gets_one_repair_then_configurable_cooldown",
        "test_incomplete_partial_attempt_does_not_invalidate_checked_progress",
        "test_rejected_delta_and_meta_stop_cannot_outrank_preferred_partial_route",
        "test_unverified_repair_does_not_erase_prior_delta_rejection",
        "test_successful_candidate_does_not_trigger_failure_widen_guarantee",
        "test_widen_is_blocked_at_configured_max_paths",
        "test_action_cost_and_finish_reserve_derive_from_configuration",
        "test_budget_diagnostics_record_rank_selection_and_blocking",
        "test_deepseek_profiles_reserve_one_widen_and_finalization_cycle",
        "test_smoke_and_formal_output_limits");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    OrchestrationParityScenarios.verify("BudgetSchedulerParityTest", authorityFunction);
  }
}
