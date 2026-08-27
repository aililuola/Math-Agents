package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalSchedulingTransitionTest {
  @Test
  void rejectsInvalidRequestsAndReportsEveryFailClosedOutcome() {
    ProofGraphStore graph = graph();

    assertThatThrownBy(
            () ->
                graph.reactivateCanonicalTarget(
                    "canonical",
                    "obligation",
                    ObligationOccurrenceSchedulingState.ACTIVE,
                    0,
                    "invalid expected state"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                graph.reactivateCanonicalTarget(
                    "canonical",
                    "obligation",
                    ObligationOccurrenceSchedulingState.RETIRED,
                    0,
                    "invalid expected state"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                graph.reactivateCanonicalTarget(
                    "canonical",
                    "obligation",
                    ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY,
                    -1,
                    "invalid round"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(
            graph.reactivateCanonicalTarget(
                "missing-canonical",
                "missing-obligation",
                ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY,
                0,
                "missing"))
        .extracting(CanonicalSchedulingTransitionResult::code)
        .isEqualTo(CanonicalSchedulingTransitionCode.TARGET_NOT_FOUND);

    ProofObligation deferred =
        add(
            graph,
            "deferred",
            "deferred target",
            ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY);
    String canonicalId = canonicalId(graph, deferred);

    assertThat(
            graph.reactivateCanonicalTarget(
                canonicalId,
                "missing-obligation",
                ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY,
                1,
                "missing occurrence"))
        .extracting(CanonicalSchedulingTransitionResult::code)
        .isEqualTo(CanonicalSchedulingTransitionCode.OCCURRENCE_NOT_FOUND);
    assertThat(
            graph.reactivateCanonicalTarget(
                canonicalId,
                deferred.obligationId(),
                ObligationOccurrenceSchedulingState.DEFERRED_FOCUSED_RECOVERY,
                1,
                "stale expected state"))
        .extracting(CanonicalSchedulingTransitionResult::code)
        .isEqualTo(CanonicalSchedulingTransitionCode.STATE_MISMATCH);

    assertThat(
            graph.reactivateCanonicalTarget(
                canonicalId,
                deferred.obligationId(),
                ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY,
                1,
                "eligible"))
        .satisfies(
            result -> {
              assertThat(result.code()).isEqualTo(CanonicalSchedulingTransitionCode.REACTIVATED);
              assertThat(result.schedulingState())
                  .isEqualTo(CanonicalObligationSchedulingState.ACTIVE);
              assertThat(result.transitioned()).isTrue();
            });
    assertThat(
            graph.reactivateCanonicalTarget(
                canonicalId,
                deferred.obligationId(),
                ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY,
                2,
                "duplicate"))
        .satisfies(
            result -> {
              assertThat(result.code()).isEqualTo(CanonicalSchedulingTransitionCode.ALREADY_ACTIVE);
              assertThat(result.transitioned()).isFalse();
            });
  }

  @Test
  void terminalTargetsCannotReactivateButMixedTargetsRemainEligible() {
    ProofGraphStore graph = graph();
    ProofObligation resolved =
        add(
            graph,
            "resolved",
            "resolved target",
            ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY);
    var fact =
        ObligationCanonicalizationTestFixtures.verifiedFact(
            "resolved-fact", resolved.normalizedStatement());
    graph.addClaimNode(fact);
    graph.closeObligation(resolved.obligationId(), fact.messageId(), 1.0d);

    assertThat(
            graph.reactivateCanonicalTarget(
                canonicalId(graph, resolved),
                resolved.obligationId(),
                ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY,
                1,
                "resolved"))
        .extracting(CanonicalSchedulingTransitionResult::code)
        .isEqualTo(CanonicalSchedulingTransitionCode.TERMINAL_TARGET);

    ProofObligation refuted =
        add(
            graph,
            "refuted",
            "refuted target",
            ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY);
    graph.refuteObligation(refuted.obligationId(), null);
    assertThat(
            graph.reactivateCanonicalTarget(
                canonicalId(graph, refuted),
                refuted.obligationId(),
                ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY,
                1,
                "refuted"))
        .extracting(CanonicalSchedulingTransitionResult::code)
        .isEqualTo(CanonicalSchedulingTransitionCode.TERMINAL_TARGET);

    ProofObligation mixedClosed =
        add(
            graph,
            "mixed-closed",
            "mixed target",
            ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY);
    ProofObligation mixedRefuted =
        add(
            graph,
            "mixed-refuted",
            "mixed target",
            ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY);
    var mixedFact =
        ObligationCanonicalizationTestFixtures.verifiedFact(
            "mixed-fact", mixedClosed.normalizedStatement());
    graph.addClaimNode(mixedFact);
    graph.closeObligation(mixedClosed.obligationId(), mixedFact.messageId(), 1.0d);
    graph.refuteObligation(mixedRefuted.obligationId(), null);

    assertThat(
            graph.reactivateCanonicalTarget(
                canonicalId(graph, mixedClosed),
                mixedClosed.obligationId(),
                ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY,
                2,
                "mixed remains actionable"))
        .extracting(CanonicalSchedulingTransitionResult::code)
        .isEqualTo(CanonicalSchedulingTransitionCode.REACTIVATED);
  }

  @Test
  void aggregateSchedulingStateUsesActiveFocusedCapacityThenRetiredPrecedence() {
    ProofGraphStore graph = graph();

    ProofObligation active =
        add(graph, "active", "active precedence", ObligationOccurrenceSchedulingState.ACTIVE);
    ProofObligation activeSibling =
        add(
            graph,
            "active-sibling",
            "active precedence",
            ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY);
    assertRetirementState(
        graph,
        activeSibling,
        ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY,
        CanonicalObligationSchedulingState.ACTIVE);
    assertThat(graph.canonicalTargetForObligation(active.obligationId())).isPresent();

    ProofObligation focused =
        add(
            graph,
            "focused",
            "focused precedence",
            ObligationOccurrenceSchedulingState.DEFERRED_FOCUSED_RECOVERY);
    ProofObligation focusedSibling =
        add(
            graph,
            "focused-sibling",
            "focused precedence",
            ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY);
    assertRetirementState(
        graph,
        focusedSibling,
        ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY,
        CanonicalObligationSchedulingState.DEFERRED_FOCUSED_RECOVERY);
    assertThat(graph.canonicalTargetForObligation(focused.obligationId())).isPresent();

    ProofObligation capacity =
        add(
            graph,
            "capacity",
            "capacity precedence",
            ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY);
    ProofObligation capacitySibling =
        add(
            graph,
            "capacity-sibling",
            "capacity precedence",
            ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY);
    assertRetirementState(
        graph,
        capacitySibling,
        ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY,
        CanonicalObligationSchedulingState.DEFERRED_CAPACITY);
    assertThat(graph.canonicalTargetForObligation(capacity.obligationId())).isPresent();

    ProofObligation retired =
        add(
            graph,
            "retired",
            "retired target",
            ObligationOccurrenceSchedulingState.DEFERRED_FOCUSED_RECOVERY);
    assertRetirementState(
        graph,
        retired,
        ObligationOccurrenceSchedulingState.DEFERRED_FOCUSED_RECOVERY,
        CanonicalObligationSchedulingState.RETIRED);
  }

  private static void assertRetirementState(
      ProofGraphStore graph,
      ProofObligation obligation,
      ObligationOccurrenceSchedulingState expectedState,
      CanonicalObligationSchedulingState expectedCanonicalState) {
    assertThat(
            graph.retireDeferredCanonicalTarget(
                canonicalId(graph, obligation),
                obligation.obligationId(),
                expectedState,
                3,
                null))
        .satisfies(
            result -> {
              assertThat(result.code()).isEqualTo(CanonicalSchedulingTransitionCode.RETIRED);
              assertThat(result.schedulingState()).isEqualTo(expectedCanonicalState);
              assertThat(result.transitioned()).isTrue();
            });
  }

  private static ProofGraphStore graph() {
    return new ProofGraphStore(
        ObligationCanonicalizationTestFixtures.PROBLEM_HASH, ProofGraphPolicy.defaults());
  }

  private static ProofObligation add(
      ProofGraphStore graph,
      String obligationId,
      String statement,
      ObligationOccurrenceSchedulingState schedulingState) {
    ProofObligation obligation =
        ObligationCanonicalizationTestFixtures.obligation(
            obligationId,
            "route-" + obligationId,
            statement,
            statement,
            "family-" + statement.replace(' ', '-'));
    graph.addObligationCanonicalized(
        obligation,
        ObligationCanonicalizationTestFixtures.context(
                obligation,
                "route-" + obligationId,
                "family-" + statement.replace(' ', '-'),
                List.of("global"),
                "positive",
                Map.of(),
                0)
            .withSchedulingState(schedulingState));
    return obligation;
  }

  private static String canonicalId(ProofGraphStore graph, ProofObligation obligation) {
    return graph
        .canonicalTargetForObligation(obligation.obligationId())
        .orElseThrow()
        .canonicalTargetId();
  }
}
