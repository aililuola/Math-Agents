package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopFocusedRecoverySelectedBindingGateTest {
  private static final int ROUNDS = 10;
  private static final int RESTORE_ROUND = 5;

  @Test
  void tenRoundsGateEveryRecoverySourceAndPreservePolicyAcrossRestore(
      @TempDir Path directory) throws Exception {
    DesktopResearchCheckpointBlackBoxHarness harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-selected-binding-ten-round",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused");
    Map<String, Integer> selectedAllows = new LinkedHashMap<>();
    Map<String, Integer> unselectedBlocks = new LinkedHashMap<>();
    int exactSelected = 0;
    int exactUnselected = 0;
    int postRestoreLeaks = 0;
    try {
      harness.prepareProductionRoute();
      var bindings =
          DesktopDeferredReactivationTestSupport.enterFocusedWithSelectedAndUnselectedTargets(
              harness);
      int baselineTasks = DesktopProofGraphIssue005BlackBoxSupport.pendingTaskCount(harness);
      int baselineLeases =
          DesktopProofGraphIssue005BlackBoxSupport.canonicalTaskLeaseCount(harness);
      List<String> sources =
          List.of(
              "focused-recovery",
              "focused-skeptic",
              "family-bridge",
              "proof-debt-stall",
              "meta-review");
      sources.forEach(
          source -> {
            selectedAllows.put(source, 0);
            unselectedBlocks.put(source, 0);
          });

      for (int logicalRound = 0; logicalRound < ROUNDS; logicalRound++) {
        if (logicalRound == RESTORE_ROUND) {
          DesktopSolveCheckpoint checkpoint = harness.checkpointRoundTrip();
          DesktopResearchCheckpointBlackBoxHarness restored = harness.restored(checkpoint);
          harness.close();
          harness = restored;
        }
        int productionRound = 10 + logicalRound;
        DesktopProofGraphIssue005BlackBoxSupport.setRound(harness, productionRound);
        for (String source : sources) {
          int selectedDeferralsBefore =
              deferredCountFor(harness, bindings.selectedObligationId());
          DesktopProofGraphIssue005BlackBoxSupport.enqueue(
              harness,
              source + ":selected:" + logicalRound,
              "route-1",
              bindings.selectedObligationId(),
              "REPAIR");
          boolean selectedPassedGate =
              deferredCountFor(harness, bindings.selectedObligationId())
                  == selectedDeferralsBefore;
          boolean unselected =
              DesktopProofGraphIssue005BlackBoxSupport.enqueue(
                  harness,
                  source + ":unselected:" + logicalRound,
                  "route-1",
                  bindings.unselectedObligationId(),
                  "REPAIR");
          selectedAllows.compute(
              source, (ignored, count) -> count + (selectedPassedGate ? 1 : 0));
          unselectedBlocks.compute(source, (ignored, count) -> count + (unselected ? 0 : 1));
          if (logicalRound >= RESTORE_ROUND && unselected) {
            postRestoreLeaks++;
          }
        }
        int selectedExactDeferralsBefore =
            deferredCountFor(harness, bindings.selectedObligationId());
        DesktopProofGraphIssue005BlackBoxSupport.enqueue(
            harness,
            "exact-falsification:selected:" + logicalRound,
            "route-1",
            bindings.selectedObligationId(),
            "FALSIFY");
        exactSelected +=
            deferredCountFor(harness, bindings.selectedObligationId())
                    == selectedExactDeferralsBefore
                ? 1
                : 0;
        int unselectedExactDeferralsBefore =
            deferredCountFor(harness, bindings.unselectedObligationId());
        DesktopProofGraphIssue005BlackBoxSupport.enqueue(
            harness,
            "exact-falsification:unselected:" + logicalRound,
            "route-1",
            bindings.unselectedObligationId(),
            "FALSIFY");
        exactUnselected +=
            deferredCountFor(harness, bindings.unselectedObligationId())
                    == unselectedExactDeferralsBefore
                ? 1
                : 0;
      }

      List<DesktopSolveCheckpoint.ScheduledProofTask> newTasks =
          DesktopProofGraphIssue005BlackBoxSupport.pendingTasks(harness).stream()
              .map(DesktopSolveCheckpoint.ScheduledProofTask.class::cast)
              .skip(baselineTasks)
              .toList();
      long unrelatedLeaks =
          newTasks.stream()
              .filter(task -> !task.source().contains("exact-falsification"))
              .filter(task -> task.familyId().equals(bindings.unselectedFamilyId()))
              .count();
      long duplicateTasks =
          newTasks.size() - newTasks.stream().map(DesktopSolveCheckpoint.ScheduledProofTask::taskId).distinct().count();
      int newLeases =
          DesktopProofGraphIssue005BlackBoxSupport.canonicalTaskLeaseCount(harness) - baselineLeases;
      int taskLeaseLeaks = Math.max(0, newLeases - newTasks.size());

      assertThat(selectedAllows.values()).allMatch(count -> count == ROUNDS);
      assertThat(unselectedBlocks.values()).allMatch(count -> count == ROUNDS);
      assertThat(exactSelected).isEqualTo(ROUNDS);
      assertThat(exactUnselected).isEqualTo(ROUNDS);
      assertThat(unrelatedLeaks).isZero();
      assertThat(postRestoreLeaks).isZero();
      assertThat(duplicateTasks).isZero();
      assertThat(taskLeaseLeaks).isZero();

      System.out.println("SELECTED BINDING RECOVERY DIAGNOSTIC");
      System.out.println("---------------------------------------------------------------");
      print("ROUNDS", ROUNDS);
      print("RESTORE_ROUND", RESTORE_ROUND);
      print("SELECTED_FOCUSED_PROVER_ALLOWS", selectedAllows.get("focused-recovery"));
      print("SELECTED_FOCUSED_SKEPTIC_ALLOWS", selectedAllows.get("focused-skeptic"));
      print("SELECTED_FAMILY_BRIDGE_ALLOWS", selectedAllows.get("family-bridge"));
      print("SELECTED_PROOF_DEBT_ALLOWS", selectedAllows.get("proof-debt-stall"));
      print("SELECTED_META_REVIEW_ALLOWS", selectedAllows.get("meta-review"));
      print("UNSELECTED_FOCUSED_PROVER_BLOCKS", unselectedBlocks.get("focused-recovery"));
      print("UNSELECTED_FOCUSED_SKEPTIC_BLOCKS", unselectedBlocks.get("focused-skeptic"));
      print("UNSELECTED_FAMILY_BRIDGE_BLOCKS", unselectedBlocks.get("family-bridge"));
      print("UNSELECTED_PROOF_DEBT_BLOCKS", unselectedBlocks.get("proof-debt-stall"));
      print("UNSELECTED_META_REVIEW_BLOCKS", unselectedBlocks.get("meta-review"));
      print("EXACT_FALSIFICATION_SELECTED_ALLOWS", exactSelected);
      print("EXACT_FALSIFICATION_UNSELECTED_ALLOWS", exactUnselected);
      print("UNRELATED_RECOVERY_TASK_LEAKS", unrelatedLeaks);
      print("POST_RESTORE_RECOVERY_TASK_LEAKS", postRestoreLeaks);
      print("DUPLICATE_RECOVERY_TASKS", duplicateTasks);
      print("TASK_LEASE_LEAKS", taskLeaseLeaks);
      System.out.println("RESULT=PASS");
      System.out.println("---------------------------------------------------------------");
    } finally {
      harness.close();
    }
  }

  private static void print(String name, long value) {
    System.out.println(name + "=" + value);
  }

  private static int deferredCountFor(
      DesktopResearchCheckpointBlackBoxHarness harness, String obligationId) throws Exception {
    return (int)
        DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().stream()
            .filter(record -> record.obligationId().equals(obligationId))
            .count();
  }
}
