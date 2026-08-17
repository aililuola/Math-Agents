package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.runstate.FileRunStateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopRunStateProductionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void canonicalAuthorityWinsOverStaleDesktopAndResultFiles() throws Exception {
    DesktopPaths paths = DesktopTestSupport.paths(temporaryDirectory);
    Path run = paths.safeRunDirectory("production-state");
    Files.createDirectories(run.resolve("structured"));
    Files.writeString(run.resolve("problem.txt"), "Prove P.");
    var state = DesktopRunStateTestSupport.failure("production-state", null, 7);
    new FileRunStateStore(paths.runs()).compareAndSet("production-state", -1, state, "test", 0);
    RunRepository repository = new RunRepository(paths, DesktopTestSupport.MAPPER);
    Instant now = Instant.now();
    repository.writeMetadata(
        new DesktopRunMetadata("production-state", "smoke", "running", now, now, "solve", null, 0));
    repository.writeResult("production-state", java.util.Map.of("execution_status", "completed"));
    assertThat(repository.summary("production-state"))
        .containsEntry("execution_status", "FAILED")
        .containsEntry("campaign_status", "RECOVERABLE")
        .containsEntry("total_calls", 7L);
  }
}
