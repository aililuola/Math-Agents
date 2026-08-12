package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ExperimentResult(
    @JsonProperty(value = "artifact_refs") @ContractNonNull List<EvidenceRef> artifactRefs,
    @JsonProperty(value = "cached") @ContractNonNull Boolean cached,
    @JsonProperty(value = "cases_checked") @ContractNonNull Integer casesChecked,
    @JsonProperty(value = "certificate") ObjectNode certificate,
    @JsonProperty(value = "counterexample") ObjectNode counterexample,
    @JsonProperty(value = "created_at") @ContractNonNull String createdAt,
    @JsonProperty(value = "error") String error,
    @JsonProperty(value = "evidence_strength", required = true) @ContractNonNull EvidenceStrength evidenceStrength,
    @JsonProperty(value = "exact_arithmetic") @ContractNonNull Boolean exactArithmetic,
    @JsonProperty(value = "experiment_id", required = true) @ContractNonNull String experimentId,
    @JsonProperty(value = "independently_verified") @ContractNonNull Boolean independentlyVerified,
    @JsonProperty(value = "method", required = true) @ContractNonNull ComputationMethod method,
    @JsonProperty(value = "outcome", required = true) @ContractNonNull ExperimentOutcome outcome,
    @JsonProperty(value = "parent_checkpoint_id") String parentCheckpointId,
    @JsonProperty(value = "path_id") String pathId,
    @JsonProperty(value = "program_hash") String programHash,
    @JsonProperty(value = "request_hash", required = true) @ContractNonNull String requestHash,
    @JsonProperty(value = "result_hash") @ContractNonNull String resultHash,
    @JsonProperty(value = "runtime_seconds") @ContractNonNull Double runtimeSeconds,
    @JsonProperty(value = "scope") @ContractNonNull ObjectNode scope,
    @JsonProperty(value = "target_claim", required = true) @ContractNonNull String targetClaim,
    @JsonProperty(value = "target_claim_id") String targetClaimId,
    @JsonProperty(value = "tool_name", required = true) @ContractNonNull String toolName,
    @JsonProperty(value = "tool_version", required = true) @ContractNonNull String toolVersion,
    @JsonProperty(value = "verification_notes") @ContractNonNull List<String> verificationNotes
) implements StrictContract {

  public ExperimentResult {
    if (artifactRefs == null) {
      artifactRefs = List.of();
    }
    artifactRefs = ImmutableCollections.listOrEmpty(artifactRefs);
    if (cached == null) {
      cached = false;
    }
    if (casesChecked == null) {
      casesChecked = 0;
    }
    ContractValues.minimum("cases_checked", casesChecked, 0);
    certificate = ContractValues.copyObject(certificate);
    counterexample = ContractValues.copyObject(counterexample);
    if (createdAt == null) {
      createdAt = PythonIsoTimestampCodec.now();
    }
    createdAt = ContractStrings.trim(createdAt);
    error = ContractStrings.trim(error);
    evidenceStrength = ContractValues.required("evidence_strength", evidenceStrength);
    if (exactArithmetic == null) {
      exactArithmetic = false;
    }
    experimentId = ContractStrings.trim(experimentId);
    experimentId = ContractStrings.required("experiment_id", experimentId);
    if (independentlyVerified == null) {
      independentlyVerified = false;
    }
    method = ContractValues.required("method", method);
    outcome = ContractValues.required("outcome", outcome);
    parentCheckpointId = ContractStrings.trim(parentCheckpointId);
    pathId = ContractStrings.trim(pathId);
    programHash = ContractStrings.trim(programHash);
    requestHash = ContractStrings.trim(requestHash);
    requestHash = ContractStrings.required("request_hash", requestHash);
    if (resultHash == null) {
      resultHash = "";
    }
    resultHash = ContractStrings.trim(resultHash);
    if (runtimeSeconds == null) {
      runtimeSeconds = 0.0d;
    }
    ContractValues.minimum("runtime_seconds", runtimeSeconds, 0.0);
    if (scope == null) {
      scope = JsonNodeFactory.instance.objectNode();
    }
    scope = ContractValues.objectOrEmpty(scope);
    targetClaim = ContractStrings.trim(targetClaim);
    targetClaim = ContractStrings.required("target_claim", targetClaim);
    targetClaimId = ContractStrings.trim(targetClaimId);
    toolName = ContractStrings.trim(toolName);
    toolName = ContractStrings.required("tool_name", toolName);
    toolVersion = ContractStrings.trim(toolVersion);
    toolVersion = ContractStrings.required("tool_version", toolVersion);
    if (verificationNotes == null) {
      verificationNotes = List.of();
    }
    verificationNotes = ImmutableCollections.listOrEmpty(verificationNotes);
    if (outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND) {
      if (counterexample == null) {
        throw new ContractValidationException(
            "counterexample_found requires a counterexample payload");
      }
      if (evidenceStrength != EvidenceStrength.COUNTEREXAMPLE) {
        throw new ContractValidationException(
            "a counterexample must use counterexample evidence strength");
      }
      if (!independentlyVerified) {
        throw new ContractValidationException(
            "counterexample_found requires independent deterministic verification");
      }
    }
    if (outcome == ExperimentOutcome.CERTIFIED) {
      if (certificate == null) {
        throw new ContractValidationException(
            "certified requires a certificate payload");
      }
      if (evidenceStrength != EvidenceStrength.EXHAUSTIVE_CERTIFICATE
          && evidenceStrength != EvidenceStrength.FORMAL_CERTIFICATE) {
        throw new ContractValidationException(
            "certified requires exhaustive_certificate or formal_certificate evidence");
      }
    }
    if (outcome == ExperimentOutcome.NOT_REFUTED
        && evidenceStrength != EvidenceStrength.HEURISTIC
        && evidenceStrength != EvidenceStrength.BOUNDED_EVIDENCE) {
      throw new ContractValidationException(
          "not_refuted can only be heuristic or bounded evidence");
    }
    if ((outcome == ExperimentOutcome.ERROR
            || outcome == ExperimentOutcome.INCONCLUSIVE)
        && evidenceStrength != EvidenceStrength.HEURISTIC) {
      throw new ContractValidationException(
          "failed or inconclusive computation is only heuristic");
    }
    resultHash =
        ContractHashes.checked(
            "result_hash",
            resultHash,
            ContractHashes.experimentResultHash(
                requestHash,
                targetClaim,
                method,
                outcome,
                evidenceStrength,
                scope,
                counterexample,
                certificate,
                exactArithmetic,
                casesChecked,
                toolName,
                toolVersion,
                programHash,
                independentlyVerified,
                verificationNotes,
                error));
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<EvidenceRef> artifactRefs() {
    return artifactRefs == null ? null : List.copyOf(artifactRefs);
  }

  public ObjectNode certificate() {
    return certificate == null ? null : certificate.deepCopy();
  }

  public ObjectNode counterexample() {
    return counterexample == null ? null : counterexample.deepCopy();
  }

  public ObjectNode scope() {
    return scope == null ? null : scope.deepCopy();
  }

  public List<String> verificationNotes() {
    return verificationNotes == null ? null : List.copyOf(verificationNotes);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
