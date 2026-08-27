package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record RouteAdmissionControlConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "mode") String mode,
    @JsonProperty(value = "min_goal_alignment") Double minGoalAlignment,
    @JsonProperty(value = "reject_necessary_only_as_main_route") Boolean rejectNecessaryOnlyAsMainRoute,
    @JsonProperty(value = "reject_heuristic_only_as_main_route") Boolean rejectHeuristicOnlyAsMainRoute,
    @JsonProperty(value = "require_falsification_test") Boolean requireFalsificationTest,
    @JsonProperty(value = "require_mechanism_novelty") Boolean requireMechanismNovelty,
    @JsonProperty(value = "max_regeneration_attempts") Integer maxRegenerationAttempts,
    @JsonProperty(value = "fail_closed_if_all_rejected") Boolean failClosedIfAllRejected,
    @JsonProperty(value = "semantic_repair_enabled") Boolean semanticRepairEnabled,
    @JsonProperty(value = "max_semantic_repair_calls") Integer maxSemanticRepairCalls,
    @JsonProperty(value = "semantic_repair_budget_fraction") Double semanticRepairBudgetFraction,
    @JsonProperty(value = "blueprint_review") BlueprintReviewControlConfig blueprintReview
) implements ConfigModel {

  @JsonCreator
  public RouteAdmissionControlConfig(Boolean enabled, String mode, Double minGoalAlignment, Boolean rejectNecessaryOnlyAsMainRoute, Boolean rejectHeuristicOnlyAsMainRoute, Boolean requireFalsificationTest, Boolean requireMechanismNovelty, Integer maxRegenerationAttempts, Boolean failClosedIfAllRejected, Boolean semanticRepairEnabled, Integer maxSemanticRepairCalls, Double semanticRepairBudgetFraction, BlueprintReviewControlConfig blueprintReview) {
    if (enabled == null) {
      enabled = true;
    }
    if (mode == null) {
      mode = "shadow";
    }
    mode = ConfigValidation.trim(mode);
    ConfigValidation.oneOf("mode", mode, "off", "shadow", "active");
    if (minGoalAlignment == null) {
      minGoalAlignment = 0.65d;
    }
    ConfigValidation.minimum("min_goal_alignment", minGoalAlignment, 0.0d);
    ConfigValidation.maximum("min_goal_alignment", minGoalAlignment, 1.0d);
    if (rejectNecessaryOnlyAsMainRoute == null) {
      rejectNecessaryOnlyAsMainRoute = true;
    }
    if (rejectHeuristicOnlyAsMainRoute == null) {
      rejectHeuristicOnlyAsMainRoute = true;
    }
    if (requireFalsificationTest == null) {
      requireFalsificationTest = true;
    }
    if (requireMechanismNovelty == null) {
      requireMechanismNovelty = true;
    }
    if (maxRegenerationAttempts == null) {
      maxRegenerationAttempts = 1;
    }
    ConfigValidation.minimum("max_regeneration_attempts", maxRegenerationAttempts, 0);
    ConfigValidation.maximum("max_regeneration_attempts", maxRegenerationAttempts, 4);
    if (failClosedIfAllRejected == null) {
      failClosedIfAllRejected = true;
    }
    if (semanticRepairEnabled == null) {
      semanticRepairEnabled = true;
    }
    if (maxSemanticRepairCalls == null) {
      maxSemanticRepairCalls = 2;
    }
    ConfigValidation.minimum("max_semantic_repair_calls", maxSemanticRepairCalls, 0);
    ConfigValidation.maximum("max_semantic_repair_calls", maxSemanticRepairCalls, 8);
    if (semanticRepairBudgetFraction == null) {
      semanticRepairBudgetFraction = 0.1d;
    }
    ConfigValidation.minimum("semantic_repair_budget_fraction", semanticRepairBudgetFraction, 0.0d);
    ConfigValidation.maximum("semantic_repair_budget_fraction", semanticRepairBudgetFraction, 0.5d);
    if (blueprintReview == null) {
      blueprintReview = BlueprintReviewControlConfig.defaults();
    }
    this.enabled = enabled;
    this.mode = mode;
    this.minGoalAlignment = minGoalAlignment;
    this.rejectNecessaryOnlyAsMainRoute = rejectNecessaryOnlyAsMainRoute;
    this.rejectHeuristicOnlyAsMainRoute = rejectHeuristicOnlyAsMainRoute;
    this.requireFalsificationTest = requireFalsificationTest;
    this.requireMechanismNovelty = requireMechanismNovelty;
    this.maxRegenerationAttempts = maxRegenerationAttempts;
    this.failClosedIfAllRejected = failClosedIfAllRejected;
    this.semanticRepairEnabled = semanticRepairEnabled;
    this.maxSemanticRepairCalls = maxSemanticRepairCalls;
    this.semanticRepairBudgetFraction = semanticRepairBudgetFraction;
    this.blueprintReview = blueprintReview;
  }

  public static RouteAdmissionControlConfig defaults() {
    return new RouteAdmissionControlConfig(null, null, null, null, null, null, null, null, null, null, null, null, null);
  }
}
