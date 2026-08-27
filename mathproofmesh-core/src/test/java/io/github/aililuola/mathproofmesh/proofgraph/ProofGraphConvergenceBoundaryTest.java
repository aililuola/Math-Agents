package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ProofGraphConvergenceBoundaryTest {
  @Test
  void validatesEveryConfiguredLimitAndWeightWithoutChangingTheGate() {
    int[] limits = {8, 20, 2, 2, 2, 1};
    double[] weights = {1.0e-9d, 2.0d, 2.0d, 1.0d, 1.0d, 1.0d, 0.5d, 1.0d};

    for (int index = 0; index < limits.length; index++) {
      int[] invalid = limits.clone();
      invalid[index] = index == 2 ? -1 : 0;
      assertThatThrownBy(() -> config(invalid, weights))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (int index = 0; index < weights.length; index++) {
      double[] negative = weights.clone();
      negative[index] = -1.0d;
      assertThatThrownBy(() -> config(limits, negative))
          .isInstanceOf(IllegalArgumentException.class);

      double[] nonFinite = weights.clone();
      nonFinite[index] = Double.NaN;
      assertThatThrownBy(() -> config(limits, nonFinite))
          .isInstanceOf(IllegalArgumentException.class);
    }

    ProofGraphConvergenceConfig valid = config(limits, weights);
    ProofGraphRoundMetrics current = metrics(new int[11], new double[] {4, 4, 0, 4, 0});
    assertThat(valid.score(current, null)).isZero();
    assertThat(valid.score(current, current)).isZero();
  }

  @Test
  void validatesEveryRoundCounterAndDebtAndRecognizesEachAuthoritySignal() {
    int[] counters = new int[11];
    double[] debts = {4.0d, 3.0d, 1.0d, 4.0d, 0.0d};
    for (int index = 0; index < counters.length; index++) {
      int[] invalid = counters.clone();
      invalid[index] = -1;
      assertThatThrownBy(() -> metrics(invalid, debts))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (int index = 0; index < 4; index++) {
      double[] negative = debts.clone();
      negative[index] = -1.0d;
      assertThatThrownBy(() -> metrics(counters, negative))
          .isInstanceOf(IllegalArgumentException.class);

      double[] nonFinite = debts.clone();
      nonFinite[index] = Double.POSITIVE_INFINITY;
      assertThatThrownBy(() -> metrics(counters, nonFinite))
          .isInstanceOf(IllegalArgumentException.class);
    }
    double[] nonFiniteScore = debts.clone();
    nonFiniteScore[4] = Double.NaN;
    assertThatThrownBy(() -> metrics(counters, nonFiniteScore))
        .isInstanceOf(IllegalArgumentException.class);

    ProofGraphRoundMetrics noProgress = metrics(counters, debts);
    assertThat(noProgress.authoritativeProgress()).isFalse();
    assertThat(withAuthority(counters, 8).authoritativeProgress()).isTrue();
    assertThat(withAuthority(counters, 9).authoritativeProgress()).isTrue();
    assertThat(withAuthority(counters, 10).authoritativeProgress()).isTrue();
    assertThat(noProgress.withConvergenceScore(-2.0d).convergenceScore()).isEqualTo(-2.0d);
  }

  @Test
  void snapshotDefaultsAreImmutableAndEveryInvalidCounterFailsClosed() {
    ProofGraphConvergenceSnapshot defaults = snapshot(null, null, null, new int[15], 0L, null);
    assertThat(defaults.controlMode()).isEqualTo(ProofGraphControlMode.NORMAL_EXPANSION);
    assertThat(defaults.roundHistory()).isEmpty();
    assertThat(defaults.roundClassifications()).isEmpty();
    assertThat(defaults.focusedTaskLeases()).isEmpty();

    ProofGraphRoundMetrics sample =
        ProofGraphConvergenceTestFixtures.metrics(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1.0d);
    assertThatThrownBy(
            () ->
                snapshot(
                    List.of(sample),
                    List.of(),
                    ProofGraphControlMode.NORMAL_EXPANSION,
                    new int[15],
                    0L,
                    null))
        .isInstanceOf(IllegalArgumentException.class);

    for (int index = 0; index < 15; index++) {
      int[] invalid = new int[15];
      invalid[index] = -1;
      assertThatThrownBy(
              () ->
                  snapshot(
                      List.of(),
                      List.of(),
                      ProofGraphControlMode.NORMAL_EXPANSION,
                      invalid,
                      0L,
                      null))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                snapshot(
                    List.of(),
                    List.of(),
                    ProofGraphControlMode.NORMAL_EXPANSION,
                    new int[15],
                    -1L,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                snapshot(
                    List.of(),
                    List.of(),
                    ProofGraphControlMode.FOCUSED_RECOVERY,
                    new int[15],
                    0L,
                    null))
        .isInstanceOf(IllegalArgumentException.class);

    FocusedRecoveryPlan plan = plan(1, 0, Set.of("canonical-a"));
    Set<String> leases = new LinkedHashSet<>(Set.of("lease-b", "lease-a"));
    ProofGraphConvergenceSnapshot focused =
        new ProofGraphConvergenceSnapshot(
            ProofGraphControlMode.FOCUSED_RECOVERY,
            List.of(sample),
            List.of(ProofGraphRoundClassification.STAGNATING),
            1,
            0,
            0,
            plan,
            leases,
            1,
            0,
            1,
            0,
            0,
            2,
            2,
            0,
            1,
            0,
            0,
            0,
            3L);
    leases.add("late");
    assertThat(focused.focusedTaskLeases()).containsExactly("lease-a", "lease-b");
  }

  @Test
  void focusedPlanAndBriefPreserveSelectionsAndRejectAmbiguousState() {
    Set<String> selected = new LinkedHashSet<>(Set.of("canonical-b", "canonical-a"));
    FocusedRecoveryPlan plan = plan(2, 0, selected);
    selected.add("late");
    assertThat(plan.selectedCanonicalTargetIds())
        .containsExactly("canonical-a", "canonical-b");
    assertThat(plan.selects("family-a", "unrelated")).isTrue();
    assertThat(plan.selects(null, " canonical-a ")).isTrue();
    assertThat(plan.selects("other", null)).isFalse();
    assertThat(plan.quotaRemaining()).isEqualTo(2);
    FocusedRecoveryPlan exhausted = plan.useNewTarget().useNewTarget();
    assertThat(exhausted.quotaRemaining()).isZero();
    assertThatThrownBy(exhausted::useNewTarget).isInstanceOf(IllegalStateException.class);

    assertInvalidPlan("", "problem", "root", ProofGraphConvergenceTrigger.CONSECUTIVE_STAGNATION,
        0, Set.of("a"), 1, 0);
    assertInvalidPlan("episode", "", "root", ProofGraphConvergenceTrigger.CONSECUTIVE_STAGNATION,
        0, Set.of("a"), 1, 0);
    assertInvalidPlan("episode", "problem", "", ProofGraphConvergenceTrigger.CONSECUTIVE_STAGNATION,
        0, Set.of("a"), 1, 0);
    assertInvalidPlan("episode", "problem", "root", null, 0, Set.of("a"), 1, 0);
    assertInvalidPlan("episode", "problem", "root", ProofGraphConvergenceTrigger.CONSECUTIVE_STAGNATION,
        -1, Set.of("a"), 1, 0);
    assertInvalidPlan("episode", "problem", "root", ProofGraphConvergenceTrigger.CONSECUTIVE_STAGNATION,
        0, Set.of(), 1, 0);
    assertInvalidPlan("episode", "problem", "root", ProofGraphConvergenceTrigger.CONSECUTIVE_STAGNATION,
        0, Set.of("a"), -1, 0);
    assertInvalidPlan("episode", "problem", "root", ProofGraphConvergenceTrigger.CONSECUTIVE_STAGNATION,
        0, Set.of("a"), 1, -1);
    assertInvalidPlan("episode", "problem", "root", ProofGraphConvergenceTrigger.CONSECUTIVE_STAGNATION,
        0, Set.of("a"), 1, 2);

    Map<String, Set<String>> dependencyPlans = new LinkedHashMap<>();
    Set<String> alternatives = new LinkedHashSet<>(Set.of("plan-b", "plan-a"));
    dependencyPlans.put("canonical-a", alternatives);
    FocusedRecoveryBrief brief =
        new FocusedRecoveryBrief(
            ProofGraphConvergenceTestFixtures.ROOT_HASH,
            null,
            null,
            List.of("canonical-a"),
            null,
            dependencyPlans,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0);
    alternatives.add("late");
    dependencyPlans.put("late", Set.of("late"));
    assertThat(brief.dependencyPlans()).containsOnlyKeys("canonical-a");
    assertThat(brief.dependencyPlans().get("canonical-a")).doesNotContain("late");
    assertThat(brief.verifiedFacts()).isEmpty();
    assertThat(brief.blockedGenericActions()).isEmpty();
    assertThatThrownBy(
            () ->
                new FocusedRecoveryBrief(
                    "", "", "", List.of("a"), Map.of(), Map.of(), List.of(), List.of(),
                    List.of(), List.of(), "", List.of(), List.of(), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new FocusedRecoveryBrief(
                    "root", "", "", List.of(), Map.of(), Map.of(), List.of(), List.of(),
                    List.of(), List.of(), "", List.of(), List.of(), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new FocusedRecoveryBrief(
                    "root", "", "", List.of("a"), Map.of(), Map.of(), List.of(), List.of(),
                    List.of(), List.of(), "", List.of(), List.of(), -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void expansionDecisionsAndDeferredRecordsRejectContradictoryState() {
    FocusedExpansionDecision normalized =
        new FocusedExpansionDecision(false, false, null, " code ");
    assertThat(normalized.schedulingState())
        .isEqualTo(ObligationOccurrenceSchedulingState.ACTIVE);
    assertThat(normalized.code()).isEqualTo("code");
    assertThatThrownBy(() -> new FocusedExpansionDecision(true, true, null, "bad"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FocusedExpansionDecision(false, false, null, null))
        .isInstanceOf(IllegalArgumentException.class);

    DeferredExpansionRecord valid = deferred(1, 0, "deferred", "reason");
    assertThat(valid.routeId()).isEmpty();
    assertThatThrownBy(() -> deferred(-1, 0, "deferred", "reason"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> deferred(1, -1, "deferred", "reason"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> deferred(1, 0, "active", "reason"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> deferred(1, 0, "deferred", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DeferredExpansionRecord(
                    "", "problem", 0, "", "", "", FocusedRecoveryActionType.NEW_STRATEGY,
                    ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY, "reason", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DeferredExpansionRecord(
                    "id", "", 0, "", "", "", FocusedRecoveryActionType.NEW_STRATEGY,
                    ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY, "reason", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DeferredExpansionRecord(
                    "id", "problem", 0, "", "", "", null,
                    ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY, "reason", 0))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new DeferredExpansionRecord(
                    "id", "problem", 0, "", "", "", FocusedRecoveryActionType.NEW_STRATEGY,
                    null, "reason", 0))
        .isInstanceOf(NullPointerException.class);

    DeferredExpansionSnapshot empty = new DeferredExpansionSnapshot(null, 0L);
    assertThat(empty.records()).isEmpty();
    assertThatThrownBy(() -> new DeferredExpansionSnapshot(Map.of(), -1L))
        .isInstanceOf(IllegalArgumentException.class);
    Map<String, DeferredExpansionRecord> mutable = new LinkedHashMap<>();
    mutable.put(valid.deferredId(), valid);
    DeferredExpansionSnapshot snapshot = new DeferredExpansionSnapshot(mutable, 1L);
    mutable.clear();
    assertThat(snapshot.records()).containsOnlyKeys(valid.deferredId());
  }

  private static ProofGraphConvergenceConfig config(int[] limits, double[] weights) {
    return new ProofGraphConvergenceConfig(
        limits[0], limits[1], limits[2], limits[3], limits[4], limits[5],
        weights[0], weights[1], weights[2], weights[3], weights[4], weights[5], weights[6],
        weights[7]);
  }

  private static ProofGraphRoundMetrics metrics(int[] counters, double[] debts) {
    return new ProofGraphRoundMetrics(
        counters[0], counters[1], counters[2], counters[3], counters[4], counters[5],
        counters[6], counters[7], counters[8], counters[9], counters[10], debts[0], debts[1],
        debts[2], debts[3], debts[4]);
  }

  private static ProofGraphRoundMetrics withAuthority(int[] source, int authorityIndex) {
    int[] counters = source.clone();
    counters[authorityIndex] = 1;
    return metrics(counters, new double[] {1, 1, 0, 1, 0});
  }

  private static ProofGraphConvergenceSnapshot snapshot(
      List<ProofGraphRoundMetrics> history,
      List<ProofGraphRoundClassification> classifications,
      ProofGraphControlMode mode,
      int[] counters,
      long version,
      FocusedRecoveryPlan plan) {
    return new ProofGraphConvergenceSnapshot(
        mode,
        history,
        classifications,
        counters[0],
        counters[1],
        counters[2],
        plan,
        null,
        counters[3],
        counters[4],
        counters[5],
        counters[6],
        counters[7],
        counters[8],
        counters[9],
        counters[10],
        counters[11],
        counters[12],
        counters[13],
        counters[14],
        version);
  }

  private static FocusedRecoveryPlan plan(int quota, int used, Set<String> selected) {
    return new FocusedRecoveryPlan(
        "episode-a",
        ObligationCanonicalizationTestFixtures.PROBLEM_HASH,
        ProofGraphConvergenceTestFixtures.ROOT_HASH,
        ProofGraphConvergenceTrigger.CONSECUTIVE_STAGNATION,
        0,
        "family-a",
        selected,
        quota,
        used);
  }

  private static void assertInvalidPlan(
      String episode,
      String problem,
      String root,
      ProofGraphConvergenceTrigger trigger,
      int round,
      Set<String> selected,
      int quota,
      int used) {
    assertThatThrownBy(
            () ->
                new FocusedRecoveryPlan(
                    episode, problem, root, trigger, round, "family", selected, quota, used))
        .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
  }

  private static DeferredExpansionRecord deferred(
      int round, long version, String state, String reason) {
    ObligationOccurrenceSchedulingState scheduling =
        "active".equals(state)
            ? ObligationOccurrenceSchedulingState.ACTIVE
            : ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY;
    return new DeferredExpansionRecord(
        "deferred-id",
        "problem-hash",
        round,
        null,
        null,
        null,
        FocusedRecoveryActionType.NEW_STRATEGY,
        scheduling,
        reason,
        version);
  }
}
