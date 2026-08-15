package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimProofPatch;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperation;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperationType;
import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ClaimProofPatchUntrustedEvidenceInjectionTest {
  @Test
  void modelSuppliedEvidenceReferenceCannotCreateTrustedProofEvidence() {
    var claim = ClaimCourtTestFixtures.linearClaim();
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimProofRevisionRecord base = ClaimCourtRepairTestFixtures.original(frozen, claim);
    var audit =
        ClaimCourtRepairTestFixtures.localAudit(claim.claimId(), "linear-step");
    EvidenceRef invented =
        new EvidenceRef(
            "artifact://repairer-invented",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "result",
            "Repairer says this is verified.");
    ClaimProofPatch patch =
        new ClaimProofPatch(
            "patch-untrusted-evidence",
            frozen.claimId(),
            frozen.claimSemanticHash(),
            base.revisionId(),
            base.proofHash(),
            List.of("issue-linear-step"),
            List.of("linear-step"),
            List.of(
                new ClaimProofPatchOperation(
                    "add-untrusted-evidence",
                    ClaimProofPatchOperationType.ADD_VERIFIED_EVIDENCE_REF,
                    "linear-step",
                    null,
                    null,
                    null,
                    null,
                    invented)),
            List.of("issue-linear-step"));

    var result =
        new ClaimProofPatchValidator(ClaimCourtConfig.defaults())
            .validate(frozen, base, audit, patch, Set.of());

    assertThat(result.passed()).isFalse();
    assertThat(result.failureCodes()).contains("UNKNOWN_EVIDENCE_REF");
    assertThat(result.evidenceRefs()).doesNotContain(invented);
  }
}
