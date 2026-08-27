package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementAssessment;
import org.junit.jupiter.api.Test;

final class ClaimTruthProofValiditySeparationTest {
  @Test
  void invalidProofKeepsStatementOpenWhileVerifiedRefutationRejects() {
    ClaimCourtDecisionService decisions = new ClaimCourtDecisionService();
    assertThat(
            decisions.afterStatementAndAudit(
                ClaimStatementAssessment.OPEN_NO_VERIFIED_REFUTATION,
                ClaimProofAuditVerdict.INVALID_UNREPAIRABLE))
        .isEqualTo(ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN);
    assertThat(
            decisions.afterStatementAndAudit(
                ClaimStatementAssessment.REFUTED_BY_VERIFIED_EVIDENCE,
                ClaimProofAuditVerdict.VALID))
        .isEqualTo(ClaimCourtOutcome.REFUTED);
  }
}
