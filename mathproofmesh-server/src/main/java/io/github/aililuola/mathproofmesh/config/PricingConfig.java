package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record PricingConfig(
    @JsonProperty(value = "input_per_million") Double inputPerMillion,
    @JsonProperty(value = "output_per_million") Double outputPerMillion
) implements ConfigModel {

  @JsonCreator
  public PricingConfig(Double inputPerMillion, Double outputPerMillion) {
    if (inputPerMillion == null) {
      inputPerMillion = 0.0d;
    }
    ConfigValidation.minimum("input_per_million", inputPerMillion, 0.0d);
    if (outputPerMillion == null) {
      outputPerMillion = 0.0d;
    }
    ConfigValidation.minimum("output_per_million", outputPerMillion, 0.0d);
    this.inputPerMillion = inputPerMillion;
    this.outputPerMillion = outputPerMillion;
  }

  public static PricingConfig defaults() {
    return new PricingConfig(null, null);
  }
}
