package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ComputationCertificate(
    @JsonProperty(value = "artifact_refs") @ContractNonNull List<String> artifactRefs,
    @JsonProperty(value = "certificate_id") @ContractNonNull String certificateId,
    @JsonProperty(value = "evidence_type", required = true) @ContractNonNull EvidenceType evidenceType,
    @JsonProperty(value = "experiment_id", required = true) @ContractNonNull String experimentId,
    @JsonProperty(value = "independently_verified") @ContractNonNull Boolean independentlyVerified,
    @JsonProperty(value = "outcome", required = true) @ContractNonNull ExperimentOutcome outcome,
    @JsonProperty(value = "request_hash", required = true) @ContractNonNull String requestHash,
    @JsonProperty(value = "result_hash", required = true) @ContractNonNull String resultHash,
    @JsonProperty(value = "scope") @ContractNonNull ObjectNode scope,
    @JsonProperty(value = "target_claim", required = true) @ContractNonNull String targetClaim
) implements StrictContract {

  public ComputationCertificate {
    if (artifactRefs == null) {
      artifactRefs = List.of();
    }
    artifactRefs = ImmutableCollections.listOrEmpty(artifactRefs);
    if (certificateId == null) {
      certificateId = PythonCompatibleIdGenerator.newId("compute_cert");
    }
    certificateId = ContractStrings.trim(certificateId);
    evidenceType = ContractValues.required("evidence_type", evidenceType);
    experimentId = ContractStrings.trim(experimentId);
    experimentId = ContractStrings.required("experiment_id", experimentId);
    if (independentlyVerified == null) {
      independentlyVerified = false;
    }
    outcome = ContractValues.required("outcome", outcome);
    requestHash = ContractStrings.trim(requestHash);
    requestHash = ContractStrings.required("request_hash", requestHash);
    resultHash = ContractStrings.trim(resultHash);
    resultHash = ContractStrings.required("result_hash", resultHash);
    if (scope == null) {
      scope = JsonNodeFactory.instance.objectNode();
    }
    scope = ContractValues.objectOrEmpty(scope);
    targetClaim = ContractStrings.trim(targetClaim);
    targetClaim = ContractStrings.required("target_claim", targetClaim);
  }

  public static ComputationCertificate fromResult(ExperimentResult result) {
    ContractValues.required("result", result);
    EvidenceType evidenceType =
        switch (result.evidenceStrength()) {
          case HEURISTIC -> EvidenceType.NUMERICAL_HEURISTIC;
          case BOUNDED_EVIDENCE -> EvidenceType.BOUNDED_EXPERIMENT;
          case COUNTEREXAMPLE -> EvidenceType.COUNTEREXAMPLE;
          case EXHAUSTIVE_CERTIFICATE -> EvidenceType.COMPLETE_FINITE_ENUMERATION;
          case FORMAL_CERTIFICATE -> EvidenceType.FORMAL_KERNEL_CERTIFICATE;
        };
    return new ComputationCertificate(
        result.artifactRefs().stream().map(EvidenceRef::artifactRef).toList(),
        null,
        evidenceType,
        result.experimentId(),
        result.independentlyVerified(),
        result.outcome(),
        result.requestHash(),
        result.resultHash(),
        result.scope(),
        result.targetClaim());
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> artifactRefs() {
    return artifactRefs == null ? null : List.copyOf(artifactRefs);
  }

  public ObjectNode scope() {
    return scope == null ? null : scope.deepCopy();
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
