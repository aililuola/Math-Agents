package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.proofcontrol.PivotClaimUseChange;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotClaimUsageAction;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotDeltaStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotProposedClaimDraft;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSemanticPivotHardCrashRecoveryTest {
  @Test
  void hardCrashAfterAtomicStateMoveRestoresOnlyTheAppliedPivot(
      @TempDir Path directory) throws Exception {
    String runId = "semantic-pivot-hard-crash-after-move";
    String claimId = "hard-crash-applied-claim";
    String statement = "Every applied support pivot has a durable maximal-prime witness.";
    String statementHash = PivotProposedClaimDraft.statementHash(statement);
    var first = DesktopSemanticPivotTestHarness.open(directory, runId);
    var delta =
        first.validDelta(
            702,
            List.of(
                new PivotClaimUseChange(
                    claimId,
                    statementHash,
                    PivotClaimUsageAction.ADD_AS_PROPOSED_CLAIM,
                    "Persist this candidate only as part of the complete applied Pivot.",
                    new PivotProposedClaimDraft(
                        claimId,
                        statement,
                        statementHash,
                        List.of("the global support family is nonempty"),
                        List.of("artifact://semantic-pivot/hard-crash-applied"),
                        List.of(),
                        List.of("candidate:hard-crash-applied")))));
    first.checkpoint();
    var before = first.state();
    first.hardCrashPoint(SemanticPivotFailurePoint.DURING_CHECKPOINT_PERSIST);
    assertThatThrownBy(() -> first.apply(delta))
        .isInstanceOf(SimulatedSemanticPivotProcessTermination.class)
        .hasMessageContaining("simulated semantic pivot process termination");
    DesktopSolveCheckpoint crashCheckpoint = first.persistedCheckpoint();
    var durableRecord = crashCheckpoint.semanticPivots().records().get(delta.pivotId());
    assertThat(durableRecord.status()).isEqualTo(PivotDeltaStatus.APPLIED);
    assertThat(durableRecord.applyReceipt()).isNotNull();
    assertThat(crashCheckpoint.semanticPivots().records().values())
        .noneMatch(record -> record.status() == PivotDeltaStatus.APPLYING);
    first.close();

    try (DesktopSemanticPivotTestHarness restored =
        DesktopSemanticPivotTestHarness.restore(directory, runId, crashCheckpoint)) {
      assertThat(restored.pivotApplyReceiptCount()).isEqualTo(1L);
      assertThat(restored.proposedClaimCount(claimId)).isEqualTo(1L);
      assertThat(restored.state().obligations()).isEqualTo(before.obligations() + 1);
      assertThat(restored.rootHash()).isEqualTo(before.rootHash());
      assertThat(restored.state().negativeHash()).isEqualTo(before.negativeHash());

      int branchesBeforeDuplicate = restored.checkpointLedgerSnapshot().checkpoints().size();
      assertThat(restored.apply(delta).status()).isEqualTo(PivotDeltaStatus.APPLIED);
      assertThat(restored.pivotApplyReceiptCount()).isEqualTo(1L);
      assertThat(restored.proposedClaimCount(claimId)).isEqualTo(1L);
      assertThat(restored.checkpointLedgerSnapshot().checkpoints())
          .hasSize(branchesBeforeDuplicate);
    }
    System.out.println("POST_ATOMIC_MOVE_APPLIED_RESTORES=1");
    System.out.println("POST_ATOMIC_MOVE_DUPLICATE_APPLIES=0");
  }

  @Test
  void hardCrashNeverMakesAnApplyingPivotTheAuthoritativeRestorePoint(
      @TempDir Path directory) throws Exception {
    String runId = "semantic-pivot-hard-crash";
    String claimId = "hard-crash-proposed-claim";
    String statement = "Every global support admits a crash-safe maximal-prime witness.";
    String statementHash = PivotProposedClaimDraft.statementHash(statement);
    var first = DesktopSemanticPivotTestHarness.open(directory, runId);
    var delta =
        first.validDelta(
            701,
            List.of(
                new PivotClaimUseChange(
                    claimId,
                    statementHash,
                    PivotClaimUsageAction.ADD_AS_PROPOSED_CLAIM,
                    "Materialize the candidate only with the complete Pivot transaction.",
                    new PivotProposedClaimDraft(
                        claimId,
                        statement,
                        statementHash,
                        List.of("the global support family is nonempty"),
                        List.of("artifact://semantic-pivot/hard-crash"),
                        List.of(),
                        List.of("candidate:hard-crash")))));
    first.checkpoint();
    var before = first.state();
    int taskLeasesBefore = first.taskLeaseCount();
    int pendingTasksBefore = first.pendingTaskCount();

    first.hardCrashPoint(
        SemanticPivotFailurePoint.BEFORE_APPLIED_CHECKPOINT_PERSIST);
    assertThatThrownBy(() -> first.apply(delta))
        .isInstanceOf(SimulatedSemanticPivotProcessTermination.class)
        .hasMessageContaining("simulated semantic pivot process termination");
    DesktopSolveCheckpoint crashCheckpoint = first.persistedCheckpoint();
    long applyingCheckpointsObserved =
        crashCheckpoint.semanticPivots().records().values().stream()
            .filter(record -> record.status() == PivotDeltaStatus.APPLYING)
            .count();
    first.close();

    int restoreFailures = 0;
    int partialFrontiersAfterRestore = 0;
    int pivotApplies = 0;
    int checkpointBranches = 0;
    int duplicatePivotApplies = 0;
    int duplicateCheckpointBranches = 0;
    int ghostProposedClaims = 0;
    int duplicateProposedClaims = 0;
    int partialObligationWrites = 0;
    int taskLeaseLeaks = 0;
    int pendingTaskLeaks = 0;
    int rootHashChanges = 0;
    int negativeRegistryHashChanges = 0;
    DesktopSemanticPivotTestHarness restored = null;
    try {
      restored = DesktopSemanticPivotTestHarness.restore(directory, runId, crashCheckpoint);
    } catch (IllegalStateException exception) {
      restoreFailures++;
    }
    if (restored != null) {
      try {
        partialFrontiersAfterRestore =
            (int)
                restored.semanticPivots().ledger().records().stream()
                    .filter(record -> record.status() == PivotDeltaStatus.APPLYING)
                    .count();
        partialObligationWrites = restored.state().obligations() == before.obligations() ? 0 : 1;
        taskLeaseLeaks = restored.taskLeaseCount() == taskLeasesBefore ? 0 : 1;
        pendingTaskLeaks = restored.pendingTaskCount() == pendingTasksBefore ? 0 : 1;
        rootHashChanges = restored.rootHash().equals(before.rootHash()) ? 0 : 1;
        negativeRegistryHashChanges =
            restored.state().negativeHash().equals(before.negativeHash()) ? 0 : 1;

        int pivotReceiptsBefore = (int) restored.pivotApplyReceiptCount();
        int branchesBeforeRetry = restored.checkpointLedgerSnapshot().checkpoints().size();
        assertThat(restored.apply(delta).status()).isEqualTo(PivotDeltaStatus.APPLIED);
        pivotApplies = (int) restored.pivotApplyReceiptCount() - pivotReceiptsBefore;
        checkpointBranches =
            restored.checkpointLedgerSnapshot().checkpoints().size() - branchesBeforeRetry;
        long proposedClaimsAfterApply = restored.proposedClaimCount(claimId);
        ghostProposedClaims = proposedClaimsAfterApply == 1L ? 0 : 1;
        duplicateProposedClaims = (int) Math.max(0L, proposedClaimsAfterApply - 1L);

        int receiptsBeforeDuplicate = (int) restored.pivotApplyReceiptCount();
        int branchesBeforeDuplicate = restored.checkpointLedgerSnapshot().checkpoints().size();
        assertThat(restored.apply(delta).status()).isEqualTo(PivotDeltaStatus.APPLIED);
        duplicatePivotApplies =
            (int) restored.pivotApplyReceiptCount() - receiptsBeforeDuplicate;
        duplicateCheckpointBranches =
            restored.checkpointLedgerSnapshot().checkpoints().size() - branchesBeforeDuplicate;
        rootHashChanges += restored.rootHash().equals(before.rootHash()) ? 0 : 1;
        negativeRegistryHashChanges +=
            restored.state().negativeHash().equals(before.negativeHash()) ? 0 : 1;
      } finally {
        restored.close();
      }
    }

    System.out.println("SEMANTIC PIVOT HARD-CRASH RECOVERY DIAGNOSTIC");
    System.out.println("HARD_CRASHES_INJECTED=1");
    System.out.println("APPLYING_CHECKPOINTS_OBSERVED=" + applyingCheckpointsObserved);
    System.out.println("RESTORE_FAILURES=" + restoreFailures);
    System.out.println("PARTIAL_PIVOT_FRONTIERS_AFTER_RESTORE=" + partialFrontiersAfterRestore);
    System.out.println("PIVOT_APPLIES=" + pivotApplies);
    System.out.println("CHECKPOINT_BRANCHES=" + checkpointBranches);
    System.out.println("DUPLICATE_PIVOT_APPLIES=" + duplicatePivotApplies);
    System.out.println("DUPLICATE_CHECKPOINT_BRANCHES=" + duplicateCheckpointBranches);
    System.out.println("GHOST_PROPOSED_CLAIMS=" + ghostProposedClaims);
    System.out.println("DUPLICATE_PROPOSED_CLAIMS=" + duplicateProposedClaims);
    System.out.println("PARTIAL_OBLIGATION_WRITES=" + partialObligationWrites);
    System.out.println("TASK_LEASE_LEAKS=" + taskLeaseLeaks);
    System.out.println("PENDING_TASK_LEAKS=" + pendingTaskLeaks);
    System.out.println("ROOT_HASH_CHANGES=" + rootHashChanges);
    System.out.println("NEGATIVE_REGISTRY_HASH_CHANGES=" + negativeRegistryHashChanges);

    assertThat(applyingCheckpointsObserved).isZero();
    assertThat(restoreFailures).isZero();
    assertThat(partialFrontiersAfterRestore).isZero();
    assertThat(pivotApplies).isEqualTo(1);
    assertThat(checkpointBranches).isEqualTo(1);
    assertThat(duplicatePivotApplies).isZero();
    assertThat(duplicateCheckpointBranches).isZero();
    assertThat(ghostProposedClaims).isZero();
    assertThat(duplicateProposedClaims).isZero();
    assertThat(partialObligationWrites).isZero();
    assertThat(taskLeaseLeaks).isZero();
    assertThat(pendingTaskLeaks).isZero();
    assertThat(rootHashChanges).isZero();
    assertThat(negativeRegistryHashChanges).isZero();
    System.out.println("RESULT=PASS");
  }
}
