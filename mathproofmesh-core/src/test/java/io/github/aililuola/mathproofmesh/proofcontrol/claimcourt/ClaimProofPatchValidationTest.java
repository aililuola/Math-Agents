package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class ClaimProofPatchValidationTest {
  @Test
  void appliesOnlyTheAuditedLocalJustification() {
    var claim = ClaimCourtTestFixtures.linearClaim();
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimProofRevisionRecord base = ClaimCourtRepairTestFixtures.original(frozen, claim);
    var result =
        new ClaimProofPatchValidator(ClaimCourtConfig.defaults())
            .validate(
                frozen,
                base,
                ClaimCourtRepairTestFixtures.localAudit(claim.claimId(), "linear-step"),
                ClaimCourtRepairTestFixtures.patch(
                    frozen, base, "linear-step", "issue-linear-step"),
                java.util.Set.of());
    assertThat(result.passed()).isTrue();
    assertThat(result.proofSteps().getFirst().statement())
        .isEqualTo(claim.proofSteps().getFirst().statement());
    assertThat(result.proofSteps().getFirst().justification()).contains("ker(T)");
  }
}
