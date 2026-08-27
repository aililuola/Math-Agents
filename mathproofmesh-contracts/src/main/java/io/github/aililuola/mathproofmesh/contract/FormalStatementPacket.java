package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record FormalStatementPacket(
    @JsonProperty(value = "assumptions") @ContractNonNull List<String> assumptions,
    @JsonProperty(value = "obligation_id", required = true) @ContractNonNull String obligationId,
    @JsonProperty(value = "packet_id") @ContractNonNull String packetId,
    @JsonProperty(value = "problem_hash", required = true) @ContractNonNull String problemHash,
    @JsonProperty(value = "quantifiers") @ContractNonNull List<QuantifierSpec> quantifiers,
    @JsonProperty(value = "statement", required = true) @ContractNonNull String statement,
    @JsonProperty(value = "target_language") @ContractNonNull String targetLanguage
) implements StrictContract {

  public FormalStatementPacket {
    if (assumptions == null) {
      assumptions = List.of();
    }
    assumptions = ImmutableCollections.listOrEmpty(assumptions);
    obligationId = ContractStrings.trim(obligationId);
    obligationId = ContractStrings.required("obligation_id", obligationId);
    if (packetId == null) {
      packetId = PythonCompatibleIdGenerator.newId("formal_statement");
    }
    packetId = ContractStrings.trim(packetId);
    problemHash = ContractStrings.trim(problemHash);
    problemHash = ContractStrings.required("problem_hash", problemHash);
    if (quantifiers == null) {
      quantifiers = List.of();
    }
    quantifiers = ImmutableCollections.listOrEmpty(quantifiers);
    statement = ContractStrings.trim(statement);
    statement = ContractStrings.required("statement", statement);
    if (targetLanguage == null) {
      targetLanguage = "lean4";
    }
    targetLanguage = ContractStrings.trim(targetLanguage);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> assumptions() {
    return assumptions == null ? null : List.copyOf(assumptions);
  }

  public List<QuantifierSpec> quantifiers() {
    return quantifiers == null ? null : List.copyOf(quantifiers);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
