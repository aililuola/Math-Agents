package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtStage;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtStageExecutionStatus;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimCourtHardCrashRecoveryTest {
  private static final EnumSet<ClaimCourtFailurePoint> HARD_CRASH_POINTS =
      EnumSet.of(
          ClaimCourtFailurePoint.AFTER_STATEMENT_RESULT_DURABLE,
          ClaimCourtFailurePoint.AFTER_PROOF_AUDIT_RESULT,
          ClaimCourtFailurePoint.AFTER_REPAIRED_REVISION_WRITE,
          ClaimCourtFailurePoint.AFTER_BLIND_RESULT_DURABLE,
          ClaimCourtFailurePoint.AFTER_FINAL_CHECKPOINT_PERSIST);

  @TempDir Path temporaryDirectory;

  @Test
  void durableStageFrontiersRollForwardExactlyOnceAfterProcessTermination()
      throws Exception {
    long restoreFailures = 0L;
    long partialCourtFrontiers = 0L;
    long duplicateProviderCalls = 0L;
    long duplicateRepairs = 0L;
    long duplicateFacts = 0L;
    long ghostProofRevisions = 0L;
    long statusOutcomeMismatches = 0L;

    for (ClaimCourtFailurePoint point : HARD_CRASH_POINTS) {
      String suffix = point.name().toLowerCase(java.util.Locale.ROOT);
      Path runDirectory = temporaryDirectory.resolve(suffix);
      String runId = "claim-court-hard-crash-" + suffix;
      DesktopSolveCheckpoint crashCheckpoint;
      Map<String, Long> calls = new LinkedHashMap<>();
      String claimId = "hard-crash-repairable-" + suffix;

      try (DesktopClaimSalvageTestHarness first =
          DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
        first.freezeAndCreateRoute();
        first.installSingleClaimRound(
            0,
            claimId,
            "REPAIRABLE_HARD_CRASH: a zero-kernel linear map is injective.");
        first.setHardCrashPoint(point);

        assertThatThrownBy(first::integrateInstalledRound)
            .isInstanceOf(SimulatedClaimCourtProcessTermination.class);
        crashCheckpoint = first.readPersistedCheckpoint();
        accumulateStageCalls(first, calls);
      }

      try (DesktopClaimSalvageTestHarness restored =
          DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
        try {
          restored.restore(crashCheckpoint);
          restored.integrateInstalledRound();
        } catch (RuntimeException exception) {
          restoreFailures++;
          throw exception;
        }
        accumulateStageCalls(restored, calls);

        partialCourtFrontiers +=
            restored.claimCourt().records().stream()
                .filter(record -> !record.status().terminal())
                .count();
        partialCourtFrontiers +=
            restored.claimCourtExecutions().records().stream()
                .filter(
                    execution ->
                        execution.status() != ClaimCourtStageExecutionStatus.COMPLETED)
                .count();
        duplicateRepairs +=
            Math.max(0, restored.claimProofRevisions().recordsForClaim(claimId).size() - 2);
        duplicateFacts +=
            Math.max(
                0L,
                restored.typedMemory().facts().stream()
                        .filter(fact -> fact.messageId().equals(claimId))
                        .count()
                    - 1L);
        ghostProofRevisions +=
            restored.claimProofRevisions().recordsForClaim(claimId).stream()
                .filter(
                    revision ->
                        restored.claimCourt().records().stream()
                            .noneMatch(
                                record ->
                                    record.currentProofRevisionId().equals(revision.revisionId())
                                        || record.frozenClaim()
                                            .initialProofRevisionId()
                                            .equals(revision.revisionId())))
                .count();

        var record = restored.claimCourt().records().getFirst();
        if (record.outcome() != ClaimCourtOutcome.VERIFIED
            || restored.lemmaMemory().verified().stream()
                .noneMatch(claim -> claim.claimId().equals(claimId))) {
          statusOutcomeMismatches++;
        }
        duplicateProviderCalls +=
            calls.values().stream().mapToInt(count -> Math.max(0, Math.toIntExact(count - 1L))).sum();

        assertThat(calls)
            .containsEntry("ClaimStatementFalsificationBatch", 1L)
            .containsEntry("ClaimProofAuditBatch", 1L)
            .containsEntry("ClaimMinimalRepairBatch", 1L)
            .containsEntry("ClaimBlindAdjudicationBatch", 1L);
        assertThat(
                restored.claimCourtExecutions().records().stream()
                    .collect(
                        Collectors.groupingBy(
                            execution -> execution.stage(), Collectors.counting())))
            .containsEntry(ClaimCourtStage.STATEMENT_FALSIFICATION, 1L)
            .containsEntry(ClaimCourtStage.PROOF_AUDIT, 1L)
            .containsEntry(ClaimCourtStage.MINIMAL_REPAIR, 1L)
            .containsEntry(ClaimCourtStage.BLIND_ADJUDICATION, 1L);
      }
    }

    assertThat(restoreFailures).isZero();
    assertThat(partialCourtFrontiers).isZero();
    assertThat(duplicateProviderCalls).isZero();
    assertThat(duplicateRepairs).isZero();
    assertThat(duplicateFacts).isZero();
    assertThat(ghostProofRevisions).isZero();
    assertThat(statusOutcomeMismatches).isZero();

    System.out.println("CLAIM COURT HARD-CRASH DIAGNOSTIC");
    System.out.println("HARD_CRASH_POINTS=" + HARD_CRASH_POINTS.size());
    System.out.println("RESTORE_FAILURES=" + restoreFailures);
    System.out.println("PARTIAL_COURT_FRONTIERS=" + partialCourtFrontiers);
    System.out.println("DUPLICATE_PROVIDER_CALLS=" + duplicateProviderCalls);
    System.out.println("DUPLICATE_REPAIRS=" + duplicateRepairs);
    System.out.println("DUPLICATE_FACTS=" + duplicateFacts);
    System.out.println("GHOST_PROOF_REVISIONS=" + ghostProofRevisions);
    System.out.println("CLAIM_STATUS_OUTCOME_MISMATCHES=" + statusOutcomeMismatches);
    System.out.println("RESULT=PASS");
  }

  private static void accumulateStageCalls(
      DesktopClaimSalvageTestHarness harness, Map<String, Long> calls) {
    for (String schema :
        java.util.List.of(
            "ClaimStatementFalsificationBatch",
            "ClaimProofAuditBatch",
            "ClaimMinimalRepairBatch",
            "ClaimBlindAdjudicationBatch")) {
      calls.merge(schema, harness.callsForSchema(schema), Long::sum);
    }
  }
}
