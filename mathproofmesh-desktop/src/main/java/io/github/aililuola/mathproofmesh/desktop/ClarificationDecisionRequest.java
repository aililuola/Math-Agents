package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ClarificationDecisionRequest(
    @JsonProperty("request_id") String requestId,
    @JsonProperty("canonical_statement") String canonicalStatement,
    @JsonProperty("selected_candidate_index") Integer selectedCandidateIndex) {
  public ClarificationDecisionRequest {
    requestId = required(requestId, 160, "request_id");
    canonicalStatement = required(canonicalStatement, 2_000_000, "canonical_statement");
    if (selectedCandidateIndex != null && selectedCandidateIndex < 0) {
      throw new IllegalArgumentException("selected_candidate_index must be nonnegative");
    }
  }

  private static String required(String value, int maximum, String name) {
    String safe = value == null ? "" : value.trim();
    if (safe.isEmpty() || safe.length() > maximum) {
      throw new IllegalArgumentException(name + " has an invalid length");
    }
    return safe;
  }
}
