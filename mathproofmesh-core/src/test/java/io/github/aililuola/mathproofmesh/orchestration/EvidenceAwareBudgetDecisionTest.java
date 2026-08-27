package io.github.aililuola.mathproofmesh.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ActionKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EvidenceAwareBudgetDecisionTest {
  private static final BudgetResourceVector LIMIT =
      new BudgetResourceVector(40L, 200_000L, 300_000L, 500_000L, new BigDecimal("10"));

  @Test
  void stateHashInvalidatesStaleCallerKeySemanticsAndInputOrderIsCanonical() {
    AdaptiveBudgetManager manager = manager(LIMIT, BudgetResourceVector.zero());
    PathBudgetStats routeA = path("strategy-a", "route-a", false, false, "unknown", 0);
    BudgetStateSnapshot first = state("authority-a", BudgetUsageTotals.zero(), List.of(routeA));
    var firstDecision = manager.decide(first);

    PathBudgetStats routeB = path("strategy-b", "route-b", false, true, "uncertain", 0);
    BudgetStateSnapshot second =
        state(
            "authority-b",
            new BudgetUsageTotals(8L, 10_000L, 20_000L, 30_000L, new BigDecimal("1")),
            List.of(routeB, routeA));
    var secondDecision = manager.decide(second);
    BudgetStateSnapshot reordered =
        state(
            "authority-b",
            new BudgetUsageTotals(8L, 10_000L, 20_000L, 30_000L, new BigDecimal("1")),
            List.of(routeA, routeB));

    assertThat(first.snapshotHash()).isNotEqualTo(second.snapshotHash());
    assertThat(firstDecision.identity().decisionHash())
        .isNotEqualTo(secondDecision.identity().decisionHash());
    assertThat(firstDecision.selectedActions()).extracting(BudgetActionCandidate::action)
        .containsExactly(ActionKind.DEEPEN);
    assertThat(secondDecision.selectedActions()).extracting(BudgetActionCandidate::action)
        .containsExactly(ActionKind.SYNTHESIZE);
    assertThat(reordered.snapshotHash()).isEqualTo(second.snapshotHash());
    assertThat(manager.decide(reordered)).isSameAs(secondDecision);
  }

  @Test
  void allSixActionsAreCostedAndMultidimensionalLimitsRejectBeforeExecution() {
    BudgetResourceVector narrow =
        new BudgetResourceVector(20L, 100_000L, 500L, 100_500L, new BigDecimal("0.0001"));
    AdaptiveBudgetManager manager = manager(narrow, BudgetResourceVector.zero());

    var decision =
        manager.decide(
            state(
                "narrow-authority",
                BudgetUsageTotals.zero(),
                List.of(path("strategy-a", "route-a", false, false, "unknown", 0))));

    assertThat(decision.actions()).extracting(BudgetActionCandidate::action)
        .containsExactlyInAnyOrder(
            ActionKind.WIDEN,
            ActionKind.DEEPEN,
            ActionKind.VERIFY,
            ActionKind.REVISE,
            ActionKind.SYNTHESIZE,
            ActionKind.STOP);
    assertThat(decision.actions())
        .filteredOn(candidate -> candidate.action() != ActionKind.STOP)
        .allMatch(candidate -> !candidate.eligible());
    assertThat(decision.stopReason()).isEqualTo("STOP_BUDGET_EXHAUSTED");
  }

  @Test
  void finishReserveAndPerTargetZeroGainProtectFinalization() {
    BudgetResourceVector limit =
        new BudgetResourceVector(12L, 120_000L, 120_000L, 240_000L, new BigDecimal("5"));
    BudgetResourceVector finish =
        new BudgetResourceVector(5L, 20_000L, 40_000L, 60_000L, new BigDecimal("1"));
    AdaptiveBudgetManager manager = manager(limit, finish);
    PathBudgetStats candidate = path("strategy-a", "route-a", false, true, "pass", 0);
    BudgetUsageTotals used =
        new BudgetUsageTotals(4L, 20_000L, 20_000L, 40_000L, new BigDecimal("0.5"));

    var decision = manager.decide(state("finish-pressure", used, List.of(candidate)));

    assertThat(decision.selectedActions()).extracting(BudgetActionCandidate::action)
        .containsExactly(ActionKind.SYNTHESIZE);
    assertThat(decision.actions())
        .filteredOn(candidateAction -> candidateAction.action() == ActionKind.DEEPEN)
        .allMatch(candidateAction -> !candidateAction.eligible());

    PathBudgetStats partial = path("strategy-z", "route-z", false, false, "unknown", 2);
    ZeroGainState zero =
        new ZeroGainState(Map.of(partial.key(ActionKind.DEEPEN), 2), 2, Set.of(), null);
    var stalled = manager.decide(state("zero-gain", BudgetUsageTotals.zero(), List.of(partial), zero));
    assertThat(stalled.actions())
        .filteredOn(action -> action.action() == ActionKind.DEEPEN)
        .allSatisfy(
            action -> {
              assertThat(action.eligible()).isFalse();
              assertThat(action.blockedReason()).isEqualTo("STOP_ZERO_GAIN_TARGET");
            });
  }

  private static AdaptiveBudgetManager manager(
      BudgetResourceVector limit, BudgetResourceVector finish) {
    return new AdaptiveBudgetManager(
        8,
        limit,
        finish,
        new ActionCostEstimator(ActionCostEstimator.Profile.defaults(), pricing()),
        2,
        3);
  }

  private static PricingSnapshot pricing() {
    return new PricingSnapshot(
        "deepseek",
        "deepseek-test",
        new BigDecimal("0.5"),
        new BigDecimal("1.0"),
        PricingSnapshot.BillingMode.BILLED,
        "config-hash",
        null);
  }

  private static BudgetStateSnapshot state(
      String authority, BudgetUsageTotals usage, List<PathBudgetStats> paths) {
    return state(authority, usage, paths, ZeroGainState.empty());
  }

  private static BudgetStateSnapshot state(
      String authority,
      BudgetUsageTotals usage,
      List<PathBudgetStats> paths,
      ZeroGainState zeroGainState) {
    return new BudgetStateSnapshot(
        "run-13",
        authority,
        "epoch-1",
        1L,
        "config-hash",
        pricing().pricingHash(),
        paths.size(),
        usage,
        BudgetUsageTotals.zero(),
        Map.of(),
        BudgetUsageTotals.zero(),
        paths,
        zeroGainState,
        null);
  }

  private static PathBudgetStats path(
      String strategy, String route, boolean complete, boolean verified, String verdict, int stagnation) {
    return new PathBudgetStats(
        strategy,
        route,
        "attempt-" + route,
        complete,
        verified,
        verified ? 0.8d : 0.5d,
        verified ? 0.7d : 0.3d,
        0.5d,
        verified ? 0.1d : 0.5d,
        verified ? 0.9d : 0.4d,
        verdict,
        AttemptEvidence.FailureClass.NONE,
        0.0d,
        0,
        0,
        complete ? 0 : 1,
        stagnation,
        0L,
        BigDecimal.ZERO,
        true,
        "mechanism-" + strategy);
  }
}
