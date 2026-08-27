package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record ClaimCounterexampleWitnessReviewBatch(
    @JsonProperty(value = "batch_id") String batchId,
    @JsonProperty(value = "agent_id", required = true) @ContractNonNull String agentId,
    @JsonProperty(value = "decisions") @ContractNonNull
        List<ClaimCounterexampleWitnessReviewDecision> decisions,
    @JsonProperty(value = "raw_artifact_ref") String rawArtifactRef,
    @JsonProperty(value = "usage") @ContractNonNull UsageRecord usage)
    implements StrictContract {
  public ClaimCounterexampleWitnessReviewBatch {
    if (batchId == null) {
      batchId = PythonCompatibleIdGenerator.newId("counterexample-witness-review");
    }
    batchId = ContractStrings.required("batch_id", ContractStrings.trim(batchId));
    agentId = ContractStrings.required("agent_id", ContractStrings.trim(agentId));
    decisions = ImmutableCollections.listOrEmpty(decisions);
    Set<String> candidateIds = new LinkedHashSet<>();
    for (ClaimCounterexampleWitnessReviewDecision decision : decisions) {
      if (!candidateIds.add(decision.candidateId())) {
        throw new ContractValidationException(
            "duplicate counterexample witness decision: " + decision.candidateId());
      }
    }
    rawArtifactRef = ContractStrings.trim(rawArtifactRef);
    usage = usage == null ? new UsageRecord() : usage;
  }

  @Override
  public List<ClaimCounterexampleWitnessReviewDecision> decisions() {
    return List.copyOf(decisions);
  }
}
