package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSharedBottleneckTaskExplosionBlackBoxTest {
  @Test
  void oneSharedBottleneckProducesOneAutomaticFamilyTask(@TempDir Path directory)
      throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-shared-bottleneck-baseline",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      ProofGraphStore graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
      List<String> variants =
          List.of(
              "After deleting p, all earlier terms remain hit.",
              "After removal of p, every earlier term is still hit.",
              "Deleting the selected prime p preserves coverage of every earlier term.");
      for (int index = 0; index < variants.size(); index++) {
        graph.addObligation(
            DesktopProofGraphIssue005BlackBoxSupport.obligation(
                "shared-bottleneck-" + index,
                "route-" + (index + 1),
                variants.get(index),
                "after deleting p all earlier terms remain hit",
                "same-support-representative",
                "alternative-plan-" + index));
      }

      int sharedGroups = graph.findSharedBottlenecks(2).size();
      int before = DesktopProofGraphIssue005BlackBoxSupport.pendingTaskCount(harness);
      for (String source :
          List.of("proof-debt-repair", "meta-review", "inspiration", "bridge-request")) {
        DesktopProofGraphIssue005BlackBoxSupport.enqueue(
            harness, source, "route-1", "shared-bottleneck-0", "DEEPEN");
      }
      int actualAutomaticTasks =
          DesktopProofGraphIssue005BlackBoxSupport.pendingTaskCount(harness) - before;

      System.out.println("SHARED BOTTLENECK TASK EXPLOSION BASELINE");
      System.out.println("SHARED_BOTTLENECK_GROUPS_DETECTED=" + sharedGroups);
      System.out.println("EXPECTED_FAMILY_TASKS=1");
      System.out.println("ACTUAL_AUTOMATIC_TASKS=" + actualAutomaticTasks);

      assertThat(sharedGroups).isEqualTo(1);
      assertThat(actualAutomaticTasks).isEqualTo(1);
    }
  }
}
