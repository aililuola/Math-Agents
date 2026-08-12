package io.github.aililuola.mathproofmesh.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ActionKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase17AdaptiveBudgetBranchTest {

  @Test
  void constructorKeysAndFiniteRatioGuardsFailClosed() {
    assertThatThrownBy(() -> new AdaptiveBudgetManager(0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AdaptiveBudgetManager(1, -1))
        .isInstanceOf(IllegalArgumentException.class);
    AdaptiveBudgetManager manager = new AdaptiveBudgetManager(2, 1);
    assertThatThrownBy(() -> manager.decide(null, List.of(), 0, 1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> manager.decide(" ", List.of(), 0, 1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> manager.decide("nan", List.of(), 0, 1, Double.NaN, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> manager.decide("infinite", List.of(), 0, 1, 0, Double.POSITIVE_INFINITY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void emptyFailedSuccessfulAndPartialEvidenceCoverEverySchedulingShape() {
    AdaptiveBudgetManager manager = new AdaptiveBudgetManager(3, 2);

    var empty = manager.decide("empty", null, 0, 2, -1, 2);
    assertThat(empty.forcedWiden()).isFalse();
    assertThat(empty.failureRate()).isZero();
    assertThat(empty.coverage()).isZero();
    assertThat(empty.globalUncertainty()).isEqualTo(1.0d);
    assertThat(empty.actions()).isEmpty();

    AttemptEvidence failed =
        new AttemptEvidence(
            "failed", false, AttemptEvidence.FailureClass.STRUCTURAL, 2.0, 0.9, 2, true);
    var widen = manager.decide("widen", List.of(failed), 1, 5, 0.2, 0.8);
    assertThat(widen.forcedWiden()).isTrue();
    assertThat(widen.actions()).singleElement().extracting(action -> action.action())
        .isEqualTo(ActionKind.WIDEN);
    assertThat(widen.candidates()).allSatisfy(action -> assertThat(action.rank()).isNotNull());

    AttemptEvidence success =
        new AttemptEvidence(
            "success", true, AttemptEvidence.FailureClass.NONE, 1.0, 0.1, 1, true);
    var deepenSuccess = manager.decide("success", List.of(failed, success), 1, 5, 0.9, 0.1);
    assertThat(deepenSuccess.actions()).singleElement().extracting(action -> action.action())
        .isEqualTo(ActionKind.DEEPEN);
    assertThat(deepenSuccess.allEvaluatedPathsFailed()).isFalse();

    AttemptEvidence partial =
        new AttemptEvidence(
            "partial", false, AttemptEvidence.FailureClass.NONE, 0.5, 0.2, 1, false);
    var deepenPartial = manager.decide("partial", List.of(partial), 1, 5, 0.5, 0.5);
    assertThat(deepenPartial.actions()).singleElement().extracting(action -> action.targetId())
        .isEqualTo("partial");

    var blockedByPaths = manager.decide("paths", List.of(failed), 3, 5, 0.5, 0.5);
    assertThat(blockedByPaths.forcedWiden()).isFalse();
    assertThat(blockedByPaths.candidates())
        .anyMatch(action -> action.action() == ActionKind.WIDEN && !action.eligible());

    var blockedByReserve = manager.decide("reserve", List.of(failed), 1, 2, 0.5, 0.5);
    assertThat(blockedByReserve.actions()).isEmpty();
    assertThat(blockedByReserve.candidates()).noneMatch(action -> action.selected());
  }

  @Test
  void decisionsAreIdempotentAndPreferredRoutesUseDebtTieBreaks() {
    AdaptiveBudgetManager manager = new AdaptiveBudgetManager(4, 0);
    AttemptEvidence highDebt =
        new AttemptEvidence(
            "high", true, AttemptEvidence.FailureClass.NONE, 5.0, 0.1, 1, false);
    AttemptEvidence lowDebt =
        new AttemptEvidence(
            "low", true, AttemptEvidence.FailureClass.NONE, 1.0, 0.1, 1, false);
    var first = manager.decide("idempotent", List.of(highDebt, lowDebt), 0, 1, 0.5, 0.5);
    assertThat(first.actions()).singleElement().extracting(action -> action.targetId())
        .isEqualTo("low");
    assertThat(manager.decide("idempotent", List.of(), 4, 0, 0, 0)).isSameAs(first);
  }

  @Test
  void routeCapBlocksOnlyWidenAndNeverBlocksExistingRouteWork() {
    AdaptiveBudgetManager manager = new AdaptiveBudgetManager(12, 0);
    AttemptEvidence partial =
        new AttemptEvidence(
            "partial", false, AttemptEvidence.FailureClass.NONE, 2.0, 0.3, 2, false);
    var deepen = manager.decide("round-4-partial", List.of(partial), 12, 20, 0.4, 0.6);

    assertThat(deepen.actions())
        .singleElement()
        .satisfies(
            action -> {
              assertThat(action.action()).isEqualTo(ActionKind.DEEPEN);
              assertThat(action.targetId()).isEqualTo("partial");
            });
    assertThat(deepen.candidates())
        .anyMatch(action -> action.action() == ActionKind.WIDEN && !action.eligible());

    AttemptEvidence structuralFailure =
        new AttemptEvidence(
            "structural", false, AttemptEvidence.FailureClass.STRUCTURAL, 3.0, 0.8, 1, false);
    var revise =
        manager.decide(
            "round-4-structural", List.of(structuralFailure), 12, 20, 0.1, 0.9);

    assertThat(revise.actions())
        .singleElement()
        .satisfies(
            action -> {
              assertThat(action.action()).isEqualTo(ActionKind.REVISE);
              assertThat(action.targetId()).isEqualTo("structural");
            });
  }
}
