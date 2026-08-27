package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.ActionKind;
import io.github.aililuola.mathproofmesh.orchestration.AdaptiveBudgetManager;
import io.github.aililuola.mathproofmesh.orchestration.BudgetActionCandidate;
import io.github.aililuola.mathproofmesh.orchestration.BudgetStateSnapshot;
import io.github.aililuola.mathproofmesh.orchestration.EvidenceAwareBudgetDecision;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Reopens only terminals created by a superseded, inapplicable scheduler decision. */
final class DesktopStaleBudgetPolicyTerminalRecovery {
  private DesktopStaleBudgetPolicyTerminalRecovery() {}

  static String evaluate(
      DesktopSolveCheckpoint checkpoint,
      String workflowCursor,
      AdaptiveBudgetManager adaptiveBudget,
      Supplier<BudgetStateSnapshot> currentState,
      Predicate<String> completedFailedRoute) {
    DesktopSolveCheckpoint.SchedulerStop stopped = checkpoint.schedulerStop();
    if (!checkpoint.terminal()
        || !"terminal".equals(workflowCursor)
        || stopped == null
        || !"no_candidate".equals(stopped.code())
        || stopped.remainingCalls() < 1
        || stopped.remainingRounds() < 1
        || stopped.openObligations() < 1) {
      return null;
    }

    List<EvidenceAwareBudgetDecision> persisted = checkpoint.budgetDecisions().decisions();
    if (persisted.isEmpty()
        || persisted.stream()
            .anyMatch(
                decision ->
                    AdaptiveBudgetManager.currentPolicyVersion()
                        .equals(decision.identity().policyVersion()))) {
      return null;
    }
    EvidenceAwareBudgetDecision stale =
        persisted.stream()
            .filter(
                decision ->
                    decision.selectedActions().stream()
                        .anyMatch(
                            action ->
                                action.action() == ActionKind.VERIFY
                                    && completedFailedRoute.test(action.targetId())))
            .findFirst()
            .orElse(null);
    if (stale == null) {
      return null;
    }

    EvidenceAwareBudgetDecision recomputed = adaptiveBudget.decide(currentState.get());
    BudgetActionCandidate selected = recomputed.selectedActions().stream().findFirst().orElse(null);
    if (selected == null
        || selected.action() == ActionKind.STOP
        || selected.action() == ActionKind.SYNTHESIZE) {
      return null;
    }
    return stale.identity().policyVersion()
        + " -> "
        + recomputed.identity().policyVersion()
        + ": "
        + selected.action().name();
  }
}
