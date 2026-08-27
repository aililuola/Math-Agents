package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record InductionControlConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "max_candidates_per_trigger") Integer maxCandidatesPerTrigger,
    @JsonProperty(value = "trigger_on_first_occurrence_barrier") Boolean triggerOnFirstOccurrenceBarrier,
    @JsonProperty(value = "trigger_on_recursive_same_type_dependency") Boolean triggerOnRecursiveSameTypeDependency,
    @JsonProperty(value = "trigger_on_repeated_feature") Boolean triggerOnRepeatedFeature,
    @JsonProperty(value = "require_well_foundedness_statement") Boolean requireWellFoundednessStatement
) implements ConfigModel {

  @JsonCreator
  public InductionControlConfig(Boolean enabled, Integer maxCandidatesPerTrigger, Boolean triggerOnFirstOccurrenceBarrier, Boolean triggerOnRecursiveSameTypeDependency, Boolean triggerOnRepeatedFeature, Boolean requireWellFoundednessStatement) {
    if (enabled == null) {
      enabled = true;
    }
    if (maxCandidatesPerTrigger == null) {
      maxCandidatesPerTrigger = 3;
    }
    ConfigValidation.minimum("max_candidates_per_trigger", maxCandidatesPerTrigger, 1);
    ConfigValidation.maximum("max_candidates_per_trigger", maxCandidatesPerTrigger, 16);
    if (triggerOnFirstOccurrenceBarrier == null) {
      triggerOnFirstOccurrenceBarrier = true;
    }
    if (triggerOnRecursiveSameTypeDependency == null) {
      triggerOnRecursiveSameTypeDependency = true;
    }
    if (triggerOnRepeatedFeature == null) {
      triggerOnRepeatedFeature = true;
    }
    if (requireWellFoundednessStatement == null) {
      requireWellFoundednessStatement = true;
    }
    this.enabled = enabled;
    this.maxCandidatesPerTrigger = maxCandidatesPerTrigger;
    this.triggerOnFirstOccurrenceBarrier = triggerOnFirstOccurrenceBarrier;
    this.triggerOnRecursiveSameTypeDependency = triggerOnRecursiveSameTypeDependency;
    this.triggerOnRepeatedFeature = triggerOnRepeatedFeature;
    this.requireWellFoundednessStatement = requireWellFoundednessStatement;
  }

  public static InductionControlConfig defaults() {
    return new InductionControlConfig(null, null, null, null, null, null);
  }
}
