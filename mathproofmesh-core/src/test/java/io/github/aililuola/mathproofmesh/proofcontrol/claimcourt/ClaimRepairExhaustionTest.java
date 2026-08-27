package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementAssessment;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClaimRepairExhaustionTest {
  @Test
  void boundedRepairFailureKeepsStatementOpen() {
    var claim = ClaimCourtTestFixtures.linearClaim();
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimCourtLedger court = new ClaimCourtLedger();
    court.open(frozen, ClaimCourtTestFixtures.roles());
    court.beginStatementScreening(frozen.courtCaseId());
    court.recordStatementAssessment(
        frozen.courtCaseId(),
        new ClaimStatementAuthorityService.Result(
            ClaimStatementAssessment.OPEN_NO_VERIFIED_REFUTATION,
            List.of(),
            "no verified refutation"));
    court.recordProofAudit(
        frozen.courtCaseId(),
        "audit-1",
        ClaimCourtRepairTestFixtures.localAudit(claim.claimId(), "linear-step"));
    court.beginRepair(frozen.courtCaseId(), ClaimCourtConfig.defaults());
    var exhausted =
        court.recordRepairFailure(frozen.courtCaseId(), true, "local patch did not validate");
    assertThat(exhausted.outcome()).isEqualTo(ClaimCourtOutcome.REPAIR_EXHAUSTED);
    assertThat(exhausted.statementStatus())
        .isEqualTo(io.github.aililuola.mathproofmesh.contract.ClaimStatementStatus.OPEN);
  }
}
