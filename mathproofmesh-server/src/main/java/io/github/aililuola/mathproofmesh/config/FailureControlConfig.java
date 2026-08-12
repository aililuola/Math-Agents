package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record FailureControlConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "ambiguous_model_review") Boolean ambiguousModelReview,
    @JsonProperty(value = "min_classification_confidence") Double minClassificationConfidence,
    @JsonProperty(value = "max_blueprint_rewrites_per_route") Integer maxBlueprintRewritesPerRoute,
    @JsonProperty(value = "framing_failure_forces_reanchor") Boolean framingFailureForcesReanchor,
    @JsonProperty(value = "plan_failure_blocks_local_deepen") Boolean planFailureBlocksLocalDeepen,
    @JsonProperty(value = "bridge_failure_prefers_bridge_action") Boolean bridgeFailurePrefersBridgeAction
) implements ConfigModel {

  @JsonCreator
  public FailureControlConfig(Boolean enabled, Boolean ambiguousModelReview, Double minClassificationConfidence, Integer maxBlueprintRewritesPerRoute, Boolean framingFailureForcesReanchor, Boolean planFailureBlocksLocalDeepen, Boolean bridgeFailurePrefersBridgeAction) {
    if (enabled == null) {
      enabled = true;
    }
    if (ambiguousModelReview == null) {
      ambiguousModelReview = true;
    }
    if (minClassificationConfidence == null) {
      minClassificationConfidence = 0.7d;
    }
    ConfigValidation.minimum("min_classification_confidence", minClassificationConfidence, 0.0d);
    ConfigValidation.maximum("min_classification_confidence", minClassificationConfidence, 1.0d);
    if (maxBlueprintRewritesPerRoute == null) {
      maxBlueprintRewritesPerRoute = 1;
    }
    ConfigValidation.minimum("max_blueprint_rewrites_per_route", maxBlueprintRewritesPerRoute, 0);
    ConfigValidation.maximum("max_blueprint_rewrites_per_route", maxBlueprintRewritesPerRoute, 8);
    if (framingFailureForcesReanchor == null) {
      framingFailureForcesReanchor = true;
    }
    if (planFailureBlocksLocalDeepen == null) {
      planFailureBlocksLocalDeepen = true;
    }
    if (bridgeFailurePrefersBridgeAction == null) {
      bridgeFailurePrefersBridgeAction = true;
    }
    this.enabled = enabled;
    this.ambiguousModelReview = ambiguousModelReview;
    this.minClassificationConfidence = minClassificationConfidence;
    this.maxBlueprintRewritesPerRoute = maxBlueprintRewritesPerRoute;
    this.framingFailureForcesReanchor = framingFailureForcesReanchor;
    this.planFailureBlocksLocalDeepen = planFailureBlocksLocalDeepen;
    this.bridgeFailurePrefersBridgeAction = bridgeFailurePrefersBridgeAction;
  }

  public static FailureControlConfig defaults() {
    return new FailureControlConfig(null, null, null, null, null, null, null);
  }
}
