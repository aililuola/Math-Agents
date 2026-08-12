package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record VerificationIssue(
    @JsonProperty(value = "claim_id") String claimId,
    @JsonProperty(value = "conclusion_summary") @ContractNonNull String conclusionSummary,
    @JsonProperty(value = "counterexample") String counterexample,
    @JsonProperty(value = "description", required = true) @ContractNonNull String description,
    @JsonProperty(value = "issue_code") String issueCode,
    @JsonProperty(value = "issue_id") @ContractNonNull String issueId,
    @JsonProperty(value = "phase", required = true) @ContractNonNull String phase,
    @JsonProperty(value = "premise_summary") @ContractNonNull String premiseSummary,
    @JsonProperty(value = "repair_hint") String repairHint,
    @JsonProperty(value = "severity", required = true) @ContractNonNull Severity severity,
    @JsonProperty(value = "step_id") String stepId
) implements StrictContract {

  public VerificationIssue {
    claimId = ContractStrings.trim(claimId);
    if (conclusionSummary == null) {
      conclusionSummary = "";
    }
    conclusionSummary = ContractStrings.trim(conclusionSummary);
    counterexample = ContractStrings.trim(counterexample);
    description = ContractStrings.trim(description);
    description = ContractStrings.required("description", description);
    issueCode = ContractStrings.trim(issueCode);
    if (issueId == null) {
      issueId = PythonCompatibleIdGenerator.newId("issue");
    }
    issueId = ContractStrings.trim(issueId);
    phase = ContractStrings.trim(phase);
    phase = ContractStrings.required("phase", phase);
    if (premiseSummary == null) {
      premiseSummary = "";
    }
    premiseSummary = ContractStrings.trim(premiseSummary);
    repairHint = ContractStrings.trim(repairHint);
    severity = ContractValues.required("severity", severity);
    stepId = ContractStrings.trim(stepId);
  }
}
