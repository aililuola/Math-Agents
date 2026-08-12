package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record QuantifierSpec(
    @JsonProperty(value = "display_name", required = true) @ContractNonNull String displayName,
    @JsonProperty(value = "domain", required = true) @ContractNonNull String domain,
    @JsonProperty(value = "kind", required = true) @ContractNonNull String kind,
    @JsonProperty(value = "order", required = true) @ContractNonNull Integer order,
    @JsonProperty(value = "restrictions") @ContractNonNull List<String> restrictions,
    @JsonProperty(value = "variable_id", required = true) @ContractNonNull String variableId
) implements StrictContract {

  public QuantifierSpec {
    displayName = ContractStrings.trim(displayName);
    displayName = ContractStrings.required("display_name", displayName);
    ContractValues.minimumLength("display_name", displayName, 1);
    domain = ContractStrings.trim(domain);
    domain = ContractStrings.required("domain", domain);
    ContractValues.minimumLength("domain", domain, 1);
    kind = ContractStrings.trim(kind);
    kind = ContractStrings.required("kind", kind);
    ContractValues.oneOf("kind", kind, "forall", "exists", "exists_unique");
    order = ContractValues.required("order", order);
    ContractValues.minimum("order", order, 0);
    if (restrictions == null) {
      restrictions = List.of();
    }
    restrictions = ImmutableCollections.listOrEmpty(restrictions);
    variableId = ContractStrings.trim(variableId);
    variableId = ContractStrings.required("variable_id", variableId);
    ContractValues.minimumLength("variable_id", variableId, 1);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> restrictions() {
    return restrictions == null ? null : List.copyOf(restrictions);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
