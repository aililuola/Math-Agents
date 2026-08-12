package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record MetaDirectiveAudit(
    @JsonProperty(value = "accepted", required = true) @ContractNonNull Boolean accepted,
    @JsonProperty(value = "auditor_id") @ContractNonNull String auditorId,
    @JsonProperty(value = "budget_safe", required = true) @ContractNonNull Boolean budgetSafe,
    @JsonProperty(value = "directive_id", required = true) @ContractNonNull String directiveId,
    @JsonProperty(value = "evidence_complete", required = true) @ContractNonNull Boolean evidenceComplete,
    @JsonProperty(value = "reason", required = true) @ContractNonNull String reason,
    @JsonProperty(value = "targets_valid", required = true) @ContractNonNull Boolean targetsValid
) implements StrictContract {

  public MetaDirectiveAudit {
    accepted = ContractValues.required("accepted", accepted);
    if (auditorId == null) {
      auditorId = "deterministic_meta_directive_auditor";
    }
    auditorId = ContractStrings.trim(auditorId);
    budgetSafe = ContractValues.required("budget_safe", budgetSafe);
    directiveId = ContractStrings.trim(directiveId);
    directiveId = ContractStrings.required("directive_id", directiveId);
    evidenceComplete = ContractValues.required("evidence_complete", evidenceComplete);
    reason = ContractStrings.trim(reason);
    reason = ContractStrings.required("reason", reason);
    targetsValid = ContractValues.required("targets_valid", targetsValid);
  }
}
