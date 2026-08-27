package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class ClaimProofRevisionLedgerTest {
  @Test
  void createsOneStableRepairedRevisionAndBlindTransition() {
    var claim = ClaimCourtTestFixtures.linearClaim();
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimProofRevisionLedger ledger = new ClaimProofRevisionLedger();
    ClaimProofRevisionRecord original =
        ledger.createOriginal(frozen, claim.proofSteps(), claim.evidenceRefs());
    var audit = ClaimCourtRepairTestFixtures.localAudit(claim.claimId(), "linear-step");
    var patch =
        ClaimCourtRepairTestFixtures.patch(frozen, original, "linear-step", "issue-linear-step");
    var validation =
        new ClaimProofPatchValidator(ClaimCourtConfig.defaults())
            .validate(frozen, original, audit, patch, java.util.Set.of());
    ClaimProofRevisionRecord repaired =
        ledger.createRepaired(
            frozen, original, patch, validation.proofSteps(), "repairer-agent");
    assertThat(
            ledger
                .createRepaired(
                    frozen, original, patch, validation.proofSteps(), "repairer-agent")
                .revisionId())
        .isEqualTo(repaired.revisionId());
    assertThat(ledger.markBlindVerified(repaired.revisionId()).status())
        .isEqualTo(ClaimProofRevisionStatus.BLIND_VERIFIED);
  }
}
