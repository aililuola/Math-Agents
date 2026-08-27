package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AgentCapabilityConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "min_observations_before_trust_update") Integer minObservationsBeforeTrustUpdate,
    @JsonProperty(value = "recency_decay") Double recencyDecay,
    @JsonProperty(value = "mutation_benchmark_weight") Double mutationBenchmarkWeight,
    @JsonProperty(value = "tool_agreement_weight") Double toolAgreementWeight,
    @JsonProperty(value = "first_error_accuracy_weight") Double firstErrorAccuracyWeight,
    @JsonProperty(value = "overturn_rate_penalty") Double overturnRatePenalty,
    @JsonProperty(value = "use_self_reported_confidence") Boolean useSelfReportedConfidence
) implements ConfigModel {

  @JsonCreator
  public AgentCapabilityConfig(Boolean enabled, Integer minObservationsBeforeTrustUpdate, Double recencyDecay, Double mutationBenchmarkWeight, Double toolAgreementWeight, Double firstErrorAccuracyWeight, Double overturnRatePenalty, Boolean useSelfReportedConfidence) {
    if (enabled == null) {
      enabled = true;
    }
    if (minObservationsBeforeTrustUpdate == null) {
      minObservationsBeforeTrustUpdate = 5;
    }
    ConfigValidation.minimum("min_observations_before_trust_update", minObservationsBeforeTrustUpdate, 1);
    ConfigValidation.maximum("min_observations_before_trust_update", minObservationsBeforeTrustUpdate, 10000);
    if (recencyDecay == null) {
      recencyDecay = 0.98d;
    }
    ConfigValidation.minimum("recency_decay", recencyDecay, 0.0d);
    ConfigValidation.maximum("recency_decay", recencyDecay, 1.0d);
    if (mutationBenchmarkWeight == null) {
      mutationBenchmarkWeight = 0.3d;
    }
    ConfigValidation.minimum("mutation_benchmark_weight", mutationBenchmarkWeight, 0.0d);
    ConfigValidation.maximum("mutation_benchmark_weight", mutationBenchmarkWeight, 1.0d);
    if (toolAgreementWeight == null) {
      toolAgreementWeight = 0.25d;
    }
    ConfigValidation.minimum("tool_agreement_weight", toolAgreementWeight, 0.0d);
    ConfigValidation.maximum("tool_agreement_weight", toolAgreementWeight, 1.0d);
    if (firstErrorAccuracyWeight == null) {
      firstErrorAccuracyWeight = 0.25d;
    }
    ConfigValidation.minimum("first_error_accuracy_weight", firstErrorAccuracyWeight, 0.0d);
    ConfigValidation.maximum("first_error_accuracy_weight", firstErrorAccuracyWeight, 1.0d);
    if (overturnRatePenalty == null) {
      overturnRatePenalty = 0.2d;
    }
    ConfigValidation.minimum("overturn_rate_penalty", overturnRatePenalty, 0.0d);
    ConfigValidation.maximum("overturn_rate_penalty", overturnRatePenalty, 1.0d);
    if (useSelfReportedConfidence == null) {
      useSelfReportedConfidence = false;
    }
    this.enabled = enabled;
    this.minObservationsBeforeTrustUpdate = minObservationsBeforeTrustUpdate;
    this.recencyDecay = recencyDecay;
    this.mutationBenchmarkWeight = mutationBenchmarkWeight;
    this.toolAgreementWeight = toolAgreementWeight;
    this.firstErrorAccuracyWeight = firstErrorAccuracyWeight;
    this.overturnRatePenalty = overturnRatePenalty;
    this.useSelfReportedConfidence = useSelfReportedConfidence;
    ConfigInvariants.validate(this);
  }

  public static AgentCapabilityConfig defaults() {
    return new AgentCapabilityConfig(null, null, null, null, null, null, null, null);
  }
}
