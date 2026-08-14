package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ProofGraphConvergenceControlBoundaryTest {
  @Test
  void emptyGraphCannotManufactureARecoveryTargetOrLease() {
    ProofGraphStore empty = graph();
    ProofGraphConvergenceMonitor monitor = new ProofGraphConvergenceMonitor();

    monitor.observe(stagnant(0, 0, 0.0d), empty, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    monitor.observe(stagnant(1, 0, 0.0d), empty, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    monitor.recordFocusedNewTarget();
    monitor.recordGenericExpansionAttempt(true);

    assertThat(monitor.controlMode()).isEqualTo(ProofGraphControlMode.NORMAL_EXPANSION);
    assertThat(monitor.focusedRecoveryPlan()).isEmpty();
    assertThat(monitor.stagnationEpisodes()).isEqualTo(1);
    assertThat(monitor.focusedRecoveryEntries()).isZero();
    assertThat(monitor.acquireFocusedTaskLease(FocusedRecoveryActionType.FOCUSED_PROVER, 2))
        .isFalse();
    assertThat(monitor.genericExpansionAttempts()).isEqualTo(1);
    assertThat(monitor.genericExpansionBlocks()).isZero();
    assertThat(monitor.genericExpansionLeaks()).isZero();
  }

  @Test
  void classifiesDebtOnlyProgressAndEachDivergenceSignal() {
    ProofGraphConvergenceMonitor monitor = new ProofGraphConvergenceMonitor();
    ProofGraphRoundMetrics previous = stagnant(0, 1, 5.0d);
    ProofGraphRoundMetrics debtProgress = stagnant(1, 1, 3.0d);
    ProofGraphRoundMetrics debtDivergence = stagnant(1, 1, 6.0d);
    ProofGraphRoundMetrics targetDivergence = stagnant(1, 2, 5.0d);

    assertThat(monitor.classify(debtProgress, previous))
        .isEqualTo(ProofGraphRoundClassification.PROGRESSING);
    assertThat(monitor.classify(debtDivergence, previous))
        .isEqualTo(ProofGraphRoundClassification.DIVERGING);
    assertThat(monitor.classify(targetDivergence, previous))
        .isEqualTo(ProofGraphRoundClassification.DIVERGING);
    assertThatThrownBy(() -> monitor.classify(null, previous))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void capacityAndFocusedGatesCoverRouteCampaignQuotaAndSelectedBindings() {
    ProofGraphConvergenceMonitor normal = new ProofGraphConvergenceMonitor();
    assertThat(
            normal.decideExpansion(
                FocusedRecoveryActionType.NEW_STRATEGY, false, 0, 0, "", ""))
        .isEqualTo(FocusedExpansionDecision.allow());
    assertThat(
            normal.decideExpansion(
                FocusedRecoveryActionType.NEW_STRATEGY, false, 8, 0, "", ""))
        .isEqualTo(FocusedExpansionDecision.deferCapacity());
    assertThat(
            normal.decideExpansion(
                FocusedRecoveryActionType.NEW_STRATEGY, false, 0, 20, "", ""))
        .isEqualTo(FocusedExpansionDecision.deferCapacity());
    assertThatThrownBy(
            () -> normal.decideExpansion(null, false, 0, 0, "", ""))
        .isInstanceOf(NullPointerException.class);

    ProofGraphStore graph = ProofGraphConvergenceTestFixtures.graphWithTarget();
    ProofGraphConvergenceMonitor focused =
        new ProofGraphConvergenceMonitor(config(1, 20, 2, 99, 99, 1));
    focused.observe(stagnant(0, 1, 4.0d), graph, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    FocusedRecoveryPlan plan = focused.focusedRecoveryPlan().orElseThrow();
    String canonicalId = plan.selectedCanonicalTargetIds().iterator().next();

    assertThat(plan.trigger()).isEqualTo(ProofGraphConvergenceTrigger.ACTIVE_TARGET_CAPACITY);
    assertThat(
            focused.decideExpansion(
                FocusedRecoveryActionType.GENERIC_INSPIRATION,
                true,
                1,
                1,
                "",
                "unrelated"))
        .isEqualTo(FocusedExpansionDecision.deferFocusedRecovery());
    assertThat(
            focused.decideExpansion(
                FocusedRecoveryActionType.FOCUSED_PROVER,
                true,
                1,
                1,
                "",
                "unrelated"))
        .extracting(FocusedExpansionDecision::code)
        .isEqualTo("DEFER_UNSELECTED_RECOVERY_BINDING");
    assertThat(
            focused.decideExpansion(
                FocusedRecoveryActionType.GENERIC_INSPIRATION,
                true,
                1,
                1,
                plan.selectedFamilyId(),
                canonicalId))
        .isEqualTo(FocusedExpansionDecision.allow());
    assertThat(
            focused.decideExpansion(
                FocusedRecoveryActionType.GENERIC_INSPIRATION,
                false,
                0,
                0,
                "",
                "unrelated"))
        .isEqualTo(FocusedExpansionDecision.deferFocusedRecovery());
    assertThat(
            focused.decideExpansion(
                FocusedRecoveryActionType.GENERIC_INSPIRATION,
                false,
                0,
                0,
                plan.selectedFamilyId(),
                canonicalId))
        .isEqualTo(FocusedExpansionDecision.allow());
    assertThat(
            focused.decideExpansion(
                FocusedRecoveryActionType.EXACT_FALSIFICATION,
                false,
                0,
                0,
                "",
                "unrelated"))
        .isEqualTo(FocusedExpansionDecision.deferFocusedRecovery());

    assertThat(focused.acquireFocusedTaskLease(FocusedRecoveryActionType.FOCUSED_PROVER, -1))
        .isFalse();
    assertThatThrownBy(() -> focused.acquireFocusedTaskLease(null, 1))
        .isInstanceOf(NullPointerException.class);
    assertThat(focused.acquireFocusedTaskLease(FocusedRecoveryActionType.FOCUSED_PROVER, 1))
        .isTrue();
    assertThat(focused.acquireFocusedTaskLease(FocusedRecoveryActionType.FOCUSED_PROVER, 1))
        .isFalse();

    focused.recordGenericExpansionAttempt(true);
    focused.recordGenericExpansionAttempt(false);
    focused.recordFocusedNewTarget();
    focused.recordFocusedNewTarget();
    assertThat(focused.genericExpansionLeaks()).isEqualTo(1);
    assertThat(focused.genericExpansionBlocks()).isEqualTo(1);
    assertThat(
            focused.decideExpansion(
                FocusedRecoveryActionType.EXACT_FALSIFICATION,
                false,
                0,
                0,
                "",
                "unrelated"))
        .isEqualTo(FocusedExpansionDecision.deferFocusedRecovery());
  }

  @Test
  void campaignCapacityAndDivergenceCanFocusAndCooldownCanReenter() {
    ProofGraphStore graph = ProofGraphConvergenceTestFixtures.graphWithTarget();
    ProofGraphConvergenceMonitor campaignCapacity =
        new ProofGraphConvergenceMonitor(config(99, 1, 2, 99, 99, 1));
    campaignCapacity.observe(
        stagnant(0, 1, 4.0d), graph, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    assertThat(campaignCapacity.controlMode())
        .isEqualTo(ProofGraphControlMode.FOCUSED_RECOVERY);

    ProofGraphConvergenceMonitor divergence = new ProofGraphConvergenceMonitor();
    divergence.observe(stagnant(0, 1, 2.0d), graph, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    divergence.observe(stagnant(1, 1, 3.0d), graph, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    divergence.observe(stagnant(2, 1, 4.0d), graph, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    assertThat(divergence.controlMode()).isEqualTo(ProofGraphControlMode.FOCUSED_RECOVERY);
    assertThat(divergence.divergenceEpisodes()).isEqualTo(1);
    assertThat(divergence.focusedRecoveryPlan().orElseThrow().trigger())
        .isEqualTo(ProofGraphConvergenceTrigger.CONSECUTIVE_DIVERGENCE);

    divergence.observe(
        ProofGraphConvergenceTestFixtures.metrics(
            3, 1, 0, 0, 0, 0, 0, 1, 0, 0, 4.0d),
        graph,
        ProofGraphConvergenceTestFixtures.ROOT_HASH);
    assertThat(divergence.controlMode()).isEqualTo(ProofGraphControlMode.RECOVERY_COOLDOWN);
    divergence.observe(stagnant(4, 1, 5.0d), graph, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    assertThat(divergence.controlMode()).isEqualTo(ProofGraphControlMode.FOCUSED_RECOVERY);
    assertThat(divergence.focusedRecoveryEntries()).isEqualTo(2);
    assertThat(divergence.recoveryCooldownEntries()).isEqualTo(1);
  }

  @Test
  void samplingSeparatesActiveDeferredDuplicateAndClosedState() {
    ProofGraphStore graph = graph();
    ProofObligation active = add(graph, "active", "route-a", false, 0);
    add(graph, "deferred", "route-b", true, 0);
    ProofGraphConvergenceMonitor monitor = new ProofGraphConvergenceMonitor();

    ProofGraphRoundMetrics first =
        monitor.sample(0, graph, 0, 0, 0, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    assertThat(first.activeCanonicalTargets()).isEqualTo(1);
    assertThat(first.deferredCanonicalTargets()).isEqualTo(1);
    assertThat(first.closedCanonicalTargets()).isZero();

    ProofObligation duplicate =
        ObligationCanonicalizationTestFixtures.obligation(
            "active-alias",
            "route-c",
            active.statement(),
            active.normalizedStatement(),
            "family-active");
    graph.addObligationCanonicalized(
        duplicate,
        ObligationCanonicalizationTestFixtures.context(
            duplicate,
            "route-c",
            "family-active",
            List.of("global"),
            "positive",
            Map.of(),
            1));
    graph.refuteObligation(active.obligationId(), null);
    graph.refuteObligation(duplicate.obligationId(), null);

    ProofGraphRoundMetrics second =
        monitor.sample(1, graph, 1, 1, 1, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    assertThat(second.duplicateOccurrences()).isEqualTo(1);
    assertThat(second.verifiedClaimGains()).isEqualTo(1);
    assertThat(second.exactRefutationGains()).isEqualTo(1);
    assertThat(second.forbiddenProposals()).isEqualTo(1);
    assertThat(second.newlyClosedCanonicalTargets()).isEqualTo(1);
  }

  private static ProofGraphStore graph() {
    return new ProofGraphStore(
        ObligationCanonicalizationTestFixtures.PROBLEM_HASH, ProofGraphPolicy.defaults());
  }

  private static ProofObligation add(
      ProofGraphStore graph, String id, String routeId, boolean deferred, int round) {
    ProofObligation obligation =
        ObligationCanonicalizationTestFixtures.obligation(
            id,
            routeId,
            "Prove target " + id + ".",
            "prove target " + id,
            "family-" + id);
    ObligationCreationContext context =
        ObligationCanonicalizationTestFixtures.context(
            obligation,
            routeId,
            "family-" + id,
            List.of("global"),
            "positive",
            Map.of(),
            round);
    graph.addObligationCanonicalized(
        obligation,
        deferred
            ? context.withSchedulingState(
                ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY)
            : context);
    return obligation;
  }

  private static ProofGraphRoundMetrics stagnant(int round, int active, double debt) {
    return ProofGraphConvergenceTestFixtures.metrics(
        round, active, 0, 0, 0, 1, 0, 0, 0, 0, debt);
  }

  private static ProofGraphConvergenceConfig config(
      int routeCapacity,
      int campaignCapacity,
      int focusedQuota,
      int stagnationWindow,
      int divergenceWindow,
      int cooldown) {
    return new ProofGraphConvergenceConfig(
        routeCapacity,
        campaignCapacity,
        focusedQuota,
        stagnationWindow,
        divergenceWindow,
        cooldown,
        1.0e-9d,
        2.0d,
        2.0d,
        1.0d,
        1.0d,
        1.0d,
        0.5d,
        1.0d);
  }
}
