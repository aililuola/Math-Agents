package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record GoalInterpretationCandidate(
    @JsonProperty(value = "confidence", required = true) @ContractNonNull Double confidence,
    @JsonProperty(value = "rationale", required = true) @ContractNonNull String rationale,
    @JsonProperty(value = "statement", required = true) @ContractNonNull String statement
) implements StrictContract {

  public GoalInterpretationCandidate {
    confidence = ContractValues.required("confidence", confidence);
    ContractValues.minimum("confidence", confidence, 0.0);
    ContractValues.maximum("confidence", confidence, 1.0);
    rationale = ContractStrings.trim(rationale);
    rationale = ContractStrings.required("rationale", rationale);
    ContractValues.minimumLength("rationale", rationale, 1);
    statement = ContractStrings.trim(statement);
    statement = ContractStrings.required("statement", statement);
    ContractValues.minimumLength("statement", statement, 1);
  }
}
