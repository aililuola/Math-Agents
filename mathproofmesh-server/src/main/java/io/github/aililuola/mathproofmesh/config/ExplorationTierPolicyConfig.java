package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ExplorationTierPolicyConfig(
    @JsonProperty(value = "output_tokens", required = true) Integer outputTokens,
    @JsonProperty(value = "artifact_recovery_tokens", required = true) Integer artifactRecoveryTokens
) implements ConfigModel {

  @JsonCreator
  public ExplorationTierPolicyConfig(Integer outputTokens, Integer artifactRecoveryTokens) {
    outputTokens = ConfigValidation.required("output_tokens", outputTokens);
    ConfigValidation.minimum("output_tokens", outputTokens, 512);
    ConfigValidation.maximum("output_tokens", outputTokens, 384000);
    artifactRecoveryTokens = ConfigValidation.required("artifact_recovery_tokens", artifactRecoveryTokens);
    ConfigValidation.minimum("artifact_recovery_tokens", artifactRecoveryTokens, 256);
    ConfigValidation.maximum("artifact_recovery_tokens", artifactRecoveryTokens, 128000);
    this.outputTokens = outputTokens;
    this.artifactRecoveryTokens = artifactRecoveryTokens;
    ConfigInvariants.validate(this);
  }
}
