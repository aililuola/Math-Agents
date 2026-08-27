package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimPreflightStatus;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPreflightExecutionStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopPreflightCrashAfterReservationTest {
  @TempDir Path temp;

  @Test
  void emptyReservationIsSafelyExecutedOnceAfterRestore() throws Exception {
    String runId = "preflight-crash-after-reservation";
    StrategyCard candidate = DesktopPreflightCrashTestSupport.safeStrategy("reserved-candidate");
    DesktopSolveCheckpoint checkpoint;
    try (DesktopStrategyPortfolioTestHarness first =
        DesktopStrategyPortfolioTestHarness.open(temp, runId)) {
      first.freeze();
      first.setStrategies(List.of(candidate));
      first.setPreflightHardCrashPoint(StrategyPreflightFailurePoint.AFTER_RESERVATION);
      assertThatThrownBy(first::generateAndAdmit)
          .isInstanceOf(
              DesktopSolveCoordinator.SimulatedStrategyPreflightProcessTermination.class);
      assertThat(first.onlyPreflightExecution().status())
          .isEqualTo(StrategyPreflightExecutionStatus.RESERVED);
      assertThat(first.preflightExecutionCount()).isZero();
      checkpoint = first.readPersistedCheckpoint();
    }

    try (DesktopStrategyPortfolioTestHarness restored =
        DesktopStrategyPortfolioTestHarness.open(temp, runId)) {
      restored.restore(checkpoint);
      List<String> selected =
          restored.prepareAgainSelection("reservation-recovery", candidate);
      var execution = restored.onlyPreflightExecution();
      var report = restored.preflights().find(candidate.strategyId()).orElseThrow();

      int hardRejections =
          report.claims().stream()
                  .anyMatch(
                      claim ->
                          claim.status() == CriticalClaimPreflightStatus.ERROR
                              || report.hardRejected())
              ? 1
              : 0;
      System.out.println("SAFE_REEXECUTIONS_AFTER_EMPTY_RESERVATION=" + execution.executionCount());
      System.out.println("INCOMPLETE_FRONTIER_HARD_REJECTIONS=" + hardRejections);
      assertThat(execution.status()).isEqualTo(StrategyPreflightExecutionStatus.COMPLETED);
      assertThat(execution.executionCount()).isEqualTo(1);
      assertThat(hardRejections).isZero();
      assertThat(selected).containsExactly(candidate.strategyId());
    }
  }
}
