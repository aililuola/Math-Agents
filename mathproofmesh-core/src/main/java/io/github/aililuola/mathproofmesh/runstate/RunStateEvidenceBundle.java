package io.github.aililuola.mathproofmesh.runstate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RunStateEvidenceBundle(
    String runId,
    String problemHash,
    String executionAttemptId,
    RunExecutionStatus executionStatus,
    RunTerminalReason terminalReason,
    String currentStage,
    boolean semanticCheckpointPresent,
    boolean semanticCheckpointTerminal,
    String semanticCheckpointRef,
    String semanticCheckpointHash,
    String proofGraphHash,
    RunMathematicalProgressSnapshot mathematicalProgress,
    List<RunUsageEvidence> usageEvidence,
    RunStateSnapshot previousState,
    RunProjectionSnapshot projection,
    Instant observedAt) {
  public RunStateEvidenceBundle {
    runId = RunStateHashes.required(runId, "runId");
    problemHash = RunStateHashes.required(problemHash, "problemHash");
    executionAttemptId = RunStateHashes.required(executionAttemptId, "executionAttemptId");
    executionStatus = Objects.requireNonNull(executionStatus, "executionStatus");
    terminalReason = terminalReason == null ? RunTerminalReason.NONE : terminalReason;
    currentStage = RunStateHashes.required(currentStage, "currentStage");
    semanticCheckpointRef = RunStateHashes.optional(semanticCheckpointRef);
    semanticCheckpointHash = RunStateHashes.optional(semanticCheckpointHash);
    proofGraphHash = RunStateHashes.optional(proofGraphHash);
    mathematicalProgress =
        mathematicalProgress == null
            ? RunMathematicalProgressSnapshot.empty()
            : mathematicalProgress;
    usageEvidence = usageEvidence == null ? List.of() : List.copyOf(usageEvidence);
    observedAt = Objects.requireNonNull(observedAt, "observedAt");
  }

  @Override
  public List<RunUsageEvidence> usageEvidence() {
    return List.copyOf(usageEvidence);
  }
}
