package io.github.aililuola.mathproofmesh.runstate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record RunAuthoritySnapshot(
    String runId,
    String problemHash,
    String executionAttemptId,
    long authoritySequence,
    RunExecutionStatus executionStatus,
    RunMathematicalStatus mathStatus,
    RunUsageStatus usageStatus,
    RunCampaignStatus campaignStatus,
    RunTerminalReason terminalReason,
    String currentStage,
    boolean recoverable,
    RunUsageSnapshot usage,
    RunMathematicalProgressSnapshot mathematicalProgress,
    String latestSemanticCheckpointRef,
    String latestSemanticCheckpointHash,
    String proofGraphHash,
    String authorityHash,
    long version) {

  public RunAuthoritySnapshot {
    runId = RunStateHashes.required(runId, "runId");
    problemHash = RunStateHashes.required(problemHash, "problemHash");
    executionAttemptId = RunStateHashes.required(executionAttemptId, "executionAttemptId");
    executionStatus = Objects.requireNonNull(executionStatus, "executionStatus");
    mathStatus = Objects.requireNonNull(mathStatus, "mathStatus");
    usageStatus = Objects.requireNonNull(usageStatus, "usageStatus");
    campaignStatus = Objects.requireNonNull(campaignStatus, "campaignStatus");
    terminalReason = terminalReason == null ? RunTerminalReason.NONE : terminalReason;
    currentStage = RunStateHashes.required(currentStage, "currentStage");
    usage = usage == null ? RunUsageSnapshot.empty() : usage;
    mathematicalProgress =
        mathematicalProgress == null
            ? RunMathematicalProgressSnapshot.empty()
            : mathematicalProgress;
    latestSemanticCheckpointRef = RunStateHashes.optional(latestSemanticCheckpointRef);
    latestSemanticCheckpointHash = RunStateHashes.optional(latestSemanticCheckpointHash);
    proofGraphHash = RunStateHashes.optional(proofGraphHash);
    if (authoritySequence < 0L || version < 0L) {
      throw new IllegalArgumentException("authority counters must not be negative");
    }
    if (recoverable != (campaignStatus == RunCampaignStatus.RECOVERABLE)) {
      throw new IllegalArgumentException("recoverable must match campaignStatus");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("runId", runId);
    payload.put("problemHash", problemHash);
    payload.put("executionAttemptId", executionAttemptId);
    payload.put("authoritySequence", authoritySequence);
    payload.put("executionStatus", executionStatus);
    payload.put("mathStatus", mathStatus);
    payload.put("usageStatus", usageStatus);
    payload.put("campaignStatus", campaignStatus);
    payload.put("terminalReason", terminalReason);
    payload.put("currentStage", currentStage);
    payload.put("recoverable", recoverable);
    payload.put("usage", usage);
    payload.put("mathematicalProgress", mathematicalProgress);
    payload.put("latestSemanticCheckpointRef", latestSemanticCheckpointRef);
    payload.put("latestSemanticCheckpointHash", latestSemanticCheckpointHash);
    payload.put("proofGraphHash", proofGraphHash);
    payload.put("version", version);
    authorityHash = RunStateHashes.generatedOrVerified(authorityHash, payload, "authority");
  }
}
