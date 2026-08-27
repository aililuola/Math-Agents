package io.github.aililuola.mathproofmesh.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContinuationCheckpointLedgerSnapshotTest {
  @Test
  void snapshotRestoresBranchPointersAuditAndVersionThenRetryIsIdempotent() {
    ContinuationFunctions.CheckpointLedger ledger =
        new ContinuationFunctions.CheckpointLedger();
    ContinuationFunctions.Checkpoint source =
        new ContinuationFunctions.Checkpoint(
            "checkpoint-source",
            "",
            "problem-hash",
            "path-1",
            "strategy-source",
            0,
            "branch-source",
            true);
    ledger.seed(source);
    ContinuationFunctions.CheckpointLedgerSnapshot before = ledger.snapshot();

    ledger.branchForStrategy(
        source.checkpointId(), "branch-semantic-pivot", "strategy-pivot");
    assertThat(ledger.snapshot().stableHash()).isNotEqualTo(before.stableHash());

    ledger.restore(before);
    assertThat(ledger.snapshot()).isEqualTo(before);
    assertThat(ledger.snapshot().stableHash()).isEqualTo(before.stableHash());

    ContinuationFunctions.Checkpoint first =
        ledger.branchForStrategy(
            source.checkpointId(), "branch-semantic-pivot", "strategy-pivot");
    ContinuationFunctions.Checkpoint duplicate =
        ledger.branchForStrategy(
            source.checkpointId(), "branch-semantic-pivot", "strategy-pivot");
    ContinuationFunctions.CheckpointLedgerSnapshot retried = ledger.snapshot();

    assertThat(duplicate).isEqualTo(first);
    assertThat(retried.checkpoints()).hasSize(before.checkpoints().size() + 1);
    assertThat(retried.latestByBranch().get("branch-semantic-pivot"))
        .isEqualTo(first.checkpointId());
    assertThat(retried.audit()).hasSize(before.audit().size() + 1);
    assertThat(retried.version()).isEqualTo(before.version() + 1L);
  }
}
