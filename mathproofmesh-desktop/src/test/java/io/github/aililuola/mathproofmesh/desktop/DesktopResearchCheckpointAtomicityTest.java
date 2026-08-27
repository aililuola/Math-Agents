package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopResearchCheckpointAtomicityTest {
  @Test
  void structuredProjectionExistsBeforeProviderResultApplicationCompletes(@TempDir Path directory)
      throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "checkpoint-atomicity",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.TRUNCATED_RESULT,
            "finding retained before JSON repair")) {
      harness.runProductionExploration();
      assertThat(harness.researchLedger().findings()).hasSize(1);
      assertThat(Files.isRegularFile(directory.resolve("structured/research-checkpoints.json")))
          .isTrue();
      assertThat(Files.isRegularFile(directory.resolve("structured/research-finding-audit.json")))
          .isTrue();
    }
  }
}
