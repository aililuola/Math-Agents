package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record ClaimProofAuditBatch(
    @JsonProperty(value = "batch_id") String batchId,
    @JsonProperty(value = "agent_id", required = true) @ContractNonNull String agentId,
    @JsonProperty(value = "route_id", required = true) @ContractNonNull String routeId,
    @JsonProperty(value = "attempt_id", required = true) @ContractNonNull String attemptId,
    @JsonProperty(value = "decisions") @ContractNonNull List<ClaimProofAuditDecision> decisions,
    @JsonProperty(value = "raw_artifact_ref") String rawArtifactRef,
    @JsonProperty(value = "usage") @ContractNonNull UsageRecord usage)
    implements StrictContract {
  public ClaimProofAuditBatch {
    if (batchId == null) {
      batchId = PythonCompatibleIdGenerator.newId("claim-proof-audit");
    }
    batchId = ContractStrings.required("batch_id", ContractStrings.trim(batchId));
    agentId = ContractStrings.required("agent_id", ContractStrings.trim(agentId));
    routeId = ContractStrings.required("route_id", ContractStrings.trim(routeId));
    attemptId = ContractStrings.required("attempt_id", ContractStrings.trim(attemptId));
    decisions = ImmutableCollections.listOrEmpty(decisions);
    Set<String> claimIds = new LinkedHashSet<>();
    for (ClaimProofAuditDecision decision : decisions) {
      if (!claimIds.add(decision.claimId())) {
        throw new ContractValidationException(
            "duplicate proof audit decision: " + decision.claimId());
      }
    }
    rawArtifactRef = ContractStrings.trim(rawArtifactRef);
    usage = usage == null ? new UsageRecord() : usage;
  }

  @Override
  public List<ClaimProofAuditDecision> decisions() {
    return List.copyOf(decisions);
  }
}
