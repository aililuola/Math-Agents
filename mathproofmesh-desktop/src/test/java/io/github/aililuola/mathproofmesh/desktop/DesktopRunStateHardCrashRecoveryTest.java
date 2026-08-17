package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.runstate.DesktopMetadataProjectionService;
import io.github.aililuola.mathproofmesh.runstate.FileRunStateStore;
import io.github.aililuola.mathproofmesh.runstate.RunResultProjectionService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopRunStateHardCrashRecoveryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void committedAuthorityRepairsMissingProjectionsAfterSimulatedProcessDeath() {
    FileRunStateStore firstProcess = new FileRunStateStore(temporaryDirectory);
    var committed = DesktopRunStateTestSupport.failure("hard-crash", null, 11);
    firstProcess.compareAndSet("hard-crash", -1, committed, "first", 0);

    FileRunStateStore restoredProcess = new FileRunStateStore(temporaryDirectory);
    var restored = restoredProcess.load("hard-crash").orElseThrow();
    new RunResultProjectionService()
        .project(temporaryDirectory.resolve("hard-crash"), restored, java.util.Map.of());
    new DesktopMetadataProjectionService()
        .project(temporaryDirectory.resolve("hard-crash"), restored, java.util.Map.of());

    assertThat(restored.authority().authorityHash()).isEqualTo(committed.authority().authorityHash());
    assertThat(temporaryDirectory.resolve("hard-crash/structured/run_result.json")).isRegularFile();
    assertThat(temporaryDirectory.resolve("hard-crash/desktop_run.json")).isRegularFile();
    assertThat(restoredProcess.transitions("hard-crash")).hasSize(1);
    assertThat(Files.exists(temporaryDirectory.resolve("hard-crash/structured/.run-state.lock"))).isTrue();
  }
}
