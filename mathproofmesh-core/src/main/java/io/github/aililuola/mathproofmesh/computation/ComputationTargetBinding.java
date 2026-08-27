package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.Map;

/** Server-owned exact identity connecting one computation to one authority target. */
public record ComputationTargetBinding(
    String problemHash,
    String claimId,
    String claimStatementHash,
    String claimSemanticHash,
    String obligationId,
    String obligationSemanticHash,
    String canonicalTargetId,
    String scopeHash,
    String polarity,
    boolean isolatedComputationQuestion,
    String bindingHash) {
  public ComputationTargetBinding {
    problemHash = required(problemHash, "problemHash");
    claimId = normalize(claimId);
    claimStatementHash = normalize(claimStatementHash);
    claimSemanticHash = normalize(claimSemanticHash);
    obligationId = required(obligationId, "obligationId");
    obligationSemanticHash = required(obligationSemanticHash, "obligationSemanticHash");
    canonicalTargetId = required(canonicalTargetId, "canonicalTargetId");
    scopeHash = required(scopeHash, "scopeHash");
    polarity = required(polarity, "polarity");
    if (isolatedComputationQuestion) {
      claimId = "";
      claimStatementHash = "";
      claimSemanticHash = "";
    } else if (claimId.isEmpty()
        || claimStatementHash.isEmpty()
        || claimSemanticHash.isEmpty()) {
      throw new IllegalArgumentException("claim-bound computation requires exact claim hashes");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("problem_hash", problemHash);
    payload.put("claim_id", claimId);
    payload.put("claim_statement_hash", claimStatementHash);
    payload.put("claim_semantic_hash", claimSemanticHash);
    payload.put("obligation_id", obligationId);
    payload.put("obligation_semantic_hash", obligationSemanticHash);
    payload.put("canonical_target_id", canonicalTargetId);
    payload.put("scope_hash", scopeHash);
    payload.put("polarity", polarity);
    payload.put("isolated_computation_question", isolatedComputationQuestion);
    String expected = CanonicalJson.stableHash(payload);
    bindingHash = bindingHash == null || bindingHash.isBlank() ? expected : bindingHash.strip();
    if (!ComputationJson.hashesEqual(expected, bindingHash)) {
      throw new IllegalArgumentException("computation target binding hash mismatch");
    }
  }

  private static String required(String value, String field) {
    String normalized = normalize(value);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip();
  }
}
