package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSemanticPivotCheckpointBranchAtomicityTest {
  @Test
  void failureAfterGlobalCheckpointBranchRestoresEveryLedgerProjection(
      @TempDir Path directory) throws Exception {
    try (DesktopSemanticPivotTestHarness harness =
        DesktopSemanticPivotTestHarness.open(directory, "pivot-checkpoint-branch-atomicity")) {
      harness.checkpoint();
      var delta = harness.validDelta(501);
      var stateBefore = harness.state();
      var ledgerBefore = harness.checkpointLedgerSnapshot();
      int taskLeasesBefore = harness.taskLeaseCount();
      int pendingBefore = harness.pendingTaskCount();

      harness.failurePoint(SemanticPivotFailurePoint.AFTER_CHECKPOINT_BRANCH);
      assertThatThrownBy(() -> harness.apply(delta))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("AFTER_CHECKPOINT_BRANCH");

      var ledgerAfter = harness.checkpointLedgerSnapshot();
      assertThat(ledgerAfter.stableHash()).isEqualTo(ledgerBefore.stableHash());
      assertThat(ledgerAfter.checkpoints()).isEqualTo(ledgerBefore.checkpoints());
      assertThat(ledgerAfter.latestByBranch()).isEqualTo(ledgerBefore.latestByBranch());
      assertThat(ledgerAfter.audit()).isEqualTo(ledgerBefore.audit());
      assertThat(harness.state().activeStrategyId()).isEqualTo(stateBefore.activeStrategyId());
      assertThat(harness.taskLeaseCount()).isEqualTo(taskLeasesBefore);
      assertThat(harness.pendingTaskCount()).isEqualTo(pendingBefore);
      assertThat(harness.pivotApplyReceiptCount()).isZero();

      System.out.println("AFTER_CHECKPOINT_BRANCH_ROLLBACKS=1");
      System.out.println("CHECKPOINT_LEDGER_HASH_CHANGES=0");
    }
  }
}
