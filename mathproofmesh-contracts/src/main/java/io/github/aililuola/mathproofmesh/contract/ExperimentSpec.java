package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ExperimentSpec(
    @JsonProperty(value = "arguments") @ContractNonNull ObjectNode arguments,
    @JsonProperty(value = "assumptions") @ContractNonNull List<String> assumptions,
    @JsonProperty(value = "broad_search") @ContractNonNull Boolean broadSearch,
    @JsonProperty(value = "decision_if_confirmed", required = true) @ContractNonNull String decisionIfConfirmed,
    @JsonProperty(value = "decision_if_refuted", required = true) @ContractNonNull String decisionIfRefuted,
    @JsonProperty(value = "domains") @ContractNonNull ObjectNode domains,
    @JsonProperty(value = "exact_arithmetic") @ContractNonNull Boolean exactArithmetic,
    @JsonProperty(value = "execution_hash") @ContractNonNull String executionHash,
    @JsonProperty(value = "experiment_id") @ContractNonNull String experimentId,
    @JsonProperty(value = "max_cases") @ContractNonNull Integer maxCases,
    @JsonProperty(value = "method", required = true) @ContractNonNull ComputationMethod method,
    @JsonProperty(value = "noncomputational_alternative", required = true) @ContractNonNull String noncomputationalAlternative,
    @JsonProperty(value = "parent_checkpoint_id") String parentCheckpointId,
    @JsonProperty(value = "path_id") String pathId,
    @JsonProperty(value = "purpose") @ContractNonNull ComputationPurpose purpose,
    @JsonProperty(value = "reasoning_basis", required = true) @ContractNonNull String reasoningBasis,
    @JsonProperty(value = "request_hash") @ContractNonNull String requestHash,
    @JsonProperty(value = "requested_by") String requestedBy,
    @JsonProperty(value = "runtime_fingerprint") @ContractNonNull ObjectNode runtimeFingerprint,
    @JsonProperty(value = "seed") @ContractNonNull Integer seed,
    @JsonProperty(value = "target_claim", required = true) @ContractNonNull String targetClaim,
    @JsonProperty(value = "typed_tool_gap") String typedToolGap,
    @JsonProperty(value = "why_computation_is_needed", required = true) @ContractNonNull String whyComputationIsNeeded,
    @JsonProperty(value = "target_claim_id") @JsonInclude(JsonInclude.Include.NON_NULL)
        String targetClaimId,
    @JsonProperty(value = "claim_evidence_semantic_binding")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ClaimEvidenceSemanticBinding claimEvidenceSemanticBinding
) implements StrictContract {

  public ExperimentSpec {
    if (arguments == null) {
      arguments = JsonNodeFactory.instance.objectNode();
    }
    arguments = ContractValues.objectOrEmpty(arguments);
    if (assumptions == null) {
      assumptions = List.of();
    }
    assumptions = ImmutableCollections.listOrEmpty(assumptions);
    if (broadSearch == null) {
      broadSearch = false;
    }
    decisionIfConfirmed = ContractStrings.trim(decisionIfConfirmed);
    decisionIfConfirmed = ContractStrings.required("decision_if_confirmed", decisionIfConfirmed);
    ContractValues.minimumLength("decision_if_confirmed", decisionIfConfirmed, 1);
    decisionIfRefuted = ContractStrings.trim(decisionIfRefuted);
    decisionIfRefuted = ContractStrings.required("decision_if_refuted", decisionIfRefuted);
    ContractValues.minimumLength("decision_if_refuted", decisionIfRefuted, 1);
    if (domains == null) {
      domains = JsonNodeFactory.instance.objectNode();
    }
    domains = ContractValues.objectOrEmpty(domains);
    if (exactArithmetic == null) {
      exactArithmetic = true;
    }
    if (experimentId == null) {
      experimentId = PythonCompatibleIdGenerator.newId("experiment");
    }
    experimentId = ContractStrings.trim(experimentId);
    if (maxCases == null) {
      maxCases = 100000;
    }
    ContractValues.minimum("max_cases", maxCases, 1);
    ContractValues.maximum("max_cases", maxCases, 100000000);
    method = ContractValues.required("method", method);
    noncomputationalAlternative = ContractStrings.trim(noncomputationalAlternative);
    noncomputationalAlternative = ContractStrings.required("noncomputational_alternative", noncomputationalAlternative);
    ContractValues.minimumLength("noncomputational_alternative", noncomputationalAlternative, 1);
    parentCheckpointId = ContractStrings.trim(parentCheckpointId);
    pathId = ContractStrings.trim(pathId);
    if (purpose == null) {
      purpose = ComputationPurpose.FALSIFY_CLAIM;
    }
    reasoningBasis = ContractStrings.trim(reasoningBasis);
    reasoningBasis = ContractStrings.required("reasoning_basis", reasoningBasis);
    ContractValues.minimumLength("reasoning_basis", reasoningBasis, 1);
    if (requestHash == null) {
      requestHash = "";
    }
    requestHash = ContractStrings.trim(requestHash);
    requestedBy = ContractStrings.trim(requestedBy);
    if (runtimeFingerprint == null) {
      runtimeFingerprint = JsonNodeFactory.instance.objectNode();
    }
    runtimeFingerprint = ContractValues.objectOrEmpty(runtimeFingerprint);
    if (seed == null) {
      seed = 20260719;
    }
    targetClaim = ContractStrings.trim(targetClaim);
    targetClaim = ContractStrings.required("target_claim", targetClaim);
    ContractValues.minimumLength("target_claim", targetClaim, 1);
    targetClaimId = ContractStrings.trim(targetClaimId);
    if (claimEvidenceSemanticBinding != null) {
      if (targetClaimId == null
          || !targetClaimId.equals(claimEvidenceSemanticBinding.claimId())) {
        throw new ContractValidationException(
            "claim evidence binding requires an exact target_claim_id");
      }
      if (!assumptions.equals(claimEvidenceSemanticBinding.assumptions())) {
        throw new ContractValidationException(
            "claim evidence binding assumptions must match the computation request");
      }
      if (!domains.equals(claimEvidenceSemanticBinding.computationDomains())) {
        throw new ContractValidationException(
            "claim evidence binding domains must match the computation request");
      }
    }
    typedToolGap = ContractStrings.trim(typedToolGap);
    whyComputationIsNeeded = ContractStrings.trim(whyComputationIsNeeded);
    whyComputationIsNeeded = ContractStrings.required("why_computation_is_needed", whyComputationIsNeeded);
    ContractValues.minimumLength("why_computation_is_needed", whyComputationIsNeeded, 1);
    if (purpose == ComputationPurpose.DISCOVER_PATTERN && !broadSearch) {
      throw new ContractValidationException(
          "discover_pattern requests must set broad_search=true");
    }
    if (broadSearch && purpose == ComputationPurpose.FALSIFY_CLAIM) {
      throw new ContractValidationException(
          "falsify_claim is a targeted request; broad searches must use discover_pattern");
    }
    String expectedExecutionHash =
        CanonicalJson.stableHash(
            ContractHashes.experimentExecutionPayload(
                method,
                domains,
                arguments,
                exactArithmetic,
                runtimeFingerprint,
                seed));
    String suppliedExecutionHash = ContractStrings.trim(executionHash);
    executionHash =
        ContractHashes.sameHash(suppliedExecutionHash, expectedExecutionHash)
            ? suppliedExecutionHash
            : expectedExecutionHash;
    requestHash =
        ContractHashes.checked(
            "request_hash",
            requestHash,
            CanonicalJson.stableHash(
                requestPayload(
                    purpose,
                    targetClaim,
                    assumptions,
                    reasoningBasis,
                    whyComputationIsNeeded,
                    decisionIfConfirmed,
                    decisionIfRefuted,
                    noncomputationalAlternative,
                    method,
                    domains,
                    arguments,
                    exactArithmetic,
                    broadSearch,
                    typedToolGap,
                    maxCases,
                    seed,
                    runtimeFingerprint,
                    targetClaimId,
                    claimEvidenceSemanticBinding)));
  }

  public ExperimentSpec(
      ObjectNode arguments,
      List<String> assumptions,
      Boolean broadSearch,
      String decisionIfConfirmed,
      String decisionIfRefuted,
      ObjectNode domains,
      Boolean exactArithmetic,
      String executionHash,
      String experimentId,
      Integer maxCases,
      ComputationMethod method,
      String noncomputationalAlternative,
      String parentCheckpointId,
      String pathId,
      ComputationPurpose purpose,
      String reasoningBasis,
      String requestHash,
      String requestedBy,
      ObjectNode runtimeFingerprint,
      Integer seed,
      String targetClaim,
      String typedToolGap,
      String whyComputationIsNeeded) {
    this(
        arguments,
        assumptions,
        broadSearch,
        decisionIfConfirmed,
        decisionIfRefuted,
        domains,
        exactArithmetic,
        executionHash,
        experimentId,
        maxCases,
        method,
        noncomputationalAlternative,
        parentCheckpointId,
        pathId,
        purpose,
        reasoningBasis,
        requestHash,
        requestedBy,
        runtimeFingerprint,
        seed,
        targetClaim,
        typedToolGap,
        whyComputationIsNeeded,
        null,
        null);
  }

  @JsonIgnore
  public ObjectNode normalizedExecutionPayload() {
    return ContractHashes.experimentExecutionPayload(
        method, domains, arguments, exactArithmetic, runtimeFingerprint, seed);
  }

  @JsonIgnore
  public ObjectNode normalizedPayload() {
    return requestPayload(
        purpose,
        targetClaim,
        assumptions,
        reasoningBasis,
        whyComputationIsNeeded,
        decisionIfConfirmed,
        decisionIfRefuted,
        noncomputationalAlternative,
        method,
        domains,
        arguments,
        exactArithmetic,
        broadSearch,
        typedToolGap,
        maxCases,
        seed,
        runtimeFingerprint,
        targetClaimId,
        claimEvidenceSemanticBinding);
  }

  public ExperimentSpec bindRuntimeFingerprint(ObjectNode fingerprint) {
    return new ExperimentSpec(
        arguments,
        assumptions,
        broadSearch,
        decisionIfConfirmed,
        decisionIfRefuted,
        domains,
        exactArithmetic,
        null,
        experimentId,
        maxCases,
        method,
        noncomputationalAlternative,
        parentCheckpointId,
        pathId,
        purpose,
        reasoningBasis,
        null,
        requestedBy,
        ContractValues.objectOrEmpty(fingerprint),
        seed,
        targetClaim,
        typedToolGap,
        whyComputationIsNeeded,
        targetClaimId,
        claimEvidenceSemanticBinding);
  }

  private static ObjectNode requestPayload(
      ComputationPurpose purpose,
      String targetClaim,
      List<String> assumptions,
      String reasoningBasis,
      String whyComputationIsNeeded,
      String decisionIfConfirmed,
      String decisionIfRefuted,
      String noncomputationalAlternative,
      ComputationMethod method,
      ObjectNode domains,
      ObjectNode arguments,
      Boolean exactArithmetic,
      Boolean broadSearch,
      String typedToolGap,
      Integer maxCases,
      Integer seed,
      ObjectNode runtimeFingerprint,
      String targetClaimId,
      ClaimEvidenceSemanticBinding binding) {
    ObjectNode payload =
        ContractHashes.experimentRequestPayload(
            purpose,
            targetClaim,
            assumptions,
            reasoningBasis,
            whyComputationIsNeeded,
            decisionIfConfirmed,
            decisionIfRefuted,
            noncomputationalAlternative,
            method,
            domains,
            arguments,
            exactArithmetic,
            broadSearch,
            typedToolGap,
            maxCases,
            seed,
            runtimeFingerprint);
    if (targetClaimId != null) {
      payload.put("target_claim_id", targetClaimId);
    }
    if (binding != null) {
      payload.set("claim_evidence_semantic_binding", ContractObjectMapper.toTree(binding));
    }
    return payload;
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public ObjectNode arguments() {
    return arguments == null ? null : arguments.deepCopy();
  }

  public List<String> assumptions() {
    return assumptions == null ? null : List.copyOf(assumptions);
  }

  public ObjectNode domains() {
    return domains == null ? null : domains.deepCopy();
  }

  public ObjectNode runtimeFingerprint() {
    return runtimeFingerprint == null ? null : runtimeFingerprint.deepCopy();
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
