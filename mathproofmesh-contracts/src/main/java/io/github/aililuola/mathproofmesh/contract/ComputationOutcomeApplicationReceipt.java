package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Exactly-once receipt for a typed outcome projection through existing authority gates. */
public record ComputationOutcomeApplicationReceipt(
    @JsonProperty(value = "application_id", required = true)
        @ContractNonNull
        String applicationId,
    @JsonProperty(value = "execution_id", required = true) @ContractNonNull String executionId,
    @JsonProperty(value = "decision_plan_hash", required = true)
        @ContractNonNull
        String decisionPlanHash,
    @JsonProperty(value = "verification_receipt_hash", required = true)
        @ContractNonNull
        String verificationReceiptHash,
    @JsonProperty(value = "action", required = true)
        @ContractNonNull
        ComputationDecisionAction action,
    @JsonProperty(value = "applied") @ContractNonNull Boolean applied,
    @JsonProperty(value = "diagnostic") @ContractNonNull String diagnostic,
    @JsonProperty(value = "receipt_hash") @ContractNonNull String receiptHash) {
  public ComputationOutcomeApplicationReceipt {
    applicationId = required(applicationId, "applicationId");
    executionId = required(executionId, "executionId");
    decisionPlanHash = required(decisionPlanHash, "decisionPlanHash");
    verificationReceiptHash = required(verificationReceiptHash, "verificationReceiptHash");
    action = ContractValues.required("action", action);
    applied = Boolean.TRUE.equals(applied);
    diagnostic = diagnostic == null ? "" : diagnostic.strip();
    ReceiptHashPayload values =
        new ReceiptHashPayload(
            applicationId,
            executionId,
            decisionPlanHash,
            verificationReceiptHash,
            action,
            applied,
            diagnostic);
    receiptHash =
        ContractHashes.checked(
            "receipt_hash", receiptHash, CanonicalJson.stableHash(values));
  }

  private record ReceiptHashPayload(
      @JsonProperty("application_id") String applicationId,
      @JsonProperty("execution_id") String executionId,
      @JsonProperty("decision_plan_hash") String decisionPlanHash,
      @JsonProperty("verification_receipt_hash") String verificationReceiptHash,
      @JsonProperty("action") ComputationDecisionAction action,
      @JsonProperty("applied") boolean applied,
      @JsonProperty("diagnostic") String diagnostic) {}

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new ContractValidationException(field + " is required");
    }
    return normalized;
  }
}
