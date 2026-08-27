package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopActivityTailCannotOverrideRunStateBlackBoxTest {
  @TempDir Path temporaryDirectory;

  @Test
  void staleRunningActivityCannotBecomeStatusAuthority() throws Exception {
    var fixture = RunStateBlackBoxFixtures.legacyRun(temporaryDirectory, "activity-tail", 2, 10, 10);
    Files.writeString(
        fixture.runDirectory().resolve("activity.jsonl"),
        "{\"sequence\":1,\"event_type\":\"agent_started\",\"status\":\"running\",\"task_id\":\"agent:a\"}\n");
    assertThat(fixture.repository().summary("activity-tail"))
        .containsEntry("execution_status", "FAILED")
        .containsEntry("campaign_status", "RECOVERABLE");
  }
}
