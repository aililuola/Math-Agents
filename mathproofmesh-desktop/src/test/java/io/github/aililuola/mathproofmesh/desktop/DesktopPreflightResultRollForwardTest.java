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

class DesktopPreflightResultRollForwardTest {
  @TempDir Path temp;

  @Test
  void durableResultRollsForwardWithoutDuplicateComputationOrEvidence() throws Exception {
    String runId = "preflight-result-roll-forward";
    StrategyCard candidate = DesktopPreflightCrashTestSupport.safeStrategy("roll-forward-candidate");
    DesktopSolveCheckpoint checkpoint;
    try (DesktopStrategyPortfolioTestHarness first =
        DesktopStrategyPortfolioTestHarness.open(temp, runId)) {
      first.freeze();
      first.setStrategies(List.of(candidate));
      first.setPreflightHardCrashPoint(
          StrategyPreflightFailurePoint.AFTER_RESULT_DURABLE_BEFORE_COMPLETION);
      assertThatThrownBy(first::generateAndAdmit)
          .isInstanceOf(
              DesktopSolveCoordinator.SimulatedStrategyPreflightProcessTermination.class);
      checkpoint = first.readPersistedCheckpoint();
      assertThat(first.preflightExecutionCount()).isEqualTo(1);
    }

    try (DesktopStrategyPortfolioTestHarness restored =
        DesktopStrategyPortfolioTestHarness.open(temp, runId)) {
      restored.restore(checkpoint);
      List<String> selected =
          restored.prepareAgainSelection("result-roll-forward", candidate);
      var execution = restored.onlyPreflightExecution();
      var report = restored.preflights().find(candidate.strategyId()).orElseThrow();
      long evidenceCount =
          report.claims().stream().mapToLong(claim -> claim.evidence().size()).sum();
      int hardRejections =
          report.hardRejected()
                  || report.claims().stream()
                      .anyMatch(claim -> claim.status() == CriticalClaimPreflightStatus.ERROR)
              ? 1
              : 0;
      int selectionChanges = selected.equals(List.of(candidate.strategyId())) ? 0 : 1;

      System.out.println("RESULT_ROLL_FORWARDS=1");
      System.out.println("DUPLICATE_COMPUTATION_EXECUTIONS=" + (execution.executionCount() - 1));
      System.out.println("DUPLICATE_PREFLIGHT_EVIDENCE=" + Math.max(0L, evidenceCount - 1L));
      System.out.println("POST_RESTORE_STRATEGY_SELECTION_CHANGES=" + selectionChanges);
      assertThat(execution.status()).isEqualTo(StrategyPreflightExecutionStatus.COMPLETED);
      assertThat(execution.executionCount()).isEqualTo(1);
      assertThat(evidenceCount).isEqualTo(1L);
      assertThat(hardRejections).isZero();
      assertThat(selectionChanges).isZero();
    }
  }
}
