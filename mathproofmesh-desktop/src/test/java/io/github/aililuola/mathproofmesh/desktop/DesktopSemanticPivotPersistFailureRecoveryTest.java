package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.proofcontrol.PivotDeltaStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSemanticPivotPersistFailureRecoveryTest {
  @Test
  void persistenceFailuresCompensateDurableStateAndOneRetryCommitsExactlyOnce(
      @TempDir Path directory) throws Exception {
    int checkpointBranchLeaks = 0;
    int latestPointerLeaks = 0;
    int checkpointAuditLeaks = 0;
    int taskLeaseLeaks = 0;
    int pendingTaskLeaks = 0;
    int partialPivotReceipts = 0;
    int postRetryPivotApplies = 0;
    int postRetryCheckpointBranches = 0;
    int postRetryDuplicateBranches = 0;

    List<SemanticPivotFailurePoint> points =
        List.of(
            SemanticPivotFailurePoint.DURING_CHECKPOINT_PERSIST,
            SemanticPivotFailurePoint.AFTER_CHECKPOINT_PERSIST_BEFORE_APPLY_RECEIPT);
    for (int index = 0; index < points.size(); index++) {
      SemanticPivotFailurePoint point = points.get(index);
      try (DesktopSemanticPivotTestHarness harness =
          DesktopSemanticPivotTestHarness.open(
              directory.resolve("persist-" + index), "pivot-persist-" + index)) {
        harness.checkpoint();
        var delta = harness.validDelta(601 + index);
        var stateBefore = harness.state();
        var ledgerBefore = harness.checkpointLedgerSnapshot();
        int leasesBefore = harness.taskLeaseCount();
        int pendingBefore = harness.pendingTaskCount();

        harness.failurePoint(point);
        assertThatThrownBy(() -> harness.apply(delta))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(point.name());

        var ledgerAfter = harness.checkpointLedgerSnapshot();
        DesktopSolveCheckpoint durableAfter = harness.persistedCheckpoint();
        checkpointBranchLeaks +=
            ledgerAfter.checkpoints().equals(ledgerBefore.checkpoints()) ? 0 : 1;
        latestPointerLeaks +=
            ledgerAfter.latestByBranch().equals(ledgerBefore.latestByBranch()) ? 0 : 1;
        checkpointAuditLeaks += ledgerAfter.audit().equals(ledgerBefore.audit()) ? 0 : 1;
        taskLeaseLeaks += harness.taskLeaseCount() == leasesBefore ? 0 : 1;
        pendingTaskLeaks += harness.pendingTaskCount() == pendingBefore ? 0 : 1;
        partialPivotReceipts += harness.pivotApplyReceiptCount() == 0L ? 0 : 1;
        partialPivotReceipts +=
            durableAfter.semanticPivots().records().values().stream()
                    .noneMatch(record -> record.applyReceipt() != null)
                ? 0
                : 1;

        assertThat(harness.state().activeStrategyId()).isEqualTo(stateBefore.activeStrategyId());
        assertThat(durableAfter.routes().getFirst().strategy().strategyId())
            .isEqualTo(stateBefore.activeStrategyId());

        if (point
            == SemanticPivotFailurePoint.AFTER_CHECKPOINT_PERSIST_BEFORE_APPLY_RECEIPT) {
          harness.failurePoint(SemanticPivotFailurePoint.NONE);
          int checkpointCountBeforeRetry = ledgerBefore.checkpoints().size();
          assertThat(harness.apply(delta).status()).isEqualTo(PivotDeltaStatus.APPLIED);
          var afterRetry = harness.checkpointLedgerSnapshot();
          postRetryPivotApplies += (int) harness.pivotApplyReceiptCount();
          postRetryCheckpointBranches +=
              afterRetry.checkpoints().size() - checkpointCountBeforeRetry;
          int branchCountBeforeDuplicate = afterRetry.checkpoints().size();
          assertThat(harness.apply(delta).status()).isEqualTo(PivotDeltaStatus.APPLIED);
          postRetryDuplicateBranches +=
              harness.checkpointLedgerSnapshot().checkpoints().size()
                  - branchCountBeforeDuplicate;
        }
      }
    }

    System.out.println("CHECKPOINT_BRANCH_LEAKS=" + checkpointBranchLeaks);
    System.out.println("LATEST_BRANCH_POINTER_LEAKS=" + latestPointerLeaks);
    System.out.println("CHECKPOINT_AUDIT_LEAKS=" + checkpointAuditLeaks);
    System.out.println("TASK_LEASE_LEAKS=" + taskLeaseLeaks);
    System.out.println("PENDING_TASK_LEAKS=" + pendingTaskLeaks);
    System.out.println("PARTIAL_PIVOT_RECEIPTS=" + partialPivotReceipts);
    System.out.println("POST_RETRY_PIVOT_APPLIES=" + postRetryPivotApplies);
    System.out.println("POST_RETRY_CHECKPOINT_BRANCHES=" + postRetryCheckpointBranches);
    System.out.println("POST_RETRY_DUPLICATE_BRANCHES=" + postRetryDuplicateBranches);

    assertThat(checkpointBranchLeaks).isZero();
    assertThat(latestPointerLeaks).isZero();
    assertThat(checkpointAuditLeaks).isZero();
    assertThat(taskLeaseLeaks).isZero();
    assertThat(pendingTaskLeaks).isZero();
    assertThat(partialPivotReceipts).isZero();
    assertThat(postRetryPivotApplies).isEqualTo(1);
    assertThat(postRetryCheckpointBranches).isEqualTo(1);
    assertThat(postRetryDuplicateBranches).isZero();
  }
}
