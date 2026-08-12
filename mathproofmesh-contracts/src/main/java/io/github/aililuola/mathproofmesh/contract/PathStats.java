package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record PathStats(
    @JsonProperty(value = "analogy_opportunity") @ContractNonNull Double analogyOpportunity,
    @JsonProperty(value = "attempt_id") String attemptId,
    @JsonProperty(value = "bridge_opportunity") @ContractNonNull Double bridgeOpportunity,
    @JsonProperty(value = "common_mode_risk") @ContractNonNull Double commonModeRisk,
    @JsonProperty(value = "complete") @ContractNonNull Boolean complete,
    @JsonProperty(value = "consecutive_failures") @ContractNonNull Integer consecutiveFailures,
    @JsonProperty(value = "construction_opportunity") @ContractNonNull Double constructionOpportunity,
    @JsonProperty(value = "contradiction_count") @ContractNonNull Integer contradictionCount,
    @JsonProperty(value = "core_open_obligation_count") @ContractNonNull Integer coreOpenObligationCount,
    @JsonProperty(value = "core_proof_debt") @ContractNonNull Double coreProofDebt,
    @JsonProperty(value = "core_proof_debt_reduction") @ContractNonNull Double coreProofDebtReduction,
    @JsonProperty(value = "core_verified_bridge_gain") @ContractNonNull Integer coreVerifiedBridgeGain,
    @JsonProperty(value = "counterexample_count") @ContractNonNull Integer counterexampleCount,
    @JsonProperty(value = "failed_repair_attempts") @ContractNonNull Integer failedRepairAttempts,
    @JsonProperty(value = "failure_confidence") @ContractNonNull Double failureConfidence,
    @JsonProperty(value = "failure_level") @ContractNonNull FailureLevel failureLevel,
    @JsonProperty(value = "gap_reduction") @ContractNonNull Double gapReduction,
    @JsonProperty(value = "goal_alignment_score") @ContractNonNull Double goalAlignmentScore,
    @JsonProperty(value = "high_centrality_obligation_count") @ContractNonNull Integer highCentralityObligationCount,
    @JsonProperty(value = "incomplete_only") @ContractNonNull Boolean incompleteOnly,
    @JsonProperty(value = "inspiration_trigger_count") @ContractNonNull Integer inspirationTriggerCount,
    @JsonProperty(value = "last_round_index") @ContractNonNull Integer lastRoundIndex,
    @JsonProperty(value = "latest_delta_rejected") @ContractNonNull Boolean latestDeltaRejected,
    @JsonProperty(value = "latest_verdict") VerificationVerdict latestVerdict,
    @JsonProperty(value = "marginal_progress") @ContractNonNull Double marginalProgress,
    @JsonProperty(value = "message_utility") @ContractNonNull Double messageUtility,
    @JsonProperty(value = "meta_preferred") @ContractNonNull Boolean metaPreferred,
    @JsonProperty(value = "meta_recommended_action") ActionKind metaRecommendedAction,
    @JsonProperty(value = "meta_review_confidence") @ContractNonNull Double metaReviewConfidence,
    @JsonProperty(value = "negative_memory_hits") @ContractNonNull Integer negativeMemoryHits,
    @JsonProperty(value = "novelty") @ContractNonNull Double novelty,
    @JsonProperty(value = "novelty_score") @ContractNonNull Double noveltyScore,
    @JsonProperty(value = "progress") @ContractNonNull Double progress,
    @JsonProperty(value = "proof_debt") @ContractNonNull Double proofDebt,
    @JsonProperty(value = "proof_debt_reduction") @ContractNonNull Double proofDebtReduction,
    @JsonProperty(value = "rejected_delta_count") @ContractNonNull Integer rejectedDeltaCount,
    @JsonProperty(value = "representation_diversity") @ContractNonNull Double representationDiversity,
    @JsonProperty(value = "route_redundancy") @ContractNonNull Double routeRedundancy,
    @JsonProperty(value = "shared_obligation_count") @ContractNonNull Integer sharedObligationCount,
    @JsonProperty(value = "stagnation_rounds") @ContractNonNull Integer stagnationRounds,
    @JsonProperty(value = "strategy_id", required = true) @ContractNonNull String strategyId,
    @JsonProperty(value = "structurally_valid") Boolean structurallyValid,
    @JsonProperty(value = "surprise_budget_remaining") @ContractNonNull Integer surpriseBudgetRemaining,
    @JsonProperty(value = "tokens_spent") @ContractNonNull Integer tokensSpent,
    @JsonProperty(value = "uncertainty") @ContractNonNull Double uncertainty,
    @JsonProperty(value = "unresolved_gap_count") @ContractNonNull Integer unresolvedGapCount,
    @JsonProperty(value = "verification_score") @ContractNonNull Double verificationScore,
    @JsonProperty(value = "verified_delta_count") @ContractNonNull Integer verifiedDeltaCount,
    @JsonProperty(value = "verified_fact_gain") @ContractNonNull Integer verifiedFactGain
) implements StrictContract {

  public PathStats {
    if (analogyOpportunity == null) {
      analogyOpportunity = 0.0d;
    }
    ContractValues.minimum("analogy_opportunity", analogyOpportunity, 0.0);
    ContractValues.maximum("analogy_opportunity", analogyOpportunity, 1.0);
    attemptId = ContractStrings.trim(attemptId);
    if (bridgeOpportunity == null) {
      bridgeOpportunity = 0.0d;
    }
    ContractValues.minimum("bridge_opportunity", bridgeOpportunity, 0.0);
    ContractValues.maximum("bridge_opportunity", bridgeOpportunity, 1.0);
    if (commonModeRisk == null) {
      commonModeRisk = 0.0d;
    }
    ContractValues.minimum("common_mode_risk", commonModeRisk, 0.0);
    ContractValues.maximum("common_mode_risk", commonModeRisk, 1.0);
    if (complete == null) {
      complete = false;
    }
    if (consecutiveFailures == null) {
      consecutiveFailures = 0;
    }
    ContractValues.minimum("consecutive_failures", consecutiveFailures, 0);
    if (constructionOpportunity == null) {
      constructionOpportunity = 0.0d;
    }
    ContractValues.minimum("construction_opportunity", constructionOpportunity, 0.0);
    ContractValues.maximum("construction_opportunity", constructionOpportunity, 1.0);
    if (contradictionCount == null) {
      contradictionCount = 0;
    }
    ContractValues.minimum("contradiction_count", contradictionCount, 0);
    if (coreOpenObligationCount == null) {
      coreOpenObligationCount = 0;
    }
    ContractValues.minimum("core_open_obligation_count", coreOpenObligationCount, 0);
    if (coreProofDebt == null) {
      coreProofDebt = 0.0d;
    }
    ContractValues.minimum("core_proof_debt", coreProofDebt, 0.0);
    if (coreProofDebtReduction == null) {
      coreProofDebtReduction = 0.0d;
    }
    if (coreVerifiedBridgeGain == null) {
      coreVerifiedBridgeGain = 0;
    }
    ContractValues.minimum("core_verified_bridge_gain", coreVerifiedBridgeGain, 0);
    if (counterexampleCount == null) {
      counterexampleCount = 0;
    }
    ContractValues.minimum("counterexample_count", counterexampleCount, 0);
    if (failedRepairAttempts == null) {
      failedRepairAttempts = 0;
    }
    ContractValues.minimum("failed_repair_attempts", failedRepairAttempts, 0);
    if (failureConfidence == null) {
      failureConfidence = 0.0d;
    }
    ContractValues.minimum("failure_confidence", failureConfidence, 0.0);
    ContractValues.maximum("failure_confidence", failureConfidence, 1.0);
    if (failureLevel == null) {
      failureLevel = FailureLevel.NONE;
    }
    if (gapReduction == null) {
      gapReduction = 0.0d;
    }
    ContractValues.minimum("gap_reduction", gapReduction, -1.0);
    ContractValues.maximum("gap_reduction", gapReduction, 1.0);
    if (goalAlignmentScore == null) {
      goalAlignmentScore = 0.0d;
    }
    ContractValues.minimum("goal_alignment_score", goalAlignmentScore, 0.0);
    ContractValues.maximum("goal_alignment_score", goalAlignmentScore, 1.0);
    if (highCentralityObligationCount == null) {
      highCentralityObligationCount = 0;
    }
    ContractValues.minimum("high_centrality_obligation_count", highCentralityObligationCount, 0);
    if (incompleteOnly == null) {
      incompleteOnly = false;
    }
    if (inspirationTriggerCount == null) {
      inspirationTriggerCount = 0;
    }
    ContractValues.minimum("inspiration_trigger_count", inspirationTriggerCount, 0);
    if (lastRoundIndex == null) {
      lastRoundIndex = 0;
    }
    ContractValues.minimum("last_round_index", lastRoundIndex, 0);
    if (latestDeltaRejected == null) {
      latestDeltaRejected = false;
    }
    if (marginalProgress == null) {
      marginalProgress = 0.0d;
    }
    ContractValues.minimum("marginal_progress", marginalProgress, -1.0);
    ContractValues.maximum("marginal_progress", marginalProgress, 1.0);
    if (messageUtility == null) {
      messageUtility = 0.0d;
    }
    ContractValues.minimum("message_utility", messageUtility, 0.0);
    ContractValues.maximum("message_utility", messageUtility, 1.0);
    if (metaPreferred == null) {
      metaPreferred = false;
    }
    if (metaReviewConfidence == null) {
      metaReviewConfidence = 0.0d;
    }
    ContractValues.minimum("meta_review_confidence", metaReviewConfidence, 0.0);
    ContractValues.maximum("meta_review_confidence", metaReviewConfidence, 1.0);
    if (negativeMemoryHits == null) {
      negativeMemoryHits = 0;
    }
    ContractValues.minimum("negative_memory_hits", negativeMemoryHits, 0);
    if (novelty == null) {
      novelty = 0.5d;
    }
    ContractValues.minimum("novelty", novelty, 0.0);
    ContractValues.maximum("novelty", novelty, 1.0);
    if (noveltyScore == null) {
      noveltyScore = 0.0d;
    }
    ContractValues.minimum("novelty_score", noveltyScore, 0.0);
    ContractValues.maximum("novelty_score", noveltyScore, 1.0);
    if (progress == null) {
      progress = 0.0d;
    }
    ContractValues.minimum("progress", progress, 0.0);
    ContractValues.maximum("progress", progress, 1.0);
    if (proofDebt == null) {
      proofDebt = 0.0d;
    }
    ContractValues.minimum("proof_debt", proofDebt, 0.0);
    if (proofDebtReduction == null) {
      proofDebtReduction = 0.0d;
    }
    if (rejectedDeltaCount == null) {
      rejectedDeltaCount = 0;
    }
    ContractValues.minimum("rejected_delta_count", rejectedDeltaCount, 0);
    if (representationDiversity == null) {
      representationDiversity = 0.0d;
    }
    ContractValues.minimum("representation_diversity", representationDiversity, 0.0);
    ContractValues.maximum("representation_diversity", representationDiversity, 1.0);
    if (routeRedundancy == null) {
      routeRedundancy = 0.0d;
    }
    ContractValues.minimum("route_redundancy", routeRedundancy, 0.0);
    ContractValues.maximum("route_redundancy", routeRedundancy, 1.0);
    if (sharedObligationCount == null) {
      sharedObligationCount = 0;
    }
    ContractValues.minimum("shared_obligation_count", sharedObligationCount, 0);
    if (stagnationRounds == null) {
      stagnationRounds = 0;
    }
    ContractValues.minimum("stagnation_rounds", stagnationRounds, 0);
    strategyId = ContractStrings.trim(strategyId);
    strategyId = ContractStrings.required("strategy_id", strategyId);
    if (surpriseBudgetRemaining == null) {
      surpriseBudgetRemaining = 0;
    }
    ContractValues.minimum("surprise_budget_remaining", surpriseBudgetRemaining, 0);
    if (tokensSpent == null) {
      tokensSpent = 0;
    }
    ContractValues.minimum("tokens_spent", tokensSpent, 0);
    if (uncertainty == null) {
      uncertainty = 1.0d;
    }
    ContractValues.minimum("uncertainty", uncertainty, 0.0);
    ContractValues.maximum("uncertainty", uncertainty, 1.0);
    if (unresolvedGapCount == null) {
      unresolvedGapCount = 0;
    }
    ContractValues.minimum("unresolved_gap_count", unresolvedGapCount, 0);
    if (verificationScore == null) {
      verificationScore = 0.0d;
    }
    ContractValues.minimum("verification_score", verificationScore, 0.0);
    ContractValues.maximum("verification_score", verificationScore, 1.0);
    if (verifiedDeltaCount == null) {
      verifiedDeltaCount = 0;
    }
    ContractValues.minimum("verified_delta_count", verifiedDeltaCount, 0);
    if (verifiedFactGain == null) {
      verifiedFactGain = 0;
    }
    ContractValues.minimum("verified_fact_gain", verifiedFactGain, 0);
  }
}
