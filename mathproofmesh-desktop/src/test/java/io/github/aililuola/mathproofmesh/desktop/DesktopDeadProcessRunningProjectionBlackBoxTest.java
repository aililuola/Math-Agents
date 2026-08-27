package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopDeadProcessRunningProjectionBlackBoxTest {
  @TempDir Path temporaryDirectory;

  @Test
  void deadProcessCannotOverrideDurableFailureEvidence() throws Exception {
    var fixture = RunStateBlackBoxFixtures.legacyRun(temporaryDirectory, "dead-process", 3, 20, 10);
    Instant now = Instant.now();
    fixture.repository().writeMetadata(
        new DesktopRunMetadata(
            "dead-process", "smoke", "running", now, now, "solve", null, Long.MAX_VALUE));
    assertThat(fixture.repository().summary("dead-process"))
        .containsEntry("execution_status", "FAILED")
        .containsEntry("campaign_status", "RECOVERABLE");
  }
}
