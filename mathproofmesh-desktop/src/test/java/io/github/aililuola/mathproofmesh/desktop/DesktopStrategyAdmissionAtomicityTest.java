package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopStrategyAdmissionAtomicityTest {
  @TempDir Path temporaryDirectory;

  @Test
  void everyInjectedFailureLeavesNoPartialActiveProjectionAndRetryCommitsOnce()
      throws Exception {
    EnumSet<StrategyPortfolioFailurePoint> points =
        EnumSet.of(
            StrategyPortfolioFailurePoint.AFTER_CANDIDATE_LEDGER,
            StrategyPortfolioFailurePoint.AFTER_PREFLIGHT,
            StrategyPortfolioFailurePoint.AFTER_PORTFOLIO_SELECTION,
            StrategyPortfolioFailurePoint.AFTER_ARCHIVE,
            StrategyPortfolioFailurePoint.AFTER_ROUTE_CREATION,
            StrategyPortfolioFailurePoint.DURING_CHECKPOINT_PERSIST);
    int partialArchiveWrites = 0;
    int partialBlueprintWrites = 0;
    int partialGoalLinkWrites = 0;
    int partialAdmittedStrategies = 0;
    int partialRouteCreations = 0;
    int partialProofGraphWrites = 0;
    int pendingTaskLeaks = 0;

    for (StrategyPortfolioFailurePoint point : points) {
      Path runDirectory = temporaryDirectory.resolve(point.name().toLowerCase(java.util.Locale.ROOT));
      try (DesktopStrategyPortfolioTestHarness harness =
          DesktopStrategyPortfolioTestHarness.open(
              runDirectory, "portfolio-atomic-" + point.name().toLowerCase(java.util.Locale.ROOT))) {
        harness.freeze();
        harness.setStrategies(DesktopStrategyPortfolioTestHarness.fourIndependent("atomic"));
        DesktopStrategyPortfolioTestHarness.ProductionState before = harness.state();
        harness.setFailurePoint(point);

        assertThatThrownBy(harness::generateAndAdmit)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("injected strategy portfolio failure");
        DesktopStrategyPortfolioTestHarness.ProductionState after = harness.state();
        partialArchiveWrites += delta(before.archiveCount(), after.archiveCount());
        partialBlueprintWrites += delta(before.blueprintCount(), after.blueprintCount());
        partialGoalLinkWrites += delta(before.goalLinkCount(), after.goalLinkCount());
        partialAdmittedStrategies +=
            delta(before.admittedStrategyIds().size(), after.admittedStrategyIds().size());
        partialRouteCreations += delta(before.routeIds().size(), after.routeIds().size());
        partialProofGraphWrites += delta(before.obligationCount(), after.obligationCount());
        pendingTaskLeaks += delta(before.pendingTaskCount(), after.pendingTaskCount());

        assertThat(after.archiveCount()).isEqualTo(before.archiveCount());
        assertThat(after.blueprintCount()).isEqualTo(before.blueprintCount());
        assertThat(after.goalLinkCount()).isEqualTo(before.goalLinkCount());
        assertThat(after.admittedStrategyIds()).isEqualTo(before.admittedStrategyIds());
        assertThat(after.routeIds()).isEqualTo(before.routeIds());
        assertThat(after.obligationCount()).isEqualTo(before.obligationCount());
        assertThat(after.pendingTaskCount()).isEqualTo(before.pendingTaskCount());

        harness.generateAndAdmit();
        assertThat(harness.portfolios().snapshot().receipts()).hasSize(1);
        assertThat(harness.routeStrategyIds()).doesNotHaveDuplicates();
      }
    }

    assertThat(partialArchiveWrites).isZero();
    assertThat(partialBlueprintWrites).isZero();
    assertThat(partialGoalLinkWrites).isZero();
    assertThat(partialAdmittedStrategies).isZero();
    assertThat(partialRouteCreations).isZero();
    assertThat(partialProofGraphWrites).isZero();
    assertThat(pendingTaskLeaks).isZero();
    System.out.println("PARTIAL_ARCHIVE_WRITES=" + partialArchiveWrites);
    System.out.println("PARTIAL_BLUEPRINT_WRITES=" + partialBlueprintWrites);
    System.out.println("PARTIAL_GOAL_LINK_WRITES=" + partialGoalLinkWrites);
    System.out.println("PARTIAL_ADMITTED_STRATEGIES=" + partialAdmittedStrategies);
    System.out.println("PARTIAL_ROUTE_CREATIONS=" + partialRouteCreations);
    System.out.println("PARTIAL_PROOF_GRAPH_WRITES=" + partialProofGraphWrites);
    System.out.println("TASK_LEASE_LEAKS=" + pendingTaskLeaks);
  }

  private static int delta(int before, int after) {
    return Math.max(0, after - before);
  }
}
