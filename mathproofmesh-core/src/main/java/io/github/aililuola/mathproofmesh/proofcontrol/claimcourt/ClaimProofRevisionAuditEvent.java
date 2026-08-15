package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

public record ClaimProofRevisionAuditEvent(
    long sequence,
    String revisionId,
    ClaimProofRevisionStatus fromStatus,
    ClaimProofRevisionStatus toStatus,
    String detail,
    long version) {
  public ClaimProofRevisionAuditEvent {
    if (sequence < 0L || version < 0L) {
      throw new IllegalArgumentException("revision audit sequence and version must be nonnegative");
    }
    revisionId = ClaimCourtValues.required(revisionId, "revisionId");
    toStatus = java.util.Objects.requireNonNull(toStatus, "toStatus");
    detail = ClaimCourtValues.required(detail, "detail");
  }
}
