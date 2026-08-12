package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ClaimBatch(
    @JsonProperty(value = "attempt_id", required = true) @ContractNonNull String attemptId,
    @JsonProperty(value = "claims") @ContractNonNull List<ClaimCard> claims,
    @JsonProperty(value = "discarded_material") @ContractNonNull List<String> discardedMaterial,
    @JsonProperty(value = "reusable_insights") @ContractNonNull List<String> reusableInsights,
    @JsonProperty(value = "summary", required = true) @ContractNonNull String summary
) implements StrictContract {

  public ClaimBatch {
    attemptId = ContractStrings.trim(attemptId);
    attemptId = ContractStrings.required("attempt_id", attemptId);
    if (claims == null) {
      claims = List.of();
    }
    claims = ImmutableCollections.listOrEmpty(claims);
    if (discardedMaterial == null) {
      discardedMaterial = List.of();
    }
    discardedMaterial = ImmutableCollections.listOrEmpty(discardedMaterial);
    if (reusableInsights == null) {
      reusableInsights = List.of();
    }
    reusableInsights = ImmutableCollections.listOrEmpty(reusableInsights);
    summary = ContractStrings.trim(summary);
    summary = ContractStrings.required("summary", summary);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<ClaimCard> claims() {
    return claims == null ? null : List.copyOf(claims);
  }

  public List<String> discardedMaterial() {
    return discardedMaterial == null ? null : List.copyOf(discardedMaterial);
  }

  public List<String> reusableInsights() {
    return reusableInsights == null ? null : List.copyOf(reusableInsights);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
