package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopResultMetadataSplitBrainBlackBoxTest {
  @TempDir Path temporaryDirectory;

  @Test
  void canonicalStateWinsWhenMetadataStillSaysRunning() throws Exception {
    var fixture = RunStateBlackBoxFixtures.legacyRun(temporaryDirectory, "split-brain", 3, 20, 10);
    Instant now = Instant.now();
    fixture.repository().writeMetadata(
        new DesktopRunMetadata(
            "split-brain", "smoke", "running", now, now, "solve", null,
            ProcessHandle.current().pid()));
    assertThat(fixture.repository().summary("split-brain"))
        .containsEntry("execution_status", "FAILED")
        .containsEntry("campaign_status", "RECOVERABLE");
  }
}
