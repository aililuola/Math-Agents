package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopInterimFindingRetentionProductionTest {
  @Test
  void completeTraceFrameEntersLedgerAndNextRealPrompt(@TempDir Path directory)
      throws Exception {
    String finding = "interim exact support decomposition";
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "interim-retention",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            finding)) {
      harness.runProductionExploration();
      assertThat(harness.researchLedger().findings())
          .singleElement()
          .extracting(record -> record.statement())
          .isEqualTo(finding);
      assertThat(harness.downstreamPromptContains(finding)).isTrue();
    }
  }
}
