package io.github.aililuola.mathproofmesh.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend.ExecutionUsage;
import io.github.aililuola.mathproofmesh.runstate.RunCampaignStatus;
import io.github.aililuola.mathproofmesh.runstate.RunExecutionStatus;
import io.github.aililuola.mathproofmesh.runstate.RunMathematicalStatus;
import io.github.aililuola.mathproofmesh.runstate.RunReconciliationStatus;
import io.github.aililuola.mathproofmesh.runstate.RunReportStatus;
import io.github.aililuola.mathproofmesh.runstate.RunTerminalReason;
import io.github.aililuola.mathproofmesh.runstate.RunUsageStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class RunApiModels {
  private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
  private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");

  private RunApiModels() {}

  public static String safeRunId(String runId) {
    String result = ActivitySanitizer.identifier(runId, 128);
    if (!RUN_ID.matcher(result).matches() || result.contains("..")) {
      throw new IllegalArgumentException("run_id has an invalid format");
    }
    return result;
  }

  public static String safeOptionalRunId(String runId) {
    return runId == null || runId.isBlank() ? null : safeRunId(runId);
  }

  public static String safeHash(String hash) {
    String result = ActivitySanitizer.identifier(hash, 64);
    if (!HASH.matcher(result).matches()) {
      throw new IllegalArgumentException("artifact hash has an invalid format");
    }
    return result;
  }

  static String publicEventType(String type) {
    String safe = ActivitySanitizer.identifier(type, 120);
    if (ApiEvent.TYPES.contains(safe)) {
      return safe;
    }
    if (safe.startsWith("stage_")
        || "problem_frozen".equals(safe)
        || "resume_planned".equals(safe)) {
      return "stage_changed";
    }
    if (safe.contains("checkpoint")) {
      return "checkpoint";
    }
    if (safe.startsWith("broker_") || safe.contains("_message")) {
      return "message";
    }
    if (safe.contains("verification")
        || safe.contains("review")
        || safe.startsWith("validation_")
        || safe.endsWith("_gate")) {
      return "verification";
    }
    if (safe.contains("budget")) {
      return "budget";
    }
    if (safe.contains("computation")) {
      return "computation";
    }
    if ("run_cancelled".equals(safe)) {
      return "error";
    }
    // Detailed scheduler events remain in activity.jsonl. The public stream carries their
    // redacted summary under the stable route-update type, so a new internal event can never
    // abort an otherwise valid live solve.
    return "route_updated";
  }

  public record RunView(
      @JsonProperty("run_id") String runId,
      String status,
      @JsonProperty("current_stage") String currentStage,
      String summary,
      @JsonProperty("result_reference") String resultReference,
      @JsonProperty("trace_id") String traceId,
      int budget,
      @JsonProperty("latest_event_id") long latestEventId,
      @JsonProperty("completed_route_ids") List<String> completedRouteIds,
      @JsonProperty("verified_local_claim_ids") List<String> verifiedLocalClaimIds,
      @JsonProperty("total_usage") UsageView totalUsage,
      @JsonProperty("execution_status") RunExecutionStatus executionStatus,
      @JsonProperty("math_status") RunMathematicalStatus mathStatus,
      @JsonProperty("usage_status") RunUsageStatus usageStatus,
      @JsonProperty("campaign_status") RunCampaignStatus campaignStatus,
      @JsonProperty("report_status") RunReportStatus reportStatus,
      @JsonProperty("reconciliation_status") RunReconciliationStatus reconciliationStatus,
      @JsonProperty("terminal_reason") RunTerminalReason terminalReason,
      boolean recoverable,
      @JsonProperty("authority_state_hash") String authorityStateHash,
      @JsonProperty("state_sequence") long stateSequence,
      @JsonProperty("provider_calls") long providerCalls,
      @JsonProperty("logical_steps") int logicalSteps) {
    public RunView {
      runId = safeRunId(runId);
      status = ActivitySanitizer.identifier(status, 40);
      currentStage = ActivitySanitizer.identifier(currentStage, 120);
      summary = ActivitySanitizer.text(summary, 800);
      resultReference = ActivitySanitizer.nullableIdentifier(resultReference, 240);
      traceId = TraceContext.validate(traceId);
      if (budget < 0
          || latestEventId < 0
          || stateSequence < 0L
          || providerCalls < 0L
          || logicalSteps < 0) {
        throw new IllegalArgumentException("run counters must be nonnegative");
      }
      completedRouteIds = List.copyOf(completedRouteIds);
      verifiedLocalClaimIds = List.copyOf(verifiedLocalClaimIds);
      totalUsage = totalUsage == null ? UsageView.zero() : totalUsage;
      executionStatus =
          executionStatus == null ? legacyExecutionStatus(status) : executionStatus;
      mathStatus =
          mathStatus == null
              ? "completed".equals(status)
                  ? RunMathematicalStatus.VERIFIED
                  : RunMathematicalStatus.NOT_STARTED
              : mathStatus;
      usageStatus = usageStatus == null ? RunUsageStatus.NOT_RECORDED : usageStatus;
      campaignStatus =
          campaignStatus == null ? legacyCampaignStatus(status, mathStatus) : campaignStatus;
      reportStatus = reportStatus == null ? RunReportStatus.ABSENT : reportStatus;
      reconciliationStatus =
          reconciliationStatus == null
              ? RunReconciliationStatus.CONSISTENT
              : reconciliationStatus;
      terminalReason = terminalReason == null ? RunTerminalReason.NONE : terminalReason;
      authorityStateHash =
          authorityStateHash == null || authorityStateHash.isBlank()
              ? ""
              : ActivitySanitizer.identifier(authorityStateHash, 64);
    }

    public RunView(
        String runId,
        String status,
        String currentStage,
        String summary,
        String resultReference,
        String traceId,
        int budget,
        long latestEventId,
        List<String> completedRouteIds,
        List<String> verifiedLocalClaimIds,
        UsageView totalUsage) {
      this(
          runId,
          status,
          currentStage,
          summary,
          resultReference,
          traceId,
          budget,
          latestEventId,
          completedRouteIds,
          verifiedLocalClaimIds,
          totalUsage,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          false,
          "",
          0L,
          0L,
          budget);
    }

    public RunView(
        String runId,
        String status,
        String currentStage,
        String summary,
        String resultReference,
        String traceId,
        int budget,
        long latestEventId,
        List<String> completedRouteIds,
        List<String> verifiedLocalClaimIds) {
      this(
          runId,
          status,
          currentStage,
          summary,
          resultReference,
          traceId,
          budget,
          latestEventId,
          completedRouteIds,
          verifiedLocalClaimIds,
          UsageView.zero());
    }

    private static RunExecutionStatus legacyExecutionStatus(String status) {
      return switch (status) {
        case "failed" -> RunExecutionStatus.FAILED;
        case "network_interrupted", "interrupted" -> RunExecutionStatus.INTERRUPTED;
        case "cancelled" -> RunExecutionStatus.CANCELLED;
        case "running" -> RunExecutionStatus.RUNNING;
        default -> RunExecutionStatus.SUCCEEDED;
      };
    }

    private static RunCampaignStatus legacyCampaignStatus(
        String status, RunMathematicalStatus mathStatus) {
      if ("running".equals(status)) {
        return RunCampaignStatus.ACTIVE;
      }
      return mathStatus == RunMathematicalStatus.VERIFIED
          ? RunCampaignStatus.TERMINAL
          : RunCampaignStatus.RECOVERABLE;
    }
  }

  public record UsageView(
      @JsonProperty("input_tokens") long inputTokens,
      @JsonProperty("output_tokens") long outputTokens,
      @JsonProperty("total_tokens") long totalTokens,
      @JsonProperty("estimated_cost_usd") BigDecimal estimatedCostUsd,
      @JsonProperty("latency_ms") double latencyMs) {
    public UsageView {
      estimatedCostUsd = Objects.requireNonNull(estimatedCostUsd, "estimatedCostUsd");
      long splitTotal = Math.addExact(inputTokens, outputTokens);
      if (inputTokens < 0L
          || outputTokens < 0L
          || totalTokens < 0L
          || splitTotal != totalTokens
          || estimatedCostUsd.signum() < 0
          || !Double.isFinite(latencyMs)
          || latencyMs < 0.0d) {
        throw new IllegalArgumentException("usage counters must be finite, nonnegative, and consistent");
      }
    }

    public static UsageView from(ExecutionUsage usage) {
      Objects.requireNonNull(usage, "usage");
      return new UsageView(
          usage.inputTokens(),
          usage.outputTokens(),
          usage.totalTokens(),
          usage.estimatedCostUsd(),
          usage.latencyMs());
    }

    public static UsageView zero() {
      return new UsageView(0L, 0L, 0L, BigDecimal.ZERO, 0.0d);
    }
  }

  public record RouteView(
      @JsonProperty("route_id") String routeId,
      String status,
      String summary,
      @JsonProperty("claim_ids") List<String> claimIds) {
    public RouteView {
      routeId = ActivitySanitizer.identifier(routeId, 128);
      status = ActivitySanitizer.identifier(status, 40);
      summary = ActivitySanitizer.text(summary, 800);
      claimIds = List.copyOf(claimIds);
    }
  }

  public record ProofGraphView(
      @JsonProperty("run_id") String runId,
      List<Map<String, String>> nodes,
      List<Map<String, String>> edges) {
    public ProofGraphView {
      runId = safeRunId(runId);
      nodes = List.copyOf(nodes);
      edges = List.copyOf(edges);
    }
  }

  public record ArtifactPayload(String hash, String mediaType, byte[] bytes) {
    public ArtifactPayload {
      hash = safeHash(hash);
      mediaType = Objects.requireNonNull(mediaType, "mediaType");
      bytes = bytes.clone();
      if (bytes.length > 1_048_576) {
        throw new IllegalArgumentException("artifact exceeds the API download limit");
      }
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  public record ApiEvent(
      @JsonProperty("event_id") long eventId,
      String type,
      String stage,
      @JsonProperty("agent_id") String agentId,
      @JsonProperty("elapsed_ms") long elapsedMs,
      String status,
      String summary,
      @JsonProperty("result_reference") String resultReference,
      @JsonProperty("trace_id") String traceId) {
    private static final java.util.Set<String> TYPES =
        java.util.Set.of(
            "run_started",
            "stage_changed",
            "agent_started",
            "agent_completed",
            "agent_failed",
            "route_updated",
            "computation",
            "sandbox_preflight",
            "message",
            "checkpoint",
            "verification",
            "budget",
            "warning",
            "result",
            "error",
            "run_failed",
            "heartbeat");

    public ApiEvent {
      if (eventId < 1 || elapsedMs < 0 || !TYPES.contains(type)) {
        throw new IllegalArgumentException("invalid API event");
      }
      stage = ActivitySanitizer.nullableIdentifier(stage, 120);
      agentId = ActivitySanitizer.nullableIdentifier(agentId, 160);
      status = ActivitySanitizer.identifier(status, 40);
      summary = ActivitySanitizer.text(summary, 800);
      resultReference = ActivitySanitizer.nullableIdentifier(resultReference, 240);
      traceId = TraceContext.validate(traceId);
    }
  }
}
