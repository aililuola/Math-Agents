package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatch;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperation;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperationType;
import io.github.aililuola.mathproofmesh.contract.ProofAuditIssue;
import io.github.aililuola.mathproofmesh.contract.ProofIssueKind;
import io.github.aililuola.mathproofmesh.contract.ProofRepairability;
import java.util.List;

final class ClaimCourtRepairTestFixtures {
  private ClaimCourtRepairTestFixtures() {}

  static ClaimProofRevisionRecord original(
      FrozenClaimSnapshot frozen, io.github.aililuola.mathproofmesh.contract.ClaimCard claim) {
    return new ClaimProofRevisionLedger()
        .createOriginal(frozen, claim.proofSteps(), claim.evidenceRefs());
  }

  static ClaimProofAuditDecision localAudit(String claimId, String stepId) {
    return new ClaimProofAuditDecision(
        claimId,
        ClaimProofAuditVerdict.INVALID_REPAIRABLE,
        List.of(issue(claimId, stepId, "issue-" + stepId)),
        "One local bridge is missing.");
  }

  static ProofAuditIssue issue(String claimId, String stepId, String issueId) {
    return new ProofAuditIssue(
        issueId,
        claimId,
        stepId,
        "T(x)=T(y)",
        "x=y",
        ProofIssueKind.MISSING_JUSTIFICATION,
        ProofRepairability.LOCAL_PATCH,
        List.of(),
        false,
        "Insert the kernel argument.");
  }

  static ClaimProofPatch patch(
      FrozenClaimSnapshot frozen,
      ClaimProofRevisionRecord base,
      String stepId,
      String issueId) {
    return new ClaimProofPatch(
        "patch-" + stepId,
        frozen.claimId(),
        frozen.claimSemanticHash(),
        base.revisionId(),
        base.proofHash(),
        List.of(issueId),
        List.of(stepId),
        List.of(
            new ClaimProofPatchOperation(
                "operation-" + stepId,
                ClaimProofPatchOperationType.REPLACE_STEP_JUSTIFICATION,
                stepId,
                null,
                "By linearity T(x-y)=0, hence x-y is in ker(T)={0}, so x=y.",
                null,
                null,
                null)),
        List.of(issueId));
  }
}
