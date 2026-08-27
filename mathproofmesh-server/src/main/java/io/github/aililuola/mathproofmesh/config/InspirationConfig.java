package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record InspirationConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "mode") String mode,
    @JsonProperty(value = "stagnation_rounds") Integer stagnationRounds,
    @JsonProperty(value = "minimum_verified_gain") Integer minimumVerifiedGain,
    @JsonProperty(value = "proof_debt_min_reduction") Double proofDebtMinReduction,
    @JsonProperty(value = "repeated_error_threshold") Integer repeatedErrorThreshold,
    @JsonProperty(value = "route_redundancy_trigger") Double routeRedundancyTrigger,
    @JsonProperty(value = "route_budget_share_trigger") Double routeBudgetShareTrigger,
    @JsonProperty(value = "all_routes_failed_trigger") Boolean allRoutesFailedTrigger,
    @JsonProperty(value = "shared_bottleneck_trigger") Boolean sharedBottleneckTrigger,
    @JsonProperty(value = "final_repair_failure_trigger") Boolean finalRepairFailureTrigger,
    @JsonProperty(value = "representation_switchboard") Boolean representationSwitchboard,
    @JsonProperty(value = "analogy_agent") Boolean analogyAgent,
    @JsonProperty(value = "auxiliary_construction_inventor") Boolean auxiliaryConstructionInventor,
    @JsonProperty(value = "invariant_hypothesis_agent") Boolean invariantHypothesisAgent,
    @JsonProperty(value = "reverse_goal_analysis") Boolean reverseGoalAnalysis,
    @JsonProperty(value = "bridge_lemma_generator") Boolean bridgeLemmaGenerator,
    @JsonProperty(value = "persistent_meta_strategist") Boolean persistentMetaStrategist,
    @JsonProperty(value = "surprise_exploration") Boolean surpriseExploration,
    @JsonProperty(value = "domain_operator_plugins_enabled") Boolean domainOperatorPluginsEnabled,
    @JsonProperty(value = "bidirectional_frontier_enabled") Boolean bidirectionalFrontierEnabled,
    @JsonProperty(value = "inspiration_composer_enabled") Boolean inspirationComposerEnabled,
    @JsonProperty(value = "controlled_surprise_mutation_enabled") Boolean controlledSurpriseMutationEnabled,
    @JsonProperty(value = "surprise_budget_fraction") Double surpriseBudgetFraction,
    @JsonProperty(value = "surprise_budget_min_calls") Integer surpriseBudgetMinCalls,
    @JsonProperty(value = "surprise_budget_max_calls") Integer surpriseBudgetMaxCalls,
    @JsonProperty(value = "max_inspiration_tasks_per_round") Integer maxInspirationTasksPerRound,
    @JsonProperty(value = "max_proposals_per_task") Integer maxProposalsPerTask,
    @JsonProperty(value = "active_proposals_per_task") Integer activeProposalsPerTask,
    @JsonProperty(value = "proposer_generalist_roles") List<String> proposerGeneralistRoles,
    @JsonProperty(value = "max_single_agent_proposals_per_task") Integer maxSingleAgentProposalsPerTask,
    @JsonProperty(value = "max_reviewed_proposals_per_task") Integer maxReviewedProposalsPerTask,
    @JsonProperty(value = "max_materialized_proposals_per_trigger") Integer maxMaterializedProposalsPerTrigger,
    @JsonProperty(value = "cold_context_proposals_per_task") Integer coldContextProposalsPerTask,
    @JsonProperty(value = "warm_context_max_facts") Integer warmContextMaxFacts,
    @JsonProperty(value = "warm_context_max_negatives") Integer warmContextMaxNegatives,
    @JsonProperty(value = "inspiration_context_max_chars") Integer inspirationContextMaxChars,
    @JsonProperty(value = "max_new_routes_per_trigger") Integer maxNewRoutesPerTrigger,
    @JsonProperty(value = "protect_finalization_reserve") Boolean protectFinalizationReserve,
    @JsonProperty(value = "max_consecutive_surprise_rejections") Integer maxConsecutiveSurpriseRejections,
    @JsonProperty(value = "surprise_cooldown_rounds") Integer surpriseCooldownRounds,
    @JsonProperty(value = "domain_operator_max_prompt_items") Integer domainOperatorMaxPromptItems,
    @JsonProperty(value = "frontier_max_forward_claims") Integer frontierMaxForwardClaims,
    @JsonProperty(value = "frontier_max_backward_claims") Integer frontierMaxBackwardClaims,
    @JsonProperty(value = "frontier_max_bridge_candidates") Integer frontierMaxBridgeCandidates,
    @JsonProperty(value = "frontier_min_candidate_overlap") Double frontierMinCandidateOverlap,
    @JsonProperty(value = "composer_max_candidates_per_round") Integer composerMaxCandidatesPerRound,
    @JsonProperty(value = "composer_max_sources") Integer composerMaxSources,
    @JsonProperty(value = "composer_max_combined_cost") Integer composerMaxCombinedCost,
    @JsonProperty(value = "composer_require_quick_falsification") Boolean composerRequireQuickFalsification,
    @JsonProperty(value = "novelty_threshold") Double noveltyThreshold,
    @JsonProperty(value = "mechanism_duplicate_threshold") Double mechanismDuplicateThreshold,
    @JsonProperty(value = "require_inspiration_referee") Boolean requireInspirationReferee,
    @JsonProperty(value = "proposals_enter_fact_memory") Boolean proposalsEnterFactMemory,
    @JsonProperty(value = "novelty_representation_weight") Double noveltyRepresentationWeight,
    @JsonProperty(value = "novelty_mechanism_weight") Double noveltyMechanismWeight,
    @JsonProperty(value = "novelty_object_weight") Double noveltyObjectWeight,
    @JsonProperty(value = "novelty_transformation_weight") Double noveltyTransformationWeight,
    @JsonProperty(value = "novelty_principle_weight") Double noveltyPrincipleWeight,
    @JsonProperty(value = "novelty_obligation_weight") Double noveltyObligationWeight,
    @JsonProperty(value = "analogy_library_enabled") Boolean analogyLibraryEnabled,
    @JsonProperty(value = "analogy_library_path") String analogyLibraryPath,
    @JsonProperty(value = "analogy_top_k") Integer analogyTopK,
    @JsonProperty(value = "allow_external_retrieval") Boolean allowExternalRetrieval,
    @JsonProperty(value = "meta_directives_enabled") Boolean metaDirectivesEnabled,
    @JsonProperty(value = "meta_directive_expiry_rounds") Integer metaDirectiveExpiryRounds,
    @JsonProperty(value = "meta_directive_cooldown_rounds") Integer metaDirectiveCooldownRounds,
    @JsonProperty(value = "meta_directive_max_estimated_calls") Integer metaDirectiveMaxEstimatedCalls,
    @JsonProperty(value = "meta_allow_route_abandon") Boolean metaAllowRouteAbandon,
    @JsonProperty(value = "adaptive_mechanism_selection") Boolean adaptiveMechanismSelection,
    @JsonProperty(value = "adaptive_min_observations") Integer adaptiveMinObservations,
    @JsonProperty(value = "adaptive_min_exploration_rate") Double adaptiveMinExplorationRate,
    @JsonProperty(value = "adaptive_ucb_weight") Double adaptiveUcbWeight,
    @JsonProperty(value = "adaptive_reward_fact_weight") Double adaptiveRewardFactWeight,
    @JsonProperty(value = "adaptive_reward_debt_weight") Double adaptiveRewardDebtWeight,
    @JsonProperty(value = "adaptive_reward_obligation_weight") Double adaptiveRewardObligationWeight,
    @JsonProperty(value = "adaptive_reward_final_citation_weight") Double adaptiveRewardFinalCitationWeight,
    @JsonProperty(value = "adaptive_reward_call_cost") Double adaptiveRewardCallCost,
    @JsonProperty(value = "adaptive_reward_token_cost_per_100k") Double adaptiveRewardTokenCostPer100k,
    @JsonProperty(value = "adaptive_reward_refutation_cost") Double adaptiveRewardRefutationCost,
    @JsonProperty(value = "experience_distillation_enabled") Boolean experienceDistillationEnabled,
    @JsonProperty(value = "negative_analogy_library_enabled") Boolean negativeAnalogyLibraryEnabled,
    @JsonProperty(value = "max_distilled_experiences") Integer maxDistilledExperiences,
    @JsonProperty(value = "max_negative_analogy_records") Integer maxNegativeAnalogyRecords,
    @JsonProperty(value = "cross_run_learning_enabled") Boolean crossRunLearningEnabled,
    @JsonProperty(value = "cross_run_learning_path") String crossRunLearningPath,
    @JsonProperty(value = "cross_run_require_final_citation") Boolean crossRunRequireFinalCitation,
    @JsonProperty(value = "cross_run_max_experiences") Integer crossRunMaxExperiences,
    @JsonProperty(value = "cross_run_max_negative_analogies") Integer crossRunMaxNegativeAnalogies,
    @JsonProperty(value = "cross_run_max_outcomes") Integer crossRunMaxOutcomes
) implements ConfigModel {

  @JsonCreator
  public InspirationConfig(Boolean enabled, String mode, Integer stagnationRounds, Integer minimumVerifiedGain, Double proofDebtMinReduction, Integer repeatedErrorThreshold, Double routeRedundancyTrigger, Double routeBudgetShareTrigger, Boolean allRoutesFailedTrigger, Boolean sharedBottleneckTrigger, Boolean finalRepairFailureTrigger, Boolean representationSwitchboard, Boolean analogyAgent, Boolean auxiliaryConstructionInventor, Boolean invariantHypothesisAgent, Boolean reverseGoalAnalysis, Boolean bridgeLemmaGenerator, Boolean persistentMetaStrategist, Boolean surpriseExploration, Boolean domainOperatorPluginsEnabled, Boolean bidirectionalFrontierEnabled, Boolean inspirationComposerEnabled, Boolean controlledSurpriseMutationEnabled, Double surpriseBudgetFraction, Integer surpriseBudgetMinCalls, Integer surpriseBudgetMaxCalls, Integer maxInspirationTasksPerRound, Integer maxProposalsPerTask, Integer activeProposalsPerTask, List<String> proposerGeneralistRoles, Integer maxSingleAgentProposalsPerTask, Integer maxReviewedProposalsPerTask, Integer maxMaterializedProposalsPerTrigger, Integer coldContextProposalsPerTask, Integer warmContextMaxFacts, Integer warmContextMaxNegatives, Integer inspirationContextMaxChars, Integer maxNewRoutesPerTrigger, Boolean protectFinalizationReserve, Integer maxConsecutiveSurpriseRejections, Integer surpriseCooldownRounds, Integer domainOperatorMaxPromptItems, Integer frontierMaxForwardClaims, Integer frontierMaxBackwardClaims, Integer frontierMaxBridgeCandidates, Double frontierMinCandidateOverlap, Integer composerMaxCandidatesPerRound, Integer composerMaxSources, Integer composerMaxCombinedCost, Boolean composerRequireQuickFalsification, Double noveltyThreshold, Double mechanismDuplicateThreshold, Boolean requireInspirationReferee, Boolean proposalsEnterFactMemory, Double noveltyRepresentationWeight, Double noveltyMechanismWeight, Double noveltyObjectWeight, Double noveltyTransformationWeight, Double noveltyPrincipleWeight, Double noveltyObligationWeight, Boolean analogyLibraryEnabled, String analogyLibraryPath, Integer analogyTopK, Boolean allowExternalRetrieval, Boolean metaDirectivesEnabled, Integer metaDirectiveExpiryRounds, Integer metaDirectiveCooldownRounds, Integer metaDirectiveMaxEstimatedCalls, Boolean metaAllowRouteAbandon, Boolean adaptiveMechanismSelection, Integer adaptiveMinObservations, Double adaptiveMinExplorationRate, Double adaptiveUcbWeight, Double adaptiveRewardFactWeight, Double adaptiveRewardDebtWeight, Double adaptiveRewardObligationWeight, Double adaptiveRewardFinalCitationWeight, Double adaptiveRewardCallCost, Double adaptiveRewardTokenCostPer100k, Double adaptiveRewardRefutationCost, Boolean experienceDistillationEnabled, Boolean negativeAnalogyLibraryEnabled, Integer maxDistilledExperiences, Integer maxNegativeAnalogyRecords, Boolean crossRunLearningEnabled, String crossRunLearningPath, Boolean crossRunRequireFinalCitation, Integer crossRunMaxExperiences, Integer crossRunMaxNegativeAnalogies, Integer crossRunMaxOutcomes) {
    if (enabled == null) {
      enabled = false;
    }
    if (mode == null) {
      mode = "shadow";
    }
    mode = ConfigValidation.trim(mode);
    ConfigValidation.oneOf("mode", mode, "off", "shadow", "active");
    if (stagnationRounds == null) {
      stagnationRounds = 2;
    }
    ConfigValidation.minimum("stagnation_rounds", stagnationRounds, 1);
    ConfigValidation.maximum("stagnation_rounds", stagnationRounds, 32);
    if (minimumVerifiedGain == null) {
      minimumVerifiedGain = 1;
    }
    ConfigValidation.minimum("minimum_verified_gain", minimumVerifiedGain, 0);
    ConfigValidation.maximum("minimum_verified_gain", minimumVerifiedGain, 128);
    if (proofDebtMinReduction == null) {
      proofDebtMinReduction = 0.03d;
    }
    ConfigValidation.minimum("proof_debt_min_reduction", proofDebtMinReduction, 0.0d);
    ConfigValidation.maximum("proof_debt_min_reduction", proofDebtMinReduction, 1.0d);
    if (repeatedErrorThreshold == null) {
      repeatedErrorThreshold = 2;
    }
    ConfigValidation.minimum("repeated_error_threshold", repeatedErrorThreshold, 1);
    ConfigValidation.maximum("repeated_error_threshold", repeatedErrorThreshold, 32);
    if (routeRedundancyTrigger == null) {
      routeRedundancyTrigger = 0.8d;
    }
    ConfigValidation.minimum("route_redundancy_trigger", routeRedundancyTrigger, 0.0d);
    ConfigValidation.maximum("route_redundancy_trigger", routeRedundancyTrigger, 1.0d);
    if (routeBudgetShareTrigger == null) {
      routeBudgetShareTrigger = 0.5d;
    }
    ConfigValidation.minimum("route_budget_share_trigger", routeBudgetShareTrigger, 0.0d);
    ConfigValidation.maximum("route_budget_share_trigger", routeBudgetShareTrigger, 1.0d);
    if (allRoutesFailedTrigger == null) {
      allRoutesFailedTrigger = true;
    }
    if (sharedBottleneckTrigger == null) {
      sharedBottleneckTrigger = true;
    }
    if (finalRepairFailureTrigger == null) {
      finalRepairFailureTrigger = true;
    }
    if (representationSwitchboard == null) {
      representationSwitchboard = true;
    }
    if (analogyAgent == null) {
      analogyAgent = true;
    }
    if (auxiliaryConstructionInventor == null) {
      auxiliaryConstructionInventor = true;
    }
    if (invariantHypothesisAgent == null) {
      invariantHypothesisAgent = true;
    }
    if (reverseGoalAnalysis == null) {
      reverseGoalAnalysis = true;
    }
    if (bridgeLemmaGenerator == null) {
      bridgeLemmaGenerator = true;
    }
    if (persistentMetaStrategist == null) {
      persistentMetaStrategist = true;
    }
    if (surpriseExploration == null) {
      surpriseExploration = true;
    }
    if (domainOperatorPluginsEnabled == null) {
      domainOperatorPluginsEnabled = true;
    }
    if (bidirectionalFrontierEnabled == null) {
      bidirectionalFrontierEnabled = true;
    }
    if (inspirationComposerEnabled == null) {
      inspirationComposerEnabled = true;
    }
    if (controlledSurpriseMutationEnabled == null) {
      controlledSurpriseMutationEnabled = true;
    }
    if (surpriseBudgetFraction == null) {
      surpriseBudgetFraction = 0.08d;
    }
    ConfigValidation.minimum("surprise_budget_fraction", surpriseBudgetFraction, 0.0d);
    ConfigValidation.maximum("surprise_budget_fraction", surpriseBudgetFraction, 0.5d);
    if (surpriseBudgetMinCalls == null) {
      surpriseBudgetMinCalls = 10;
    }
    ConfigValidation.minimum("surprise_budget_min_calls", surpriseBudgetMinCalls, 0);
    ConfigValidation.maximum("surprise_budget_min_calls", surpriseBudgetMinCalls, 64);
    if (surpriseBudgetMaxCalls == null) {
      surpriseBudgetMaxCalls = 32;
    }
    ConfigValidation.minimum("surprise_budget_max_calls", surpriseBudgetMaxCalls, 0);
    ConfigValidation.maximum("surprise_budget_max_calls", surpriseBudgetMaxCalls, 128);
    if (maxInspirationTasksPerRound == null) {
      maxInspirationTasksPerRound = 2;
    }
    ConfigValidation.minimum("max_inspiration_tasks_per_round", maxInspirationTasksPerRound, 0);
    ConfigValidation.maximum("max_inspiration_tasks_per_round", maxInspirationTasksPerRound, 32);
    if (maxProposalsPerTask == null) {
      maxProposalsPerTask = 3;
    }
    ConfigValidation.minimum("max_proposals_per_task", maxProposalsPerTask, 1);
    ConfigValidation.maximum("max_proposals_per_task", maxProposalsPerTask, 16);
    if (activeProposalsPerTask == null) {
      activeProposalsPerTask = 3;
    }
    ConfigValidation.minimum("active_proposals_per_task", activeProposalsPerTask, 1);
    ConfigValidation.maximum("active_proposals_per_task", activeProposalsPerTask, 16);
    if (proposerGeneralistRoles == null) {
      proposerGeneralistRoles = List.of("explorer", "route_prover");
    }
    proposerGeneralistRoles = ConfigValidation.trimStrings("proposer_generalist_roles", proposerGeneralistRoles);
    ConfigValidation.itemsOneOf("proposer_generalist_roles", proposerGeneralistRoles, "planner", "explorer", "summarizer", "structural_verifier", "detailed_verifier", "meta_reviewer", "synthesizer", "final_verifier", "experimenter", "route_prover", "route_skeptic", "tool_specialist", "route_referee", "bridge_prover", "conflict_resolver", "counterexample_hunter", "representation_switchboard", "analogy_agent", "construction_inventor", "invariant_hypothesis_agent", "reverse_goal_analyzer", "meta_strategist", "inspiration_referee", "general");
    if (maxSingleAgentProposalsPerTask == null) {
      maxSingleAgentProposalsPerTask = 2;
    }
    ConfigValidation.minimum("max_single_agent_proposals_per_task", maxSingleAgentProposalsPerTask, 1);
    ConfigValidation.maximum("max_single_agent_proposals_per_task", maxSingleAgentProposalsPerTask, 16);
    if (maxReviewedProposalsPerTask == null) {
      maxReviewedProposalsPerTask = 2;
    }
    ConfigValidation.minimum("max_reviewed_proposals_per_task", maxReviewedProposalsPerTask, 1);
    ConfigValidation.maximum("max_reviewed_proposals_per_task", maxReviewedProposalsPerTask, 16);
    if (maxMaterializedProposalsPerTrigger == null) {
      maxMaterializedProposalsPerTrigger = 1;
    }
    ConfigValidation.minimum("max_materialized_proposals_per_trigger", maxMaterializedProposalsPerTrigger, 0);
    ConfigValidation.maximum("max_materialized_proposals_per_trigger", maxMaterializedProposalsPerTrigger, 16);
    if (coldContextProposalsPerTask == null) {
      coldContextProposalsPerTask = 1;
    }
    ConfigValidation.minimum("cold_context_proposals_per_task", coldContextProposalsPerTask, 0);
    ConfigValidation.maximum("cold_context_proposals_per_task", coldContextProposalsPerTask, 16);
    if (warmContextMaxFacts == null) {
      warmContextMaxFacts = 5;
    }
    ConfigValidation.minimum("warm_context_max_facts", warmContextMaxFacts, 0);
    ConfigValidation.maximum("warm_context_max_facts", warmContextMaxFacts, 64);
    if (warmContextMaxNegatives == null) {
      warmContextMaxNegatives = 5;
    }
    ConfigValidation.minimum("warm_context_max_negatives", warmContextMaxNegatives, 0);
    ConfigValidation.maximum("warm_context_max_negatives", warmContextMaxNegatives, 64);
    if (inspirationContextMaxChars == null) {
      inspirationContextMaxChars = 24000;
    }
    ConfigValidation.minimum("inspiration_context_max_chars", inspirationContextMaxChars, 1000);
    ConfigValidation.maximum("inspiration_context_max_chars", inspirationContextMaxChars, 500000);
    if (maxNewRoutesPerTrigger == null) {
      maxNewRoutesPerTrigger = 2;
    }
    ConfigValidation.minimum("max_new_routes_per_trigger", maxNewRoutesPerTrigger, 0);
    ConfigValidation.maximum("max_new_routes_per_trigger", maxNewRoutesPerTrigger, 16);
    if (protectFinalizationReserve == null) {
      protectFinalizationReserve = true;
    }
    if (maxConsecutiveSurpriseRejections == null) {
      maxConsecutiveSurpriseRejections = 2;
    }
    ConfigValidation.minimum("max_consecutive_surprise_rejections", maxConsecutiveSurpriseRejections, 1);
    ConfigValidation.maximum("max_consecutive_surprise_rejections", maxConsecutiveSurpriseRejections, 32);
    if (surpriseCooldownRounds == null) {
      surpriseCooldownRounds = 2;
    }
    ConfigValidation.minimum("surprise_cooldown_rounds", surpriseCooldownRounds, 0);
    ConfigValidation.maximum("surprise_cooldown_rounds", surpriseCooldownRounds, 32);
    if (domainOperatorMaxPromptItems == null) {
      domainOperatorMaxPromptItems = 8;
    }
    ConfigValidation.minimum("domain_operator_max_prompt_items", domainOperatorMaxPromptItems, 1);
    ConfigValidation.maximum("domain_operator_max_prompt_items", domainOperatorMaxPromptItems, 64);
    if (frontierMaxForwardClaims == null) {
      frontierMaxForwardClaims = 8;
    }
    ConfigValidation.minimum("frontier_max_forward_claims", frontierMaxForwardClaims, 1);
    ConfigValidation.maximum("frontier_max_forward_claims", frontierMaxForwardClaims, 64);
    if (frontierMaxBackwardClaims == null) {
      frontierMaxBackwardClaims = 8;
    }
    ConfigValidation.minimum("frontier_max_backward_claims", frontierMaxBackwardClaims, 1);
    ConfigValidation.maximum("frontier_max_backward_claims", frontierMaxBackwardClaims, 64);
    if (frontierMaxBridgeCandidates == null) {
      frontierMaxBridgeCandidates = 3;
    }
    ConfigValidation.minimum("frontier_max_bridge_candidates", frontierMaxBridgeCandidates, 1);
    ConfigValidation.maximum("frontier_max_bridge_candidates", frontierMaxBridgeCandidates, 32);
    if (frontierMinCandidateOverlap == null) {
      frontierMinCandidateOverlap = 0.35d;
    }
    ConfigValidation.minimum("frontier_min_candidate_overlap", frontierMinCandidateOverlap, 0.0d);
    ConfigValidation.maximum("frontier_min_candidate_overlap", frontierMinCandidateOverlap, 1.0d);
    if (composerMaxCandidatesPerRound == null) {
      composerMaxCandidatesPerRound = 2;
    }
    ConfigValidation.minimum("composer_max_candidates_per_round", composerMaxCandidatesPerRound, 0);
    ConfigValidation.maximum("composer_max_candidates_per_round", composerMaxCandidatesPerRound, 16);
    if (composerMaxSources == null) {
      composerMaxSources = 2;
    }
    ConfigValidation.minimum("composer_max_sources", composerMaxSources, 2);
    ConfigValidation.maximum("composer_max_sources", composerMaxSources, 4);
    if (composerMaxCombinedCost == null) {
      composerMaxCombinedCost = 4;
    }
    ConfigValidation.minimum("composer_max_combined_cost", composerMaxCombinedCost, 1);
    ConfigValidation.maximum("composer_max_combined_cost", composerMaxCombinedCost, 32);
    if (composerRequireQuickFalsification == null) {
      composerRequireQuickFalsification = true;
    }
    if (noveltyThreshold == null) {
      noveltyThreshold = 0.65d;
    }
    ConfigValidation.minimum("novelty_threshold", noveltyThreshold, 0.0d);
    ConfigValidation.maximum("novelty_threshold", noveltyThreshold, 1.0d);
    if (mechanismDuplicateThreshold == null) {
      mechanismDuplicateThreshold = 0.86d;
    }
    ConfigValidation.minimum("mechanism_duplicate_threshold", mechanismDuplicateThreshold, 0.0d);
    ConfigValidation.maximum("mechanism_duplicate_threshold", mechanismDuplicateThreshold, 1.0d);
    if (requireInspirationReferee == null) {
      requireInspirationReferee = true;
    }
    if (proposalsEnterFactMemory == null) {
      proposalsEnterFactMemory = false;
    }
    if (noveltyRepresentationWeight == null) {
      noveltyRepresentationWeight = 0.24d;
    }
    ConfigValidation.minimum("novelty_representation_weight", noveltyRepresentationWeight, 0.0d);
    ConfigValidation.maximum("novelty_representation_weight", noveltyRepresentationWeight, 1.0d);
    if (noveltyMechanismWeight == null) {
      noveltyMechanismWeight = 0.24d;
    }
    ConfigValidation.minimum("novelty_mechanism_weight", noveltyMechanismWeight, 0.0d);
    ConfigValidation.maximum("novelty_mechanism_weight", noveltyMechanismWeight, 1.0d);
    if (noveltyObjectWeight == null) {
      noveltyObjectWeight = 0.16d;
    }
    ConfigValidation.minimum("novelty_object_weight", noveltyObjectWeight, 0.0d);
    ConfigValidation.maximum("novelty_object_weight", noveltyObjectWeight, 1.0d);
    if (noveltyTransformationWeight == null) {
      noveltyTransformationWeight = 0.16d;
    }
    ConfigValidation.minimum("novelty_transformation_weight", noveltyTransformationWeight, 0.0d);
    ConfigValidation.maximum("novelty_transformation_weight", noveltyTransformationWeight, 1.0d);
    if (noveltyPrincipleWeight == null) {
      noveltyPrincipleWeight = 0.1d;
    }
    ConfigValidation.minimum("novelty_principle_weight", noveltyPrincipleWeight, 0.0d);
    ConfigValidation.maximum("novelty_principle_weight", noveltyPrincipleWeight, 1.0d);
    if (noveltyObligationWeight == null) {
      noveltyObligationWeight = 0.1d;
    }
    ConfigValidation.minimum("novelty_obligation_weight", noveltyObligationWeight, 0.0d);
    ConfigValidation.maximum("novelty_obligation_weight", noveltyObligationWeight, 1.0d);
    if (analogyLibraryEnabled == null) {
      analogyLibraryEnabled = true;
    }
    if (analogyLibraryPath == null) {
      analogyLibraryPath = "benchmarks/analogy_library.jsonl";
    }
    analogyLibraryPath = ConfigValidation.trim(analogyLibraryPath);
    if (analogyTopK == null) {
      analogyTopK = 6;
    }
    ConfigValidation.minimum("analogy_top_k", analogyTopK, 1);
    ConfigValidation.maximum("analogy_top_k", analogyTopK, 64);
    if (allowExternalRetrieval == null) {
      allowExternalRetrieval = false;
    }
    if (metaDirectivesEnabled == null) {
      metaDirectivesEnabled = true;
    }
    if (metaDirectiveExpiryRounds == null) {
      metaDirectiveExpiryRounds = 2;
    }
    ConfigValidation.minimum("meta_directive_expiry_rounds", metaDirectiveExpiryRounds, 0);
    ConfigValidation.maximum("meta_directive_expiry_rounds", metaDirectiveExpiryRounds, 32);
    if (metaDirectiveCooldownRounds == null) {
      metaDirectiveCooldownRounds = 2;
    }
    ConfigValidation.minimum("meta_directive_cooldown_rounds", metaDirectiveCooldownRounds, 1);
    ConfigValidation.maximum("meta_directive_cooldown_rounds", metaDirectiveCooldownRounds, 32);
    if (metaDirectiveMaxEstimatedCalls == null) {
      metaDirectiveMaxEstimatedCalls = 8;
    }
    ConfigValidation.minimum("meta_directive_max_estimated_calls", metaDirectiveMaxEstimatedCalls, 0);
    ConfigValidation.maximum("meta_directive_max_estimated_calls", metaDirectiveMaxEstimatedCalls, 64);
    if (metaAllowRouteAbandon == null) {
      metaAllowRouteAbandon = true;
    }
    if (adaptiveMechanismSelection == null) {
      adaptiveMechanismSelection = true;
    }
    if (adaptiveMinObservations == null) {
      adaptiveMinObservations = 1;
    }
    ConfigValidation.minimum("adaptive_min_observations", adaptiveMinObservations, 0);
    ConfigValidation.maximum("adaptive_min_observations", adaptiveMinObservations, 64);
    if (adaptiveMinExplorationRate == null) {
      adaptiveMinExplorationRate = 0.15d;
    }
    ConfigValidation.minimum("adaptive_min_exploration_rate", adaptiveMinExplorationRate, 0.0d);
    ConfigValidation.maximum("adaptive_min_exploration_rate", adaptiveMinExplorationRate, 1.0d);
    if (adaptiveUcbWeight == null) {
      adaptiveUcbWeight = 0.75d;
    }
    ConfigValidation.minimum("adaptive_ucb_weight", adaptiveUcbWeight, 0.0d);
    ConfigValidation.maximum("adaptive_ucb_weight", adaptiveUcbWeight, 8.0d);
    if (adaptiveRewardFactWeight == null) {
      adaptiveRewardFactWeight = 4.0d;
    }
    ConfigValidation.minimum("adaptive_reward_fact_weight", adaptiveRewardFactWeight, 0.0d);
    ConfigValidation.maximum("adaptive_reward_fact_weight", adaptiveRewardFactWeight, 32.0d);
    if (adaptiveRewardDebtWeight == null) {
      adaptiveRewardDebtWeight = 1.0d;
    }
    ConfigValidation.minimum("adaptive_reward_debt_weight", adaptiveRewardDebtWeight, 0.0d);
    ConfigValidation.maximum("adaptive_reward_debt_weight", adaptiveRewardDebtWeight, 32.0d);
    if (adaptiveRewardObligationWeight == null) {
      adaptiveRewardObligationWeight = 1.0d;
    }
    ConfigValidation.minimum("adaptive_reward_obligation_weight", adaptiveRewardObligationWeight, 0.0d);
    ConfigValidation.maximum("adaptive_reward_obligation_weight", adaptiveRewardObligationWeight, 32.0d);
    if (adaptiveRewardFinalCitationWeight == null) {
      adaptiveRewardFinalCitationWeight = 3.0d;
    }
    ConfigValidation.minimum("adaptive_reward_final_citation_weight", adaptiveRewardFinalCitationWeight, 0.0d);
    ConfigValidation.maximum("adaptive_reward_final_citation_weight", adaptiveRewardFinalCitationWeight, 32.0d);
    if (adaptiveRewardCallCost == null) {
      adaptiveRewardCallCost = 0.1d;
    }
    ConfigValidation.minimum("adaptive_reward_call_cost", adaptiveRewardCallCost, 0.0d);
    ConfigValidation.maximum("adaptive_reward_call_cost", adaptiveRewardCallCost, 8.0d);
    if (adaptiveRewardTokenCostPer100k == null) {
      adaptiveRewardTokenCostPer100k = 0.1d;
    }
    ConfigValidation.minimum("adaptive_reward_token_cost_per_100k", adaptiveRewardTokenCostPer100k, 0.0d);
    ConfigValidation.maximum("adaptive_reward_token_cost_per_100k", adaptiveRewardTokenCostPer100k, 8.0d);
    if (adaptiveRewardRefutationCost == null) {
      adaptiveRewardRefutationCost = 1.0d;
    }
    ConfigValidation.minimum("adaptive_reward_refutation_cost", adaptiveRewardRefutationCost, 0.0d);
    ConfigValidation.maximum("adaptive_reward_refutation_cost", adaptiveRewardRefutationCost, 32.0d);
    if (experienceDistillationEnabled == null) {
      experienceDistillationEnabled = true;
    }
    if (negativeAnalogyLibraryEnabled == null) {
      negativeAnalogyLibraryEnabled = true;
    }
    if (maxDistilledExperiences == null) {
      maxDistilledExperiences = 256;
    }
    ConfigValidation.minimum("max_distilled_experiences", maxDistilledExperiences, 0);
    ConfigValidation.maximum("max_distilled_experiences", maxDistilledExperiences, 10000);
    if (maxNegativeAnalogyRecords == null) {
      maxNegativeAnalogyRecords = 256;
    }
    ConfigValidation.minimum("max_negative_analogy_records", maxNegativeAnalogyRecords, 0);
    ConfigValidation.maximum("max_negative_analogy_records", maxNegativeAnalogyRecords, 10000);
    if (crossRunLearningEnabled == null) {
      crossRunLearningEnabled = false;
    }
    if (crossRunLearningPath == null) {
      crossRunLearningPath = ".mathproofmesh/learning";
    }
    crossRunLearningPath = ConfigValidation.trim(crossRunLearningPath);
    if (crossRunRequireFinalCitation == null) {
      crossRunRequireFinalCitation = true;
    }
    if (crossRunMaxExperiences == null) {
      crossRunMaxExperiences = 2000;
    }
    ConfigValidation.minimum("cross_run_max_experiences", crossRunMaxExperiences, 0);
    ConfigValidation.maximum("cross_run_max_experiences", crossRunMaxExperiences, 100000);
    if (crossRunMaxNegativeAnalogies == null) {
      crossRunMaxNegativeAnalogies = 2000;
    }
    ConfigValidation.minimum("cross_run_max_negative_analogies", crossRunMaxNegativeAnalogies, 0);
    ConfigValidation.maximum("cross_run_max_negative_analogies", crossRunMaxNegativeAnalogies, 100000);
    if (crossRunMaxOutcomes == null) {
      crossRunMaxOutcomes = 10000;
    }
    ConfigValidation.minimum("cross_run_max_outcomes", crossRunMaxOutcomes, 0);
    ConfigValidation.maximum("cross_run_max_outcomes", crossRunMaxOutcomes, 500000);
    this.enabled = enabled;
    this.mode = mode;
    this.stagnationRounds = stagnationRounds;
    this.minimumVerifiedGain = minimumVerifiedGain;
    this.proofDebtMinReduction = proofDebtMinReduction;
    this.repeatedErrorThreshold = repeatedErrorThreshold;
    this.routeRedundancyTrigger = routeRedundancyTrigger;
    this.routeBudgetShareTrigger = routeBudgetShareTrigger;
    this.allRoutesFailedTrigger = allRoutesFailedTrigger;
    this.sharedBottleneckTrigger = sharedBottleneckTrigger;
    this.finalRepairFailureTrigger = finalRepairFailureTrigger;
    this.representationSwitchboard = representationSwitchboard;
    this.analogyAgent = analogyAgent;
    this.auxiliaryConstructionInventor = auxiliaryConstructionInventor;
    this.invariantHypothesisAgent = invariantHypothesisAgent;
    this.reverseGoalAnalysis = reverseGoalAnalysis;
    this.bridgeLemmaGenerator = bridgeLemmaGenerator;
    this.persistentMetaStrategist = persistentMetaStrategist;
    this.surpriseExploration = surpriseExploration;
    this.domainOperatorPluginsEnabled = domainOperatorPluginsEnabled;
    this.bidirectionalFrontierEnabled = bidirectionalFrontierEnabled;
    this.inspirationComposerEnabled = inspirationComposerEnabled;
    this.controlledSurpriseMutationEnabled = controlledSurpriseMutationEnabled;
    this.surpriseBudgetFraction = surpriseBudgetFraction;
    this.surpriseBudgetMinCalls = surpriseBudgetMinCalls;
    this.surpriseBudgetMaxCalls = surpriseBudgetMaxCalls;
    this.maxInspirationTasksPerRound = maxInspirationTasksPerRound;
    this.maxProposalsPerTask = maxProposalsPerTask;
    this.activeProposalsPerTask = activeProposalsPerTask;
    this.proposerGeneralistRoles = proposerGeneralistRoles;
    this.maxSingleAgentProposalsPerTask = maxSingleAgentProposalsPerTask;
    this.maxReviewedProposalsPerTask = maxReviewedProposalsPerTask;
    this.maxMaterializedProposalsPerTrigger = maxMaterializedProposalsPerTrigger;
    this.coldContextProposalsPerTask = coldContextProposalsPerTask;
    this.warmContextMaxFacts = warmContextMaxFacts;
    this.warmContextMaxNegatives = warmContextMaxNegatives;
    this.inspirationContextMaxChars = inspirationContextMaxChars;
    this.maxNewRoutesPerTrigger = maxNewRoutesPerTrigger;
    this.protectFinalizationReserve = protectFinalizationReserve;
    this.maxConsecutiveSurpriseRejections = maxConsecutiveSurpriseRejections;
    this.surpriseCooldownRounds = surpriseCooldownRounds;
    this.domainOperatorMaxPromptItems = domainOperatorMaxPromptItems;
    this.frontierMaxForwardClaims = frontierMaxForwardClaims;
    this.frontierMaxBackwardClaims = frontierMaxBackwardClaims;
    this.frontierMaxBridgeCandidates = frontierMaxBridgeCandidates;
    this.frontierMinCandidateOverlap = frontierMinCandidateOverlap;
    this.composerMaxCandidatesPerRound = composerMaxCandidatesPerRound;
    this.composerMaxSources = composerMaxSources;
    this.composerMaxCombinedCost = composerMaxCombinedCost;
    this.composerRequireQuickFalsification = composerRequireQuickFalsification;
    this.noveltyThreshold = noveltyThreshold;
    this.mechanismDuplicateThreshold = mechanismDuplicateThreshold;
    this.requireInspirationReferee = requireInspirationReferee;
    this.proposalsEnterFactMemory = proposalsEnterFactMemory;
    this.noveltyRepresentationWeight = noveltyRepresentationWeight;
    this.noveltyMechanismWeight = noveltyMechanismWeight;
    this.noveltyObjectWeight = noveltyObjectWeight;
    this.noveltyTransformationWeight = noveltyTransformationWeight;
    this.noveltyPrincipleWeight = noveltyPrincipleWeight;
    this.noveltyObligationWeight = noveltyObligationWeight;
    this.analogyLibraryEnabled = analogyLibraryEnabled;
    this.analogyLibraryPath = analogyLibraryPath;
    this.analogyTopK = analogyTopK;
    this.allowExternalRetrieval = allowExternalRetrieval;
    this.metaDirectivesEnabled = metaDirectivesEnabled;
    this.metaDirectiveExpiryRounds = metaDirectiveExpiryRounds;
    this.metaDirectiveCooldownRounds = metaDirectiveCooldownRounds;
    this.metaDirectiveMaxEstimatedCalls = metaDirectiveMaxEstimatedCalls;
    this.metaAllowRouteAbandon = metaAllowRouteAbandon;
    this.adaptiveMechanismSelection = adaptiveMechanismSelection;
    this.adaptiveMinObservations = adaptiveMinObservations;
    this.adaptiveMinExplorationRate = adaptiveMinExplorationRate;
    this.adaptiveUcbWeight = adaptiveUcbWeight;
    this.adaptiveRewardFactWeight = adaptiveRewardFactWeight;
    this.adaptiveRewardDebtWeight = adaptiveRewardDebtWeight;
    this.adaptiveRewardObligationWeight = adaptiveRewardObligationWeight;
    this.adaptiveRewardFinalCitationWeight = adaptiveRewardFinalCitationWeight;
    this.adaptiveRewardCallCost = adaptiveRewardCallCost;
    this.adaptiveRewardTokenCostPer100k = adaptiveRewardTokenCostPer100k;
    this.adaptiveRewardRefutationCost = adaptiveRewardRefutationCost;
    this.experienceDistillationEnabled = experienceDistillationEnabled;
    this.negativeAnalogyLibraryEnabled = negativeAnalogyLibraryEnabled;
    this.maxDistilledExperiences = maxDistilledExperiences;
    this.maxNegativeAnalogyRecords = maxNegativeAnalogyRecords;
    this.crossRunLearningEnabled = crossRunLearningEnabled;
    this.crossRunLearningPath = crossRunLearningPath;
    this.crossRunRequireFinalCitation = crossRunRequireFinalCitation;
    this.crossRunMaxExperiences = crossRunMaxExperiences;
    this.crossRunMaxNegativeAnalogies = crossRunMaxNegativeAnalogies;
    this.crossRunMaxOutcomes = crossRunMaxOutcomes;
    ConfigInvariants.validate(this);
  }

  public static InspirationConfig defaults() {
    return new InspirationConfig(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  @JsonProperty("proposer_generalist_roles")
  @Override
  public List<String> proposerGeneralistRoles() {
    return proposerGeneralistRoles == null ? null : List.copyOf(proposerGeneralistRoles);
  }

}
