package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatch;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperation;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperationType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClaimProofPatchMinimalityTest {
  @Test
  void rejectsPatchThatChangesTooMuchOfTheProof() {
    var claim =
        ClaimCourtTestFixtures.claim(
            "multi-step-claim",
            "A synthetic finite proof claim.",
            "Synthetic conclusion",
            List.of(),
            List.of(
                ClaimCourtTestFixtures.step("s1", "S1", "J1"),
                ClaimCourtTestFixtures.step("s2", "S2", "J2"),
                ClaimCourtTestFixtures.step("s3", "S3", "J3"),
                ClaimCourtTestFixtures.step("s4", "S4", "J4")));
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimProofRevisionRecord base = ClaimCourtRepairTestFixtures.original(frozen, claim);
    List<io.github.aililuola.mathproofmesh.contract.ProofAuditIssue> issues = new ArrayList<>();
    List<ClaimProofPatchOperation> operations = new ArrayList<>();
    for (String stepId : List.of("s1", "s2", "s3")) {
      issues.add(ClaimCourtRepairTestFixtures.issue(claim.claimId(), stepId, "i-" + stepId));
      operations.add(
          new ClaimProofPatchOperation(
              "o-" + stepId,
              ClaimProofPatchOperationType.REPLACE_STEP_JUSTIFICATION,
              stepId,
              null,
              "Replacement " + stepId,
              null,
              null,
              null));
    }
    List<String> issueIds = List.of("i-s1", "i-s2", "i-s3");
    ClaimProofPatch patch =
        new ClaimProofPatch(
            "large-patch",
            claim.claimId(),
            frozen.claimSemanticHash(),
            base.revisionId(),
            base.proofHash(),
            issueIds,
            List.of("s1", "s2", "s3"),
            operations,
            issueIds);
    var result =
        new ClaimProofPatchValidator(ClaimCourtConfig.defaults())
            .validate(
                frozen,
                base,
                new ClaimProofAuditDecision(
                    claim.claimId(),
                    ClaimProofAuditVerdict.INVALID_REPAIRABLE,
                    issues,
                    "Too many steps are implicated."),
                patch,
                java.util.Set.of());
    assertThat(result.failureCodes()).contains("PATCH_CHANGED_STEP_LIMIT_EXCEEDED");
  }
}
