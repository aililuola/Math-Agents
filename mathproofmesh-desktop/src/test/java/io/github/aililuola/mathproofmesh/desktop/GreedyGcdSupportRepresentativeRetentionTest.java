package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GreedyGcdSupportRepresentativeRetentionTest {
  @Test
  void sameSupportMinimalRepresentativeSurvivesTheRealCoordinatorPath(@TempDir Path directory)
      throws Exception {
    String finding = "same-support minimal representative";
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "greedy-gcd-representative",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.BUDGET_EXHAUSTION,
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
