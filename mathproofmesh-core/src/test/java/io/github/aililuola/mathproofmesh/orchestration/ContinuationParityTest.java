package io.github.aililuola.mathproofmesh.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ContinuationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_continuation_prompt_requires_external_dependency_prefix",
        "test_verifier_does_not_require_bibliography_for_standard_theorems",
        "test_checkpoint_commit_round_trip_and_hash",
        "test_store_rejects_non_linear_checkpoint_advance",
        "test_delta_must_extend_latest_checkpoint",
        "test_merge_verified_delta_builds_resumable_attempt",
        "test_normalized_delta_claim_keeps_its_narrow_verification_scope",
        "test_runner_switches_to_backup_key_after_retry_exhaustion",
        "test_continuation_end_to_end_and_process_resume",
        "test_two_verified_segments_advance_in_one_exploration_action",
        "test_oversized_delta_is_rejected_without_validation_crash",
        "test_resume_after_budget_interruption_uses_committed_checkpoint",
        "test_orchestrator_failover_commits_backup_agent_checkpoint",
        "test_synthesis_switches_to_backup_after_connect_failure",
        "test_repairable_final_gap_is_revised_and_fully_reaudited",
        "test_resume_can_restart_before_first_stage_checkpoint",
        "test_resume_prefers_persisted_lemma_memory_over_stale_stage_snapshot",
        "test_delta_rejects_self_dependent_claim",
        "test_delta_allows_claim_supported_by_new_steps",
        "test_failover_never_selects_excluded_author_agent",
        "test_auth_failure_skips_same_key_retries_but_fails_over_to_backup");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    OrchestrationParityScenarios.verify("ContinuationParityTest", authorityFunction);
  }

  @Test
  void strategyRevisionPreservesCommittedParentAndRouteIdentity() {
    ContinuationFunctions.CheckpointLedger ledger =
        new ContinuationFunctions.CheckpointLedger();
    ContinuationFunctions.Checkpoint parent =
        new ContinuationFunctions.Checkpoint(
            "checkpoint-0", "", "problem-hash", "route-1", "strategy-a", 2,
            "route-1", true);
    ledger.seed(parent);
    ledger.rollbackAndBranch("checkpoint-0", "route-1-revision-1");

    ContinuationFunctions.Checkpoint revision =
        ledger.branchForStrategy("checkpoint-0", "route-1-revision-1", "strategy-b");

    assertThat(revision.parentCheckpointId()).isEqualTo(parent.checkpointId());
    assertThat(revision.pathId()).isEqualTo(parent.pathId());
    assertThat(revision.segmentIndex()).isEqualTo(parent.segmentIndex());
    assertThat(revision.strategyId()).isEqualTo("strategy-b");
    assertThat(ledger.latest("route-1")).isEqualTo(parent);
    assertThat(ledger.latest("route-1-revision-1")).isEqualTo(revision);
    assertThat(ledger.audit())
        .anySatisfy(
            event -> {
              assertThat(event.subjectId()).isEqualTo(parent.checkpointId());
              assertThat(event.action()).isEqualTo("strategy_branched");
            });
  }
}
