package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopFinalJsonCompressionLossBlackBoxTest {
  private static final String FINDING = "a1=15 triangle hitting-set structure";

  @Test
  void reasoningFindingOmittedFromFinalJsonRemainsVisibleToTheNextProductionPrompt(
      @TempDir Path runDirectory) throws Exception {
    try (DesktopResearchCheckpointBlackBoxHarness harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            runDirectory,
            "final-json-finding-loss",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.FINAL_JSON_OMISSION,
            FINDING)) {
      harness.runProductionExploration();

      assertThat(harness.traceContains(FINDING)).isTrue();
      assertThat(harness.downstreamPromptContains(FINDING))
          .as("material trace finding omitted by final JSON must remain in route context")
          .isTrue();
    }
  }
}
