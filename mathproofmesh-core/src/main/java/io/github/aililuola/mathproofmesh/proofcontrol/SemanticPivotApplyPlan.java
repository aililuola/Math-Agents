package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewDecision;
import java.util.List;

/** Fully preflighted plan; construction is impossible before every deterministic gate passes. */
public record SemanticPivotApplyPlan(
    String planId,
    PivotDelta delta,
    PivotDeltaAudit deterministicAudit,
    SemanticPivotReviewDecision reviewDecision,
    String proposerAgentId,
    String reviewerAgentId,
    List<String> plannedNewObligationIds,
    String strategyEpochId) {
  public SemanticPivotApplyPlan {
    delta = java.util.Objects.requireNonNull(delta, "delta");
    deterministicAudit =
        java.util.Objects.requireNonNull(deterministicAudit, "deterministicAudit");
    reviewDecision = java.util.Objects.requireNonNull(reviewDecision, "reviewDecision");
    proposerAgentId = PivotValues.required(proposerAgentId, "proposerAgentId");
    reviewerAgentId = PivotValues.required(reviewerAgentId, "reviewerAgentId");
    plannedNewObligationIds = PivotValues.copy(plannedNewObligationIds);
    strategyEpochId = PivotValues.required(strategyEpochId, "strategyEpochId");
    if (!deterministicAudit.passed()
        || !delta.pivotId().equals(deterministicAudit.pivotId())
        || !delta.pivotId().equals(reviewDecision.pivotId())
        || proposerAgentId.equals(reviewerAgentId)
        || !strategyEpochId.equals(delta.proposedStrategyId())) {
      throw new IllegalArgumentException("semantic pivot apply plan has not passed every gate");
    }
    String computed =
        "pivot_plan_"
            + io.github.aililuola.mathproofmesh.contract.CanonicalJson.stableHash(
                    java.util.Map.of(
                        "pivot", delta.pivotId(),
                        "review", reviewDecision,
                        "obligations", plannedNewObligationIds,
                        "epoch", strategyEpochId))
                .substring(0, 20);
    if (planId != null && !planId.isBlank() && !computed.equals(planId.strip())) {
      throw new IllegalArgumentException("planId is server-owned");
    }
    planId = computed;
  }

  @Override
  public List<String> plannedNewObligationIds() {
    return List.copyOf(plannedNewObligationIds);
  }
}
