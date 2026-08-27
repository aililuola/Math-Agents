package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopFinalArtifactOmissionRetentionTest {
  @Test
  void finalResultOmissionDoesNotDeleteTraceFinding(@TempDir Path directory) throws Exception {
    String finding = "a1=15 triangle hitting-set structure";
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "final-omission-production",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.FINAL_JSON_OMISSION,
            finding)) {
      harness.runProductionExploration();
      assertThat(harness.researchLedger().activeFindings("route-1"))
          .singleElement()
          .extracting(record -> record.statement())
          .isEqualTo(finding);
      assertThat(harness.downstreamPromptContains(finding)).isTrue();
    }
  }
}
