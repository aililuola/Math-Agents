package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ExperimentProgram(
    @JsonProperty(value = "code_hash") @ContractNonNull String codeHash,
    @JsonProperty(value = "created_at") @ContractNonNull String createdAt,
    @JsonProperty(value = "dependencies") @ContractNonNull List<String> dependencies,
    @JsonProperty(value = "experiment_id", required = true) @ContractNonNull String experimentId,
    @JsonProperty(value = "input_schema") @ContractNonNull ObjectNode inputSchema,
    @JsonProperty(value = "output_schema") @ContractNonNull ObjectNode outputSchema,
    @JsonProperty(value = "source", required = true) @ContractNonNull String source
) implements StrictContract {

  public ExperimentProgram {
    if (codeHash == null) {
      codeHash = "";
    }
    codeHash = ContractStrings.trim(codeHash);
    if (createdAt == null) {
      createdAt = PythonIsoTimestampCodec.now();
    }
    createdAt = ContractStrings.trim(createdAt);
    if (dependencies == null) {
      dependencies = List.of();
    }
    dependencies = ImmutableCollections.listOrEmpty(dependencies);
    experimentId = ContractStrings.trim(experimentId);
    experimentId = ContractStrings.required("experiment_id", experimentId);
    if (inputSchema == null) {
      inputSchema = JsonNodeFactory.instance.objectNode();
    }
    inputSchema = ContractValues.objectOrEmpty(inputSchema);
    if (outputSchema == null) {
      outputSchema = JsonNodeFactory.instance.objectNode();
    }
    outputSchema = ContractValues.objectOrEmpty(outputSchema);
    source = ContractStrings.trim(source);
    source = ContractStrings.required("source", source);
    codeHash =
        ContractHashes.checked(
            "code_hash", codeHash, CanonicalJson.stableHash(source));
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> dependencies() {
    return dependencies == null ? null : List.copyOf(dependencies);
  }

  public ObjectNode inputSchema() {
    return inputSchema == null ? null : inputSchema.deepCopy();
  }

  public ObjectNode outputSchema() {
    return outputSchema == null ? null : outputSchema.deepCopy();
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
