package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record MessageUtilityControlConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "require_utility_contract_for_cross_route") Boolean requireUtilityContractForCrossRoute,
    @JsonProperty(value = "broadcast_min_expected_core_debt_reduction") Double broadcastMinExpectedCoreDebtReduction,
    @JsonProperty(value = "max_target_obligations") Integer maxTargetObligations,
    @JsonProperty(value = "utility_credit_horizon_rounds") Integer utilityCreditHorizonRounds,
    @JsonProperty(value = "no_use_cooldown_threshold") Integer noUseCooldownThreshold,
    @JsonProperty(value = "final_citation_credit") Double finalCitationCredit,
    @JsonProperty(value = "obligation_close_credit") Double obligationCloseCredit,
    @JsonProperty(value = "verified_step_credit") Double verifiedStepCredit,
    @JsonProperty(value = "refutation_credit") Double refutationCredit,
    @JsonProperty(value = "blueprint_rewrite_credit") Double blueprintRewriteCredit
) implements ConfigModel {

  @JsonCreator
  public MessageUtilityControlConfig(Boolean enabled, Boolean requireUtilityContractForCrossRoute, Double broadcastMinExpectedCoreDebtReduction, Integer maxTargetObligations, Integer utilityCreditHorizonRounds, Integer noUseCooldownThreshold, Double finalCitationCredit, Double obligationCloseCredit, Double verifiedStepCredit, Double refutationCredit, Double blueprintRewriteCredit) {
    if (enabled == null) {
      enabled = true;
    }
    if (requireUtilityContractForCrossRoute == null) {
      requireUtilityContractForCrossRoute = true;
    }
    if (broadcastMinExpectedCoreDebtReduction == null) {
      broadcastMinExpectedCoreDebtReduction = 0.0d;
    }
    ConfigValidation.minimum("broadcast_min_expected_core_debt_reduction", broadcastMinExpectedCoreDebtReduction, 0.0d);
    ConfigValidation.maximum("broadcast_min_expected_core_debt_reduction", broadcastMinExpectedCoreDebtReduction, 1000.0d);
    if (maxTargetObligations == null) {
      maxTargetObligations = 8;
    }
    ConfigValidation.minimum("max_target_obligations", maxTargetObligations, 1);
    ConfigValidation.maximum("max_target_obligations", maxTargetObligations, 64);
    if (utilityCreditHorizonRounds == null) {
      utilityCreditHorizonRounds = 3;
    }
    ConfigValidation.minimum("utility_credit_horizon_rounds", utilityCreditHorizonRounds, 1);
    ConfigValidation.maximum("utility_credit_horizon_rounds", utilityCreditHorizonRounds, 32);
    if (noUseCooldownThreshold == null) {
      noUseCooldownThreshold = 3;
    }
    ConfigValidation.minimum("no_use_cooldown_threshold", noUseCooldownThreshold, 1);
    ConfigValidation.maximum("no_use_cooldown_threshold", noUseCooldownThreshold, 32);
    if (finalCitationCredit == null) {
      finalCitationCredit = 1.0d;
    }
    ConfigValidation.minimum("final_citation_credit", finalCitationCredit, 0.0d);
    ConfigValidation.maximum("final_citation_credit", finalCitationCredit, 4.0d);
    if (obligationCloseCredit == null) {
      obligationCloseCredit = 0.8d;
    }
    ConfigValidation.minimum("obligation_close_credit", obligationCloseCredit, 0.0d);
    ConfigValidation.maximum("obligation_close_credit", obligationCloseCredit, 4.0d);
    if (verifiedStepCredit == null) {
      verifiedStepCredit = 0.5d;
    }
    ConfigValidation.minimum("verified_step_credit", verifiedStepCredit, 0.0d);
    ConfigValidation.maximum("verified_step_credit", verifiedStepCredit, 4.0d);
    if (refutationCredit == null) {
      refutationCredit = 0.8d;
    }
    ConfigValidation.minimum("refutation_credit", refutationCredit, 0.0d);
    ConfigValidation.maximum("refutation_credit", refutationCredit, 4.0d);
    if (blueprintRewriteCredit == null) {
      blueprintRewriteCredit = 0.4d;
    }
    ConfigValidation.minimum("blueprint_rewrite_credit", blueprintRewriteCredit, 0.0d);
    ConfigValidation.maximum("blueprint_rewrite_credit", blueprintRewriteCredit, 4.0d);
    this.enabled = enabled;
    this.requireUtilityContractForCrossRoute = requireUtilityContractForCrossRoute;
    this.broadcastMinExpectedCoreDebtReduction = broadcastMinExpectedCoreDebtReduction;
    this.maxTargetObligations = maxTargetObligations;
    this.utilityCreditHorizonRounds = utilityCreditHorizonRounds;
    this.noUseCooldownThreshold = noUseCooldownThreshold;
    this.finalCitationCredit = finalCitationCredit;
    this.obligationCloseCredit = obligationCloseCredit;
    this.verifiedStepCredit = verifiedStepCredit;
    this.refutationCredit = refutationCredit;
    this.blueprintRewriteCredit = blueprintRewriteCredit;
  }

  public static MessageUtilityControlConfig defaults() {
    return new MessageUtilityControlConfig(null, null, null, null, null, null, null, null, null, null, null);
  }
}
