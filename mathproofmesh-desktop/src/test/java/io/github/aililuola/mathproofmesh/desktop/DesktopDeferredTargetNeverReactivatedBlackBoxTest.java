package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofgraph.BottleneckRelationType;
import io.github.aililuola.mathproofmesh.proofgraph.CanonicalObligationSchedulingState;
import io.github.aililuola.mathproofmesh.proofgraph.FocusedRecoveryActionType;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationCreationContext;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationOccurrenceSchedulingState;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationSourceType;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopDeferredTargetNeverReactivatedBlackBoxTest {
  @Test
  void releasedCapacityReactivatesPreviouslyDeferredTarget(@TempDir Path directory)
      throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-deferred-target-reactivation-black-box",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      var graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
      int index = 0;
      while (graph.activeCanonicalTargetCount("route-1") < 8) {
        String id = "reactivation-active-" + index++;
        graph.addObligation(
            DesktopProofGraphIssue005BlackBoxSupport.obligation(
                id,
                "route-1",
                "Prove independent reactivation target " + id + ".",
                "prove independent reactivation target " + id,
                "reactivation-family-" + id,
                "reactivation-plan-" + id));
      }
      var deferred =
          DesktopProofGraphIssue005BlackBoxSupport.obligation(
              "reactivation-deferred",
              "route-1",
              "Prove the deferred reactivation target.",
              "prove the deferred reactivation target",
              "reactivation-deferred-family",
              "reactivation-deferred-plan");
      boolean admitted =
          DesktopProofGraphIssue005BlackBoxSupport.addControlledObligation(
              harness,
              deferred,
              new ObligationCreationContext(
                  DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH,
                  "route-1",
                  "deferred-reactivation-black-box",
                  ObligationSourceType.STRATEGY_BLUEPRINT,
                  "blueprint://deferred-reactivation/black-box",
                  List.of("global"),
                  "positive",
                  Map.of(),
                  "reactivation-deferred-family",
                  "reactivation-deferred-family",
                  BottleneckRelationType.SHARES_UPSTREAM_BOTTLENECK,
                  ObligationOccurrenceSchedulingState.ACTIVE,
                  0),
              FocusedRecoveryActionType.NEW_STRATEGY);
      assertThat(admitted).isFalse();
      assertThat(DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records())
          .hasSize(1);

      DesktopProofGraphIssue005BlackBoxSupport.closeFirstCanonicalTarget(harness);
      int availableCapacity = 8 - graph.activeCanonicalTargetCount("route-1");
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 1);
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 2);

      var target = graph.canonicalTargetForObligation(deferred.obligationId()).orElseThrow();
      int actualReactivations =
          target.schedulingState() == CanonicalObligationSchedulingState.ACTIVE ? 1 : 0;
      long reactivationTasks =
          DesktopProofGraphIssue005BlackBoxSupport.pendingTasks(harness).stream()
              .map(DesktopSolveCheckpoint.ScheduledProofTask.class::cast)
              .filter(task -> task.source().startsWith("deferred-reactivation:"))
              .count();
      int stillUnscheduled = actualReactivations == 0 ? 1 : 0;

      System.out.println("DEFERRED TARGET REACTIVATION BLACK-BOX DIAGNOSTIC");
      System.out.println("DEFERRED_TARGETS_BEFORE_RELEASE=1");
      System.out.println("AVAILABLE_CAPACITY_AFTER_RELEASE=" + availableCapacity);
      System.out.println("EXPECTED_REACTIVATIONS=1");
      System.out.println("ACTUAL_REACTIVATIONS=" + actualReactivations);
      System.out.println("DEFERRED_TARGET_STILL_UNSCHEDULED=" + stillUnscheduled);
      System.out.println("REACTIVATION_TASKS=" + reactivationTasks);

      assertThat(availableCapacity).isEqualTo(1);
      assertThat(actualReactivations).isEqualTo(1);
      assertThat(stillUnscheduled).isZero();
      assertThat(reactivationTasks).isEqualTo(1);
    }
  }
}
