package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record TypedCommunicationConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "schema_version") String schemaVersion,
    @JsonProperty(value = "require_problem_hash") Boolean requireProblemHash,
    @JsonProperty(value = "require_content_hash") Boolean requireContentHash,
    @JsonProperty(value = "require_receipt") Boolean requireReceipt,
    @JsonProperty(value = "exactly_once_delivery") Boolean exactlyOnceDelivery,
    @JsonProperty(value = "max_message_chars") Integer maxMessageChars,
    @JsonProperty(value = "max_assumptions") Integer maxAssumptions,
    @JsonProperty(value = "max_dependencies") Integer maxDependencies
) implements ConfigModel {

  @JsonCreator
  public TypedCommunicationConfig(Boolean enabled, String schemaVersion, Boolean requireProblemHash, Boolean requireContentHash, Boolean requireReceipt, Boolean exactlyOnceDelivery, Integer maxMessageChars, Integer maxAssumptions, Integer maxDependencies) {
    if (enabled == null) {
      enabled = false;
    }
    if (schemaVersion == null) {
      schemaVersion = "1";
    }
    schemaVersion = ConfigValidation.trim(schemaVersion);
    if (requireProblemHash == null) {
      requireProblemHash = true;
    }
    if (requireContentHash == null) {
      requireContentHash = true;
    }
    if (requireReceipt == null) {
      requireReceipt = true;
    }
    if (exactlyOnceDelivery == null) {
      exactlyOnceDelivery = true;
    }
    if (maxMessageChars == null) {
      maxMessageChars = 24000;
    }
    ConfigValidation.minimum("max_message_chars", maxMessageChars, 1000);
    ConfigValidation.maximum("max_message_chars", maxMessageChars, 500000);
    if (maxAssumptions == null) {
      maxAssumptions = 24;
    }
    ConfigValidation.minimum("max_assumptions", maxAssumptions, 0);
    ConfigValidation.maximum("max_assumptions", maxAssumptions, 256);
    if (maxDependencies == null) {
      maxDependencies = 64;
    }
    ConfigValidation.minimum("max_dependencies", maxDependencies, 0);
    ConfigValidation.maximum("max_dependencies", maxDependencies, 1024);
    this.enabled = enabled;
    this.schemaVersion = schemaVersion;
    this.requireProblemHash = requireProblemHash;
    this.requireContentHash = requireContentHash;
    this.requireReceipt = requireReceipt;
    this.exactlyOnceDelivery = exactlyOnceDelivery;
    this.maxMessageChars = maxMessageChars;
    this.maxAssumptions = maxAssumptions;
    this.maxDependencies = maxDependencies;
  }

  public static TypedCommunicationConfig defaults() {
    return new TypedCommunicationConfig(null, null, null, null, null, null, null, null, null);
  }
}
