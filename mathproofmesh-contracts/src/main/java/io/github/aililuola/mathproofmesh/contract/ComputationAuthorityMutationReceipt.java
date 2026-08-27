package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Durable receipt for the real mathematical state mutation performed by an authority gate. */
public record ComputationAuthorityMutationReceipt(
    @JsonProperty(value = "mutation_id", required = true) @ContractNonNull String mutationId,
    @JsonProperty(value = "execution_id", required = true) @ContractNonNull String executionId,
    @JsonProperty(value = "target_binding_hash", required = true) @ContractNonNull
        String targetBindingHash,
    @JsonProperty(value = "action", required = true) @ContractNonNull
        ComputationDecisionAction action,
    @JsonProperty(value = "fact_message_id") String factMessageId,
    @JsonProperty(value = "counterexample_message_id") String counterexampleMessageId,
    @JsonProperty(value = "closed_obligation_id") String closedObligationId,
    @JsonProperty(value = "refuted_obligation_id") String refutedObligationId,
    @JsonProperty(value = "claim_court_evidence_id") String claimCourtEvidenceId,
    @JsonProperty(value = "mutation_hash") String mutationHash) {
  public ComputationAuthorityMutationReceipt {
    mutationId = required(mutationId, "mutationId");
    executionId = required(executionId, "executionId");
    targetBindingHash = required(targetBindingHash, "targetBindingHash");
    action = ContractValues.required("action", action);
    factMessageId = normalize(factMessageId);
    counterexampleMessageId = normalize(counterexampleMessageId);
    closedObligationId = normalize(closedObligationId);
    refutedObligationId = normalize(refutedObligationId);
    claimCourtEvidenceId = normalize(claimCourtEvidenceId);
    MutationHashPayload payload =
        new MutationHashPayload(
            mutationId,
            executionId,
            targetBindingHash,
            action,
            factMessageId,
            counterexampleMessageId,
            closedObligationId,
            refutedObligationId,
            claimCourtEvidenceId);
    String expected = CanonicalJson.stableHash(payload);
    mutationHash =
        mutationHash == null || mutationHash.isBlank() ? expected : mutationHash.strip();
    if (!ContractHashes.sameHash(expected, mutationHash)) {
      throw new ContractValidationException("mutation_hash does not match authority mutation");
    }
  }

  public boolean changedMathematicalAuthority() {
    return !factMessageId.isEmpty()
        || !counterexampleMessageId.isEmpty()
        || !closedObligationId.isEmpty()
        || !refutedObligationId.isEmpty()
        || !claimCourtEvidenceId.isEmpty();
  }

  private record MutationHashPayload(
      @JsonProperty("mutation_id") String mutationId,
      @JsonProperty("execution_id") String executionId,
      @JsonProperty("target_binding_hash") String targetBindingHash,
      @JsonProperty("action") ComputationDecisionAction action,
      @JsonProperty("fact_message_id") String factMessageId,
      @JsonProperty("counterexample_message_id") String counterexampleMessageId,
      @JsonProperty("closed_obligation_id") String closedObligationId,
      @JsonProperty("refuted_obligation_id") String refutedObligationId,
      @JsonProperty("claim_court_evidence_id") String claimCourtEvidenceId) {}

  private static String required(String value, String field) {
    String normalized = normalize(value);
    if (normalized.isEmpty()) {
      throw new ContractValidationException(field + " is required");
    }
    return normalized;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip();
  }
}
