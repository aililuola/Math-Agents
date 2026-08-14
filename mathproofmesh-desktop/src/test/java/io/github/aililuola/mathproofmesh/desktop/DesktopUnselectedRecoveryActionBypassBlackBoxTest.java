package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofgraph.BottleneckRelationType;
import io.github.aililuola.mathproofmesh.proofgraph.FocusedRecoveryActionType;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationCreationContext;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationOccurrenceSchedulingState;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationSourceType;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphControlMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopUnselectedRecoveryActionBypassBlackBoxTest {
  @Test
  void everyBindingRequiredRecoverySourceIsBlockedOutsideSelectedFamily(
      @TempDir Path directory) throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-unselected-recovery-bypass",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      addTarget(harness, "selected-a-1", "selected-family-a", 0);
      addTarget(harness, "selected-a-2", "selected-family-a", 0);
      addTarget(harness, "unselected-b-1", "unselected-family-b", 0);

      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 0);
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 1);
      var monitor = DesktopProofGraphIssue005BlackBoxSupport.convergence(harness);
      assertThat(monitor.controlMode()).isEqualTo(ProofGraphControlMode.FOCUSED_RECOVERY);
      assertThat(monitor.focusedRecoveryPlan()).isPresent();
      var graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
      var plan = monitor.focusedRecoveryPlan().orElseThrow();
      String selectedObligationId =
          graph.rawObligationOccurrences().stream()
              .filter(occurrence -> plan.selectedCanonicalTargetIds().contains(occurrence.canonicalTargetId()))
              .map(occurrence -> occurrence.obligationId())
              .findFirst()
              .orElseThrow();
      String unselectedObligationId =
          List.of("selected-a-1", "selected-a-2", "unselected-b-1").stream()
              .filter(
                  obligationId ->
                      graph.canonicalTargetForObligation(obligationId)
                          .map(target -> !plan.selects("", target.canonicalTargetId()))
                          .orElse(false))
              .findFirst()
              .orElseThrow();

      List<String> sources =
          List.of(
              "proof-debt-stall:black-box",
              "meta-review:black-box",
              "family-bridge:black-box",
              "focused-recovery:black-box",
              "focused-skeptic:black-box");
      List<Boolean> unselectedResults = new ArrayList<>();
      for (String source : sources) {
        unselectedResults.add(
            DesktopProofGraphIssue005BlackBoxSupport.enqueue(
                harness, source, "route-1", unselectedObligationId, "REPAIR"));
      }
      int selectedDeferralsBefore =
          deferredCountFor(harness, selectedObligationId);
      DesktopProofGraphIssue005BlackBoxSupport.enqueue(
          harness, "proof-debt-stall:selected", "route-1", selectedObligationId, "REPAIR");
      boolean selectedRecoveryAllowed =
          deferredCountFor(harness, selectedObligationId) == selectedDeferralsBefore;

      long actualBlocks = unselectedResults.stream().filter(result -> !result).count();
      long leaks = unselectedResults.size() - actualBlocks;
      System.out.println("UNSELECTED RECOVERY BYPASS BLACK-BOX DIAGNOSTIC");
      System.out.println("UNSELECTED_RECOVERY_ATTEMPTS=" + unselectedResults.size());
      System.out.println("EXPECTED_BLOCKS=" + unselectedResults.size());
      System.out.println("ACTUAL_BLOCKS=" + actualBlocks);
      System.out.println("UNRELATED_RECOVERY_TASK_LEAKS=" + leaks);
      System.out.println("SELECTED_RECOVERY_ACTION_ALLOWED=" + (selectedRecoveryAllowed ? 1 : 0));

      assertThat(actualBlocks).isEqualTo(unselectedResults.size());
      assertThat(leaks).isZero();
      assertThat(selectedRecoveryAllowed).isTrue();
    }
  }

  private static void addTarget(
      DesktopResearchCheckpointBlackBoxHarness harness,
      String obligationId,
      String familyId,
      int round)
      throws Exception {
    boolean admitted =
        DesktopProofGraphIssue005BlackBoxSupport.addControlledObligation(
            harness,
            DesktopProofGraphIssue005BlackBoxSupport.obligation(
                obligationId,
                "route-1",
                "Resolve target " + obligationId + ".",
                "resolve target " + obligationId,
                familyId,
                "plan-" + obligationId),
            new ObligationCreationContext(
                DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH,
                "route-1",
                "selected-binding-black-box",
                ObligationSourceType.STRATEGY_BLUEPRINT,
                "blueprint://selected-binding/" + obligationId,
                List.of("global"),
                "positive",
                Map.of(),
                familyId,
                familyId,
                BottleneckRelationType.SHARES_UPSTREAM_BOTTLENECK,
                ObligationOccurrenceSchedulingState.ACTIVE,
                round),
            FocusedRecoveryActionType.NEW_STRATEGY);
    assertThat(admitted).isTrue();
  }

  private static int deferredCountFor(
      DesktopResearchCheckpointBlackBoxHarness harness, String obligationId) throws Exception {
    return (int)
        DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().stream()
            .filter(record -> record.obligationId().equals(obligationId))
            .count();
  }
}
