package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ActionKind;
import io.github.aililuola.mathproofmesh.contract.BudgetAction;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DesktopSchedulerActionBatchProductionTest {
  @Test
  void schedulerDraftsMergeInStableRouteOrder() {
    var run =
        DesktopResearchConcurrencyTestSupport.run(
            ResearchWorkKind.PIVOT_REVIEW, 4, ignored -> 25L);
    assertThat(run.mergePlan().decisions())
        .extracting(decision -> decision.routeId())
        .containsExactly("route-0", "route-1", "route-2", "route-3");
  }

  @Test
  void productionSchedulerKeepsFourCompatibleRouteActionsInOneBatch() {
    List<BudgetAction> actions =
        List.of(
            action(ActionKind.DEEPEN, "route-0", 1),
            action(ActionKind.REVISE, "route-1", 2),
            action(ActionKind.DEEPEN, "route-2", 3),
            action(ActionKind.REVISE, "route-3", 4));

    assertThat(DesktopSolveCoordinator.compatibleSchedulerActions(actions, 4))
        .extracting(BudgetAction::targetId)
        .containsExactly("route-0", "route-1", "route-2", "route-3");
  }

  @Test
  void productionSchedulerRejectsConflictingActionsForTheSameRoute() {
    List<BudgetAction> actions =
        List.of(
            action(ActionKind.DEEPEN, "route-0", 1),
            action(ActionKind.REVISE, "route-0", 2),
            action(ActionKind.DEEPEN, "route-1", 3));

    assertThat(DesktopSolveCoordinator.compatibleSchedulerActions(actions, 4))
        .extracting(BudgetAction::targetId)
        .containsExactly("route-0", "route-1");
  }

  private static BudgetAction action(ActionKind kind, String targetId, int rank) {
    return new BudgetAction(
        kind, "", true, 1, false, 1, rank, "compatible route action", 1.0d, true, "", targetId);
  }
}
