package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopBottleneckFamilyProductionTest {
  @Test
  void routeAndBlueprintTargetsUseOneSchedulingOnlyBottleneckFamily(@TempDir Path directory)
      throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-production-family",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      var graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);

      assertThat(graph.activeBottleneckFamilies()).isNotEmpty();
      assertThat(graph.activeBottleneckFamilies())
          .allMatch(family -> !family.canonicalTargetIds().isEmpty())
          .allMatch(family -> !family.representativeCanonicalTargetId().isBlank());
      assertThat(graph.obligations())
          .allMatch(obligation -> !obligation.status().equals("closed"));
    }
  }
}
