package io.github.aililuola.mathproofmesh.communication.artifact;

import java.util.Set;

public record BrokerArtifactEffectObservation(
    Set<String> committedStepIds,
    Set<String> verifiedClaimIds,
    Set<String> refutedClaimIds,
    Set<String> closedObligationIds,
    Set<String> retiredDependencyIds,
    String focusCanonicalTargetIdAfter,
    String localRepairId,
    String semanticPivotId,
    String computationPlanId,
    boolean citedByFinalProof,
    double canonicalProofDebtAfter) {
  public BrokerArtifactEffectObservation {
    committedStepIds = BrokerArtifactValues.set(committedStepIds);
    verifiedClaimIds = BrokerArtifactValues.set(verifiedClaimIds);
    refutedClaimIds = BrokerArtifactValues.set(refutedClaimIds);
    closedObligationIds = BrokerArtifactValues.set(closedObligationIds);
    retiredDependencyIds = BrokerArtifactValues.set(retiredDependencyIds);
    focusCanonicalTargetIdAfter = BrokerArtifactValues.nullable(focusCanonicalTargetIdAfter);
    localRepairId = BrokerArtifactValues.nullable(localRepairId);
    semanticPivotId = BrokerArtifactValues.nullable(semanticPivotId);
    computationPlanId = BrokerArtifactValues.nullable(computationPlanId);
    if (canonicalProofDebtAfter < 0.0d) throw new IllegalArgumentException("proof debt is invalid");
  }
}
