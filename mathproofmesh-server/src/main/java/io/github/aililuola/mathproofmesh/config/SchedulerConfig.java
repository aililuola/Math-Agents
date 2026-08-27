package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record SchedulerConfig(
    @JsonProperty(value = "max_actions_per_round") Integer maxActionsPerRound,
    @JsonProperty(value = "widen_paths_per_action") Integer widenPathsPerAction,
    @JsonProperty(value = "force_widen_when_all_failed") Boolean forceWidenWhenAllFailed,
    @JsonProperty(value = "max_execution_repairs_per_path") Integer maxExecutionRepairsPerPath,
    @JsonProperty(value = "max_plan_repairs_per_path") Integer maxPlanRepairsPerPath,
    @JsonProperty(value = "max_unknown_failure_repairs_per_path") Integer maxUnknownFailureRepairsPerPath,
    @JsonProperty(value = "allow_strategy_failure_repair") Boolean allowStrategyFailureRepair,
    @JsonProperty(value = "failed_path_cooldown_rounds") Integer failedPathCooldownRounds,
    @JsonProperty(value = "meaningful_progress_threshold") Double meaningfulProgressThreshold,
    @JsonProperty(value = "unverified_progress_discount") Double unverifiedProgressDiscount,
    @JsonProperty(value = "uncertain_progress_discount") Double uncertainProgressDiscount,
    @JsonProperty(value = "failed_progress_discount") Double failedProgressDiscount,
    @JsonProperty(value = "structural_failure_progress_cap") Double structuralFailureProgressCap,
    @JsonProperty(value = "execution_failure_progress_cap") Double executionFailureProgressCap,
    @JsonProperty(value = "plan_failure_progress_cap") Double planFailureProgressCap,
    @JsonProperty(value = "strategy_failure_progress_cap") Double strategyFailureProgressCap,
    @JsonProperty(value = "structural_failure_penalty") Double structuralFailurePenalty,
    @JsonProperty(value = "execution_failure_penalty") Double executionFailurePenalty,
    @JsonProperty(value = "plan_failure_penalty") Double planFailurePenalty,
    @JsonProperty(value = "strategy_failure_penalty") Double strategyFailurePenalty,
    @JsonProperty(value = "repeated_failure_penalty") Double repeatedFailurePenalty,
    @JsonProperty(value = "reserve_revision_cycles") Integer reserveRevisionCycles,
    @JsonProperty(value = "include_post_action_verification_in_cost") Boolean includePostActionVerificationInCost,
    @JsonProperty(value = "include_meta_review_in_cost") Boolean includeMetaReviewInCost,
    @JsonProperty(value = "verification_call_safety_margin") Integer verificationCallSafetyMargin,
    @JsonProperty(value = "finish_transition_buffer_calls") Integer finishTransitionBufferCalls,
    @JsonProperty(value = "diagnostics_enabled") Boolean diagnosticsEnabled,
    @JsonProperty(value = "diagnostic_candidate_limit") Integer diagnosticCandidateLimit,
    @JsonProperty(value = "hard_stagnation_enabled") Boolean hardStagnationEnabled,
    @JsonProperty(value = "max_normal_attempts_per_signature") Integer maxNormalAttemptsPerSignature,
    @JsonProperty(value = "max_repair_attempts_per_signature") Integer maxRepairAttemptsPerSignature,
    @JsonProperty(value = "global_no_progress_rounds_before_meta_pivot") Integer globalNoProgressRoundsBeforeMetaPivot,
    @JsonProperty(value = "global_no_progress_rounds_before_stop") Integer globalNoProgressRoundsBeforeStop,
    @JsonProperty(value = "proof_debt_reduction_weight") Double proofDebtReductionWeight,
    @JsonProperty(value = "verified_fact_gain_weight") Double verifiedFactGainWeight,
    @JsonProperty(value = "shared_bottleneck_weight") Double sharedBottleneckWeight,
    @JsonProperty(value = "message_utility_weight") Double messageUtilityWeight,
    @JsonProperty(value = "route_redundancy_penalty") Double routeRedundancyPenalty,
    @JsonProperty(value = "contradiction_priority_weight") Double contradictionPriorityWeight,
    @JsonProperty(value = "counterexample_priority_weight") Double counterexamplePriorityWeight,
    @JsonProperty(value = "bridge_priority_weight") Double bridgePriorityWeight,
    @JsonProperty(value = "inspiration_novelty_weight") Double inspirationNoveltyWeight,
    @JsonProperty(value = "representation_switch_weight") Double representationSwitchWeight,
    @JsonProperty(value = "analogy_relevance_weight") Double analogyRelevanceWeight,
    @JsonProperty(value = "construction_relevance_weight") Double constructionRelevanceWeight,
    @JsonProperty(value = "surprise_exploration_weight") Double surpriseExplorationWeight,
    @JsonProperty(value = "meta_replan_weight") Double metaReplanWeight,
    @JsonProperty(value = "core_proof_debt_reduction_weight") Double coreProofDebtReductionWeight,
    @JsonProperty(value = "core_verified_bridge_gain_weight") Double coreVerifiedBridgeGainWeight,
    @JsonProperty(value = "auxiliary_fact_gain_weight") Double auxiliaryFactGainWeight,
    @JsonProperty(value = "goal_alignment_weight") Double goalAlignmentWeight,
    @JsonProperty(value = "common_mode_risk_penalty") Double commonModeRiskPenalty,
    @JsonProperty(value = "obligation_base_weight") Double obligationBaseWeight,
    @JsonProperty(value = "obligation_main_goal_weight") Double obligationMainGoalWeight,
    @JsonProperty(value = "obligation_centrality_weight") Double obligationCentralityWeight,
    @JsonProperty(value = "obligation_dependency_weight") Double obligationDependencyWeight,
    @JsonProperty(value = "obligation_shared_route_weight") Double obligationSharedRouteWeight,
    @JsonProperty(value = "obligation_failure_weight") Double obligationFailureWeight,
    @JsonProperty(value = "obligation_conflict_weight") Double obligationConflictWeight
) implements ConfigModel {

  @JsonCreator
  public SchedulerConfig(Integer maxActionsPerRound, Integer widenPathsPerAction, Boolean forceWidenWhenAllFailed, Integer maxExecutionRepairsPerPath, Integer maxPlanRepairsPerPath, Integer maxUnknownFailureRepairsPerPath, Boolean allowStrategyFailureRepair, Integer failedPathCooldownRounds, Double meaningfulProgressThreshold, Double unverifiedProgressDiscount, Double uncertainProgressDiscount, Double failedProgressDiscount, Double structuralFailureProgressCap, Double executionFailureProgressCap, Double planFailureProgressCap, Double strategyFailureProgressCap, Double structuralFailurePenalty, Double executionFailurePenalty, Double planFailurePenalty, Double strategyFailurePenalty, Double repeatedFailurePenalty, Integer reserveRevisionCycles, Boolean includePostActionVerificationInCost, Boolean includeMetaReviewInCost, Integer verificationCallSafetyMargin, Integer finishTransitionBufferCalls, Boolean diagnosticsEnabled, Integer diagnosticCandidateLimit, Boolean hardStagnationEnabled, Integer maxNormalAttemptsPerSignature, Integer maxRepairAttemptsPerSignature, Integer globalNoProgressRoundsBeforeMetaPivot, Integer globalNoProgressRoundsBeforeStop, Double proofDebtReductionWeight, Double verifiedFactGainWeight, Double sharedBottleneckWeight, Double messageUtilityWeight, Double routeRedundancyPenalty, Double contradictionPriorityWeight, Double counterexamplePriorityWeight, Double bridgePriorityWeight, Double inspirationNoveltyWeight, Double representationSwitchWeight, Double analogyRelevanceWeight, Double constructionRelevanceWeight, Double surpriseExplorationWeight, Double metaReplanWeight, Double coreProofDebtReductionWeight, Double coreVerifiedBridgeGainWeight, Double auxiliaryFactGainWeight, Double goalAlignmentWeight, Double commonModeRiskPenalty, Double obligationBaseWeight, Double obligationMainGoalWeight, Double obligationCentralityWeight, Double obligationDependencyWeight, Double obligationSharedRouteWeight, Double obligationFailureWeight, Double obligationConflictWeight) {
    if (maxActionsPerRound == null) {
      maxActionsPerRound = 2;
    }
    ConfigValidation.minimum("max_actions_per_round", maxActionsPerRound, 1);
    ConfigValidation.maximum("max_actions_per_round", maxActionsPerRound, 16);
    if (widenPathsPerAction == null) {
      widenPathsPerAction = 2;
    }
    ConfigValidation.minimum("widen_paths_per_action", widenPathsPerAction, 1);
    ConfigValidation.maximum("widen_paths_per_action", widenPathsPerAction, 32);
    if (forceWidenWhenAllFailed == null) {
      forceWidenWhenAllFailed = true;
    }
    if (maxExecutionRepairsPerPath == null) {
      maxExecutionRepairsPerPath = 1;
    }
    ConfigValidation.minimum("max_execution_repairs_per_path", maxExecutionRepairsPerPath, 0);
    ConfigValidation.maximum("max_execution_repairs_per_path", maxExecutionRepairsPerPath, 32);
    if (maxPlanRepairsPerPath == null) {
      maxPlanRepairsPerPath = 1;
    }
    ConfigValidation.minimum("max_plan_repairs_per_path", maxPlanRepairsPerPath, 0);
    ConfigValidation.maximum("max_plan_repairs_per_path", maxPlanRepairsPerPath, 32);
    if (maxUnknownFailureRepairsPerPath == null) {
      maxUnknownFailureRepairsPerPath = 1;
    }
    ConfigValidation.minimum("max_unknown_failure_repairs_per_path", maxUnknownFailureRepairsPerPath, 0);
    ConfigValidation.maximum("max_unknown_failure_repairs_per_path", maxUnknownFailureRepairsPerPath, 32);
    if (allowStrategyFailureRepair == null) {
      allowStrategyFailureRepair = false;
    }
    if (failedPathCooldownRounds == null) {
      failedPathCooldownRounds = 1;
    }
    ConfigValidation.minimum("failed_path_cooldown_rounds", failedPathCooldownRounds, 0);
    ConfigValidation.maximum("failed_path_cooldown_rounds", failedPathCooldownRounds, 32);
    if (meaningfulProgressThreshold == null) {
      meaningfulProgressThreshold = 0.04d;
    }
    ConfigValidation.minimum("meaningful_progress_threshold", meaningfulProgressThreshold, 0.0d);
    ConfigValidation.maximum("meaningful_progress_threshold", meaningfulProgressThreshold, 1.0d);
    if (unverifiedProgressDiscount == null) {
      unverifiedProgressDiscount = 0.55d;
    }
    ConfigValidation.minimum("unverified_progress_discount", unverifiedProgressDiscount, 0.0d);
    ConfigValidation.maximum("unverified_progress_discount", unverifiedProgressDiscount, 1.0d);
    if (uncertainProgressDiscount == null) {
      uncertainProgressDiscount = 0.4d;
    }
    ConfigValidation.minimum("uncertain_progress_discount", uncertainProgressDiscount, 0.0d);
    ConfigValidation.maximum("uncertain_progress_discount", uncertainProgressDiscount, 1.0d);
    if (failedProgressDiscount == null) {
      failedProgressDiscount = 0.1d;
    }
    ConfigValidation.minimum("failed_progress_discount", failedProgressDiscount, 0.0d);
    ConfigValidation.maximum("failed_progress_discount", failedProgressDiscount, 1.0d);
    if (structuralFailureProgressCap == null) {
      structuralFailureProgressCap = 0.1d;
    }
    ConfigValidation.minimum("structural_failure_progress_cap", structuralFailureProgressCap, 0.0d);
    ConfigValidation.maximum("structural_failure_progress_cap", structuralFailureProgressCap, 1.0d);
    if (executionFailureProgressCap == null) {
      executionFailureProgressCap = 0.45d;
    }
    ConfigValidation.minimum("execution_failure_progress_cap", executionFailureProgressCap, 0.0d);
    ConfigValidation.maximum("execution_failure_progress_cap", executionFailureProgressCap, 1.0d);
    if (planFailureProgressCap == null) {
      planFailureProgressCap = 0.18d;
    }
    ConfigValidation.minimum("plan_failure_progress_cap", planFailureProgressCap, 0.0d);
    ConfigValidation.maximum("plan_failure_progress_cap", planFailureProgressCap, 1.0d);
    if (strategyFailureProgressCap == null) {
      strategyFailureProgressCap = 0.05d;
    }
    ConfigValidation.minimum("strategy_failure_progress_cap", strategyFailureProgressCap, 0.0d);
    ConfigValidation.maximum("strategy_failure_progress_cap", strategyFailureProgressCap, 1.0d);
    if (structuralFailurePenalty == null) {
      structuralFailurePenalty = 0.3d;
    }
    ConfigValidation.minimum("structural_failure_penalty", structuralFailurePenalty, 0.0d);
    ConfigValidation.maximum("structural_failure_penalty", structuralFailurePenalty, 2.0d);
    if (executionFailurePenalty == null) {
      executionFailurePenalty = 0.12d;
    }
    ConfigValidation.minimum("execution_failure_penalty", executionFailurePenalty, 0.0d);
    ConfigValidation.maximum("execution_failure_penalty", executionFailurePenalty, 2.0d);
    if (planFailurePenalty == null) {
      planFailurePenalty = 0.3d;
    }
    ConfigValidation.minimum("plan_failure_penalty", planFailurePenalty, 0.0d);
    ConfigValidation.maximum("plan_failure_penalty", planFailurePenalty, 2.0d);
    if (strategyFailurePenalty == null) {
      strategyFailurePenalty = 0.5d;
    }
    ConfigValidation.minimum("strategy_failure_penalty", strategyFailurePenalty, 0.0d);
    ConfigValidation.maximum("strategy_failure_penalty", strategyFailurePenalty, 2.0d);
    if (repeatedFailurePenalty == null) {
      repeatedFailurePenalty = 0.15d;
    }
    ConfigValidation.minimum("repeated_failure_penalty", repeatedFailurePenalty, 0.0d);
    ConfigValidation.maximum("repeated_failure_penalty", repeatedFailurePenalty, 2.0d);
    if (reserveRevisionCycles == null) {
      reserveRevisionCycles = 1;
    }
    ConfigValidation.minimum("reserve_revision_cycles", reserveRevisionCycles, 0);
    ConfigValidation.maximum("reserve_revision_cycles", reserveRevisionCycles, 32);
    if (includePostActionVerificationInCost == null) {
      includePostActionVerificationInCost = true;
    }
    if (includeMetaReviewInCost == null) {
      includeMetaReviewInCost = true;
    }
    if (verificationCallSafetyMargin == null) {
      verificationCallSafetyMargin = 0;
    }
    ConfigValidation.minimum("verification_call_safety_margin", verificationCallSafetyMargin, 0);
    ConfigValidation.maximum("verification_call_safety_margin", verificationCallSafetyMargin, 16);
    if (finishTransitionBufferCalls == null) {
      finishTransitionBufferCalls = 1;
    }
    ConfigValidation.minimum("finish_transition_buffer_calls", finishTransitionBufferCalls, 0);
    ConfigValidation.maximum("finish_transition_buffer_calls", finishTransitionBufferCalls, 64);
    if (diagnosticsEnabled == null) {
      diagnosticsEnabled = true;
    }
    if (diagnosticCandidateLimit == null) {
      diagnosticCandidateLimit = 12;
    }
    ConfigValidation.minimum("diagnostic_candidate_limit", diagnosticCandidateLimit, 1);
    ConfigValidation.maximum("diagnostic_candidate_limit", diagnosticCandidateLimit, 128);
    if (hardStagnationEnabled == null) {
      hardStagnationEnabled = true;
    }
    if (maxNormalAttemptsPerSignature == null) {
      maxNormalAttemptsPerSignature = 1;
    }
    ConfigValidation.minimum("max_normal_attempts_per_signature", maxNormalAttemptsPerSignature, 1);
    ConfigValidation.maximum("max_normal_attempts_per_signature", maxNormalAttemptsPerSignature, 4);
    if (maxRepairAttemptsPerSignature == null) {
      maxRepairAttemptsPerSignature = 1;
    }
    ConfigValidation.minimum("max_repair_attempts_per_signature", maxRepairAttemptsPerSignature, 0);
    ConfigValidation.maximum("max_repair_attempts_per_signature", maxRepairAttemptsPerSignature, 4);
    if (globalNoProgressRoundsBeforeMetaPivot == null) {
      globalNoProgressRoundsBeforeMetaPivot = 2;
    }
    ConfigValidation.minimum("global_no_progress_rounds_before_meta_pivot", globalNoProgressRoundsBeforeMetaPivot, 1);
    ConfigValidation.maximum("global_no_progress_rounds_before_meta_pivot", globalNoProgressRoundsBeforeMetaPivot, 32);
    if (globalNoProgressRoundsBeforeStop == null) {
      globalNoProgressRoundsBeforeStop = 3;
    }
    ConfigValidation.minimum("global_no_progress_rounds_before_stop", globalNoProgressRoundsBeforeStop, 2);
    ConfigValidation.maximum("global_no_progress_rounds_before_stop", globalNoProgressRoundsBeforeStop, 64);
    if (proofDebtReductionWeight == null) {
      proofDebtReductionWeight = 0.22d;
    }
    ConfigValidation.minimum("proof_debt_reduction_weight", proofDebtReductionWeight, 0.0d);
    ConfigValidation.maximum("proof_debt_reduction_weight", proofDebtReductionWeight, 4.0d);
    if (verifiedFactGainWeight == null) {
      verifiedFactGainWeight = 0.24d;
    }
    ConfigValidation.minimum("verified_fact_gain_weight", verifiedFactGainWeight, 0.0d);
    ConfigValidation.maximum("verified_fact_gain_weight", verifiedFactGainWeight, 4.0d);
    if (sharedBottleneckWeight == null) {
      sharedBottleneckWeight = 0.18d;
    }
    ConfigValidation.minimum("shared_bottleneck_weight", sharedBottleneckWeight, 0.0d);
    ConfigValidation.maximum("shared_bottleneck_weight", sharedBottleneckWeight, 4.0d);
    if (messageUtilityWeight == null) {
      messageUtilityWeight = 0.1d;
    }
    ConfigValidation.minimum("message_utility_weight", messageUtilityWeight, 0.0d);
    ConfigValidation.maximum("message_utility_weight", messageUtilityWeight, 4.0d);
    if (routeRedundancyPenalty == null) {
      routeRedundancyPenalty = 0.24d;
    }
    ConfigValidation.minimum("route_redundancy_penalty", routeRedundancyPenalty, 0.0d);
    ConfigValidation.maximum("route_redundancy_penalty", routeRedundancyPenalty, 4.0d);
    if (contradictionPriorityWeight == null) {
      contradictionPriorityWeight = 0.36d;
    }
    ConfigValidation.minimum("contradiction_priority_weight", contradictionPriorityWeight, 0.0d);
    ConfigValidation.maximum("contradiction_priority_weight", contradictionPriorityWeight, 4.0d);
    if (counterexamplePriorityWeight == null) {
      counterexamplePriorityWeight = 0.42d;
    }
    ConfigValidation.minimum("counterexample_priority_weight", counterexamplePriorityWeight, 0.0d);
    ConfigValidation.maximum("counterexample_priority_weight", counterexamplePriorityWeight, 4.0d);
    if (bridgePriorityWeight == null) {
      bridgePriorityWeight = 0.28d;
    }
    ConfigValidation.minimum("bridge_priority_weight", bridgePriorityWeight, 0.0d);
    ConfigValidation.maximum("bridge_priority_weight", bridgePriorityWeight, 4.0d);
    if (inspirationNoveltyWeight == null) {
      inspirationNoveltyWeight = 0.2d;
    }
    ConfigValidation.minimum("inspiration_novelty_weight", inspirationNoveltyWeight, 0.0d);
    ConfigValidation.maximum("inspiration_novelty_weight", inspirationNoveltyWeight, 4.0d);
    if (representationSwitchWeight == null) {
      representationSwitchWeight = 0.16d;
    }
    ConfigValidation.minimum("representation_switch_weight", representationSwitchWeight, 0.0d);
    ConfigValidation.maximum("representation_switch_weight", representationSwitchWeight, 4.0d);
    if (analogyRelevanceWeight == null) {
      analogyRelevanceWeight = 0.12d;
    }
    ConfigValidation.minimum("analogy_relevance_weight", analogyRelevanceWeight, 0.0d);
    ConfigValidation.maximum("analogy_relevance_weight", analogyRelevanceWeight, 4.0d);
    if (constructionRelevanceWeight == null) {
      constructionRelevanceWeight = 0.14d;
    }
    ConfigValidation.minimum("construction_relevance_weight", constructionRelevanceWeight, 0.0d);
    ConfigValidation.maximum("construction_relevance_weight", constructionRelevanceWeight, 4.0d);
    if (surpriseExplorationWeight == null) {
      surpriseExplorationWeight = 0.1d;
    }
    ConfigValidation.minimum("surprise_exploration_weight", surpriseExplorationWeight, 0.0d);
    ConfigValidation.maximum("surprise_exploration_weight", surpriseExplorationWeight, 4.0d);
    if (metaReplanWeight == null) {
      metaReplanWeight = 0.15d;
    }
    ConfigValidation.minimum("meta_replan_weight", metaReplanWeight, 0.0d);
    ConfigValidation.maximum("meta_replan_weight", metaReplanWeight, 4.0d);
    if (coreProofDebtReductionWeight == null) {
      coreProofDebtReductionWeight = 0.32d;
    }
    ConfigValidation.minimum("core_proof_debt_reduction_weight", coreProofDebtReductionWeight, 0.0d);
    ConfigValidation.maximum("core_proof_debt_reduction_weight", coreProofDebtReductionWeight, 4.0d);
    if (coreVerifiedBridgeGainWeight == null) {
      coreVerifiedBridgeGainWeight = 0.25d;
    }
    ConfigValidation.minimum("core_verified_bridge_gain_weight", coreVerifiedBridgeGainWeight, 0.0d);
    ConfigValidation.maximum("core_verified_bridge_gain_weight", coreVerifiedBridgeGainWeight, 4.0d);
    if (auxiliaryFactGainWeight == null) {
      auxiliaryFactGainWeight = 0.08d;
    }
    ConfigValidation.minimum("auxiliary_fact_gain_weight", auxiliaryFactGainWeight, 0.0d);
    ConfigValidation.maximum("auxiliary_fact_gain_weight", auxiliaryFactGainWeight, 4.0d);
    if (goalAlignmentWeight == null) {
      goalAlignmentWeight = 0.18d;
    }
    ConfigValidation.minimum("goal_alignment_weight", goalAlignmentWeight, 0.0d);
    ConfigValidation.maximum("goal_alignment_weight", goalAlignmentWeight, 4.0d);
    if (commonModeRiskPenalty == null) {
      commonModeRiskPenalty = 0.3d;
    }
    ConfigValidation.minimum("common_mode_risk_penalty", commonModeRiskPenalty, 0.0d);
    ConfigValidation.maximum("common_mode_risk_penalty", commonModeRiskPenalty, 4.0d);
    if (obligationBaseWeight == null) {
      obligationBaseWeight = 1.0d;
    }
    ConfigValidation.minimum("obligation_base_weight", obligationBaseWeight, 0.0d);
    ConfigValidation.maximum("obligation_base_weight", obligationBaseWeight, 100.0d);
    if (obligationMainGoalWeight == null) {
      obligationMainGoalWeight = 2.0d;
    }
    ConfigValidation.minimum("obligation_main_goal_weight", obligationMainGoalWeight, 0.0d);
    ConfigValidation.maximum("obligation_main_goal_weight", obligationMainGoalWeight, 100.0d);
    if (obligationCentralityWeight == null) {
      obligationCentralityWeight = 1.0d;
    }
    ConfigValidation.minimum("obligation_centrality_weight", obligationCentralityWeight, 0.0d);
    ConfigValidation.maximum("obligation_centrality_weight", obligationCentralityWeight, 100.0d);
    if (obligationDependencyWeight == null) {
      obligationDependencyWeight = 0.25d;
    }
    ConfigValidation.minimum("obligation_dependency_weight", obligationDependencyWeight, 0.0d);
    ConfigValidation.maximum("obligation_dependency_weight", obligationDependencyWeight, 100.0d);
    if (obligationSharedRouteWeight == null) {
      obligationSharedRouteWeight = 0.4d;
    }
    ConfigValidation.minimum("obligation_shared_route_weight", obligationSharedRouteWeight, 0.0d);
    ConfigValidation.maximum("obligation_shared_route_weight", obligationSharedRouteWeight, 100.0d);
    if (obligationFailureWeight == null) {
      obligationFailureWeight = 0.3d;
    }
    ConfigValidation.minimum("obligation_failure_weight", obligationFailureWeight, 0.0d);
    ConfigValidation.maximum("obligation_failure_weight", obligationFailureWeight, 100.0d);
    if (obligationConflictWeight == null) {
      obligationConflictWeight = 0.75d;
    }
    ConfigValidation.minimum("obligation_conflict_weight", obligationConflictWeight, 0.0d);
    ConfigValidation.maximum("obligation_conflict_weight", obligationConflictWeight, 100.0d);
    this.maxActionsPerRound = maxActionsPerRound;
    this.widenPathsPerAction = widenPathsPerAction;
    this.forceWidenWhenAllFailed = forceWidenWhenAllFailed;
    this.maxExecutionRepairsPerPath = maxExecutionRepairsPerPath;
    this.maxPlanRepairsPerPath = maxPlanRepairsPerPath;
    this.maxUnknownFailureRepairsPerPath = maxUnknownFailureRepairsPerPath;
    this.allowStrategyFailureRepair = allowStrategyFailureRepair;
    this.failedPathCooldownRounds = failedPathCooldownRounds;
    this.meaningfulProgressThreshold = meaningfulProgressThreshold;
    this.unverifiedProgressDiscount = unverifiedProgressDiscount;
    this.uncertainProgressDiscount = uncertainProgressDiscount;
    this.failedProgressDiscount = failedProgressDiscount;
    this.structuralFailureProgressCap = structuralFailureProgressCap;
    this.executionFailureProgressCap = executionFailureProgressCap;
    this.planFailureProgressCap = planFailureProgressCap;
    this.strategyFailureProgressCap = strategyFailureProgressCap;
    this.structuralFailurePenalty = structuralFailurePenalty;
    this.executionFailurePenalty = executionFailurePenalty;
    this.planFailurePenalty = planFailurePenalty;
    this.strategyFailurePenalty = strategyFailurePenalty;
    this.repeatedFailurePenalty = repeatedFailurePenalty;
    this.reserveRevisionCycles = reserveRevisionCycles;
    this.includePostActionVerificationInCost = includePostActionVerificationInCost;
    this.includeMetaReviewInCost = includeMetaReviewInCost;
    this.verificationCallSafetyMargin = verificationCallSafetyMargin;
    this.finishTransitionBufferCalls = finishTransitionBufferCalls;
    this.diagnosticsEnabled = diagnosticsEnabled;
    this.diagnosticCandidateLimit = diagnosticCandidateLimit;
    this.hardStagnationEnabled = hardStagnationEnabled;
    this.maxNormalAttemptsPerSignature = maxNormalAttemptsPerSignature;
    this.maxRepairAttemptsPerSignature = maxRepairAttemptsPerSignature;
    this.globalNoProgressRoundsBeforeMetaPivot = globalNoProgressRoundsBeforeMetaPivot;
    this.globalNoProgressRoundsBeforeStop = globalNoProgressRoundsBeforeStop;
    this.proofDebtReductionWeight = proofDebtReductionWeight;
    this.verifiedFactGainWeight = verifiedFactGainWeight;
    this.sharedBottleneckWeight = sharedBottleneckWeight;
    this.messageUtilityWeight = messageUtilityWeight;
    this.routeRedundancyPenalty = routeRedundancyPenalty;
    this.contradictionPriorityWeight = contradictionPriorityWeight;
    this.counterexamplePriorityWeight = counterexamplePriorityWeight;
    this.bridgePriorityWeight = bridgePriorityWeight;
    this.inspirationNoveltyWeight = inspirationNoveltyWeight;
    this.representationSwitchWeight = representationSwitchWeight;
    this.analogyRelevanceWeight = analogyRelevanceWeight;
    this.constructionRelevanceWeight = constructionRelevanceWeight;
    this.surpriseExplorationWeight = surpriseExplorationWeight;
    this.metaReplanWeight = metaReplanWeight;
    this.coreProofDebtReductionWeight = coreProofDebtReductionWeight;
    this.coreVerifiedBridgeGainWeight = coreVerifiedBridgeGainWeight;
    this.auxiliaryFactGainWeight = auxiliaryFactGainWeight;
    this.goalAlignmentWeight = goalAlignmentWeight;
    this.commonModeRiskPenalty = commonModeRiskPenalty;
    this.obligationBaseWeight = obligationBaseWeight;
    this.obligationMainGoalWeight = obligationMainGoalWeight;
    this.obligationCentralityWeight = obligationCentralityWeight;
    this.obligationDependencyWeight = obligationDependencyWeight;
    this.obligationSharedRouteWeight = obligationSharedRouteWeight;
    this.obligationFailureWeight = obligationFailureWeight;
    this.obligationConflictWeight = obligationConflictWeight;
    ConfigInvariants.validate(this);
  }

  public static SchedulerConfig defaults() {
    return new SchedulerConfig(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }
}
