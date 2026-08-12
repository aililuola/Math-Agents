package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record PropositionNormalizationItem(
    @JsonProperty(value = "is_mathematical_proposition") @ContractNonNull Boolean isMathematicalProposition,
    @JsonProperty(value = "normalized_statement", required = true) @ContractNonNull String normalizedStatement,
    @JsonProperty(value = "note") @ContractNonNull String note,
    @JsonProperty(value = "original_statement", required = true) @ContractNonNull String originalStatement
) implements StrictContract {

  public PropositionNormalizationItem {
    if (isMathematicalProposition == null) {
      isMathematicalProposition = true;
    }
    normalizedStatement = ContractStrings.trim(normalizedStatement);
    normalizedStatement = ContractStrings.required("normalized_statement", normalizedStatement);
    if (note == null) {
      note = "";
    }
    note = ContractStrings.trim(note);
    originalStatement = ContractStrings.trim(originalStatement);
    originalStatement = ContractStrings.required("original_statement", originalStatement);
  }
}
