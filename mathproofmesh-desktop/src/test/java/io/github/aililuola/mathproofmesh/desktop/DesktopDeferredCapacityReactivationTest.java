package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofgraph.CanonicalObligationSchedulingState;
import io.github.aililuola.mathproofmesh.proofgraph.DeferredExpansionStatus;
import io.github.aililuola.mathproofmesh.proofgraph.FocusedRecoveryActionType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopDeferredCapacityReactivationTest {
  @Test
  void releasedSlotReactivatesExactlyOneTargetWithoutChangingGlobalDebt(
      @TempDir Path directory) throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-capacity-reactivation",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      DesktopDeferredReactivationTestSupport.fillRouteCapacity(harness, "capacity-reactivation");
      var graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
      int activeAtCapacity = graph.activeCanonicalTargetCount("route-1");
      var deferred =
          DesktopDeferredReactivationTestSupport.addControlledTarget(
              harness,
              "capacity-reactivation-deferred",
              "capacity-reactivation-deferred-family",
              FocusedRecoveryActionType.NEW_STRATEGY,
              0);
      var record =
          DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().getFirst();
      assertThat(record.status()).isEqualTo(DeferredExpansionStatus.DEFERRED);
      long capacityDeferredRecords =
          DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().stream()
              .filter(item -> item.obligationId().equals(deferred.obligationId()))
              .count();
      DesktopProofGraphIssue005BlackBoxSupport.closeFirstCanonicalTarget(harness);
      int activeAfterRelease = graph.activeCanonicalTargetCount("route-1");
      int capacityReleaseEvents = activeAtCapacity - activeAfterRelease;
      double globalBefore = graph.globalCanonicalProofDebt();
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 1);

      var target = graph.canonicalTargetForObligation(deferred.obligationId()).orElseThrow();
      var finalRecord =
          DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().getFirst();
      long tasks =
          DesktopProofGraphIssue005BlackBoxSupport.pendingTasks(harness).stream()
              .map(DesktopSolveCheckpoint.ScheduledProofTask.class::cast)
              .filter(task -> task.source().equals("deferred-reactivation:" + record.deferredId()))
              .count();
      long uniqueTasks =
          DesktopProofGraphIssue005BlackBoxSupport.pendingTasks(harness).stream()
              .map(DesktopSolveCheckpoint.ScheduledProofTask.class::cast)
              .filter(task -> task.source().equals("deferred-reactivation:" + record.deferredId()))
              .map(DesktopSolveCheckpoint.ScheduledProofTask::taskId)
              .distinct()
              .count();
      long reactivatedRecords =
          DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().stream()
              .filter(item -> item.obligationId().equals(deferred.obligationId()))
              .filter(item -> item.status() == DeferredExpansionStatus.REACTIVATED)
              .count();
      long duplicateReactivations = Math.max(0L, reactivatedRecords - 1L);
      int falseDebtDecreases = graph.globalCanonicalProofDebt() == globalBefore ? 0 : 1;

      assertThat(activeAtCapacity).isEqualTo(8);
      assertThat(capacityDeferredRecords).isEqualTo(1);
      assertThat(capacityReleaseEvents).isEqualTo(1);
      assertThat(finalRecord.status()).isEqualTo(DeferredExpansionStatus.REACTIVATED);
      assertThat(reactivatedRecords).isEqualTo(1);
      assertThat(duplicateReactivations).isZero();
      assertThat(target.schedulingState()).isEqualTo(CanonicalObligationSchedulingState.ACTIVE);
      assertThat(graph.activeCanonicalTargetCount("route-1")).isEqualTo(8);
      assertThat(tasks).isEqualTo(1);
      assertThat(uniqueTasks).isEqualTo(1);
      assertThat(falseDebtDecreases).isZero();

      System.out.println("CAPACITY REACTIVATION DIAGNOSTIC");
      System.out.println("---------------------------------------------------------------");
      print("ACTIVE_TARGETS_AT_CAPACITY", activeAtCapacity);
      print("CAPACITY_DEFERRED_RECORDS", capacityDeferredRecords);
      print("CAPACITY_RELEASE_EVENTS", capacityReleaseEvents);
      print("REACTIVATED_AFTER_RELEASE", reactivatedRecords);
      print("ACTIVE_TARGETS_AFTER_REACTIVATION", graph.activeCanonicalTargetCount("route-1"));
      print("DUPLICATE_REACTIVATIONS", duplicateReactivations);
      print("DUPLICATE_REACTIVATION_TASKS", tasks - uniqueTasks);
      print("GLOBAL_DEBT_FALSE_DECREASES", falseDebtDecreases);
      System.out.println("GLOBAL_DEBT_BEFORE_REACTIVATION=" + globalBefore);
      System.out.println("GLOBAL_DEBT_AFTER_REACTIVATION=" + graph.globalCanonicalProofDebt());
      System.out.println("RESULT=PASS");
      System.out.println("---------------------------------------------------------------");
    }
  }

  private static void print(String name, long value) {
    System.out.println(name + "=" + value);
  }
}
