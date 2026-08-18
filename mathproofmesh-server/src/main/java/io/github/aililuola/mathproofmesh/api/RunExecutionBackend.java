package io.github.aililuola.mathproofmesh.api;

import io.github.aililuola.mathproofmesh.api.RunApiModels.RouteView;
import io.github.aililuola.mathproofmesh.runstate.RunStateSnapshot;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Pluggable production boundary behind the REST and desktop run APIs. */
public interface RunExecutionBackend {
  RunExecutionResult execute(
      SolveRequest request,
      String runId,
      String traceId,
      Path runDirectory,
      ProgressSink progress);

  /** Resumes from backend-owned committed state without changing the immutable solve request. */
  default RunExecutionResult resume(
      SolveRequest originalRequest,
      ResumeRequest resumeRequest,
      String traceId,
      Path runDirectory,
      ProgressSink progress) {
    Objects.requireNonNull(originalRequest, "originalRequest");
    Objects.requireNonNull(resumeRequest, "resumeRequest");
    return execute(
        originalRequest, resumeRequest.runId(), traceId, runDirectory, progress);
  }

  @FunctionalInterface
  interface ProgressSink {
    void emit(
        String type,
        String stage,
        String agentId,
        String status,
        String summary,
        String reference);
  }

  record RunExecutionResult(
      String status,
      String currentStage,
      String summary,
      List<RouteView> routes,
      List<String> verifiedLocalClaimIds,
      String reportBody,
      int logicalSteps,
      ExecutionUsage usage,
      RunStateSnapshot runState) {
    public RunExecutionResult {
      status = required(status, "status");
      currentStage = required(currentStage, "currentStage");
      summary = required(summary, "summary");
      routes = routes == null ? List.of() : List.copyOf(routes);
      verifiedLocalClaimIds =
          verifiedLocalClaimIds == null ? List.of() : List.copyOf(verifiedLocalClaimIds);
      reportBody = reportBody == null ? "" : reportBody.strip();
      if (logicalSteps < 0) {
        throw new IllegalArgumentException("logicalSteps must not be negative");
      }
      usage = usage == null ? ExecutionUsage.zero() : usage;
    }

    public RunExecutionResult(
        String status,
        String currentStage,
        String summary,
        List<RouteView> routes,
        List<String> verifiedLocalClaimIds,
        String reportBody,
        int logicalSteps,
        ExecutionUsage usage) {
      this(
          status,
          currentStage,
          summary,
          routes,
          verifiedLocalClaimIds,
          reportBody,
          logicalSteps,
          usage,
          null);
    }

    public RunExecutionResult(
        String status,
        String currentStage,
        String summary,
        List<RouteView> routes,
        List<String> verifiedLocalClaimIds,
        String reportBody,
        int logicalSteps) {
      this(
          status,
          currentStage,
          summary,
          routes,
          verifiedLocalClaimIds,
          reportBody,
          logicalSteps,
          ExecutionUsage.zero(),
          null);
    }

    @Override
    public List<RouteView> routes() {
      return List.copyOf(routes);
    }

    @Override
    public List<String> verifiedLocalClaimIds() {
      return List.copyOf(verifiedLocalClaimIds);
    }

    private static String required(String value, String label) {
      String normalized = Objects.requireNonNull(value, label).strip();
      if (normalized.isEmpty()) {
        throw new IllegalArgumentException(label + " must not be blank");
      }
      return normalized;
    }
  }

  record ExecutionUsage(
      long providerCalls,
      long inputTokens,
      long outputTokens,
      BigDecimal estimatedCostUsd,
      double latencyMs,
      List<io.github.aililuola.mathproofmesh.runstate.ProviderCallUsageEvidence>
          providerCallEvidence) {
    public ExecutionUsage(
        long inputTokens,
        long outputTokens,
        BigDecimal estimatedCostUsd,
        double latencyMs) {
      this(0L, inputTokens, outputTokens, estimatedCostUsd, latencyMs, List.of());
    }

    public ExecutionUsage(
        long providerCalls,
        long inputTokens,
        long outputTokens,
        BigDecimal estimatedCostUsd,
        double latencyMs) {
      this(providerCalls, inputTokens, outputTokens, estimatedCostUsd, latencyMs, List.of());
    }

    public ExecutionUsage {
      estimatedCostUsd = Objects.requireNonNull(estimatedCostUsd, "estimatedCostUsd");
      providerCallEvidence =
          providerCallEvidence == null ? List.of() : List.copyOf(providerCallEvidence);
      if (providerCalls < 0L
          || inputTokens < 0L
          || outputTokens < 0L
          || estimatedCostUsd.signum() < 0
          || !Double.isFinite(latencyMs)
          || latencyMs < 0.0d) {
        throw new IllegalArgumentException("usage counters must be finite and nonnegative");
      }
      if (inputTokens > Long.MAX_VALUE - outputTokens) {
        throw new IllegalArgumentException("total token count exceeds the supported range");
      }
    }

    public long totalTokens() {
      return Math.addExact(inputTokens, outputTokens);
    }

    @Override
    public List<io.github.aililuola.mathproofmesh.runstate.ProviderCallUsageEvidence>
        providerCallEvidence() {
      return List.copyOf(providerCallEvidence);
    }

    public static ExecutionUsage zero() {
      return new ExecutionUsage(0L, 0L, 0L, BigDecimal.ZERO, 0.0d, List.of());
    }
  }
}
