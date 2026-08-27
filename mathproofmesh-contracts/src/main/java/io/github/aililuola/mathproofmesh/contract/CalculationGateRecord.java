package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record CalculationGateRecord(
    @JsonProperty(value = "created_at") @ContractNonNull String createdAt,
    @JsonProperty(value = "evidence_refs") @ContractNonNull List<EvidenceRef> evidenceRefs,
    @JsonProperty(value = "experiment_id") String experimentId,
    @JsonProperty(value = "gate_id") @ContractNonNull String gateId,
    @JsonProperty(value = "path_id", required = true) @ContractNonNull String pathId,
    @JsonProperty(value = "reason", required = true) @ContractNonNull String reason,
    @JsonProperty(value = "request") ToolRequest request,
    @JsonProperty(value = "request_hash") String requestHash,
    @JsonProperty(value = "result_hash") String resultHash,
    @JsonProperty(value = "scope_id", required = true) @ContractNonNull String scopeId,
    @JsonProperty(value = "scope_type", required = true) @ContractNonNull String scopeType,
    @JsonProperty(value = "trigger", required = true) @ContractNonNull String trigger,
    @JsonProperty(value = "verdict", required = true) @ContractNonNull CalculationGateVerdict verdict
) implements StrictContract {

  public CalculationGateRecord {
    if (createdAt == null) {
      createdAt = PythonIsoTimestampCodec.now();
    }
    createdAt = ContractStrings.trim(createdAt);
    if (evidenceRefs == null) {
      evidenceRefs = List.of();
    }
    evidenceRefs = ImmutableCollections.listOrEmpty(evidenceRefs);
    experimentId = ContractStrings.trim(experimentId);
    if (gateId == null) {
      gateId = PythonCompatibleIdGenerator.newId("calcgate");
    }
    gateId = ContractStrings.trim(gateId);
    pathId = ContractStrings.trim(pathId);
    pathId = ContractStrings.required("path_id", pathId);
    reason = ContractStrings.trim(reason);
    reason = ContractStrings.required("reason", reason);
    requestHash = ContractStrings.trim(requestHash);
    resultHash = ContractStrings.trim(resultHash);
    scopeId = ContractStrings.trim(scopeId);
    scopeId = ContractStrings.required("scope_id", scopeId);
    scopeType = ContractStrings.trim(scopeType);
    scopeType = ContractStrings.required("scope_type", scopeType);
    ContractValues.oneOf("scope_type", scopeType, "strategy", "proof_step", "final_step");
    trigger = ContractStrings.trim(trigger);
    trigger = ContractStrings.required("trigger", trigger);
    verdict = ContractValues.required("verdict", verdict);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<EvidenceRef> evidenceRefs() {
    return evidenceRefs == null ? null : List.copyOf(evidenceRefs);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
