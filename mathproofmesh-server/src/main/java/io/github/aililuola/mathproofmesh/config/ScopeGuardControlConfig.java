package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ScopeGuardControlConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "deterministic_first") Boolean deterministicFirst,
    @JsonProperty(value = "ambiguous_model_review") Boolean ambiguousModelReview,
    @JsonProperty(value = "risk_confidence_threshold") Double riskConfidenceThreshold,
    @JsonProperty(value = "max_countermodel_tasks_per_round") Integer maxCountermodelTasksPerRound,
    @JsonProperty(value = "block_fact_promotion_on_open_scope_risk") Boolean blockFactPromotionOnOpenScopeRisk,
    @JsonProperty(value = "block_obligation_close_on_scope_mismatch") Boolean blockObligationCloseOnScopeMismatch
) implements ConfigModel {

  @JsonCreator
  public ScopeGuardControlConfig(Boolean enabled, Boolean deterministicFirst, Boolean ambiguousModelReview, Double riskConfidenceThreshold, Integer maxCountermodelTasksPerRound, Boolean blockFactPromotionOnOpenScopeRisk, Boolean blockObligationCloseOnScopeMismatch) {
    if (enabled == null) {
      enabled = true;
    }
    if (deterministicFirst == null) {
      deterministicFirst = true;
    }
    if (ambiguousModelReview == null) {
      ambiguousModelReview = true;
    }
    if (riskConfidenceThreshold == null) {
      riskConfidenceThreshold = 0.7d;
    }
    ConfigValidation.minimum("risk_confidence_threshold", riskConfidenceThreshold, 0.0d);
    ConfigValidation.maximum("risk_confidence_threshold", riskConfidenceThreshold, 1.0d);
    if (maxCountermodelTasksPerRound == null) {
      maxCountermodelTasksPerRound = 2;
    }
    ConfigValidation.minimum("max_countermodel_tasks_per_round", maxCountermodelTasksPerRound, 0);
    ConfigValidation.maximum("max_countermodel_tasks_per_round", maxCountermodelTasksPerRound, 16);
    if (blockFactPromotionOnOpenScopeRisk == null) {
      blockFactPromotionOnOpenScopeRisk = true;
    }
    if (blockObligationCloseOnScopeMismatch == null) {
      blockObligationCloseOnScopeMismatch = true;
    }
    this.enabled = enabled;
    this.deterministicFirst = deterministicFirst;
    this.ambiguousModelReview = ambiguousModelReview;
    this.riskConfidenceThreshold = riskConfidenceThreshold;
    this.maxCountermodelTasksPerRound = maxCountermodelTasksPerRound;
    this.blockFactPromotionOnOpenScopeRisk = blockFactPromotionOnOpenScopeRisk;
    this.blockObligationCloseOnScopeMismatch = blockObligationCloseOnScopeMismatch;
  }

  public static ScopeGuardControlConfig defaults() {
    return new ScopeGuardControlConfig(null, null, null, null, null, null, null);
  }
}
