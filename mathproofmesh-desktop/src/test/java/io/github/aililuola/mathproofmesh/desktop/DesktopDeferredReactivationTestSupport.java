package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.proofgraph.BottleneckRelationType;
import io.github.aililuola.mathproofmesh.proofgraph.FocusedRecoveryActionType;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationCreationContext;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationOccurrenceSchedulingState;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationSourceType;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphControlMode;
import java.util.List;
import java.util.Map;

final class DesktopDeferredReactivationTestSupport {
  private DesktopDeferredReactivationTestSupport() {}

  record FocusedBindings(
      String selectedObligationId,
      String selectedFamilyId,
      String unselectedObligationId,
      String unselectedFamilyId) {}

  static FocusedBindings enterFocusedWithSelectedAndUnselectedTargets(
      DesktopResearchCheckpointBlackBoxHarness harness) throws Exception {
    for (String id : List.of("binding-a-1", "binding-a-2", "binding-b-1")) {
      addControlledTarget(
          harness,
          id,
          id.startsWith("binding-a") ? "binding-family-a" : "binding-family-b",
          FocusedRecoveryActionType.NEW_STRATEGY,
          0);
    }
    DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 0);
    DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 1);
    var monitor = DesktopProofGraphIssue005BlackBoxSupport.convergence(harness);
    assertThat(monitor.controlMode()).isEqualTo(ProofGraphControlMode.FOCUSED_RECOVERY);
    var plan = monitor.focusedRecoveryPlan().orElseThrow();
    var graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
    String selectedObligation =
        graph.rawObligationOccurrences().stream()
            .filter(occurrence -> plan.selectedCanonicalTargetIds().contains(occurrence.canonicalTargetId()))
            .map(occurrence -> occurrence.obligationId())
            .findFirst()
            .orElseThrow();
    String unselectedObligation =
        List.of("binding-a-1", "binding-a-2", "binding-b-1").stream()
            .filter(
                id ->
                    graph.canonicalTargetForObligation(id)
                        .map(target -> !plan.selects("", target.canonicalTargetId()))
                        .orElse(false))
            .findFirst()
            .orElseThrow();
    String unselectedFamily =
        graph.bottleneckFamilyForCanonical(
                graph.canonicalTargetForObligation(unselectedObligation)
                    .orElseThrow()
                    .canonicalTargetId())
            .orElseThrow()
            .familyId();
    return new FocusedBindings(
        selectedObligation,
        plan.selectedFamilyId(),
        unselectedObligation,
        unselectedFamily);
  }

  static ProofObligation addControlledTarget(
      DesktopResearchCheckpointBlackBoxHarness harness,
      String id,
      String family,
      FocusedRecoveryActionType action,
      int round)
      throws Exception {
    ProofObligation obligation =
        DesktopProofGraphIssue005BlackBoxSupport.obligation(
            id,
            "route-1",
            "Resolve controlled target " + id + ".",
            "resolve controlled target " + id,
            family,
            "plan-" + id);
    DesktopProofGraphIssue005BlackBoxSupport.addControlledObligation(
        harness, obligation, context(id, family, round), action);
    return obligation;
  }

  static ObligationCreationContext context(String id, String family, int round) {
    return new ObligationCreationContext(
        DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH,
        "route-1",
        "final-issue-005-patch",
        ObligationSourceType.STRATEGY_BLUEPRINT,
        "blueprint://final-issue-005/" + id,
        List.of("global"),
        "positive",
        Map.of(),
        family,
        family,
        BottleneckRelationType.SHARES_UPSTREAM_BOTTLENECK,
        ObligationOccurrenceSchedulingState.ACTIVE,
        round);
  }

  static int fillRouteCapacity(DesktopResearchCheckpointBlackBoxHarness harness, String prefix)
      throws Exception {
    var graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
    int created = 0;
    while (graph.activeCanonicalTargetCount("route-1") < 8) {
      String id = prefix + "-active-" + created++;
      graph.addObligation(
          DesktopProofGraphIssue005BlackBoxSupport.obligation(
              id,
              "route-1",
              "Resolve capacity target " + id + ".",
              "resolve capacity target " + id,
              prefix + "-family-" + id,
              prefix + "-plan-" + id));
    }
    return created;
  }
}
