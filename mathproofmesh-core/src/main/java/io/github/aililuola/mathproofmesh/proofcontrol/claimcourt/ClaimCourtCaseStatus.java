package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

public enum ClaimCourtCaseStatus {
  FROZEN,
  STATEMENT_SCREENING,
  STATEMENT_REFUTED,
  PROOF_AUDIT_PENDING,
  PROOF_VALID,
  PROOF_INVALID_REPAIRABLE,
  PROOF_INVALID_OPEN,
  REPAIR_PENDING,
  REPAIRED_PENDING_ADJUDICATION,
  BLIND_ADJUDICATION_PENDING,
  VERIFIED,
  REPAIR_EXHAUSTED,
  INCONCLUSIVE,
  DEFERRED;

  public boolean terminal() {
    return switch (this) {
      case STATEMENT_REFUTED,
          PROOF_INVALID_OPEN,
          VERIFIED,
          REPAIR_EXHAUSTED,
          INCONCLUSIVE,
          DEFERRED -> true;
      default -> false;
    };
  }
}
