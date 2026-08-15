package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimProofPatch;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperation;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperationType;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClaimProofPatchDependencyBoundaryTest {
  @Test
  void cannotIntroduceAnUnverifiedClaimDependency() {
    var claim = ClaimCourtTestFixtures.linearClaim();
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimProofRevisionRecord base = ClaimCourtRepairTestFixtures.original(frozen, claim);
    ClaimProofPatch patch =
        new ClaimProofPatch(
            "dependency-patch",
            claim.claimId(),
            frozen.claimSemanticHash(),
            base.revisionId(),
            base.proofHash(),
            List.of("issue-linear-step"),
            List.of("linear-step"),
            List.of(
                new ClaimProofPatchOperation(
                    "dependency-operation",
                    ClaimProofPatchOperationType.REBIND_VERIFIED_DEPENDENCY,
                    "linear-step",
                    null,
                    null,
                    null,
                    "unverified-lemma",
                    null)),
            List.of("issue-linear-step"));
    var result =
        new ClaimProofPatchValidator(ClaimCourtConfig.defaults())
            .validate(
                frozen,
                base,
                ClaimCourtRepairTestFixtures.localAudit(claim.claimId(), "linear-step"),
                patch,
                java.util.Set.of());
    assertThat(result.failureCodes()).contains("UNVERIFIED_DEPENDENCY_ADDITION");
  }
}
