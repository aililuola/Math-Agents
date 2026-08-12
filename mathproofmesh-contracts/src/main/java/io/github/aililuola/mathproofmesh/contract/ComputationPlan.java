package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ComputationPlan(
    @JsonProperty(value = "assumptions") @ContractNonNull List<String> assumptions,
    @JsonProperty(value = "bounded_scope") @ContractNonNull ObjectNode boundedScope,
    @JsonProperty(value = "decision_use", required = true) @ContractNonNull String decisionUse,
    @JsonProperty(value = "exact_arithmetic") @ContractNonNull Boolean exactArithmetic,
    @JsonProperty(value = "experiment_id", required = true) @ContractNonNull String experimentId,
    @JsonProperty(value = "method", required = true) @ContractNonNull ComputationMethod method,
    @JsonProperty(value = "plan_id") @ContractNonNull String planId,
    @JsonProperty(value = "request_hash", required = true) @ContractNonNull String requestHash,
    @JsonProperty(value = "source_artifact_ref") String sourceArtifactRef,
    @JsonProperty(value = "target_claim", required = true) @ContractNonNull String targetClaim
) implements StrictContract {

  public ComputationPlan {
    if (assumptions == null) {
      assumptions = List.of();
    }
    assumptions = ImmutableCollections.listOrEmpty(assumptions);
    if (boundedScope == null) {
      boundedScope = JsonNodeFactory.instance.objectNode();
    }
    boundedScope = ContractValues.objectOrEmpty(boundedScope);
    decisionUse = ContractStrings.trim(decisionUse);
    decisionUse = ContractStrings.required("decision_use", decisionUse);
    if (exactArithmetic == null) {
      exactArithmetic = true;
    }
    experimentId = ContractStrings.trim(experimentId);
    experimentId = ContractStrings.required("experiment_id", experimentId);
    method = ContractValues.required("method", method);
    if (planId == null) {
      planId = PythonCompatibleIdGenerator.newId("compute_plan");
    }
    planId = ContractStrings.trim(planId);
    requestHash = ContractStrings.trim(requestHash);
    requestHash = ContractStrings.required("request_hash", requestHash);
    sourceArtifactRef = ContractStrings.trim(sourceArtifactRef);
    targetClaim = ContractStrings.trim(targetClaim);
    targetClaim = ContractStrings.required("target_claim", targetClaim);
  }

  public static ComputationPlan fromSpec(ExperimentSpec spec) {
    ContractValues.required("spec", spec);
    ObjectNode boundedScope = JsonNodeFactory.instance.objectNode();
    boundedScope.set("domains", spec.domains().deepCopy());
    boundedScope.set("arguments", spec.arguments().deepCopy());
    boundedScope.put("max_cases", spec.maxCases());
    String decisionUse =
        "If confirmed: "
            + spec.decisionIfConfirmed()
            + " If refuted: "
            + spec.decisionIfRefuted();
    return new ComputationPlan(
        spec.assumptions(),
        boundedScope,
        decisionUse,
        spec.exactArithmetic(),
        spec.experimentId(),
        spec.method(),
        null,
        spec.requestHash(),
        null,
        spec.targetClaim());
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> assumptions() {
    return assumptions == null ? null : List.copyOf(assumptions);
  }

  public ObjectNode boundedScope() {
    return boundedScope == null ? null : boundedScope.deepCopy();
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
