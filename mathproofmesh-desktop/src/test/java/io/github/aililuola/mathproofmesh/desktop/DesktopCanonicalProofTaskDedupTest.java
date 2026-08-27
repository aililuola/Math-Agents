package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopCanonicalProofTaskDedupTest {
  @Test
  void automaticAliasesAcquireOneFamilyTaskLease(@TempDir Path directory) throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-task-dedup",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      var graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
      String obligationId =
          graph.rawObligationOccurrences().stream()
              .filter(record -> !record.bottleneckFamilyId().isBlank())
              .findFirst()
              .orElseThrow()
              .obligationId();
      for (String source : List.of("proof-debt", "meta-review", "inspiration", "bridge")) {
        DesktopProofGraphIssue005BlackBoxSupport.enqueue(
            harness, source, "route-1", obligationId, "DEEPEN");
      }

      assertThat(DesktopProofGraphIssue005BlackBoxSupport.pendingTasks(harness)).hasSize(1);
      DesktopSolveCheckpoint.ScheduledProofTask task =
          (DesktopSolveCheckpoint.ScheduledProofTask)
              DesktopProofGraphIssue005BlackBoxSupport.pendingTasks(harness).getFirst();
      assertThat(task.scope())
          .isEqualTo(
              io.github.aililuola.mathproofmesh.proofgraph.ProofTaskScope.BOTTLENECK_FAMILY);
      assertThat(task.familyId()).isNotBlank();
      assertThat(task.actionKey()).isEqualTo("repair");
    }
  }
}
