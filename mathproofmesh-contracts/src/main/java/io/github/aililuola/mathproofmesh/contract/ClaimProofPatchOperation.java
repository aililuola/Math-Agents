package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ClaimProofPatchOperation(
    @JsonProperty(value = "operation_id") String operationId,
    @JsonProperty(value = "operation_type", required = true) @ContractNonNull
        ClaimProofPatchOperationType operationType,
    @JsonProperty(value = "target_step_id", required = true) @ContractNonNull
        String targetStepId,
    @JsonProperty(value = "replacement_statement") String replacementStatement,
    @JsonProperty(value = "replacement_justification") String replacementJustification,
    @JsonProperty(value = "inserted_step") ProofStep insertedStep,
    @JsonProperty(value = "verified_dependency_claim_id") String verifiedDependencyClaimId,
    @JsonProperty(value = "evidence_ref") EvidenceRef evidenceRef)
    implements StrictContract {
  public ClaimProofPatchOperation {
    if (operationId == null) {
      operationId = PythonCompatibleIdGenerator.newId("proof-patch-operation");
    }
    operationId = ContractStrings.required("operation_id", ContractStrings.trim(operationId));
    operationType = ContractValues.required("operation_type", operationType);
    targetStepId =
        ContractStrings.required("target_step_id", ContractStrings.trim(targetStepId));
    replacementStatement = ContractStrings.trim(replacementStatement);
    replacementJustification = ContractStrings.trim(replacementJustification);
    verifiedDependencyClaimId = ContractStrings.trim(verifiedDependencyClaimId);
    switch (operationType) {
      case REPLACE_STEP_JUSTIFICATION ->
          ContractStrings.required("replacement_justification", replacementJustification);
      case REPLACE_STEP_STATEMENT ->
          ContractStrings.required("replacement_statement", replacementStatement);
      case INSERT_STEP_BEFORE, INSERT_STEP_AFTER ->
          ContractValues.required("inserted_step", insertedStep);
      case REBIND_VERIFIED_DEPENDENCY ->
          ContractStrings.required("verified_dependency_claim_id", verifiedDependencyClaimId);
      case ADD_VERIFIED_EVIDENCE_REF -> ContractValues.required("evidence_ref", evidenceRef);
      case DELETE_REDUNDANT_STEP -> {
        // The target step identifies the complete deletion operation.
      }
    }
  }
}
