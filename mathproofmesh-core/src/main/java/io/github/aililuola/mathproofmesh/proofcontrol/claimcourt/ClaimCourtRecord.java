package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.contract.ClaimProofStatus;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementStatus;
import java.util.List;

public record ClaimCourtRecord(
    String courtCaseId,
    FrozenClaimSnapshot frozenClaim,
    ClaimCourtCaseStatus status,
    ClaimStatementStatus statementStatus,
    ClaimProofStatus proofStatus,
    ClaimCourtOutcome outcome,
    List<String> refutationEvidenceIds,
    String proofAuditId,
    String currentProofRevisionId,
    int repairAttempts,
    ClaimCourtRolePolicy.Assignment roleAssignment,
    String legacyClassification,
    long version,
    List<String> history) {
  public ClaimCourtRecord {
    courtCaseId = ClaimCourtValues.required(courtCaseId, "courtCaseId");
    frozenClaim = java.util.Objects.requireNonNull(frozenClaim, "frozenClaim");
    if (!courtCaseId.equals(frozenClaim.courtCaseId())) {
      throw new IllegalArgumentException("claim court case ID does not match frozen claim");
    }
    status = java.util.Objects.requireNonNull(status, "status");
    statementStatus = java.util.Objects.requireNonNull(statementStatus, "statementStatus");
    proofStatus = java.util.Objects.requireNonNull(proofStatus, "proofStatus");
    refutationEvidenceIds = ClaimCourtValues.copy(refutationEvidenceIds);
    proofAuditId = ClaimCourtValues.nullable(proofAuditId);
    currentProofRevisionId =
        ClaimCourtValues.required(currentProofRevisionId, "currentProofRevisionId");
    if (repairAttempts < 0 || version < 0L) {
      throw new IllegalArgumentException("repair attempts and version must be nonnegative");
    }
    legacyClassification = ClaimCourtValues.nullable(legacyClassification);
    history = ClaimCourtValues.copy(history);
    if (status.terminal() && outcome == null) {
      throw new IllegalArgumentException("terminal claim court case requires outcome");
    }
    if (outcome == ClaimCourtOutcome.REFUTED
        && (statementStatus != ClaimStatementStatus.REFUTED
            || refutationEvidenceIds.isEmpty())) {
      throw new IllegalArgumentException("refuted claim requires verified evidence");
    }
  }

  @Override
  public List<String> refutationEvidenceIds() {
    return List.copyOf(refutationEvidenceIds);
  }

  @Override
  public List<String> history() {
    return List.copyOf(history);
  }
}
