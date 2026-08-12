package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record VariableBinding(
    @JsonProperty(value = "aliases") @ContractNonNull List<String> aliases,
    @JsonProperty(value = "display_name", required = true) @ContractNonNull String displayName,
    @JsonProperty(value = "domain", required = true) @ContractNonNull String domain,
    @JsonProperty(value = "owner_scope", required = true) @ContractNonNull String ownerScope,
    @JsonProperty(value = "variable_id", required = true) @ContractNonNull String variableId
) implements StrictContract {

  public VariableBinding {
    if (aliases == null) {
      aliases = List.of();
    }
    aliases = ImmutableCollections.listOrEmpty(aliases);
    displayName = ContractStrings.trim(displayName);
    displayName = ContractStrings.required("display_name", displayName);
    ContractValues.minimumLength("display_name", displayName, 1);
    domain = ContractStrings.trim(domain);
    domain = ContractStrings.required("domain", domain);
    ContractValues.minimumLength("domain", domain, 1);
    ownerScope = ContractStrings.trim(ownerScope);
    ownerScope = ContractStrings.required("owner_scope", ownerScope);
    ContractValues.minimumLength("owner_scope", ownerScope, 1);
    variableId = ContractStrings.trim(variableId);
    variableId = ContractStrings.required("variable_id", variableId);
    ContractValues.minimumLength("variable_id", variableId, 1);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> aliases() {
    return aliases == null ? null : List.copyOf(aliases);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
