package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.runstate.FileRunStateStore;
import io.github.aililuola.mathproofmesh.runstate.RunExecutionStatus;
import io.github.aililuola.mathproofmesh.runstate.RunMathematicalProgressSnapshot;
import io.github.aililuola.mathproofmesh.runstate.RunProjectionSnapshot;
import io.github.aililuola.mathproofmesh.runstate.RunReportStatus;
import io.github.aililuola.mathproofmesh.runstate.RunStateEvidenceBundle;
import io.github.aililuola.mathproofmesh.runstate.RunStateReconciler;
import io.github.aililuola.mathproofmesh.runstate.RunStateSnapshot;
import io.github.aililuola.mathproofmesh.runstate.RunTerminalReason;
import io.github.aililuola.mathproofmesh.runstate.RunUsageEvidence;
import io.github.aililuola.mathproofmesh.runstate.RunUsageEvidenceSource;
import io.github.aililuola.mathproofmesh.runstate.RunUsageSnapshot;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Deterministically upgrades legacy desktop projections without invoking a provider or tool. */
final class LegacyRunStateMigrator {
  private final ObjectMapper mapper;
  private final FileRunStateStore store;

  LegacyRunStateMigrator(Path runRoot, ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.store = new FileRunStateStore(runRoot);
  }

  RunStateSnapshot migrate(Path runDirectory, DesktopRunMetadata metadata) {
    String runId = Objects.requireNonNull(runDirectory.getFileName(), "run directory name").toString();
    Path checkpointPath = runDirectory.resolve("structured/desktop-solve-state.json");
    Path resultPath = runDirectory.resolve("structured/run_result.json");
    JsonNode checkpoint = read(checkpointPath);
    JsonNode result = read(resultPath);
    String problem = readText(runDirectory.resolve("problem.txt"));
    String problemHash =
        text(checkpoint.path("problemHash"), CanonicalJson.stableHash(problem.isBlank() ? runId : problem));
    RunExecutionStatus execution = execution(metadata, result);
    RunUsageSnapshot checkpointUsage = usage(checkpoint.path("usageTotals"));
    RunUsageSnapshot resultUsage = resultUsage(result);
    RunUsageSnapshot selected =
        checkpointUsage.totalTokens() > 0L || checkpointUsage.providerCalls() > 0L
            ? checkpointUsage
            : resultUsage;
    RunMathematicalProgressSnapshot progress = progress(checkpoint);
    RunProjectionSnapshot projection =
        new RunProjectionSnapshot(
            "",
            Files.isRegularFile(runDirectory.resolve("reports/run_report.md"))
                ? RunReportStatus.STALE
                : result.isMissingNode() || result.isEmpty()
                    ? RunReportStatus.ABSENT
                    : RunReportStatus.PARTIAL,
            Files.isRegularFile(resultPath) ? "structured/run_result.json" : "",
            hash(resultPath),
            Files.isRegularFile(runDirectory.resolve("desktop_run.json")) ? "desktop_run.json" : "",
            hash(runDirectory.resolve("desktop_run.json")),
            Files.isRegularFile(runDirectory.resolve("reports/run_report.md"))
                ? "reports/run_report.md"
                : "",
            hash(runDirectory.resolve("reports/run_report.md")),
            0L,
            List.of("LEGACY_STATE_MIGRATED"),
            null);
    RunStateSnapshot state =
        new RunStateReconciler()
            .reconcile(
                new RunStateEvidenceBundle(
                    runId,
                    problemHash,
                    "legacy-attempt-" + CanonicalJson.stableHash(runId).substring(0, 16),
                    execution,
                    RunTerminalReason.NONE,
                    text(checkpoint.path("currentStage"), "report"),
                    Files.isRegularFile(checkpointPath),
                    checkpoint.path("terminal").asBoolean(false),
                    Files.isRegularFile(checkpointPath)
                        ? "structured/desktop-solve-state.json"
                        : "",
                    hash(checkpointPath),
                    hash(runDirectory.resolve("structured/proof-graph.json")),
                    progress,
                    List.of(
                        RunUsageEvidence.aggregate(
                            selected == checkpointUsage
                                ? RunUsageEvidenceSource.SEMANTIC_CHECKPOINT
                                : RunUsageEvidenceSource.RESULT_PROJECTION,
                            selected,
                            selected == checkpointUsage ? "semantic-checkpoint" : "run-result")),
                    null,
                    projection,
                    metadata == null ? Instant.now() : metadata.updatedAt()))
            .state();
    return store.compareAndSet(runId, -1L, state, "legacy-migrator", 0L);
  }

