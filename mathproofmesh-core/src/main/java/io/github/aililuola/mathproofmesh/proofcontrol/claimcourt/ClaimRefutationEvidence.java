package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

public record ClaimRefutationEvidence(
    String evidenceId,
    ClaimRefutationEvidenceType evidenceType,
    String claimId,
    String claimStatementHash,
    String claimSemanticHash,
    String witness,
    String artifactRef,
    boolean replayValid,
    boolean trusted) {
  public ClaimRefutationEvidence {
    evidenceId = ClaimCourtValues.required(evidenceId, "evidenceId");
    evidenceType = java.util.Objects.requireNonNull(evidenceType, "evidenceType");
    claimId = ClaimCourtValues.required(claimId, "claimId");
    claimStatementHash = ClaimCourtValues.required(claimStatementHash, "claimStatementHash");
    claimSemanticHash = ClaimCourtValues.required(claimSemanticHash, "claimSemanticHash");
    witness = ClaimCourtValues.required(witness, "witness");
    artifactRef = ClaimCourtValues.required(artifactRef, "artifactRef");
  }

  public boolean exactFor(FrozenClaimSnapshot frozen) {
    return trusted
        && replayValid
        && claimId.equals(frozen.claimId())
        && claimStatementHash.equals(frozen.claimStatementHash())
        && claimSemanticHash.equals(frozen.claimSemanticHash());
  }
}
