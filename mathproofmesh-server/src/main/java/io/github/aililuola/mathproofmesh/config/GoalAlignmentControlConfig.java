package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record GoalAlignmentControlConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "min_alignment_confidence") Double minAlignmentConfidence,
    @JsonProperty(value = "overstrength_penalty") Double overstrengthPenalty,
    @JsonProperty(value = "require_target_obligation") Boolean requireTargetObligation,
    @JsonProperty(value = "require_implication_outline") Boolean requireImplicationOutline,
    @JsonProperty(value = "run_countermodel_on_unknown_relation") Boolean runCountermodelOnUnknownRelation,
    @JsonProperty(value = "max_strategy_rewrite_attempts") Integer maxStrategyRewriteAttempts
) implements ConfigModel {

  @JsonCreator
  public GoalAlignmentControlConfig(Boolean enabled, Double minAlignmentConfidence, Double overstrengthPenalty, Boolean requireTargetObligation, Boolean requireImplicationOutline, Boolean runCountermodelOnUnknownRelation, Integer maxStrategyRewriteAttempts) {
    if (enabled == null) {
      enabled = true;
    }
    if (minAlignmentConfidence == null) {
      minAlignmentConfidence = 0.72d;
    }
    ConfigValidation.minimum("min_alignment_confidence", minAlignmentConfidence, 0.0d);
    ConfigValidation.maximum("min_alignment_confidence", minAlignmentConfidence, 1.0d);
    if (overstrengthPenalty == null) {
      overstrengthPenalty = 0.25d;
    }
    ConfigValidation.minimum("overstrength_penalty", overstrengthPenalty, 0.0d);
    ConfigValidation.maximum("overstrength_penalty", overstrengthPenalty, 2.0d);
    if (requireTargetObligation == null) {
      requireTargetObligation = true;
    }
    if (requireImplicationOutline == null) {
      requireImplicationOutline = true;
    }
    if (runCountermodelOnUnknownRelation == null) {
      runCountermodelOnUnknownRelation = true;
    }
    if (maxStrategyRewriteAttempts == null) {
      maxStrategyRewriteAttempts = 1;
    }
    ConfigValidation.minimum("max_strategy_rewrite_attempts", maxStrategyRewriteAttempts, 0);
    ConfigValidation.maximum("max_strategy_rewrite_attempts", maxStrategyRewriteAttempts, 8);
    this.enabled = enabled;
    this.minAlignmentConfidence = minAlignmentConfidence;
    this.overstrengthPenalty = overstrengthPenalty;
    this.requireTargetObligation = requireTargetObligation;
    this.requireImplicationOutline = requireImplicationOutline;
    this.runCountermodelOnUnknownRelation = runCountermodelOnUnknownRelation;
    this.maxStrategyRewriteAttempts = maxStrategyRewriteAttempts;
  }

  public static GoalAlignmentControlConfig defaults() {
    return new GoalAlignmentControlConfig(null, null, null, null, null, null, null);
  }
}
