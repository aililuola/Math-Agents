package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record CoreDebtControlConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "main_goal_weight") Double mainGoalWeight,
    @JsonProperty(value = "core_bridge_weight") Double coreBridgeWeight,
    @JsonProperty(value = "auxiliary_weight") Double auxiliaryWeight,
    @JsonProperty(value = "necessary_only_weight") Double necessaryOnlyWeight,
    @JsonProperty(value = "unresolved_scope_risk_weight") Double unresolvedScopeRiskWeight,
    @JsonProperty(value = "common_mode_weight") Double commonModeWeight,
    @JsonProperty(value = "core_stagnation_rounds") Integer coreStagnationRounds
) implements ConfigModel {

  @JsonCreator
  public CoreDebtControlConfig(Boolean enabled, Double mainGoalWeight, Double coreBridgeWeight, Double auxiliaryWeight, Double necessaryOnlyWeight, Double unresolvedScopeRiskWeight, Double commonModeWeight, Integer coreStagnationRounds) {
    if (enabled == null) {
      enabled = true;
    }
    if (mainGoalWeight == null) {
      mainGoalWeight = 4.0d;
    }
    ConfigValidation.minimum("main_goal_weight", mainGoalWeight, 0.0d);
    ConfigValidation.maximum("main_goal_weight", mainGoalWeight, 100.0d);
    if (coreBridgeWeight == null) {
      coreBridgeWeight = 2.5d;
    }
    ConfigValidation.minimum("core_bridge_weight", coreBridgeWeight, 0.0d);
    ConfigValidation.maximum("core_bridge_weight", coreBridgeWeight, 100.0d);
    if (auxiliaryWeight == null) {
      auxiliaryWeight = 0.35d;
    }
    ConfigValidation.minimum("auxiliary_weight", auxiliaryWeight, 0.0d);
    ConfigValidation.maximum("auxiliary_weight", auxiliaryWeight, 100.0d);
    if (necessaryOnlyWeight == null) {
      necessaryOnlyWeight = 0.15d;
    }
    ConfigValidation.minimum("necessary_only_weight", necessaryOnlyWeight, 0.0d);
    ConfigValidation.maximum("necessary_only_weight", necessaryOnlyWeight, 100.0d);
    if (unresolvedScopeRiskWeight == null) {
      unresolvedScopeRiskWeight = 1.5d;
    }
    ConfigValidation.minimum("unresolved_scope_risk_weight", unresolvedScopeRiskWeight, 0.0d);
    ConfigValidation.maximum("unresolved_scope_risk_weight", unresolvedScopeRiskWeight, 100.0d);
    if (commonModeWeight == null) {
      commonModeWeight = 1.5d;
    }
    ConfigValidation.minimum("common_mode_weight", commonModeWeight, 0.0d);
    ConfigValidation.maximum("common_mode_weight", commonModeWeight, 100.0d);
    if (coreStagnationRounds == null) {
      coreStagnationRounds = 2;
    }
    ConfigValidation.minimum("core_stagnation_rounds", coreStagnationRounds, 1);
    ConfigValidation.maximum("core_stagnation_rounds", coreStagnationRounds, 32);
    this.enabled = enabled;
    this.mainGoalWeight = mainGoalWeight;
    this.coreBridgeWeight = coreBridgeWeight;
    this.auxiliaryWeight = auxiliaryWeight;
    this.necessaryOnlyWeight = necessaryOnlyWeight;
    this.unresolvedScopeRiskWeight = unresolvedScopeRiskWeight;
    this.commonModeWeight = commonModeWeight;
    this.coreStagnationRounds = coreStagnationRounds;
  }

  public static CoreDebtControlConfig defaults() {
    return new CoreDebtControlConfig(null, null, null, null, null, null, null, null);
  }
}
