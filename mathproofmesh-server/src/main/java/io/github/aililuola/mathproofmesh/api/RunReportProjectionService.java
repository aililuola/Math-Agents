package io.github.aililuola.mathproofmesh.api;

import io.github.aililuola.mathproofmesh.api.RunApiModels.RouteView;
import io.github.aililuola.mathproofmesh.api.RunApiModels.RunView;
import io.github.aililuola.mathproofmesh.runstate.RunProjectionReceipt;
import io.github.aililuola.mathproofmesh.runstate.RunReportStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Best-effort report projection that returns a failure receipt without mutating authority. */
public final class RunReportProjectionService {
  public Projection project(
      Path runDirectory, RunView view, List<RouteView> routes, String reportBody) {
    try {
      ReportFunctions.RunReport report =
          ReportFunctions.writeRunReport(runDirectory, view, routes, reportBody);
      RunReportStatus status = view.recoverable() ? RunReportStatus.PARTIAL : RunReportStatus.FINAL;
      return new Projection(
          report,
          new RunProjectionReceipt(
              view.authorityStateHash(),
              report.reference(),
              report.hash(),
              status,
              List.of(),
              Instant.now()));
    } catch (RuntimeException exception) {
      return new Projection(
          null,
          new RunProjectionReceipt(
              view.authorityStateHash(),
              "",
              "",
              RunReportStatus.PROJECTION_FAILED,
              List.of(exception.getClass().getSimpleName()),
              Instant.now()));
    }
  }

  public record Projection(ReportFunctions.RunReport report, RunProjectionReceipt receipt) {}
}
