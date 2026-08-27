package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record TypedMemoryConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "strict_fact_gate") Boolean strictFactGate,
    @JsonProperty(value = "fact_pass_threshold") Double factPassThreshold,
    @JsonProperty(value = "counterexample_is_global_negative") Boolean counterexampleIsGlobalNegative,
    @JsonProperty(value = "retain_rejected_claims") Boolean retainRejectedClaims,
    @JsonProperty(value = "retain_expired_insights") Boolean retainExpiredInsights,
    @JsonProperty(value = "max_fact_context") Integer maxFactContext,
    @JsonProperty(value = "max_insight_context") Integer maxInsightContext,
    @JsonProperty(value = "max_negative_context") Integer maxNegativeContext
) implements ConfigModel {

  @JsonCreator
  public TypedMemoryConfig(Boolean enabled, Boolean strictFactGate, Double factPassThreshold, Boolean counterexampleIsGlobalNegative, Boolean retainRejectedClaims, Boolean retainExpiredInsights, Integer maxFactContext, Integer maxInsightContext, Integer maxNegativeContext) {
    if (enabled == null) {
      enabled = false;
    }
    if (strictFactGate == null) {
      strictFactGate = true;
    }
    if (factPassThreshold == null) {
      factPassThreshold = 0.8d;
    }
    ConfigValidation.minimum("fact_pass_threshold", factPassThreshold, 0.0d);
    ConfigValidation.maximum("fact_pass_threshold", factPassThreshold, 1.0d);
    if (counterexampleIsGlobalNegative == null) {
      counterexampleIsGlobalNegative = true;
    }
    if (retainRejectedClaims == null) {
      retainRejectedClaims = true;
    }
    if (retainExpiredInsights == null) {
      retainExpiredInsights = true;
    }
    if (maxFactContext == null) {
      maxFactContext = 32;
    }
    ConfigValidation.minimum("max_fact_context", maxFactContext, 1);
    ConfigValidation.maximum("max_fact_context", maxFactContext, 512);
    if (maxInsightContext == null) {
      maxInsightContext = 16;
    }
    ConfigValidation.minimum("max_insight_context", maxInsightContext, 0);
    ConfigValidation.maximum("max_insight_context", maxInsightContext, 512);
    if (maxNegativeContext == null) {
      maxNegativeContext = 16;
    }
    ConfigValidation.minimum("max_negative_context", maxNegativeContext, 0);
    ConfigValidation.maximum("max_negative_context", maxNegativeContext, 512);
    this.enabled = enabled;
    this.strictFactGate = strictFactGate;
    this.factPassThreshold = factPassThreshold;
    this.counterexampleIsGlobalNegative = counterexampleIsGlobalNegative;
    this.retainRejectedClaims = retainRejectedClaims;
    this.retainExpiredInsights = retainExpiredInsights;
    this.maxFactContext = maxFactContext;
    this.maxInsightContext = maxInsightContext;
    this.maxNegativeContext = maxNegativeContext;
  }

  public static TypedMemoryConfig defaults() {
    return new TypedMemoryConfig(null, null, null, null, null, null, null, null, null);
  }
}
