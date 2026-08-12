package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ComputationDecision(
    @JsonProperty(value = "cache_hit") @ContractNonNull Boolean cacheHit,
    @JsonProperty(value = "canonical_request_hash") String canonicalRequestHash,
    @JsonProperty(value = "contract_repair_reason") String contractRepairReason,
    @JsonProperty(value = "contract_repair_status") @ContractNonNull ComputationContractRepairStatus contractRepairStatus,
    @JsonProperty(value = "created_at") @ContractNonNull String createdAt,
    @JsonProperty(value = "decision", required = true) @ContractNonNull ComputationDecisionStatus decision,
    @JsonProperty(value = "experiment_id", required = true) @ContractNonNull String experimentId,
    @JsonProperty(value = "original_request_hash") String originalRequestHash,
    @JsonProperty(value = "reason", required = true) @ContractNonNull String reason,
    @JsonProperty(value = "remaining_experiments") @ContractNonNull Integer remainingExperiments,
    @JsonProperty(value = "request_hash", required = true) @ContractNonNull String requestHash,
    @JsonProperty(value = "requires_meta_review") @ContractNonNull Boolean requiresMetaReview,
    @JsonProperty(value = "rule_id", required = true) @ContractNonNull String ruleId
) implements StrictContract {

  public ComputationDecision {
    if (cacheHit == null) {
      cacheHit = false;
    }
    canonicalRequestHash = ContractStrings.trim(canonicalRequestHash);
    contractRepairReason = ContractStrings.trim(contractRepairReason);
    if (contractRepairStatus == null) {
      contractRepairStatus = ComputationContractRepairStatus.NOT_NEEDED;
    }
    if (createdAt == null) {
      createdAt = PythonIsoTimestampCodec.now();
    }
    createdAt = ContractStrings.trim(createdAt);
    decision = ContractValues.required("decision", decision);
    experimentId = ContractStrings.trim(experimentId);
    experimentId = ContractStrings.required("experiment_id", experimentId);
    originalRequestHash = ContractStrings.trim(originalRequestHash);
    reason = ContractStrings.trim(reason);
    reason = ContractStrings.required("reason", reason);
    if (remainingExperiments == null) {
      remainingExperiments = 0;
    }
    ContractValues.minimum("remaining_experiments", remainingExperiments, 0);
    requestHash = ContractStrings.trim(requestHash);
    requestHash = ContractStrings.required("request_hash", requestHash);
    if (requiresMetaReview == null) {
      requiresMetaReview = false;
    }
    ruleId = ContractStrings.trim(ruleId);
    ruleId = ContractStrings.required("rule_id", ruleId);
  }
}
