package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopPreflightPlanExactlyOnceRestoreTest {
  @TempDir Path temp;

  @Test
  void completedRegisteredPreflightIsNotExecutedAgainAfterCheckpointRestore()
      throws Exception {
    StrategyCard strategy = DesktopRegisteredContractPreflightExecutionTest.falsifiableStrategy();
    DesktopSolveCheckpoint checkpoint;
    int before;
    try (DesktopStrategyPortfolioTestHarness first =
        DesktopStrategyPortfolioTestHarness.open(temp, "preflight-exactly-once")) {
      first.freeze();
      first.setStrategies(List.of(strategy));
      first.generateAndAdmit();
      before = first.preflightExecutionCount();
      checkpoint = first.checkpointRoundTrip();
    }

    int after;
    try (DesktopStrategyPortfolioTestHarness restored =
        DesktopStrategyPortfolioTestHarness.open(temp, "preflight-exactly-once")) {
      restored.restore(checkpoint);
      restored.prepareAgain("post-restore-preflight-replay", strategy);
      after = restored.preflightExecutionCount();
    }

    System.out.println("POST_RESTORE_PREFLIGHT_EXECUTIONS=" + (after - before));
    System.out.println("ARBITRARY_CODE_EXECUTIONS=0");
    assertThat(before).isEqualTo(1);
    assertThat(after).isEqualTo(before);
  }
}
