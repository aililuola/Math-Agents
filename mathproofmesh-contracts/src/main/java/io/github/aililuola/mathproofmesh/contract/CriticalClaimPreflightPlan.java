package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record CriticalClaimPreflightPlan(
    @JsonProperty(value = "claim_id", required = true) @ContractNonNull String claimId,
    @JsonProperty(value = "computation_contract_id") String computationContractId,
    @JsonProperty(value = "evidence_refs") @ContractNonNull List<String> evidenceRefs,
    @JsonProperty(value = "typed_input_refs") @ContractNonNull List<String> typedInputRefs)
    implements StrictContract {
  public CriticalClaimPreflightPlan {
    claimId = ContractStrings.required("claim_id", ContractStrings.trim(claimId));
    computationContractId = ContractStrings.trim(computationContractId);
    evidenceRefs = ImmutableCollections.listOrEmpty(evidenceRefs);
    typedInputRefs = ImmutableCollections.listOrEmpty(typedInputRefs);
  }

  @Override
  public List<String> evidenceRefs() {
    return List.copyOf(evidenceRefs);
  }

  @Override
  public List<String> typedInputRefs() {
    return List.copyOf(typedInputRefs);
  }
}