  private RunMathematicalProgressSnapshot progress(JsonNode checkpoint) {
    boolean problem = checkpoint.hasNonNull("problem") || checkpoint.hasNonNull("problemHash");
    int verified =
        checkpoint.path("claimLifecycle").path("records").isObject()
            ? countText(checkpoint.path("claimLifecycle").path("records"), "VERIFIED")
            : 0;
    int refuted =
        checkpoint.path("claimLifecycle").path("records").isObject()
            ? countText(checkpoint.path("claimLifecycle").path("records"), "REFUTED")
            : 0;
    int obligations =
        checkpoint.path("proofGraph").path("obligations").isObject()
            ? checkpoint.path("proofGraph").path("obligations").size()
            : 0;
    boolean finalProof = checkpoint.hasNonNull("finalProof");
    boolean finalReviewPassed =
        java.util.Set.of("PASS", "pass")
            .contains(checkpoint.path("finalReview").path("verdict").asText());
    boolean integrity =
        !checkpoint.hasNonNull("finalReview")
            || checkpoint.path("finalReview").path("problemIntegrityOk").asBoolean(true);
    return new RunMathematicalProgressSnapshot(
        verified,
        refuted,
        obligations,
        problem,
        checkpoint.path("admittedStrategies").size() > 0,
        checkpoint.path("routes").size() > 0,
        checkpoint.hasNonNull("proofGraph") || obligations > 0,
        checkpoint.path("researchCheckpoints").path("records").size() > 0,
        checkpoint.path("computations").size() > 0
            || checkpoint.path("computationExecutions").path("records").size() > 0,
        finalProof,
        checkpoint.path("finalValidationPassed").asBoolean(false),
        finalReviewPassed,
        integrity);
  }

  private static RunExecutionStatus execution(
      DesktopRunMetadata metadata, JsonNode result) {
    String explicit = result.path("execution_status").asText("");
    if (!explicit.isBlank()) {
      return switch (explicit) {
        case "COMPLETED", "SUCCEEDED", "completed", "succeeded" ->
            RunExecutionStatus.SUCCEEDED;
        case "CANCELLED", "cancelled" -> RunExecutionStatus.CANCELLED;
        case "INTERRUPTED", "NETWORK_INTERRUPTED", "network_interrupted", "interrupted" ->
            RunExecutionStatus.INTERRUPTED;
        default -> RunExecutionStatus.FAILED;
      };
    }
    if (metadata == null) {
      return RunExecutionStatus.INTERRUPTED;
    }
    return switch (metadata.lifecycle()) {
      case "queued" -> RunExecutionStatus.QUEUED;
      case "running", "awaiting_confirmation" ->
          metadata.processId() == ProcessHandle.current().pid()
              ? RunExecutionStatus.RUNNING
              : RunExecutionStatus.INTERRUPTED;
      case "completed" -> RunExecutionStatus.SUCCEEDED;
      case "cancelled" -> RunExecutionStatus.CANCELLED;
      case "interrupted" -> RunExecutionStatus.INTERRUPTED;
      default -> RunExecutionStatus.FAILED;
    };
  }

  private static RunUsageSnapshot usage(JsonNode node) {
    if (!node.isObject()) {
      return RunUsageSnapshot.empty();
    }
    long calls = node.path("calls").asLong(0L);
    long input = node.path("inputTokens").asLong(node.path("input_tokens").asLong(0L));
    long output = node.path("outputTokens").asLong(node.path("output_tokens").asLong(0L));
    BigDecimal cost = decimal(node.path("costUsd"), decimal(node.path("estimated_cost_usd"), BigDecimal.ZERO));
    return RunUsageSnapshot.of(
        calls,
        input,
        output,
        cost,
        node.path("latencyMs").asDouble(node.path("latency_ms").asDouble(0.0d)),
        "",
        "");
  }

  private static RunUsageSnapshot resultUsage(JsonNode result) {
    JsonNode usage = result.path("total_usage");
    long calls = result.path("total_calls").asLong(0L);
    long input = usage.path("input_tokens").asLong(0L);
    long output = usage.path("output_tokens").asLong(0L);
    return RunUsageSnapshot.of(
        calls,
        input,
        output,
        decimal(usage.path("estimated_cost_usd"), BigDecimal.ZERO),
        usage.path("latency_ms").asDouble(0.0d),
        "",
        "");
  }

  private JsonNode read(Path path) {
    if (!Files.isRegularFile(path)) {
      return mapper.missingNode();
    }
    try {
      return mapper.readTree(Files.readAllBytes(path));
    } catch (IOException | RuntimeException exception) {
      return mapper.missingNode();
    }
  }

  private static int countText(JsonNode object, String value) {
    int count = 0;
    for (JsonNode item : object) {
      if (("VERIFIED".equals(value) && java.util.Set.of("VERIFIED", "verified")
              .contains(item.path("status").asText()))
          || value.equals(item.path("status").asText())) {
        count++;
      }
    }
    return count;
  }

  private static String text(JsonNode node, String fallback) {
    return node.isTextual() && !node.textValue().isBlank() ? node.textValue() : fallback;
  }

  private static BigDecimal decimal(JsonNode node, BigDecimal fallback) {
    try {
      return node.isNumber() || node.isTextual() ? node.decimalValue() : fallback;
    } catch (ArithmeticException exception) {
      return fallback;
    }
  }

  private static String readText(Path path) {
    try {
      return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    } catch (IOException exception) {
      return "";
    }
  }

  private static String hash(Path path) {
    try {
      return Files.isRegularFile(path) ? CanonicalJson.stableHash(Files.readString(path)) : "";
    } catch (IOException exception) {
      return "";
    }
  }
}
