package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ProofAuditIssue(
    @JsonProperty(value = "issue_id") String issueId,
    @JsonProperty(value = "claim_id", required = true) @ContractNonNull String claimId,
    @JsonProperty(value = "step_id", required = true) @ContractNonNull String stepId,
    @JsonProperty(value = "premise_summary", required = true) @ContractNonNull String premiseSummary,
    @JsonProperty(value = "conclusion_summary", required = true) @ContractNonNull
        String conclusionSummary,
    @JsonProperty(value = "issue_kind", required = true) @ContractNonNull ProofIssueKind issueKind,
    @JsonProperty(value = "repairability", required = true) @ContractNonNull
        ProofRepairability repairability,
    @JsonProperty(value = "required_verified_dependency_ids") @ContractNonNull
        List<String> requiredVerifiedDependencyIds,
    @JsonProperty(value = "touches_claim_statement") @ContractNonNull
        Boolean touchesClaimStatement,
    @JsonProperty(value = "description", required = true) @ContractNonNull String description)
    implements StrictContract {
  public ProofAuditIssue {
    if (issueId == null) {
      issueId = PythonCompatibleIdGenerator.newId("proof-audit-issue");
    }
    issueId = ContractStrings.required("issue_id", ContractStrings.trim(issueId));
    claimId = ContractStrings.required("claim_id", ContractStrings.trim(claimId));
    stepId = ContractStrings.required("step_id", ContractStrings.trim(stepId));
    premiseSummary =
        ContractStrings.required("premise_summary", ContractStrings.trim(premiseSummary));
    conclusionSummary =
        ContractStrings.required("conclusion_summary", ContractStrings.trim(conclusionSummary));
    issueKind = ContractValues.required("issue_kind", issueKind);
    repairability = ContractValues.required("repairability", repairability);
    requiredVerifiedDependencyIds =
        ImmutableCollections.listOrEmpty(requiredVerifiedDependencyIds);
    touchesClaimStatement = Boolean.TRUE.equals(touchesClaimStatement);
    description = ContractStrings.required("description", ContractStrings.trim(description));
  }

  @Override
  public List<String> requiredVerifiedDependencyIds() {
    return List.copyOf(requiredVerifiedDependencyIds);
  }
}
