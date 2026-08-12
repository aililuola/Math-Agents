package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.List;
import java.util.Map;

/** Chooses the weakest lower-cost target that still closes the declared goal. */
public final class MinimalSufficiencyAnalyzer {
  public record TargetCandidate(
      String subjectId,
      String targetObligationId,
      ProofControlModels.GoalRelation relation,
      ProofControlModels.ScopeRelation scope,
      double minimalityScore,
      double cost) {
    public TargetCandidate {
      ProofControlModels.required(subjectId, "subjectId");
      ProofControlModels.required(targetObligationId, "targetObligationId");
      ProofControlModels.unit(minimalityScore, "minimalityScore");
      if (!Double.isFinite(cost) || cost < 0.0d) {
        throw new IllegalArgumentException("cost must be nonnegative");
      }
    }

    public boolean sufficient() {
      return relation == ProofControlModels.GoalRelation.EQUIVALENT
          || relation == ProofControlModels.GoalRelation.SUFFICIENT;
    }
  }

  public TargetCandidate preferred(List<TargetCandidate> candidates) {
    return candidates.stream()
        .filter(TargetCandidate::sufficient)
        .filter(
            value ->
                value.scope() == ProofControlModels.ScopeRelation.SAME
                    || value.scope() == ProofControlModels.ScopeRelation.CLAIM_STRONGER)
        .max(
            java.util.Comparator.comparingDouble(TargetCandidate::minimalityScore)
                .thenComparing(
                    java.util.Comparator.comparingDouble(TargetCandidate::cost).reversed()))
        .orElseThrow(() -> new IllegalArgumentException("no sufficient target"));
  }

  public BridgeProposal proposeWeakerBridge(
      TargetCandidate overstrong, TargetCandidate weaker) {
    if (!weaker.sufficient()
        || weaker.minimalityScore() <= overstrong.minimalityScore()
        || weaker.cost() >= overstrong.cost()) {
      throw new IllegalArgumentException(
          "weaker target must be lower-cost and more minimally sufficient");
    }
    String id =
        "minimal_bridge_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "overstrong", overstrong.subjectId(),
                        "weaker", weaker.subjectId(),
                        "target", weaker.targetObligationId()))
                .substring(0, 20);
    return new BridgeProposal(
        id,
        overstrong.subjectId(),
        weaker.subjectId(),
        weaker.targetObligationId(),
        false,
        "candidate metadata; original goal remains immutable");
  }

  public record BridgeProposal(
      String id,
      String overstrongSubjectId,
      String weakerSubjectId,
      String targetObligationId,
      boolean replacesGoal,
      String authorityNote) {}
}
