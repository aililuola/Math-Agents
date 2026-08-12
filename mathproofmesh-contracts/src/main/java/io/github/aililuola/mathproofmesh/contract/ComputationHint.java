package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ComputationHint(
    @JsonProperty(value = "broad_search") @ContractNonNull Boolean broadSearch,
    @JsonProperty(value = "decision_use", required = true) @ContractNonNull String decisionUse,
    @JsonProperty(value = "purpose", required = true) @ContractNonNull ComputationPurpose purpose,
    @JsonProperty(value = "suggested_method", required = true) @ContractNonNull ComputationMethod suggestedMethod,
    @JsonProperty(value = "target_claim", required = true) @ContractNonNull String targetClaim
) implements StrictContract {

  public ComputationHint {
    if (broadSearch == null) {
      broadSearch = false;
    }
    decisionUse = ContractStrings.trim(decisionUse);
    decisionUse = ContractStrings.required("decision_use", decisionUse);
    purpose = ContractValues.required("purpose", purpose);
    suggestedMethod = ContractValues.required("suggested_method", suggestedMethod);
    targetClaim = ContractStrings.trim(targetClaim);
    targetClaim = ContractStrings.required("target_claim", targetClaim);
  }
}
