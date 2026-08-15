package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditVerdict;
import io.github.aililuola.mathproofmesh.contract.ProofAuditIssue;
import io.github.aililuola.mathproofmesh.contract.ProofIssueKind;
import io.github.aililuola.mathproofmesh.contract.ProofRepairability;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClaimProofAuditClassificationTest {
  @Test
  void auditSeparatesLocalPatchFromGlobalRewrite() {
    ClaimProofAuditDecision local =
        decision(
            ClaimProofAuditVerdict.INVALID_REPAIRABLE,
            ProofIssueKind.MISSING_JUSTIFICATION,
            ProofRepairability.LOCAL_PATCH,
            false);
    ClaimProofAuditDecision global =
        decision(
            ClaimProofAuditVerdict.INVALID_UNREPAIRABLE,
            ProofIssueKind.GLOBAL_PROOF_ARCHITECTURE_FAILURE,
            ProofRepairability.NONLOCAL_REWRITE_REQUIRED,
            false);
    assertThat(local.verdict()).isEqualTo(ClaimProofAuditVerdict.INVALID_REPAIRABLE);
    assertThat(global.verdict()).isEqualTo(ClaimProofAuditVerdict.INVALID_UNREPAIRABLE);
  }

  private static ClaimProofAuditDecision decision(
      ClaimProofAuditVerdict verdict,
      ProofIssueKind kind,
      ProofRepairability repairability,
      boolean touchesStatement) {
    return new ClaimProofAuditDecision(
        "linear-claim",
        verdict,
        List.of(
            new ProofAuditIssue(
                "audit-issue-1",
                "linear-claim",
                "linear-step",
                "T(x)=T(y)",
                "x=y",
                kind,
                repairability,
                List.of(),
                touchesStatement,
                "The stated bridge is missing.")),
        "Classified independently of statement truth.");
  }
}
