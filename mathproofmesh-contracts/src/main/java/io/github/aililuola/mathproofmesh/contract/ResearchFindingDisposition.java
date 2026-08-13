package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ResearchFindingDisposition(
    @JsonProperty(value = "finding_id", required = true) @ContractNonNull String findingId,
    @JsonProperty(value = "action", required = true) @ContractNonNull ResearchFindingDispositionAction action,
    @JsonProperty(value = "reason") String reason,
    @JsonProperty(value = "superseded_by_finding_id") String supersededByFindingId)
    implements StrictContract {

  public ResearchFindingDisposition {
    findingId = ContractStrings.required("finding_id", ContractStrings.trim(findingId));
    ContractValues.minimumLength("finding_id", findingId, 1);
    action = ContractValues.required("action", action);
    reason = ContractStrings.trim(reason);
    supersededByFindingId = ContractStrings.trim(supersededByFindingId);
    if ((action == ResearchFindingDispositionAction.REJECT_WITH_REASON
            || action == ResearchFindingDispositionAction.DEFER)
        && (reason == null || reason.isBlank())) {
      throw new ContractValidationException(action.value() + " requires reason");
    }
    if (action == ResearchFindingDispositionAction.SUPERSEDE_WITH
        && (supersededByFindingId == null || supersededByFindingId.isBlank())) {
      throw new ContractValidationException("supersede_with requires superseded_by_finding_id");
    }
    if (action != ResearchFindingDispositionAction.SUPERSEDE_WITH
        && supersededByFindingId != null) {
      throw new ContractValidationException(
          "superseded_by_finding_id is allowed only for supersede_with");
    }
  }
}
