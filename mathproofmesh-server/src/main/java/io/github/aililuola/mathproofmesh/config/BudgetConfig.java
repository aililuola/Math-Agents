package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record BudgetConfig(
    @JsonProperty(value = "max_total_calls") Integer maxTotalCalls,
    @JsonProperty(value = "max_rounds") Integer maxRounds,
    @JsonProperty(value = "initial_paths") Integer initialPaths,
    @JsonProperty(value = "max_paths") Integer maxPaths,
    @JsonProperty(value = "strategies_to_generate") Integer strategiesToGenerate,
    @JsonProperty(value = "candidates_to_verify") Integer candidatesToVerify,
    @JsonProperty(value = "max_revisions") Integer maxRevisions,
    @JsonProperty(value = "base_verifier_replicas") Integer baseVerifierReplicas,
    @JsonProperty(value = "high_risk_verifier_replicas") Integer highRiskVerifierReplicas,
    @JsonProperty(value = "high_risk_threshold") Double highRiskThreshold,
    @JsonProperty(value = "verification_pass_threshold") Double verificationPassThreshold,
    @JsonProperty(value = "synthesis_threshold") Double synthesisThreshold,
    @JsonProperty(value = "max_total_tokens") @ConfigNullable Integer maxTotalTokens,
    @JsonProperty(value = "max_cost_usd") @ConfigNullable Double maxCostUsd,
    @JsonProperty(value = "breadth_share") Double breadthShare,
    @JsonProperty(value = "depth_share") Double depthShare,
    @JsonProperty(value = "verification_share") Double verificationShare,
    @JsonProperty(value = "synthesis_share") Double synthesisShare,
    @JsonProperty(value = "scale_budget_with_difficulty") Boolean scaleBudgetWithDifficulty,
    @JsonProperty(value = "hard_problem_call_multiplier") Double hardProblemCallMultiplier,
    @JsonProperty(value = "hard_problem_extra_rounds") Integer hardProblemExtraRounds
) implements ConfigModel {

  @JsonCreator
  public BudgetConfig(Integer maxTotalCalls, Integer maxRounds, Integer initialPaths, Integer maxPaths, Integer strategiesToGenerate, Integer candidatesToVerify, Integer maxRevisions, Integer baseVerifierReplicas, Integer highRiskVerifierReplicas, Double highRiskThreshold, Double verificationPassThreshold, Double synthesisThreshold, Integer maxTotalTokens, Double maxCostUsd, Double breadthShare, Double depthShare, Double verificationShare, Double synthesisShare, Boolean scaleBudgetWithDifficulty, Double hardProblemCallMultiplier, Integer hardProblemExtraRounds) {
    if (maxTotalCalls == null) {
      maxTotalCalls = 48;
    }
    ConfigValidation.minimum("max_total_calls", maxTotalCalls, 4);
    ConfigValidation.maximum("max_total_calls", maxTotalCalls, 10000);
    if (maxRounds == null) {
      maxRounds = 4;
    }
    ConfigValidation.minimum("max_rounds", maxRounds, 1);
    ConfigValidation.maximum("max_rounds", maxRounds, 64);
    if (initialPaths == null) {
      initialPaths = 4;
    }
    ConfigValidation.minimum("initial_paths", initialPaths, 1);
    ConfigValidation.maximum("initial_paths", initialPaths, 32);
    if (maxPaths == null) {
      maxPaths = 8;
    }
    ConfigValidation.minimum("max_paths", maxPaths, 1);
    ConfigValidation.maximum("max_paths", maxPaths, 64);
    if (strategiesToGenerate == null) {
      strategiesToGenerate = 7;
    }
    ConfigValidation.minimum("strategies_to_generate", strategiesToGenerate, 1);
    ConfigValidation.maximum("strategies_to_generate", strategiesToGenerate, 64);
    if (candidatesToVerify == null) {
      candidatesToVerify = 3;
    }
    ConfigValidation.minimum("candidates_to_verify", candidatesToVerify, 1);
    ConfigValidation.maximum("candidates_to_verify", candidatesToVerify, 32);
    if (maxRevisions == null) {
      maxRevisions = 3;
    }
    ConfigValidation.minimum("max_revisions", maxRevisions, 0);
    ConfigValidation.maximum("max_revisions", maxRevisions, 32);
    if (baseVerifierReplicas == null) {
      baseVerifierReplicas = 1;
    }
    ConfigValidation.minimum("base_verifier_replicas", baseVerifierReplicas, 1);
    ConfigValidation.maximum("base_verifier_replicas", baseVerifierReplicas, 8);
    if (highRiskVerifierReplicas == null) {
      highRiskVerifierReplicas = 2;
    }
    ConfigValidation.minimum("high_risk_verifier_replicas", highRiskVerifierReplicas, 1);
    ConfigValidation.maximum("high_risk_verifier_replicas", highRiskVerifierReplicas, 8);
    if (highRiskThreshold == null) {
      highRiskThreshold = 0.45d;
    }
    ConfigValidation.minimum("high_risk_threshold", highRiskThreshold, 0.0d);
    ConfigValidation.maximum("high_risk_threshold", highRiskThreshold, 1.0d);
    if (verificationPassThreshold == null) {
      verificationPassThreshold = 0.78d;
    }
    ConfigValidation.minimum("verification_pass_threshold", verificationPassThreshold, 0.0d);
    ConfigValidation.maximum("verification_pass_threshold", verificationPassThreshold, 1.0d);
    if (synthesisThreshold == null) {
      synthesisThreshold = 0.72d;
    }
    ConfigValidation.minimum("synthesis_threshold", synthesisThreshold, 0.0d);
    ConfigValidation.maximum("synthesis_threshold", synthesisThreshold, 1.0d);
    ConfigValidation.minimum("max_total_tokens", maxTotalTokens, 1000);
    ConfigValidation.minimum("max_cost_usd", maxCostUsd, 0.01d);
    if (breadthShare == null) {
      breadthShare = 0.3d;
    }
    ConfigValidation.minimum("breadth_share", breadthShare, 0.0d);
    ConfigValidation.maximum("breadth_share", breadthShare, 1.0d);
    if (depthShare == null) {
      depthShare = 0.35d;
    }
    ConfigValidation.minimum("depth_share", depthShare, 0.0d);
    ConfigValidation.maximum("depth_share", depthShare, 1.0d);
    if (verificationShare == null) {
      verificationShare = 0.25d;
    }
    ConfigValidation.minimum("verification_share", verificationShare, 0.0d);
    ConfigValidation.maximum("verification_share", verificationShare, 1.0d);
    if (synthesisShare == null) {
      synthesisShare = 0.1d;
    }
    ConfigValidation.minimum("synthesis_share", synthesisShare, 0.0d);
    ConfigValidation.maximum("synthesis_share", synthesisShare, 1.0d);
    if (scaleBudgetWithDifficulty == null) {
      scaleBudgetWithDifficulty = false;
    }
    if (hardProblemCallMultiplier == null) {
      hardProblemCallMultiplier = 2.0d;
    }
    ConfigValidation.minimum("hard_problem_call_multiplier", hardProblemCallMultiplier, 1.0d);
    ConfigValidation.maximum("hard_problem_call_multiplier", hardProblemCallMultiplier, 8.0d);
    if (hardProblemExtraRounds == null) {
      hardProblemExtraRounds = 2;
    }
    ConfigValidation.minimum("hard_problem_extra_rounds", hardProblemExtraRounds, 0);
    ConfigValidation.maximum("hard_problem_extra_rounds", hardProblemExtraRounds, 16);
    this.maxTotalCalls = maxTotalCalls;
    this.maxRounds = maxRounds;
    this.initialPaths = initialPaths;
    this.maxPaths = maxPaths;
    this.strategiesToGenerate = strategiesToGenerate;
    this.candidatesToVerify = candidatesToVerify;
    this.maxRevisions = maxRevisions;
    this.baseVerifierReplicas = baseVerifierReplicas;
    this.highRiskVerifierReplicas = highRiskVerifierReplicas;
    this.highRiskThreshold = highRiskThreshold;
    this.verificationPassThreshold = verificationPassThreshold;
    this.synthesisThreshold = synthesisThreshold;
    this.maxTotalTokens = maxTotalTokens;
    this.maxCostUsd = maxCostUsd;
    this.breadthShare = breadthShare;
    this.depthShare = depthShare;
    this.verificationShare = verificationShare;
    this.synthesisShare = synthesisShare;
    this.scaleBudgetWithDifficulty = scaleBudgetWithDifficulty;
    this.hardProblemCallMultiplier = hardProblemCallMultiplier;
    this.hardProblemExtraRounds = hardProblemExtraRounds;
    ConfigInvariants.validate(this);
  }

  public static BudgetConfig defaults() {
    return new BudgetConfig(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }
}
