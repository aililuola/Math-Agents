package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.concurrency.ResearchConcurrencyFailurePoint;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchResultLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchTaskLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import org.junit.jupiter.api.Test;

final class DesktopResearchConcurrencyAtomicityTest {
  @Test
  void failureAfterEpochPlanCannotLeakTasksResultsOrCredentialLeases() {
    var snapshot = DesktopResearchConcurrencyTestSupport.snapshot("atomic-epoch");
    var items =
        DesktopResearchConcurrencyTestSupport.items(
            snapshot, ResearchWorkKind.ROUTE_REVIEW, 4);
    ResearchEpochLedger epochs = new ResearchEpochLedger();
    ResearchTaskLedger tasks = new ResearchTaskLedger();
    ResearchResultLedger results = new ResearchResultLedger();
    try (var pool = DesktopResearchConcurrencyTestSupport.idlePool()) {
      DesktopResearchEpochExecutor executor =
          new DesktopResearchEpochExecutor(
              "atomic-run",
              pool,
              5,
              (frozen, item, lease) -> {
                throw new AssertionError("worker must not start before the injected boundary");
              },
              epochs,
              tasks,
              results,
              point -> {
                if (point == ResearchConcurrencyFailurePoint.AFTER_EPOCH_PLANNED) {
                  throw new IllegalStateException("injected");
                }
              });
      assertThatThrownBy(() -> executor.execute(snapshot, items))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("injected");
      assertThat(epochs.snapshot().epochs()).hasSize(1);
      assertThat(tasks.snapshot().tasks()).isEmpty();
      assertThat(results.snapshot().artifacts()).isEmpty();
      assertThat(pool.leaseSnapshot().leases()).isEmpty();
    }
  }
}
