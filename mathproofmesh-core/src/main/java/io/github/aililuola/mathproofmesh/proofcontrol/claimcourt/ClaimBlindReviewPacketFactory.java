package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import java.util.List;
import java.util.Set;

public final class ClaimBlindReviewPacketFactory {
  public ClaimBlindReviewPacket create(
      FrozenClaimSnapshot frozen,
      ClaimProofRevisionRecord revision,
      Set<String> verifiedDependencyClaimIds,
      List<EvidenceRef> trustedEvidenceRefs) {
    java.util.Objects.requireNonNull(frozen, "frozen");
    java.util.Objects.requireNonNull(revision, "revision");
    if (!frozen.claimId().equals(revision.claimId())
        || !frozen.claimSemanticHash().equals(revision.claimSemanticHash())) {
      throw new IllegalArgumentException("FROZEN_CLAIM_MUTATION");
    }
    List<String> verified =
        verifiedDependencyClaimIds == null
            ? List.of()
            : verifiedDependencyClaimIds.stream().sorted().toList();
    if (!verified.containsAll(revision.dependencyClaimIds())) {
      throw new IllegalArgumentException("blind packet contains unverified claim dependency");
    }
    return new ClaimBlindReviewPacket(
        frozen.courtCaseId(),
        frozen.problemHash(),
        frozen.rootGoalHash(),
        frozen.claimId(),
        frozen.claimSemanticHash(),
        frozen.statement(),
        frozen.conclusion(),
        frozen.assumptions(),
        frozen.quantifiers(),
        frozen.variableBindings(),
        frozen.scopeLimitations(),
        frozen.polarity(),
        revision.revisionId(),
        revision.proofHash(),
        revision.proofSteps(),
        verified,
        trustedEvidenceRefs);
  }
}
