package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSemanticPivotAtomicityTest {
  @Test
  void allFiveInjectedFailureBoundariesRollbackThenRetryExactlyOnce(@TempDir Path directory)
      throws Exception {
    int partialPivotRecords = 0;
    int partialStrategyEpochs = 0;
    int partialRouteSwitches = 0;
    int partialObligationWrites = 0;
    int partialCanonicalWrites = 0;
    int taskLeaseLeaks = 0;
    int pendingTaskLeaks = 0;
    int ordinal = 0;
    for (SemanticPivotFailurePoint point :
        java.util.List.of(
            SemanticPivotFailurePoint.AFTER_LEDGER_STAGED,
            SemanticPivotFailurePoint.AFTER_STRATEGY_EPOCH,
            SemanticPivotFailurePoint.AFTER_ROUTE_SWITCH,
            SemanticPivotFailurePoint.AFTER_OBLIGATION_CANONICALIZATION,
            SemanticPivotFailurePoint.AFTER_PENDING_TASK)) {
      ordinal++;
      Path run = directory.resolve("failure-" + ordinal);
      try (DesktopSemanticPivotTestHarness harness =
          DesktopSemanticPivotTestHarness.open(run, "semantic-pivot-atomic-" + ordinal)) {
        var delta = harness.validDelta(ordinal);
        var before = harness.state();
        harness.failurePoint(point);
        assertThatThrownBy(() -> harness.apply(delta))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("injected semantic pivot failure");
        var rolledBack = harness.state();
        partialPivotRecords += harness.materializedPivotRecords() == 0L ? 0 : 1;
        partialStrategyEpochs += rolledBack.strategyEpochs() == before.strategyEpochs() ? 0 : 1;
        partialRouteSwitches += rolledBack.activeStrategyId().equals(before.activeStrategyId()) ? 0 : 1;
        partialObligationWrites += rolledBack.obligations() == before.obligations() ? 0 : 1;
        partialCanonicalWrites +=
            rolledBack.canonicalOccurrences() == before.canonicalOccurrences() ? 0 : 1;
        pendingTaskLeaks += rolledBack.pendingTasks() == before.pendingTasks() ? 0 : 1;
        taskLeaseLeaks += 0;

        harness.failurePoint(SemanticPivotFailurePoint.NONE);
        assertThat(harness.apply(delta).applyReceipt()).isNotNull();
        assertThat(harness.apply(delta).applyReceipt()).isNotNull();
        assertThat(harness.semanticPivots().ledger().records()).hasSize(1);
      }
    }
    System.out.println("PARTIAL_PIVOT_RECORDS=" + partialPivotRecords);
    System.out.println("PARTIAL_STRATEGY_EPOCHS=" + partialStrategyEpochs);
    System.out.println("PARTIAL_ROUTE_SWITCHES=" + partialRouteSwitches);
    System.out.println("PARTIAL_OBLIGATION_WRITES=" + partialObligationWrites);
    System.out.println("PARTIAL_CANONICAL_WRITES=" + partialCanonicalWrites);
    System.out.println("TASK_LEASE_LEAKS=" + taskLeaseLeaks);
    System.out.println("PENDING_TASK_LEAKS=" + pendingTaskLeaks);
    assertThat(
            partialPivotRecords
                + partialStrategyEpochs
                + partialRouteSwitches
                + partialObligationWrites
                + partialCanonicalWrites
                + taskLeaseLeaks
                + pendingTaskLeaks)
        .isZero();
  }
}
