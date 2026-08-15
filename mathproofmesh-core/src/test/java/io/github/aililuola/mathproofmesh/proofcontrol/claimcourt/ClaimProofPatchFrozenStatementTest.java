package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimProofPatch;
import org.junit.jupiter.api.Test;

final class ClaimProofPatchFrozenStatementTest {
  @Test
  void rejectsPatchBoundToAnotherClaimSemanticHash() {
    var claim = ClaimCourtTestFixtures.linearClaim();
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimProofRevisionRecord base = ClaimCourtRepairTestFixtures.original(frozen, claim);
    var valid =
        ClaimCourtRepairTestFixtures.patch(frozen, base, "linear-step", "issue-linear-step");
    ClaimProofPatch mutated =
        new ClaimProofPatch(
            valid.patchId(),
            valid.claimId(),
            "different-semantic-hash",
            valid.baseProofRevisionId(),
            valid.baseProofHash(),
            valid.issueIds(),
            valid.changedStepIds(),
            valid.operations(),
            valid.expectedResolvedIssueIds());
    var result =
        new ClaimProofPatchValidator(ClaimCourtConfig.defaults())
            .validate(
                frozen,
                base,
                ClaimCourtRepairTestFixtures.localAudit(claim.claimId(), "linear-step"),
                mutated,
                java.util.Set.of());
    assertThat(result.failureCodes()).contains("FROZEN_CLAIM_MUTATION");
  }
}
