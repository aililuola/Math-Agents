package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record CriticalClaim(
    @JsonProperty(value = "claim_id") @ContractNonNull String claimId,
    @JsonProperty(value = "evidence_refs") @ContractNonNull List<String> evidenceRefs,
    @JsonProperty(value = "falsification_test", required = true) @ContractNonNull String falsificationTest,
    @JsonProperty(value = "necessity")
        @ContractNonNull
        @ContractAllowedValues({"required", "supporting"})
        String necessity,
    @JsonProperty(value = "preferred_tool") String preferredTool,
    @JsonProperty(value = "statement", required = true) @ContractNonNull String statement,
    @JsonProperty(value = "status")
        @ContractNonNull
        @ContractAllowedValues({"candidate", "needs_check", "verified", "refuted", "blocked"})
        String status
) implements StrictContract {

  public CriticalClaim {
    if (claimId == null) {
      claimId = PythonCompatibleIdGenerator.newId("critical");
    }
    claimId = ContractStrings.trim(claimId);
    if (evidenceRefs == null) {
      evidenceRefs = List.of();
    }
    evidenceRefs = ImmutableCollections.listOrEmpty(evidenceRefs);
    falsificationTest = ContractStrings.trim(falsificationTest);
    falsificationTest = ContractStrings.required("falsification_test", falsificationTest);
    ContractValues.minimumLength("falsification_test", falsificationTest, 1);
    if (necessity == null) {
      necessity = "required";
    }
    necessity = ContractStrings.trim(necessity);
    ContractValues.oneOf("necessity", necessity, "required", "supporting");
    preferredTool = ContractStrings.trim(preferredTool);
    statement = ContractStrings.trim(statement);
    statement = ContractStrings.required("statement", statement);
    ContractValues.minimumLength("statement", statement, 1);
    if (status == null) {
      status = "needs_check";
    }
    status = ContractStrings.trim(status);
    ContractValues.oneOf("status", status, "candidate", "needs_check", "verified", "refuted", "blocked");
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> evidenceRefs() {
    return evidenceRefs == null ? null : List.copyOf(evidenceRefs);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
