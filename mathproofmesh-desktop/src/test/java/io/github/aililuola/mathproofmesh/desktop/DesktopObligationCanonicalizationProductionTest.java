package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofgraph.ObligationSourceType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopObligationCanonicalizationProductionTest {
  @Test
  void productionRouteAndBlueprintWritesCreateRawAndCanonicalRecords(@TempDir Path directory)
      throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-production-canonicalization",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      var graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);

      assertThat(graph.rawObligationOccurrences()).isNotEmpty();
      assertThat(graph.allCanonicalTargets()).isNotEmpty();
      assertThat(graph.rawObligationOccurrences())
          .extracting(record -> record.sourceType())
          .contains(
              ObligationSourceType.MAIN_GOAL,
              ObligationSourceType.ROUTE_BOTTLENECK,
              ObligationSourceType.STRATEGY_BLUEPRINT);
      assertThat(graph.rawObligationOccurrences())
          .allMatch(record -> !record.canonicalTargetId().isBlank());
    }
  }
}
