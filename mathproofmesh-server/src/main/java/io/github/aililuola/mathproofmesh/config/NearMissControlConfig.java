package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record NearMissControlConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "min_verifier_confidence") Double minVerifierConfidence,
    @JsonProperty(value = "max_records") Integer maxRecords,
    @JsonProperty(value = "max_route_context_items") Integer maxRouteContextItems,
    @JsonProperty(value = "extraction_requires_salvageable_component") Boolean extractionRequiresSalvageableComponent,
    @JsonProperty(value = "allow_model_extraction_on_ambiguous") Boolean allowModelExtractionOnAmbiguous
) implements ConfigModel {

  @JsonCreator
  public NearMissControlConfig(Boolean enabled, Double minVerifierConfidence, Integer maxRecords, Integer maxRouteContextItems, Boolean extractionRequiresSalvageableComponent, Boolean allowModelExtractionOnAmbiguous) {
    if (enabled == null) {
      enabled = true;
    }
    if (minVerifierConfidence == null) {
      minVerifierConfidence = 0.65d;
    }
    ConfigValidation.minimum("min_verifier_confidence", minVerifierConfidence, 0.0d);
    ConfigValidation.maximum("min_verifier_confidence", minVerifierConfidence, 1.0d);
    if (maxRecords == null) {
      maxRecords = 512;
    }
    ConfigValidation.minimum("max_records", maxRecords, 0);
    ConfigValidation.maximum("max_records", maxRecords, 10000);
    if (maxRouteContextItems == null) {
      maxRouteContextItems = 6;
    }
    ConfigValidation.minimum("max_route_context_items", maxRouteContextItems, 0);
    ConfigValidation.maximum("max_route_context_items", maxRouteContextItems, 64);
    if (extractionRequiresSalvageableComponent == null) {
      extractionRequiresSalvageableComponent = true;
    }
    if (allowModelExtractionOnAmbiguous == null) {
      allowModelExtractionOnAmbiguous = true;
    }
    this.enabled = enabled;
    this.minVerifierConfidence = minVerifierConfidence;
    this.maxRecords = maxRecords;
    this.maxRouteContextItems = maxRouteContextItems;
    this.extractionRequiresSalvageableComponent = extractionRequiresSalvageableComponent;
    this.allowModelExtractionOnAmbiguous = allowModelExtractionOnAmbiguous;
  }

  public static NearMissControlConfig defaults() {
    return new NearMissControlConfig(null, null, null, null, null, null);
  }
}
