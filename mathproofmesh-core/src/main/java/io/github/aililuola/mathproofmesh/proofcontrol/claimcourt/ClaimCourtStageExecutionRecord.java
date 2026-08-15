package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record ClaimCourtStageExecutionRecord(
    String executionId,
    String courtCaseId,
    ClaimCourtStage stage,
    List<String> claimIds,
    String inputHash,
    String assignedAgentId,
    ClaimCourtStageExecutionStatus status,
    JsonNode resultPayload,
    String resultHash,
    long version,
    List<String> history) {
  public ClaimCourtStageExecutionRecord {
    executionId = ClaimCourtValues.required(executionId, "executionId");
    courtCaseId = ClaimCourtValues.required(courtCaseId, "courtCaseId");
    stage = java.util.Objects.requireNonNull(stage, "stage");
    claimIds = ClaimCourtValues.copy(claimIds);
    if (claimIds.isEmpty()) {
      throw new IllegalArgumentException("stage execution requires claim IDs");
    }
    inputHash = ClaimCourtValues.required(inputHash, "inputHash");
    assignedAgentId = ClaimCourtValues.required(assignedAgentId, "assignedAgentId");
    status = java.util.Objects.requireNonNull(status, "status");
    resultPayload = resultPayload == null ? null : resultPayload.deepCopy();
    resultHash = ClaimCourtValues.nullable(resultHash);
    if ((status == ClaimCourtStageExecutionStatus.RESULT_DURABLE
            || status == ClaimCourtStageExecutionStatus.COMPLETED)
        && (resultPayload == null || resultHash == null)) {
      throw new IllegalArgumentException("durable stage result requires payload and hash");
    }
    if (version < 0L) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
    history = ClaimCourtValues.copy(history);
  }

  @Override
  public JsonNode resultPayload() {
    return resultPayload == null ? null : resultPayload.deepCopy();
  }

  @Override
  public List<String> claimIds() {
    return List.copyOf(claimIds);
  }

  @Override
  public List<String> history() {
    return List.copyOf(history);
  }
}
