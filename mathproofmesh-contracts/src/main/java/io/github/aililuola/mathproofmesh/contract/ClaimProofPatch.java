package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashSet;
import java.util.List;

public record ClaimProofPatch(
    @JsonProperty(value = "patch_id") String patchId,
    @JsonProperty(value = "claim_id", required = true) @ContractNonNull String claimId,
    @JsonProperty(value = "claim_semantic_hash", required = true) @ContractNonNull
        String claimSemanticHash,
    @JsonProperty(value = "base_proof_revision_id", required = true) @ContractNonNull
        String baseProofRevisionId,
    @JsonProperty(value = "base_proof_hash", required = true) @ContractNonNull String baseProofHash,
    @JsonProperty(value = "issue_ids") @ContractNonNull List<String> issueIds,
    @JsonProperty(value = "changed_step_ids") @ContractNonNull List<String> changedStepIds,
    @JsonProperty(value = "operations") @ContractNonNull
        List<ClaimProofPatchOperation> operations,
    @JsonProperty(value = "expected_resolved_issue_ids") @ContractNonNull
        List<String> expectedResolvedIssueIds)
    implements StrictContract {
  public ClaimProofPatch {
    if (patchId == null) {
      patchId = PythonCompatibleIdGenerator.newId("proof-patch");
    }
    patchId = ContractStrings.required("patch_id", ContractStrings.trim(patchId));
    claimId = ContractStrings.required("claim_id", ContractStrings.trim(claimId));
    claimSemanticHash =
        ContractStrings.required("claim_semantic_hash", ContractStrings.trim(claimSemanticHash));
    baseProofRevisionId =
        ContractStrings.required(
            "base_proof_revision_id", ContractStrings.trim(baseProofRevisionId));
    baseProofHash =
        ContractStrings.required("base_proof_hash", ContractStrings.trim(baseProofHash));
    issueIds = ImmutableCollections.listOrEmpty(issueIds);
    changedStepIds = ImmutableCollections.listOrEmpty(changedStepIds);
    operations = ImmutableCollections.listOrEmpty(operations);
    expectedResolvedIssueIds = ImmutableCollections.listOrEmpty(expectedResolvedIssueIds);
    if (operations.isEmpty()) {
      throw new ContractValidationException("proof patch requires at least one operation");
    }
    if (new LinkedHashSet<>(issueIds).size() != issueIds.size()
        || new LinkedHashSet<>(changedStepIds).size() != changedStepIds.size()
        || new LinkedHashSet<>(expectedResolvedIssueIds).size()
            != expectedResolvedIssueIds.size()) {
      throw new ContractValidationException("proof patch identifiers must be unique");
    }
  }

  @Override
  public List<String> issueIds() {
    return List.copyOf(issueIds);
  }

  @Override
  public List<String> changedStepIds() {
    return List.copyOf(changedStepIds);
  }

  @Override
  public List<ClaimProofPatchOperation> operations() {
    return List.copyOf(operations);
  }

  @Override
  public List<String> expectedResolvedIssueIds() {
    return List.copyOf(expectedResolvedIssueIds);
  }
}
