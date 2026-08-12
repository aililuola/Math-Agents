package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record GoalNormalizationAssessment(
    @JsonProperty(value = "alternative_interpretations") @ContractNonNull List<GoalInterpretationCandidate> alternativeInterpretations,
    @JsonProperty(value = "ambiguity_reasons") @ContractNonNull List<String> ambiguityReasons,
    @JsonProperty(value = "changes_mathematical_meaning", required = true) @ContractNonNull Boolean changesMathematicalMeaning,
    @JsonProperty(value = "clarification_question") String clarificationQuestion,
    @JsonProperty(value = "has_ambiguity", required = true) @ContractNonNull Boolean hasAmbiguity,
    @JsonProperty(value = "is_well_formed", required = true) @ContractNonNull Boolean isWellFormed,
    @JsonProperty(value = "recommendation_confidence", required = true) @ContractNonNull Double recommendationConfidence,
    @JsonProperty(value = "recommended_statement", required = true) @ContractNonNull String recommendedStatement
) implements StrictContract {

  public GoalNormalizationAssessment {
    if (alternativeInterpretations == null) {
      alternativeInterpretations = List.of();
    }
    alternativeInterpretations = ImmutableCollections.listOrEmpty(alternativeInterpretations);
    if (ambiguityReasons == null) {
      ambiguityReasons = List.of();
    }
    ambiguityReasons = ImmutableCollections.listOrEmpty(ambiguityReasons);
    changesMathematicalMeaning = ContractValues.required("changes_mathematical_meaning", changesMathematicalMeaning);
    clarificationQuestion = ContractStrings.trim(clarificationQuestion);
    hasAmbiguity = ContractValues.required("has_ambiguity", hasAmbiguity);
    isWellFormed = ContractValues.required("is_well_formed", isWellFormed);
    recommendationConfidence = ContractValues.required("recommendation_confidence", recommendationConfidence);
    ContractValues.minimum("recommendation_confidence", recommendationConfidence, 0.0);
    ContractValues.maximum("recommendation_confidence", recommendationConfidence, 1.0);
    recommendedStatement = ContractStrings.trim(recommendedStatement);
    recommendedStatement = ContractStrings.required("recommended_statement", recommendedStatement);
    ContractValues.minimumLength("recommended_statement", recommendedStatement, 1);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<GoalInterpretationCandidate> alternativeInterpretations() {
    return alternativeInterpretations == null ? null : List.copyOf(alternativeInterpretations);
  }

  public List<String> ambiguityReasons() {
    return ambiguityReasons == null ? null : List.copyOf(ambiguityReasons);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
