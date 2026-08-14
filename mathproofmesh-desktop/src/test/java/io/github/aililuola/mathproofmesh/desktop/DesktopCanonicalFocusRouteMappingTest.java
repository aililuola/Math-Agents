package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopCanonicalFocusRouteMappingTest {
  @Test
  void scheduledFamilyTaskMapsRawCanonicalAndFamilyFocusOntoTheRoute(@TempDir Path directory)
      throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-focus-route-mapping",
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
      assertThat(
              DesktopProofGraphIssue005BlackBoxSupport.enqueue(
                  harness, "proof-debt", "route-1", obligationId, "DEEPEN"))
          .isTrue();
      assertThat(DesktopProofGraphIssue005BlackBoxSupport.schedulePendingTask(harness)).isTrue();
      Map<String, String> focus =
          DesktopProofGraphIssue005BlackBoxSupport.firstRouteFocus(harness);

      assertThat(focus.get("raw")).isEqualTo(obligationId);
      assertThat(focus.get("canonical")).startsWith("canonical_");
      assertThat(focus.get("family")).startsWith("family_");
    }
  }
}
