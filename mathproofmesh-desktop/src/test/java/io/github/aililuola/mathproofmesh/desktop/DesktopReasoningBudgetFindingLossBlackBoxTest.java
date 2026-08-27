package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopReasoningBudgetFindingLossBlackBoxTest {
  private static final String FINDING = "same-support minimal representative";

  @Test
  void reasoningFindingSurvivesBudgetExhaustionIntoTheNextProductionPrompt(
      @TempDir Path runDirectory) throws Exception {
    try (DesktopResearchCheckpointBlackBoxHarness harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            runDirectory,
            "budget-finding-loss",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.BUDGET_EXHAUSTION,
            FINDING)) {
      harness.runProductionExploration();

      assertThat(harness.traceContains(FINDING)).isTrue();
      assertThat(harness.downstreamPromptContains(FINDING))
          .as("material trace finding must be visible to the next production prompt")
          .isTrue();
    }
  }
}
