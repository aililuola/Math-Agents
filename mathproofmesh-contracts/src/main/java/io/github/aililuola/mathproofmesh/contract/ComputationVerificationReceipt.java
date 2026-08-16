package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Server-issued receipt from a verifier that is independent of the producer. */
public record ComputationVerificationReceipt(
    @JsonProperty(value = "receipt_id", required = true) @ContractNonNull String receiptId,
    @JsonProperty(value = "certificate_hash", required = true)
        @ContractNonNull
        String certificateHash,
    @JsonProperty(value = "verifier_id", required = true) @ContractNonNull String verifierId,
    @JsonProperty(value = "verifier_version", required = true)
        @ContractNonNull
        String verifierVersion,
    @JsonProperty(value = "status", required = true)
        @ContractNonNull
        ComputationVerificationStatus status,
    @JsonProperty(value = "valid") @ContractNonNull Boolean valid,
    @JsonProperty(value = "authority", required = true)
        @ContractNonNull
        ComputationVerifiedAuthority authority,
    @JsonProperty(value = "verified_scope_hash", required = true)
        @ContractNonNull
        String verifiedScopeHash,
    @JsonProperty(value = "diagnostics") @ContractNonNull List<String> diagnostics,
    @JsonProperty(value = "created_at") @ContractNonNull String createdAt,
    @JsonProperty(value = "receipt_hash") @ContractNonNull String receiptHash) {

  public ComputationVerificationReceipt {
    receiptId = required(receiptId, "receiptId");
    certificateHash = required(certificateHash, "certificateHash");
    verifierId = required(verifierId, "verifierId");
    verifierVersion = required(verifierVersion, "verifierVersion");
    status = ContractValues.required("status", status);
    valid = Boolean.TRUE.equals(valid);
    authority = ContractValues.required("authority", authority);
    verifiedScopeHash = required(verifiedScopeHash, "verifiedScopeHash");
    diagnostics = diagnostics == null ? List.of() : ImmutableCollections.listOrEmpty(diagnostics);
    createdAt = createdAt == null ? PythonIsoTimestampCodec.now() : required(createdAt, "createdAt");
    if (valid != (status == ComputationVerificationStatus.VALID)) {
      throw new ContractValidationException("valid must agree with verification status");
    }
    if (!valid && authority != ComputationVerifiedAuthority.AUDIT_ONLY) {
      throw new ContractValidationException("invalid verification cannot carry mathematical authority");
    }
    String expected =
        CanonicalJson.stableHash(
            payload(
                receiptId,
                certificateHash,
                verifierId,
                verifierVersion,
                status,
                valid,
                authority,
                verifiedScopeHash,
                diagnostics,
                createdAt));
    receiptHash = ContractHashes.checked("receipt_hash", receiptHash, expected);
  }

  @Override
  public List<String> diagnostics() {
    return List.copyOf(diagnostics);
  }

  private static VerificationReceiptHashPayload payload(
      String receiptId,
      String certificateHash,
      String verifierId,
      String verifierVersion,
      ComputationVerificationStatus status,
      boolean valid,
      ComputationVerifiedAuthority authority,
      String verifiedScopeHash,
      List<String> diagnostics,
      String createdAt) {
    return new VerificationReceiptHashPayload(
        receiptId,
        certificateHash,
        verifierId,
        verifierVersion,
        status,
        valid,
        authority,
        verifiedScopeHash,
        diagnostics,
        createdAt);
  }

  private record VerificationReceiptHashPayload(
      @JsonProperty("receipt_id") String receiptId,
      @JsonProperty("certificate_hash") String certificateHash,
      @JsonProperty("verifier_id") String verifierId,
      @JsonProperty("verifier_version") String verifierVersion,
      @JsonProperty("status") ComputationVerificationStatus status,
      @JsonProperty("valid") boolean valid,
      @JsonProperty("authority") ComputationVerifiedAuthority authority,
      @JsonProperty("verified_scope_hash") String verifiedScopeHash,
      @JsonProperty("diagnostics") List<String> diagnostics,
      @JsonProperty("created_at") String createdAt) {}

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new ContractValidationException(field + " is required");
    }
    return normalized;
  }
}
