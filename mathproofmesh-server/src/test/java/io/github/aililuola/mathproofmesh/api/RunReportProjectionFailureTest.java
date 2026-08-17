package io.github.aililuola.mathproofmesh.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.runstate.RunReportStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunReportProjectionFailureTest {
  @TempDir Path temporaryDirectory;

  @Test
  void reportIoFailureReturnsProjectionFailureReceipt() throws Exception {
    RunApiService service = RunStateApiProjectionTest.service(temporaryDirectory.resolve("runs"));
    var view = service.solve(new SolveRequest("Prove P.", "report-source", null, "smoke"));
    Path blocked = temporaryDirectory.resolve("blocked");
    Files.writeString(blocked, "not a directory");
    var projection = new RunReportProjectionService().project(blocked, view, java.util.List.of(), "");
    assertThat(projection.receipt().status()).isEqualTo(RunReportStatus.PROJECTION_FAILED);
    assertThat(service.status("report-source").authorityStateHash())
        .isEqualTo(view.authorityStateHash());
  }
}
