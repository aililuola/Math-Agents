package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ProblemSemanticViewCandidate(
    @JsonProperty(value = "confidence", required = true) @ContractNonNull Double confidence,
    @JsonProperty(value = "english_statement", required = true) @ContractNonNull String englishStatement,
    @JsonProperty(value = "notes") @ContractNonNull List<String> notes,
    @JsonProperty(value = "preserves_conclusion", required = true) @ContractNonNull Boolean preservesConclusion,
    @JsonProperty(value = "preserves_domains", required = true) @ContractNonNull Boolean preservesDomains,
    @JsonProperty(value = "preserves_hypotheses", required = true) @ContractNonNull Boolean preservesHypotheses,
    @JsonProperty(value = "preserves_quantifiers", required = true) @ContractNonNull Boolean preservesQuantifiers
) implements StrictContract {

  public ProblemSemanticViewCandidate {
    confidence = ContractValues.required("confidence", confidence);
    ContractValues.minimum("confidence", confidence, 0.0);
    ContractValues.maximum("confidence", confidence, 1.0);
    englishStatement = ContractStrings.trim(englishStatement);
    englishStatement = ContractStrings.required("english_statement", englishStatement);
    ContractValues.minimumLength("english_statement", englishStatement, 1);
    if (notes == null) {
      notes = List.of();
    }
    notes = ImmutableCollections.listOrEmpty(notes);
    preservesConclusion = ContractValues.required("preserves_conclusion", preservesConclusion);
    preservesDomains = ContractValues.required("preserves_domains", preservesDomains);
    preservesHypotheses = ContractValues.required("preserves_hypotheses", preservesHypotheses);
    preservesQuantifiers = ContractValues.required("preserves_quantifiers", preservesQuantifiers);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> notes() {
    return notes == null ? null : List.copyOf(notes);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
