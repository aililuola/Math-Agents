package io.github.aililuola.mathproofmesh.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.aililuola.mathproofmesh.api.RunApiModels.RunView;
import io.github.aililuola.mathproofmesh.api.RunApiModels.UsageView;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.runstate.ClaimLifecycleProgressExtractor;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class RunStateApiProjection {
  private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

  private RunStateApiProjection() {}

  static RunStateSnapshot reconcile(
      SolveRequest request,
      String runId,
      String attemptId,
      Path runDirectory,
      RunExecutionBackend.RunExecutionResult result,
      RunStateSnapshot previous) {
    String expectedProblemHash = CanonicalJson.stableHash(request.problem());
    if (result.runState() != null
        && (!runId.equals(result.runState().authority().runId())
            || !equalHash(expectedProblemHash, result.runState().authority().problemHash()))) {
        throw new IllegalArgumentException("backend run state immutable identity mismatch");
    }
    JsonNode checkpoint = checkpoint(runDirectory);
    RunUsageSnapshot checkpointUsage = checkpointUsage(checkpoint);
    RunUsageSnapshot resultUsage =
        RunUsageSnapshot.of(
            result.usage().providerCalls(),
            result.usage().inputTokens(),
            result.usage().outputTokens(),
            result.usage().estimatedCostUsd(),
            result.usage().latencyMs(),
            "",
            "");
    List<RunUsageEvidence> usage = new ArrayList<>();
    if (checkpointUsage.providerCalls() > 0L || checkpointUsage.totalTokens() > 0L) {
      usage.add(
          RunUsageEvidence.aggregate(
              RunUsageEvidenceSource.SEMANTIC_CHECKPOINT,
              checkpointUsage,
              "semantic-checkpoint"));
    }
    usage.add(
        result.usage().providerCallEvidence().isEmpty()
            ? RunUsageEvidence.aggregate(
                RunUsageEvidenceSource.RESULT_PROJECTION, resultUsage, "execution-result")
            : RunUsageEvidence.providerCalls(
                result.usage().providerCallEvidence(), "execution-result-provider-requests"));
    if (result.runState() != null) {
      usage.add(
          RunUsageEvidence.aggregate(
              RunUsageEvidenceSource.RESULT_PROJECTION,
              result.runState().authority().usage(),
              "backend-run-state-evidence"));
    }
    RunMathematicalProgressSnapshot progress = progress(checkpoint, result);
    String checkpointHash =
        checkpoint.isMissingNode() ? "" : CanonicalJson.stableHash(checkpoint);
    RunProjectionSnapshot projection =
        new RunProjectionSnapshot(
            previous == null ? "" : previous.authority().authorityHash(),
            RunReportStatus.PARTIAL,
            "",
            "",
            "",
            "",
            "",
            "",
            0L,
            List.of(),
            null);
    return new RunStateReconciler()
        .reconcile(
            new RunStateEvidenceBundle(
                runId,
                expectedProblemHash,
                attemptId,
                execution(result.status()),
                RunTerminalReason.NONE,
                result.currentStage(),
                !checkpoint.isMissingNode(),
                checkpoint.path("terminal").asBoolean(false),
                checkpoint.isMissingNode() ? "" : "structured/desktop-solve-state.json",
                checkpointHash,
                checkpoint.path("proofGraph").isMissingNode()
                    ? ""
                    : CanonicalJson.stableHash(checkpoint.path("proofGraph")),
                progress,
                usage,
                previous,
                projection,
                Instant.now()))
        .state();
  }

  static RunStateSnapshot withReport(
      RunStateSnapshot state,
      RunReportStatus reportStatus,
      String reportReference,
      String reportHash,
      List<String> errors) {
    RunProjectionSnapshot projection =
        new RunProjectionSnapshot(
            state.authority().authorityHash(),
            reportStatus,
            state.projection().runResultRef(),
            state.projection().runResultHash(),
            state.projection().desktopMetadataRef(),
            state.projection().desktopMetadataHash(),
            reportReference,
            reportHash,
            state.projection().latestActivitySequence(),
            errors,
            state.projection().projectionVersion() + 1L,
            null);
    return RunStateSnapshot.create(
        state.authority(), projection, state.reconciliationStatus(), state.conflicts(), Instant.now());
  }

  static RunStateSnapshot withResult(
      RunStateSnapshot state, String reference, String hash, List<String> errors) {
    RunProjectionSnapshot projection =
        new RunProjectionSnapshot(
            state.authority().authorityHash(),
            state.projection().reportStatus(),
            reference,
            hash,
            state.projection().desktopMetadataRef(),
            state.projection().desktopMetadataHash(),
            state.projection().reportRef(),
            state.projection().reportHash(),
            state.projection().latestActivitySequence(),
            errors,
            state.projection().projectionVersion() + 1L,
            null);
    return RunStateSnapshot.create(
        state.authority(), projection, state.reconciliationStatus(), state.conflicts(), Instant.now());
  }

  static RunView view(
      String runId,
      String traceId,
      String summary,
      String resultReference,
      int logicalSteps,
      long latestEventId,
      List<String> completedRoutes,
      List<String> verifiedClaims,
      RunStateSnapshot state) {
    var authority = state.authority();
    String status = compatibleStatus(state);
    return new RunView(
        runId,
        status,
        authority.currentStage(),
        summary,
        resultReference,
        traceId,
        logicalSteps,
        latestEventId,
        completedRoutes,
        verifiedClaims,
        new UsageView(
            authority.usage().inputTokens(),
            authority.usage().outputTokens(),
            authority.usage().totalTokens(),
            authority.usage().estimatedCostUsd(),
            authority.usage().latencyMs()),
        authority.executionStatus(),
        authority.mathStatus(),
        authority.usageStatus(),
        authority.campaignStatus(),
        state.projection().reportStatus(),
        state.reconciliationStatus(),
        authority.terminalReason(),
        authority.recoverable(),
        authority.authorityHash(),
        authority.authoritySequence(),
        authority.usage().providerCalls(),
        logicalSteps);
  }

  static String compatibleStatus(RunStateSnapshot state) {
    var authority = state.authority();
    if (authority.campaignStatus()
        == io.github.aililuola.mathproofmesh.runstate.RunCampaignStatus.ACTIVE) {
      return "running";
    }
    return switch (authority.executionStatus()) {
      case FAILED -> "failed";
      case INTERRUPTED -> "interrupted";
      case CANCELLED -> "cancelled";
      case QUEUED -> "queued";
      default ->
          authority.mathStatus()
                  == io.github.aililuola.mathproofmesh.runstate.RunMathematicalStatus.VERIFIED
              ? "completed"
              : "unverified";
    };
  }

  private static RunExecutionStatus execution(String status) {
    return switch (status) {
      case "completed" -> RunExecutionStatus.SUCCEEDED;
      case "failed" -> RunExecutionStatus.FAILED;
      case "network_interrupted", "interrupted" -> RunExecutionStatus.INTERRUPTED;
      case "cancelled" -> RunExecutionStatus.CANCELLED;
      case "running" -> RunExecutionStatus.RUNNING;
      default -> RunExecutionStatus.SUCCEEDED;
    };
  }

  private static RunMathematicalProgressSnapshot progress(
      JsonNode checkpoint, RunExecutionBackend.RunExecutionResult result) {
    boolean finalProof = checkpoint.hasNonNull("finalProof");
    boolean finalValidation = checkpoint.path("finalValidationPassed").asBoolean(false);
    boolean finalReview =
        Set.of("PASS", "pass")
            .contains(checkpoint.path("finalReview").path("verdict").asText());
    boolean integrity =
        !checkpoint.hasNonNull("finalReview")
            || checkpoint.path("finalReview").path("problemIntegrityOk").asBoolean(false);
    int obligations = checkpoint.path("proofGraph").path("obligations").size();
    Set<String> verifiedClaimIds = new LinkedHashSet<>(result.verifiedLocalClaimIds());
    ClaimLifecycleProgressExtractor.Progress lifecycleProgress =
        ClaimLifecycleProgressExtractor.extract(checkpoint.path("claimLifecycle"));
    verifiedClaimIds.addAll(lifecycleProgress.verifiedClaimIds());
    Set<String> refutedClaimIds =
        new LinkedHashSet<>(lifecycleProgress.refutedClaimIds());
    return new RunMathematicalProgressSnapshot(
        verifiedClaimIds.size(),
        refutedClaimIds.size(),
        obligations,
        !checkpoint.isMissingNode(),
        checkpoint.path("admittedStrategies").size() > 0,
        !result.routes().isEmpty() || checkpoint.path("routes").size() > 0,
        checkpoint.hasNonNull("proofGraph") || obligations > 0,
        checkpoint.path("researchCheckpoints").path("records").size() > 0,
        checkpoint.path("computations").size() > 0,
        finalProof,
        finalValidation,
        finalReview,
        integrity,
        List.copyOf(verifiedClaimIds),
        List.copyOf(refutedClaimIds),
        "",
        "",
        finalProof ? CanonicalJson.stableHash(checkpoint.path("finalProof")) : "",
        checkpoint.hasNonNull("finalReview")
            ? CanonicalJson.stableHash(checkpoint.path("finalReview"))
            : "");
  }

  private static JsonNode checkpoint(Path runDirectory) {
    Path path = runDirectory.resolve("structured/desktop-solve-state.json");
    if (!Files.isRegularFile(path)) {
      return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
    try {
      return JSON.readTree(Files.readAllBytes(path));
    } catch (IOException | RuntimeException exception) {
      return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
  }

  private static RunUsageSnapshot checkpointUsage(JsonNode checkpoint) {
    JsonNode usage = checkpoint.path("usageTotals");
    if (!usage.isObject()) {
      return RunUsageSnapshot.empty();
    }
    long input = usage.path("inputTokens").asLong(0L);
    long output = usage.path("outputTokens").asLong(0L);
    BigDecimal cost = usage.path("costUsd").isNumber() ? usage.path("costUsd").decimalValue() : BigDecimal.ZERO;
    return RunUsageSnapshot.of(
        usage.path("calls").asLong(0L),
        input,
        output,
        cost,
        usage.path("latencyMs").asDouble(0.0d),
        "",
        "");
  }

  private static boolean equalHash(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }
}
