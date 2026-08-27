package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record BlindReviewPacket(
    @JsonProperty(value = "cited_fact_packets") @ContractNonNull List<ObjectNode> citedFactPackets,
    @JsonProperty(value = "context_purpose") @ContractNonNull String contextPurpose,
    @JsonProperty(value = "fact_context_complete") @ContractNonNull Boolean factContextComplete,
    @JsonProperty(value = "fact_context_failure_reasons") @ContractNonNull List<String> factContextFailureReasons,
    @JsonProperty(value = "final_proof_text", required = true) @ContractNonNull String finalProofText,
    @JsonProperty(value = "forbidden_claims") @ContractNonNull List<String> forbiddenClaims,
    @JsonProperty(value = "missing_cited_fact_refs") @ContractNonNull List<String> missingCitedFactRefs,
    @JsonProperty(value = "negative_context_char_budget") @ContractNonNull Integer negativeContextCharBudget,
    @JsonProperty(value = "negative_context_chars_used") @ContractNonNull Integer negativeContextCharsUsed,
    @JsonProperty(value = "negative_context_complete") @ContractNonNull Boolean negativeContextComplete,
    @JsonProperty(value = "negative_context_truncated") @ContractNonNull Boolean negativeContextTruncated,
    @JsonProperty(value = "negative_evidence_omitted_count") @ContractNonNull Integer negativeEvidenceOmittedCount,
    @JsonProperty(value = "negative_evidence_packets") @ContractNonNull List<ObjectNode> negativeEvidencePackets,
    @JsonProperty(value = "negative_evidence_total_count") @ContractNonNull Integer negativeEvidenceTotalCount,
    @JsonProperty(value = "negative_mandatory_omitted_count") @ContractNonNull Integer negativeMandatoryOmittedCount,
    @JsonProperty(value = "problem", required = true) @ContractNonNull ProblemContract problem
) implements StrictContract {

  public BlindReviewPacket {
    if (citedFactPackets == null) {
      citedFactPackets = List.of();
    }
    citedFactPackets = ImmutableCollections.jsonListOrEmpty(citedFactPackets);
    if (contextPurpose == null) {
      contextPurpose = "blind_review";
    }
    contextPurpose = ContractStrings.trim(contextPurpose);
    ContractValues.constant("context_purpose", contextPurpose, "blind_review");
    if (factContextComplete == null) {
      factContextComplete = true;
    }
    if (factContextFailureReasons == null) {
      factContextFailureReasons = List.of();
    }
    factContextFailureReasons = ImmutableCollections.listOrEmpty(factContextFailureReasons);
    finalProofText = ContractStrings.trim(finalProofText);
    finalProofText = ContractStrings.required("final_proof_text", finalProofText);
    if (forbiddenClaims == null) {
      forbiddenClaims = List.of();
    }
    forbiddenClaims = ImmutableCollections.listOrEmpty(forbiddenClaims);
    if (missingCitedFactRefs == null) {
      missingCitedFactRefs = List.of();
    }
    missingCitedFactRefs = ImmutableCollections.listOrEmpty(missingCitedFactRefs);
    if (negativeContextCharBudget == null) {
      negativeContextCharBudget = 0;
    }
    ContractValues.minimum("negative_context_char_budget", negativeContextCharBudget, 0);
    if (negativeContextCharsUsed == null) {
      negativeContextCharsUsed = 0;
    }
    ContractValues.minimum("negative_context_chars_used", negativeContextCharsUsed, 0);
    if (negativeContextComplete == null) {
      negativeContextComplete = true;
    }
    if (negativeContextTruncated == null) {
      negativeContextTruncated = false;
    }
    if (negativeEvidenceOmittedCount == null) {
      negativeEvidenceOmittedCount = 0;
    }
    ContractValues.minimum("negative_evidence_omitted_count", negativeEvidenceOmittedCount, 0);
    if (negativeEvidencePackets == null) {
      negativeEvidencePackets = List.of();
    }
    negativeEvidencePackets = ImmutableCollections.jsonListOrEmpty(negativeEvidencePackets);
    if (negativeEvidenceTotalCount == null) {
      negativeEvidenceTotalCount = 0;
    }
    ContractValues.minimum("negative_evidence_total_count", negativeEvidenceTotalCount, 0);
    if (negativeMandatoryOmittedCount == null) {
      negativeMandatoryOmittedCount = 0;
    }
    ContractValues.minimum("negative_mandatory_omitted_count", negativeMandatoryOmittedCount, 0);
    problem = ContractValues.required("problem", problem);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<ObjectNode> citedFactPackets() {
    return citedFactPackets == null ? null : ImmutableCollections.copyJsonList(citedFactPackets);
  }

  public List<String> factContextFailureReasons() {
    return factContextFailureReasons == null ? null : List.copyOf(factContextFailureReasons);
  }

  public List<String> forbiddenClaims() {
    return forbiddenClaims == null ? null : List.copyOf(forbiddenClaims);
  }

  public List<String> missingCitedFactRefs() {
    return missingCitedFactRefs == null ? null : List.copyOf(missingCitedFactRefs);
  }

  public List<ObjectNode> negativeEvidencePackets() {
    return negativeEvidencePackets == null ? null : ImmutableCollections.copyJsonList(negativeEvidencePackets);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
