package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record NegativeAnalogyRecord(
    @JsonProperty(value = "distinguishing_conditions") @ContractNonNull List<String> distinguishingConditions,
    @JsonProperty(value = "failure_reason", required = true) @ContractNonNull String failureReason,
    @JsonProperty(value = "mechanism", required = true) @ContractNonNull InspirationMechanism mechanism,
    @JsonProperty(value = "negative") @ContractNonNull Boolean negative,
    @JsonProperty(value = "problem_hash", required = true) @ContractNonNull String problemHash,
    @JsonProperty(value = "proposal_id", required = true) @ContractNonNull String proposalId,
    @JsonProperty(value = "record_id", required = true) @ContractNonNull String recordId,
    @JsonProperty(value = "round_index", required = true) @ContractNonNull Integer roundIndex,
    @JsonProperty(value = "source_record_id") String sourceRecordId
) implements StrictContract {

  public NegativeAnalogyRecord {
    if (distinguishingConditions == null) {
      distinguishingConditions = List.of();
    }
    distinguishingConditions = ImmutableCollections.listOrEmpty(distinguishingConditions);
    failureReason = ContractStrings.trim(failureReason);
    failureReason = ContractStrings.required("failure_reason", failureReason);
    mechanism = ContractValues.required("mechanism", mechanism);
    if (negative == null) {
      negative = true;
    }
    ContractValues.constant("negative", negative, true);
    problemHash = ContractStrings.trim(problemHash);
    problemHash = ContractStrings.required("problem_hash", problemHash);
    proposalId = ContractStrings.trim(proposalId);
    proposalId = ContractStrings.required("proposal_id", proposalId);
    recordId = ContractStrings.trim(recordId);
    recordId = ContractStrings.required("record_id", recordId);
    roundIndex = ContractValues.required("round_index", roundIndex);
    ContractValues.minimum("round_index", roundIndex, 0);
    sourceRecordId = ContractStrings.trim(sourceRecordId);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> distinguishingConditions() {
    return distinguishingConditions == null ? null : List.copyOf(distinguishingConditions);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
