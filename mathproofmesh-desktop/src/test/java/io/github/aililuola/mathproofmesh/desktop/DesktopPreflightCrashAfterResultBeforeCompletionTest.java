package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPreflightExecutionStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopPreflightCrashAfterResultBeforeCompletionTest {
  @TempDir Path temp;

  @Test
  void replayableResultIsDurableBeforeCompletionMarker() throws Exception {
    StrategyCard candidate = DesktopPreflightCrashTestSupport.safeStrategy("durable-candidate");
    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(temp, "preflight-crash-after-result")) {
      harness.freeze();
      harness.setStrategies(List.of(candidate));
      harness.setPreflightHardCrashPoint(
          StrategyPreflightFailurePoint.AFTER_RESULT_DURABLE_BEFORE_COMPLETION);
      assertThatThrownBy(harness::generateAndAdmit)
          .isInstanceOf(
              DesktopSolveCoordinator.SimulatedStrategyPreflightProcessTermination.class);

      var execution = harness.onlyPreflightExecution();
      System.out.println("RESULT_DURABLE_FRONTIERS=" + (execution.resultDurable() ? 1 : 0));
      assertThat(execution.status()).isEqualTo(StrategyPreflightExecutionStatus.RESULT_DURABLE);
      assertThat(execution.executionCount()).isEqualTo(1);
      assertThat(execution.evidence()).isNotNull();
      assertThat(execution.resultArtifactRef()).isNotBlank();
      assertThat(execution.replayHash()).isNotBlank();
      assertThat(harness.readPersistedCheckpoint().strategyPreflights().executions())
          .containsKey(execution.executionId());
    }
  }
}
