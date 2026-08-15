package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.ClaimBlindAdjudicationVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementAssessment;

/** Pure authority classifier used before any mutable projection is touched. */
public final class ClaimCourtDecisionService {
  public ClaimCourtOutcome afterStatementAndAudit(
      ClaimStatementAssessment statementAssessment, ClaimProofAuditVerdict proofVerdict) {
    if (statementAssessment == ClaimStatementAssessment.REFUTED_BY_VERIFIED_EVIDENCE) {
      return ClaimCourtOutcome.REFUTED;
    }
    if (statementAssessment == ClaimStatementAssessment.INCONCLUSIVE
        || proofVerdict == ClaimProofAuditVerdict.INCONCLUSIVE) {
      return ClaimCourtOutcome.INCONCLUSIVE;
    }
    return switch (proofVerdict) {
      case INVALID_UNREPAIRABLE -> ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN;
      case VALID, INVALID_REPAIRABLE -> null;
      case INCONCLUSIVE -> ClaimCourtOutcome.INCONCLUSIVE;
    };
  }

  public ClaimCourtOutcome afterBlindAdjudication(
      ClaimBlindAdjudicationVerdict verdict, boolean repairAttemptUsed) {
    return switch (java.util.Objects.requireNonNull(verdict, "verdict")) {
      case PASS -> ClaimCourtOutcome.VERIFIED;
      case FAIL_PROOF ->
          repairAttemptUsed
              ? ClaimCourtOutcome.REPAIR_EXHAUSTED
              : ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN;
      case INCONCLUSIVE, COUNTEREXAMPLE_CANDIDATE -> ClaimCourtOutcome.INCONCLUSIVE;
    };
  }
}
