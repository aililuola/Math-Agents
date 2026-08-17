package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.runstate.FileRunStateStore;
import io.github.aililuola.mathproofmesh.runstate.RunProjectionSnapshot;
import io.github.aililuola.mathproofmesh.runstate.RunReportStatus;
import io.github.aililuola.mathproofmesh.runstate.RunStateSnapshot;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopRunStateReportRepairTest {
  @TempDir Path temporaryDirectory;

  @Test
  void reportRepairChangesOnlyProjectionHash() {
    var authority = DesktopRunStateTestSupport.failure("report-repair", null, 7);
    RunStateSnapshot failed = projection(authority, RunReportStatus.PROJECTION_FAILED, "");
    FileRunStateStore store = new FileRunStateStore(temporaryDirectory);
    store.compareAndSet("report-repair", -1, failed, "test", 0);
    RunStateSnapshot repaired = projection(authority, RunReportStatus.PARTIAL, "reports/run_report.md");
    store.compareAndSet("report-repair", 0, repaired, "test", 0);
    assertThat(repaired.authority()).isEqualTo(failed.authority());
    assertThat(repaired.projection().reportStatus()).isEqualTo(RunReportStatus.PARTIAL);
    assertThat(repaired.stateHash()).isNotEqualTo(failed.stateHash());
  }

  private static RunStateSnapshot projection(
      RunStateSnapshot source, RunReportStatus status, String reference) {
    return RunStateSnapshot.create(
        source.authority(),
        new RunProjectionSnapshot(
            source.authority().authorityHash(),
            status,
            "",
            "",
            "",
            "",
            reference,
            reference.isBlank() ? "" : "d".repeat(64),
            0,
            status == RunReportStatus.PROJECTION_FAILED ? List.of("IO_FAILURE") : List.of(),
            null),
        source.reconciliationStatus(),
        source.conflicts(),
        Instant.now());
  }
}
