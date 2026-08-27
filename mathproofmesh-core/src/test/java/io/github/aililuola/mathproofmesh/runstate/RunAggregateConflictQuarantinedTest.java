package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RunAggregateConflictQuarantinedTest {
  @Test
  void incomparableAggregateSnapshotsAreQuarantined() {
    RunUsageSnapshot checkpoint =
        RunUsageSnapshot.of(20, 300, 400, new BigDecimal("2.00"), 20.0, "", "");
    RunUsageSnapshot result =
        RunUsageSnapshot.of(23, 250, 500, new BigDecimal("2.30"), 23.0, "", "");

    RunUsageReconciliationResult reconciled =
        new RunUsageReconciler()
            .reconcile(
                List.of(
                    RunUsageEvidence.aggregate(
                        RunUsageEvidenceSource.SEMANTIC_CHECKPOINT, checkpoint, "checkpoint"),
                    RunUsageEvidence.aggregate(
                        RunUsageEvidenceSource.RESULT_PROJECTION, result, "result")),
                RunUsageSnapshot.empty());

    assertThat(reconciled.status()).isEqualTo(RunUsageStatus.CONFLICT);
    assertThat(reconciled.conflicts())
        .extracting(RunUsageConflict::code)
        .contains("INCOMPARABLE_AGGREGATE_USAGE");
  }
}
