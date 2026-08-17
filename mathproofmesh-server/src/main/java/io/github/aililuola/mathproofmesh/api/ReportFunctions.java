package io.github.aililuola.mathproofmesh.api;

import io.github.aililuola.mathproofmesh.api.RunApiModels.RouteView;
import io.github.aililuola.mathproofmesh.api.RunApiModels.RunView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class ReportFunctions {
  private ReportFunctions() {}

  public static RunReport writeRunReport(Path runDirectory, RunView run, List<RouteView> routes) {
    return writeRunReport(runDirectory, run, routes, "");
  }

  public static RunReport writeRunReport(
      Path runDirectory, RunView run, List<RouteView> routes, String reportBody) {
    String markdown = render(run, routes, reportBody);
    byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
    String hash = HexFormat.of().formatHex(sha256().digest(bytes));
    Path reports = runDirectory.resolve("reports");
    Path target = reports.resolve("run_report.md");
    try {
      Files.createDirectories(reports);
      Path temporary = Files.createTempFile(reports, ".run-report-", ".tmp");
      try {
        Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
        try {
          Files.move(
              temporary,
              target,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
          Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("run report could not be written", exception);
    }
    return new RunReport(
        "artifact://sha256/" + hash, hash, "text/markdown; charset=UTF-8", bytes);
  }

  public static String render(RunView run, List<RouteView> routes) {
    return render(run, routes, "");
  }

  public static String render(RunView run, List<RouteView> routes, String reportBody) {
    long passedRoutes = routes.stream().filter(route -> "verified".equals(route.status())).count();
    String header =
        String.join(
            "\n",
            "# MathProofMesh Run Report",
            "",
            "- Run: `" + run.runId() + "`",
            "- Status: `" + run.status() + "`",
            "- Execution status: `" + run.executionStatus() + "`",
            "- Mathematical status: `" + run.mathStatus() + "`",
            "- Usage status: `" + run.usageStatus() + "`",
            "- Campaign status: `" + run.campaignStatus() + "`",
            "- Report status: `" + run.reportStatus() + "`",
            "- Terminal reason: `" + run.terminalReason() + "`",
            "- Recoverable: `" + run.recoverable() + "`",
            "- Authority state hash: `" + run.authorityStateHash() + "`",
            "- Current stage: `" + run.currentStage() + "`",
            "- Provider calls: " + run.providerCalls(),
            "- Total tokens: " + run.totalUsage().totalTokens(),
            "- Estimated cost (USD): " + run.totalUsage().estimatedCostUsd(),
            "- Verified local claims: " + run.verifiedLocalClaimIds().size(),
            "- Independently passed routes: " + passedRoutes,
            "- Trace ID: `" + run.traceId() + "`",
            "",
            "## Summary",
            "",
            run.summary(),
            "",
            "The counts above remain distinct: a verified local claim does not imply",
            "that a route, dependency closure, or final proof has passed.",
            "");
    String details = reportBody == null ? "" : reportBody.strip();
    return details.isEmpty() ? header : header + "\n\n## Detailed Result\n\n" + details + "\n";
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record RunReport(String reference, String hash, String mediaType, byte[] bytes) {
    public RunReport {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}
