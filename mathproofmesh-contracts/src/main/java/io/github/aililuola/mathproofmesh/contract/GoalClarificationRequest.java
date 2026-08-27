package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record GoalClarificationRequest(
    @JsonProperty(value = "assessment", required = true) @ContractNonNull GoalNormalizationAssessment assessment,
    @JsonProperty(value = "local_precheck", required = true) @ContractNonNull LocalGoalPrecheck localPrecheck,
    @JsonProperty(value = "original_statement", required = true) @ContractNonNull String originalStatement,
    @JsonProperty(value = "request_id") @ContractNonNull String requestId
) implements StrictContract {

  public GoalClarificationRequest {
    assessment = ContractValues.required("assessment", assessment);
    localPrecheck = ContractValues.required("local_precheck", localPrecheck);
    originalStatement = ContractStrings.trim(originalStatement);
    originalStatement = ContractStrings.required("original_statement", originalStatement);
    ContractValues.minimumLength("original_statement", originalStatement, 1);
    if (requestId == null) {
      requestId = PythonCompatibleIdGenerator.newId("clarification");
    }
    requestId = ContractStrings.trim(requestId);
  }
}
