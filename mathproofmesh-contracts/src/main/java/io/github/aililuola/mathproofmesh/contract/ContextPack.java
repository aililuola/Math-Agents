package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ContextPack(
    @JsonProperty(value = "evidence_refs") @ContractNonNull List<EvidenceRef> evidenceRefs,
    @JsonProperty(value = "notes") @ContractNonNull List<String> notes,
    @JsonProperty(value = "problem", required = true) @ContractNonNull ProblemContract problem,
    @JsonProperty(value = "proof_checkpoint") ProofCheckpoint proofCheckpoint,
    @JsonProperty(value = "remaining_call_budget") @ContractNonNull Integer remainingCallBudget,
    @JsonProperty(value = "round_index") @ContractNonNull Integer roundIndex,
    @JsonProperty(value = "strategy") StrategyCard strategy,
    @JsonProperty(value = "targeted_feedback") @ContractNonNull List<String> targetedFeedback,
    @JsonProperty(value = "uncertain_claims") @ContractNonNull List<ClaimCard> uncertainClaims,
    @JsonProperty(value = "verified_claims") @ContractNonNull List<ClaimCard> verifiedClaims
) implements StrictContract {

  public ContextPack {
    if (evidenceRefs == null) {
      evidenceRefs = List.of();
    }
    evidenceRefs = ImmutableCollections.listOrEmpty(evidenceRefs);
    if (notes == null) {
      notes = List.of();
    }
    notes = ImmutableCollections.listOrEmpty(notes);
    problem = ContractValues.required("problem", problem);
    if (remainingCallBudget == null) {
      remainingCallBudget = 0;
    }
    if (roundIndex == null) {
      roundIndex = 0;
    }
    if (targetedFeedback == null) {
      targetedFeedback = List.of();
    }
    targetedFeedback = ImmutableCollections.listOrEmpty(targetedFeedback);
    if (uncertainClaims == null) {
      uncertainClaims = List.of();
    }
    uncertainClaims = ImmutableCollections.listOrEmpty(uncertainClaims);
    if (verifiedClaims == null) {
      verifiedClaims = List.of();
    }
    verifiedClaims = ImmutableCollections.listOrEmpty(verifiedClaims);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<EvidenceRef> evidenceRefs() {
    return evidenceRefs == null ? null : List.copyOf(evidenceRefs);
  }

  public List<String> notes() {
    return notes == null ? null : List.copyOf(notes);
  }

  public List<String> targetedFeedback() {
    return targetedFeedback == null ? null : List.copyOf(targetedFeedback);
  }

  public List<ClaimCard> uncertainClaims() {
    return uncertainClaims == null ? null : List.copyOf(uncertainClaims);
  }

  public List<ClaimCard> verifiedClaims() {
    return verifiedClaims == null ? null : List.copyOf(verifiedClaims);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
