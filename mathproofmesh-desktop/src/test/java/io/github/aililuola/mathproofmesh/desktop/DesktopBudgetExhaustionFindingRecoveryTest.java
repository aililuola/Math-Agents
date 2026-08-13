package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopBudgetExhaustionFindingRecoveryTest {
  @Test
  void budgetRecoveryPersistsFindingBeforeOneArtifactRecoveryCall(@TempDir Path directory)
      throws Exception {
    String finding = "budget-bound public finding";
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "budget-recovery-production",
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
