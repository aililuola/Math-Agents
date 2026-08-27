package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record CandidateAssessment(
    @JsonProperty(value = "recommended_action", required = true) @ContractNonNull ActionKind recommendedAction,
    @JsonProperty(value = "score", required = true) @ContractNonNull Double score,
    @JsonProperty(value = "strengths") @ContractNonNull List<String> strengths,
    @JsonProperty(value = "target_id", required = true) @ContractNonNull String targetId,
    @JsonProperty(value = "weaknesses") @ContractNonNull List<String> weaknesses
) implements StrictContract {

  public CandidateAssessment {
    recommendedAction = ContractValues.required("recommended_action", recommendedAction);
    score = ContractValues.required("score", score);
    ContractValues.minimum("score", score, 0.0);
    ContractValues.maximum("score", score, 1.0);
    if (strengths == null) {
      strengths = List.of();
    }
    strengths = ImmutableCollections.listOrEmpty(strengths);
    targetId = ContractStrings.trim(targetId);
    targetId = ContractStrings.required("target_id", targetId);
    if (weaknesses == null) {
      weaknesses = List.of();
    }
    weaknesses = ImmutableCollections.listOrEmpty(weaknesses);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> strengths() {
    return strengths == null ? null : List.copyOf(strengths);
  }

  public List<String> weaknesses() {
    return weaknesses == null ? null : List.copyOf(weaknesses);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
