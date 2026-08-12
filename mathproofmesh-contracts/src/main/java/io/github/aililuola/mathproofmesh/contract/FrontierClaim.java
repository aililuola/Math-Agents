package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record FrontierClaim(
    @JsonProperty(value = "assumptions") @ContractNonNull List<String> assumptions,
    @JsonProperty(value = "direction", required = true) @ContractNonNull String direction,
    @JsonProperty(value = "frontier_id", required = true) @ContractNonNull String frontierId,
    @JsonProperty(value = "quantifiers") @ContractNonNull List<QuantifierSpec> quantifiers,
    @JsonProperty(value = "scope_limitations") @ContractNonNull List<String> scopeLimitations,
    @JsonProperty(value = "source_ref") String sourceRef,
    @JsonProperty(value = "statement", required = true) @ContractNonNull String statement,
    @JsonProperty(value = "supported") @ContractNonNull Boolean supported
) implements StrictContract {

  public FrontierClaim {
    if (assumptions == null) {
      assumptions = List.of();
    }
    assumptions = ImmutableCollections.listOrEmpty(assumptions);
    direction = ContractStrings.trim(direction);
    direction = ContractStrings.required("direction", direction);
    ContractValues.oneOf("direction", direction, "forward", "backward");
    frontierId = ContractStrings.trim(frontierId);
    frontierId = ContractStrings.required("frontier_id", frontierId);
    if (quantifiers == null) {
      quantifiers = List.of();
    }
    quantifiers = ImmutableCollections.listOrEmpty(quantifiers);
    if (scopeLimitations == null) {
      scopeLimitations = List.of();
    }
    scopeLimitations = ImmutableCollections.listOrEmpty(scopeLimitations);
    sourceRef = ContractStrings.trim(sourceRef);
    statement = ContractStrings.trim(statement);
    statement = ContractStrings.required("statement", statement);
    ContractValues.minimumLength("statement", statement, 1);
    if (supported == null) {
      supported = false;
    }
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> assumptions() {
    return assumptions == null ? null : List.copyOf(assumptions);
  }

  public List<QuantifierSpec> quantifiers() {
    return quantifiers == null ? null : List.copyOf(quantifiers);
  }

  public List<String> scopeLimitations() {
    return scopeLimitations == null ? null : List.copyOf(scopeLimitations);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
