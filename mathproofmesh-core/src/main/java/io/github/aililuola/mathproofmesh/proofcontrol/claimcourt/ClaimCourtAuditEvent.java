package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

public record ClaimCourtAuditEvent(
    long sequence,
    String courtCaseId,
    ClaimCourtCaseStatus fromStatus,
    ClaimCourtCaseStatus toStatus,
    String detail,
    long version) {
  public ClaimCourtAuditEvent {
    if (sequence < 0L || version < 0L) {
      throw new IllegalArgumentException("court audit sequence and version must be nonnegative");
    }
    courtCaseId = ClaimCourtValues.required(courtCaseId, "courtCaseId");
    toStatus = java.util.Objects.requireNonNull(toStatus, "toStatus");
    detail = ClaimCourtValues.required(detail, "detail");
  }
}
