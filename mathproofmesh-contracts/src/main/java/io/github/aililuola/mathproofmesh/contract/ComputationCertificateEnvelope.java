package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Immutable, claim-neutral certificate emitted by a computation producer. */
public record ComputationCertificateEnvelope(
    @JsonProperty(value = "request_hash", required = true) @ContractNonNull String requestHash,
    @JsonProperty(value = "execution_hash", required = true) @ContractNonNull String executionHash,
    @JsonProperty(value = "capability_id", required = true) @ContractNonNull String capabilityId,
    @JsonProperty(value = "capability_version", required = true)
        @ContractNonNull
        String capabilityVersion,
    @JsonProperty(value = "scope_hash", required = true) @ContractNonNull String scopeHash,
    @JsonProperty(value = "domain_hash", required = true) @ContractNonNull String domainHash,
    @JsonProperty(value = "result_hash", required = true) @ContractNonNull String resultHash,
    @JsonProperty(value = "certificate_type", required = true)
        @ContractNonNull
        ComputationCertificateType certificateType,
    @JsonProperty(value = "cases_expected") @ContractNonNull Integer casesExpected,
    @JsonProperty(value = "cases_checked") @ContractNonNull Integer casesChecked,
    @JsonProperty(value = "witness") @ContractNonNull ObjectNode witness,
    @JsonProperty(value = "coverage_digest") @ContractNonNull String coverageDigest,
    @JsonProperty(value = "producer_id", required = true) @ContractNonNull String producerId,
    @JsonProperty(value = "producer_version", required = true)
        @ContractNonNull
        String producerVersion,
    @JsonProperty(value = "certificate_hash") @ContractNonNull String certificateHash) {

  public ComputationCertificateEnvelope {
    requestHash = required(requestHash, "requestHash");
    executionHash = required(executionHash, "executionHash");
    capabilityId = required(capabilityId, "capabilityId");
    capabilityVersion = required(capabilityVersion, "capabilityVersion");
    scopeHash = required(scopeHash, "scopeHash");
    domainHash = required(domainHash, "domainHash");
    resultHash = required(resultHash, "resultHash");
    certificateType = ContractValues.required("certificate_type", certificateType);
    if (casesExpected == null) {
      casesExpected = Integer.valueOf(0);
    }
    if (casesChecked == null) {
      casesChecked = Integer.valueOf(0);
    }
    ContractValues.minimum("cases_expected", casesExpected, 0);
    ContractValues.minimum("cases_checked", casesChecked, 0);
    if (casesExpected > 0 && casesChecked > casesExpected) {
      throw new ContractValidationException("cases_checked exceeds cases_expected");
    }
    witness =
        witness == null
            ? JsonNodeFactory.instance.objectNode()
            : ContractValues.objectOrEmpty(witness);
    coverageDigest = required(coverageDigest, "coverageDigest");
    producerId = required(producerId, "producerId");
    producerVersion = required(producerVersion, "producerVersion");
    String expected =
        CanonicalJson.stableHash(
            payload(
                requestHash,
                executionHash,
                capabilityId,
                capabilityVersion,
                scopeHash,
                domainHash,
                resultHash,
                certificateType,
                casesExpected,
                casesChecked,
                witness,
                coverageDigest,
                producerId,
                producerVersion));
    certificateHash = ContractHashes.checked("certificate_hash", certificateHash, expected);
  }

  @Override
  public ObjectNode witness() {
    return witness.deepCopy();
  }

  private static CertificateHashPayload payload(
      String requestHash,
      String executionHash,
      String capabilityId,
      String capabilityVersion,
      String scopeHash,
      String domainHash,
      String resultHash,
      ComputationCertificateType certificateType,
      int casesExpected,
      int casesChecked,
      ObjectNode witness,
      String coverageDigest,
      String producerId,
      String producerVersion) {
    return new CertificateHashPayload(
        requestHash,
        executionHash,
        capabilityId,
        capabilityVersion,
        scopeHash,
        domainHash,
        resultHash,
        certificateType,
        casesExpected,
        casesChecked,
        witness,
        coverageDigest,
        producerId,
        producerVersion);
  }

  private record CertificateHashPayload(
      @JsonProperty("request_hash") String requestHash,
      @JsonProperty("execution_hash") String executionHash,
      @JsonProperty("capability_id") String capabilityId,
      @JsonProperty("capability_version") String capabilityVersion,
      @JsonProperty("scope_hash") String scopeHash,
      @JsonProperty("domain_hash") String domainHash,
      @JsonProperty("result_hash") String resultHash,
      @JsonProperty("certificate_type") ComputationCertificateType certificateType,
      @JsonProperty("cases_expected") int casesExpected,
      @JsonProperty("cases_checked") int casesChecked,
      @JsonProperty("witness") ObjectNode witness,
      @JsonProperty("coverage_digest") String coverageDigest,
      @JsonProperty("producer_id") String producerId,
      @JsonProperty("producer_version") String producerVersion) {}

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new ContractValidationException(field + " is required");
    }
    return normalized;
  }
}
