package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record WorkingProofCheckpoint(
    @JsonProperty(value = "created_at") @ContractNonNull String createdAt,
    @JsonProperty(value = "delta", required = true) @ContractNonNull ProofDelta delta,
    @JsonProperty(value = "feedback") @ContractNonNull List<String> feedback,
    @JsonProperty(value = "parent_verified_checkpoint_id", required = true) @ContractNonNull String parentVerifiedCheckpointId,
    @JsonProperty(value = "path_id", required = true) @ContractNonNull String pathId,
    @JsonProperty(value = "problem_hash", required = true) @ContractNonNull String problemHash,
    @JsonProperty(value = "segment_index", required = true) @ContractNonNull Integer segmentIndex,
    @JsonProperty(value = "source_agent_id", required = true) @ContractNonNull String sourceAgentId,
    @JsonProperty(value = "status") @ContractNonNull String status,
    @JsonProperty(value = "strategy_id", required = true) @ContractNonNull String strategyId,
    @JsonProperty(value = "verification_report_ids") @ContractNonNull List<String> verificationReportIds,
    @JsonProperty(value = "working_checkpoint_id") @ContractNonNull String workingCheckpointId
) implements StrictContract {

  public WorkingProofCheckpoint {
    if (createdAt == null) {
      createdAt = PythonIsoTimestampCodec.now();
    }
    createdAt = ContractStrings.trim(createdAt);
    delta = ContractValues.required("delta", delta);
    if (feedback == null) {
      feedback = List.of();
    }
    feedback = ImmutableCollections.listOrEmpty(feedback);
    parentVerifiedCheckpointId = ContractStrings.trim(parentVerifiedCheckpointId);
    parentVerifiedCheckpointId = ContractStrings.required("parent_verified_checkpoint_id", parentVerifiedCheckpointId);
    pathId = ContractStrings.trim(pathId);
    pathId = ContractStrings.required("path_id", pathId);
    problemHash = ContractStrings.trim(problemHash);
    problemHash = ContractStrings.required("problem_hash", problemHash);
    segmentIndex = ContractValues.required("segment_index", segmentIndex);
    ContractValues.minimum("segment_index", segmentIndex, 1);
    sourceAgentId = ContractStrings.trim(sourceAgentId);
    sourceAgentId = ContractStrings.required("source_agent_id", sourceAgentId);
    if (status == null) {
      status = "candidate";
    }
    status = ContractStrings.trim(status);
    ContractValues.oneOf("status", status, "candidate", "uncertain", "rejected");
    strategyId = ContractStrings.trim(strategyId);
    strategyId = ContractStrings.required("strategy_id", strategyId);
    if (verificationReportIds == null) {
      verificationReportIds = List.of();
    }
    verificationReportIds = ImmutableCollections.listOrEmpty(verificationReportIds);
    if (workingCheckpointId == null) {
      workingCheckpointId = PythonCompatibleIdGenerator.newId("working");
    }
    workingCheckpointId = ContractStrings.trim(workingCheckpointId);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> feedback() {
    return feedback == null ? null : List.copyOf(feedback);
  }

  public List<String> verificationReportIds() {
    return verificationReportIds == null ? null : List.copyOf(verificationReportIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
