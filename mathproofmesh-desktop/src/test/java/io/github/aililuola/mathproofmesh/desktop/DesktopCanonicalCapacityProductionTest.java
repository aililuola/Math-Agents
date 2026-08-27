package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofgraph.BottleneckRelationType;
import io.github.aililuola.mathproofmesh.proofgraph.CanonicalObligationSchedulingState;
import io.github.aililuola.mathproofmesh.proofgraph.FocusedRecoveryActionType;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationCreationContext;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationOccurrenceSchedulingState;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationSourceType;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopCanonicalCapacityProductionTest {
  @Test
  void ninthRouteTargetIsRecordedButDeferredByProductionWriter(@TempDir Path directory)
      throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-capacity-production",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      ProofGraphStore graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
      int index = 0;
      while (graph.activeCanonicalTargetCount("route-1") < 8) {
        String id = "capacity-active-" + index++;
        graph.addObligation(
            DesktopProofGraphIssue005BlackBoxSupport.obligation(
                id,
                "route-1",
                "Prove independent capacity target " + id + ".",
                "prove independent capacity target " + id,
                "capacity-family-" + id,
                "capacity-plan-" + id));
      }
      var deferred =
          DesktopProofGraphIssue005BlackBoxSupport.obligation(
              "capacity-deferred",
              "route-1",
              "Prove the ninth independent route target.",
              "prove the ninth independent route target",
              "capacity-deferred-family",
              "capacity-deferred-plan");
      ObligationCreationContext context =
          new ObligationCreationContext(
              DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH,
              "route-1",
              "capacity-strategy",
              ObligationSourceType.STRATEGY_BLUEPRINT,
              "blueprint://capacity-deferred",
              List.of("global"),
              "positive",
              Map.of(),
              "capacity-deferred-family",
              "capacity-deferred-family",
              BottleneckRelationType.SHARES_UPSTREAM_BOTTLENECK,
              ObligationOccurrenceSchedulingState.ACTIVE,
              2);

      boolean admitted =
          DesktopProofGraphIssue005BlackBoxSupport.addControlledObligation(
              harness,
              deferred,
              context,
              FocusedRecoveryActionType.NEW_STRATEGY);

      assertThat(admitted).isFalse();
      assertThat(graph.activeCanonicalTargetCount("route-1")).isEqualTo(8);
      assertThat(graph.canonicalTargetForObligation(deferred.obligationId()).orElseThrow()
              .schedulingState())
          .isEqualTo(CanonicalObligationSchedulingState.DEFERRED_CAPACITY);
      assertThat(graph.deferredCanonicalProofDebt()).isPositive();
      assertThat(graph.globalCanonicalProofDebt())
          .isGreaterThanOrEqualTo(graph.deferredCanonicalProofDebt());
      assertThat(DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records())
          .hasSize(1);
      assertThat(graph.coreOpenWorkItems())
          .noneMatch(
              item ->
                  item.canonicalTargetIds().contains(
                      graph.canonicalTargetForObligation(deferred.obligationId())
                          .orElseThrow()
                          .canonicalTargetId()));
    }
  }
}
