package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.EvidenceRef;

/** A server-resolved evidence capability bound to one exact frozen Claim context. */
public record TrustedClaimEvidence(
    String evidenceId,
    EvidenceRef evidenceRef,
    String problemHash,
    String claimSemanticHash,
    ClaimTrustedEvidenceAuthority authority,
    boolean verified,
    boolean replayVerified,
    boolean active,
    boolean proofStepUseAllowed) {
  public TrustedClaimEvidence {
    evidenceId = ClaimCourtValues.required(evidenceId, "evidenceId");
    evidenceRef = java.util.Objects.requireNonNull(evidenceRef, "evidenceRef");
    problemHash = ClaimCourtValues.required(problemHash, "problemHash");
    claimSemanticHash = ClaimCourtValues.required(claimSemanticHash, "claimSemanticHash");
    authority = java.util.Objects.requireNonNull(authority, "authority");
  }
}
