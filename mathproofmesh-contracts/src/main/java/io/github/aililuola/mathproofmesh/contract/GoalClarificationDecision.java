package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record GoalClarificationDecision(
    @JsonProperty(value = "canonical_statement", required = true) @ContractNonNull String canonicalStatement,
    @JsonProperty(value = "request_id", required = true) @ContractNonNull String requestId,
    @JsonProperty(value = "selected_candidate_index") Integer selectedCandidateIndex,
    @JsonProperty(value = "source") @ContractNonNull String source
) implements StrictContract {

  public GoalClarificationDecision {
    canonicalStatement = ContractStrings.trim(canonicalStatement);
    canonicalStatement = ContractStrings.required("canonical_statement", canonicalStatement);
    ContractValues.minimumLength("canonical_statement", canonicalStatement, 1);
    requestId = ContractStrings.trim(requestId);
    requestId = ContractStrings.required("request_id", requestId);
    ContractValues.minimum("selected_candidate_index", selectedCandidateIndex, 0);
    if (source == null) {
      source = "user_confirmed";
    }
    source = ContractStrings.trim(source);
    ContractValues.oneOf("source", source, "user_confirmed", "auto_assumed");
  }
}
