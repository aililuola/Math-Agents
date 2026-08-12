package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record InspirationTrigger(
    @JsonProperty(value = "affected_route_ids", required = true) @ContractNonNull List<String> affectedRouteIds,
    @JsonProperty(value = "evidence_refs") @ContractNonNull List<String> evidenceRefs,
    @JsonProperty(value = "proof_debt_before") @ContractNonNull Double proofDebtBefore,
    @JsonProperty(value = "reason", required = true) @ContractNonNull String reason,
    @JsonProperty(value = "repeated_error_fingerprints") @ContractNonNull List<String> repeatedErrorFingerprints,
    @JsonProperty(value = "round_index", required = true) @ContractNonNull Integer roundIndex,
    @JsonProperty(value = "trigger_id") @ContractNonNull String triggerId,
    @JsonProperty(value = "trigger_type", required = true) @ContractNonNull InspirationTriggerType triggerType,
    @JsonProperty(value = "verified_gain_recent") @ContractNonNull Integer verifiedGainRecent
) implements StrictContract {

  public InspirationTrigger {
    affectedRouteIds = ImmutableCollections.requiredList("affected_route_ids", affectedRouteIds);
    if (evidenceRefs == null) {
      evidenceRefs = List.of();
    }
    evidenceRefs = ImmutableCollections.listOrEmpty(evidenceRefs);
    if (proofDebtBefore == null) {
      proofDebtBefore = 0.0d;
    }
    ContractValues.minimum("proof_debt_before", proofDebtBefore, 0.0);
    reason = ContractStrings.trim(reason);
    reason = ContractStrings.required("reason", reason);
    if (repeatedErrorFingerprints == null) {
      repeatedErrorFingerprints = List.of();
    }
    repeatedErrorFingerprints = ImmutableCollections.listOrEmpty(repeatedErrorFingerprints);
    roundIndex = ContractValues.required("round_index", roundIndex);
    ContractValues.minimum("round_index", roundIndex, 0);
    if (triggerId == null) {
      triggerId = PythonCompatibleIdGenerator.newId("trigger");
    }
    triggerId = ContractStrings.trim(triggerId);
    triggerType = ContractValues.required("trigger_type", triggerType);
    if (verifiedGainRecent == null) {
      verifiedGainRecent = 0;
    }
    ContractValues.minimum("verified_gain_recent", verifiedGainRecent, 0);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> affectedRouteIds() {
    return affectedRouteIds == null ? null : List.copyOf(affectedRouteIds);
  }

  public List<String> evidenceRefs() {
    return evidenceRefs == null ? null : List.copyOf(evidenceRefs);
  }

  public List<String> repeatedErrorFingerprints() {
    return repeatedErrorFingerprints == null ? null : List.copyOf(repeatedErrorFingerprints);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
