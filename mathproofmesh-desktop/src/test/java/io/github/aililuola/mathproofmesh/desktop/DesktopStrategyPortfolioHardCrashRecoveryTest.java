package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopStrategyPortfolioHardCrashRecoveryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void diskContainsPreApplyStateUntilOneCompletePortfolioCheckpointCommits()
      throws Exception {
    Path runDirectory = temporaryDirectory.resolve("hard-crash");
    DesktopSolveCheckpoint crashCheckpoint;
    String rootHash;
    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(
            runDirectory, "strategy-portfolio-hard-crash")) {
      harness.freeze();
      rootHash = harness.rootGoal().sourceStatementHash();
      harness.setStrategies(DesktopStrategyPortfolioTestHarness.fourIndependent("crash"));
      harness.setHardCrashPoint(StrategyPortfolioFailurePoint.DURING_CHECKPOINT_PERSIST);

      assertThatThrownBy(harness::generateAndAdmit)
          .isInstanceOf(
              DesktopSolveCoordinator.SimulatedStrategyPortfolioProcessTermination.class);
      crashCheckpoint = harness.readPersistedCheckpoint();
      assertThat(crashCheckpoint.admittedStrategies()).isEmpty();
      assertThat(crashCheckpoint.routes()).isEmpty();
      assertThat(crashCheckpoint.strategyPortfolios().decisions()).hasSize(1);
      assertThat(crashCheckpoint.strategyPortfolios().receipts()).isEmpty();
    }

    try (DesktopStrategyPortfolioTestHarness restored =
        DesktopStrategyPortfolioTestHarness.open(
            runDirectory, "strategy-portfolio-hard-crash")) {
      restored.restore(crashCheckpoint);
      assertThat(restored.rootGoal().sourceStatementHash()).isEqualTo(rootHash);
      assertThat(restored.admittedStrategies()).isEmpty();
      restored.generateAndAdmit();

      assertThat(restored.portfolios().snapshot().receipts()).hasSize(1);
      assertThat(restored.admittedStrategies()).hasSize(4);
      assertThat(restored.routeStrategyIds()).doesNotHaveDuplicates();
      assertThat(restored.providerStrategyCalls()).isZero();
      assertThat(restored.rootGoal().sourceStatementHash()).isEqualTo(rootHash);
    }
  }
}
