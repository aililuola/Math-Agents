package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopFailedExecutionRecoverabilityBlackBoxTest {
  @TempDir Path temporaryDirectory;

  @Test
  void failedExecutionWithNonterminalCheckpointIsRecoverable() throws Exception {
    var fixture = RunStateBlackBoxFixtures.legacyRun(temporaryDirectory, "recoverable", 4, 30, 10);
    assertThat(fixture.repository().summary("recoverable"))
        .containsEntry("campaign_status", "RECOVERABLE")
        .containsEntry("resumable", true);
  }
}
