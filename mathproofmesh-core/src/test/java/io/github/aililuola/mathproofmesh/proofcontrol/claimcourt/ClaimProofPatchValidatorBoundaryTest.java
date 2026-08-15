package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatch;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperation;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperationType;
import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import io.github.aililuola.mathproofmesh.contract.ProofAuditIssue;
import io.github.aililuola.mathproofmesh.contract.ProofIssueKind;
import io.github.aililuola.mathproofmesh.contract.ProofRepairability;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ClaimProofPatchValidatorBoundaryTest {
  @Test
  void appliesEverySupportedBoundedPatchOperationWithoutChangingClaimAuthority() {
    List<ProofStep> steps =
        List.of(
            step("s1", List.of(), List.of()),
            step("s2", List.of(), List.of()),
            step("s3", List.of("step:s1"), List.of()),
            step("s4", List.of(), List.of()),
            step("s5", List.of(), List.of()),
            step("s6", List.of(), List.of()));
    var claim =
        ClaimCourtTestFixtures.claim(
            "all-operations", "A bounded proof claim.", "Conclusion", List.of(), steps);
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimProofRevisionRecord base = ClaimCourtRepairTestFixtures.original(frozen, claim);
    List<ProofAuditIssue> issues = new ArrayList<>();
    for (String stepId : List.of("s1", "s2", "s3", "s4", "s5", "s6")) {
      issues.add(issue(claim.claimId(), stepId, "issue-" + stepId, ProofRepairability.LOCAL_PATCH, false));
    }
    List<String> issueIds = issues.stream().map(ProofAuditIssue::issueId).toList();
    EvidenceRef evidence = new EvidenceRef("artifact://verified", "hash", "section", "verified");
    List<ClaimProofPatchOperation> operations =
        List.of(
            operation(
                "justify",
                ClaimProofPatchOperationType.REPLACE_STEP_JUSTIFICATION,
                "s1",
                null,
                "A corrected local justification.",
                null,
                null,
                null),
            operation(
                "statement",
                ClaimProofPatchOperationType.REPLACE_STEP_STATEMENT,
                "s2",
                "A corrected intermediate statement.",
                null,
                null,
                null,
                null),
            operation(
                "before",
                ClaimProofPatchOperationType.INSERT_STEP_BEFORE,
                "s3",
                null,
                null,
                step("inserted-before", List.of("step:s1"), List.of()),
                null,
                null),
            operation(
                "after",
                ClaimProofPatchOperationType.INSERT_STEP_AFTER,
                "s3",
                null,
                null,
                step("inserted-after", List.of("step:s3"), List.of()),
                null,
                null),
            operation(
                "delete",
                ClaimProofPatchOperationType.DELETE_REDUNDANT_STEP,
                "s4",
                null,
                null,
                null,
                null,
                null),
            operation(
                "dependency",
                ClaimProofPatchOperationType.REBIND_VERIFIED_DEPENDENCY,
                "s5",
                null,
                null,
                null,
                "verified-claim",
                null),
            operation(
                "dependency-repeat",
                ClaimProofPatchOperationType.REBIND_VERIFIED_DEPENDENCY,
                "s5",
                null,
                null,
                null,
                "verified-claim",
                null),
            operation(
                "evidence",
                ClaimProofPatchOperationType.ADD_VERIFIED_EVIDENCE_REF,
                "s6",
                null,
                null,
                null,
                null,
                evidence),
            operation(
                "evidence-repeat",
                ClaimProofPatchOperationType.ADD_VERIFIED_EVIDENCE_REF,
                "s6",
                null,
                null,
                null,
                null,
                evidence));
    ClaimProofPatch patch =
        patch(
            frozen,
            base,
            issueIds,
            List.of("s1", "s2", "s3", "s4", "s5", "s6"),
            operations,
            issueIds);

    var result =
        new ClaimProofPatchValidator(new ClaimCourtConfig(1, 6, 1.0d, 2, true))
            .validate(
                frozen,
                base,
                audit(claim.claimId(), ClaimProofAuditVerdict.INVALID_REPAIRABLE, issues),
                patch,
                Set.of("verified-claim"));

    assertThat(result.passed()).isTrue();
    assertThat(result.failureCodes()).isEmpty();
    assertThat(result.proofSteps()).extracting(ProofStep::stepId)
        .containsExactly("s1", "s2", "inserted-before", "s3", "inserted-after", "s5", "s6");
    assertThat(result.proofSteps().getFirst().justification()).contains("corrected");
    assertThat(result.proofSteps().get(1).statement()).contains("corrected");
    assertThat(result.proofSteps().get(5).dependencies()).containsExactly("claim:verified-claim");
    assertThat(result.proofSteps().get(6).calculationEvidenceRefs()).containsExactly(evidence);
    assertThat(result.evidenceRefs()).containsExactly(evidence);
    assertThat(result.dependencyClaimIds()).isEqualTo(base.dependencyClaimIds());
  }

  @Test
  void rejectsFrozenIdentityAuditBindingAndBoundViolationsBeforeMutation() {
    var claim = ClaimCourtTestFixtures.linearClaim();
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimProofRevisionRecord base = ClaimCourtRepairTestFixtures.original(frozen, claim);
    ClaimProofPatch valid =
        ClaimCourtRepairTestFixtures.patch(frozen, base, "linear-step", "issue-linear-step");
    ClaimProofPatchValidator validator = new ClaimProofPatchValidator(ClaimCourtConfig.defaults());

    for (ClaimProofPatch mismatch :
        List.of(
            copyPatch(valid, "wrong-claim", null, null, null),
            copyPatch(valid, null, "wrong-semantic", null, null),
            copyPatch(valid, null, null, "wrong-revision", null),
            copyPatch(valid, null, null, null, "wrong-proof"))) {
      assertThat(
              validator
                  .validate(
                      frozen,
                      base,
                      ClaimCourtRepairTestFixtures.localAudit(claim.claimId(), "linear-step"),
                      mismatch,
                      Set.of())
                  .failureCodes())
          .contains("FROZEN_CLAIM_MUTATION");
    }

    ClaimProofAuditDecision validAudit =
        audit(claim.claimId(), ClaimProofAuditVerdict.VALID, List.of());
    assertThat(validator.validate(frozen, base, validAudit, valid, Set.of()).failureCodes())
        .contains("AUDIT_NOT_REPAIRABLE");

    List<ProofAuditIssue> boundaryIssues =
        List.of(
            issue(
                claim.claimId(),
                "linear-step",
                "touches-statement",
                ProofRepairability.LOCAL_PATCH,
                true),
            issue(
                claim.claimId(),
                "another-step",
                "nonlocal",
                ProofRepairability.NONLOCAL_REWRITE_REQUIRED,
                false));
    ClaimProofPatch wrongBinding =
        patch(
            frozen,
            base,
            List.of("different-issue"),
            List.of("unaudited-step"),
            List.of(
                operation(
                    "unknown",
                    ClaimProofPatchOperationType.REPLACE_STEP_JUSTIFICATION,
                    "missing-step",
                    null,
                    "replacement",
                    null,
                    null,
                    null)),
            List.of("different-resolved-issue"));
    assertThat(
            validator
                .validate(
                    frozen,
                    base,
                    audit(
                        claim.claimId(),
                        ClaimProofAuditVerdict.INVALID_REPAIRABLE,
                        boundaryIssues),
                    wrongBinding,
                    Set.of())
                .failureCodes())
        .contains(
            "STATEMENT_REFORMULATION_REQUIRED",
            "NONLOCAL_REPAIRABILITY",
            "ISSUE_BINDING_MISMATCH",
            "PATCH_TOUCHES_UNAUDITED_STEP",
            "UNKNOWN_PATCH_TARGET_STEP",
            "CHANGED_STEP_DECLARATION_MISMATCH");

    ClaimProofPatch unverifiedDependency =
        patch(
            frozen,
            base,
            List.of("issue-linear-step"),
            List.of("linear-step"),
            List.of(
                operation(
                    "dependency",
                    ClaimProofPatchOperationType.REBIND_VERIFIED_DEPENDENCY,
                    "linear-step",
                    null,
                    null,
                    null,
                    "unverified",
                    null)),
            List.of("issue-linear-step"));
    assertThat(
            validator
                .validate(
                    frozen,
                    base,
                    ClaimCourtRepairTestFixtures.localAudit(claim.claimId(), "linear-step"),
                    unverifiedDependency,
                    null)
                .failureCodes())
        .contains("UNVERIFIED_DEPENDENCY_ADDITION");
  }

  @Test
  void rejectsEvidenceDeletionDuplicateBaseAndConfiguredSizeLimits() {
    EvidenceRef evidence = new EvidenceRef("artifact://kept", null, null, "must remain");
    ProofStep evidenced = step("evidenced", List.of(), List.of(evidence));
    var evidenceClaim =
        ClaimCourtTestFixtures.claim(
            "evidence-claim", "Evidence claim", "Conclusion", List.of(), List.of(evidenced));
    FrozenClaimSnapshot evidenceFrozen = ClaimCourtTestFixtures.freeze(evidenceClaim);
    ClaimProofRevisionRecord evidenceBase =
        ClaimCourtRepairTestFixtures.original(evidenceFrozen, evidenceClaim);
    ClaimProofPatch delete =
        patch(
            evidenceFrozen,
            evidenceBase,
            List.of("delete-issue"),
            List.of("evidenced"),
            List.of(
                operation(
                    "delete",
                    ClaimProofPatchOperationType.DELETE_REDUNDANT_STEP,
                    "evidenced",
                    null,
                    null,
                    null,
                    null,
                    null)),
            List.of("delete-issue"));
    assertThat(
            new ClaimProofPatchValidator(ClaimCourtConfig.defaults())
                .validate(
                    evidenceFrozen,
                    evidenceBase,
                    audit(
                        evidenceClaim.claimId(),
                        ClaimProofAuditVerdict.INVALID_REPAIRABLE,
                        List.of(
                            issue(
                                evidenceClaim.claimId(),
                                "evidenced",
                                "delete-issue",
                                ProofRepairability.LOCAL_PATCH,
                                false))),
                    delete,
                    Set.of())
                .failureCodes())
        .contains("VALID_EVIDENCE_DELETION");

    List<ProofStep> duplicateSteps =
        List.of(step("duplicate", List.of(), List.of()), step("duplicate", List.of(), List.of()));
    var duplicateClaim =
        ClaimCourtTestFixtures.claim(
            "duplicate-base", "Duplicate base", "Conclusion", List.of(), duplicateSteps);
    FrozenClaimSnapshot duplicateFrozen = ClaimCourtTestFixtures.freeze(duplicateClaim);
    ClaimProofRevisionRecord duplicateBase =
        ClaimCourtRepairTestFixtures.original(duplicateFrozen, duplicateClaim);
    ClaimProofPatch duplicatePatch =
        patch(
            duplicateFrozen,
            duplicateBase,
            List.of("duplicate-issue"),
            List.of("duplicate"),
            List.of(
                operation(
                    "replace",
                    ClaimProofPatchOperationType.REPLACE_STEP_JUSTIFICATION,
                    "duplicate",
                    null,
                    "replacement",
                    null,
                    null,
                    null)),
            List.of("duplicate-issue"));
    assertThat(
            new ClaimProofPatchValidator(ClaimCourtConfig.defaults())
                .validate(
                    duplicateFrozen,
                    duplicateBase,
                    audit(
                        duplicateClaim.claimId(),
                        ClaimProofAuditVerdict.INVALID_REPAIRABLE,
                        List.of(
                            issue(
                                duplicateClaim.claimId(),
                                "duplicate",
                                "duplicate-issue",
                                ProofRepairability.LOCAL_PATCH,
                                false))),
                    duplicatePatch,
                    Set.of())
                .failureCodes())
        .contains("DUPLICATE_BASE_STEP_ID");

    assertThat(sizeLimitedValidation(1, 1.0d, 3).failureCodes())
        .contains("PATCH_CHANGED_STEP_LIMIT_EXCEEDED");
    assertThat(sizeLimitedValidation(3, 1.0d, 0).failureCodes())
        .contains("PATCH_INSERTED_STEP_LIMIT_EXCEEDED");
  }

  @Test
  void rejectsInvalidPostPatchTopology() {
    assertThat(validateInsertedStep(step("s1", List.of(), List.of())).failureCodes())
        .contains("DUPLICATE_PATCHED_STEP_ID");
    assertThat(validateInsertedStep(step("new-step", List.of("step:missing"), List.of())).failureCodes())
        .contains("MISSING_LOCAL_STEP_DEPENDENCY");
    assertThat(validateInsertedStep(step("cycle", List.of("step:cycle"), List.of())).failureCodes())
        .contains("PROOF_DEPENDENCY_CYCLE");
    assertThat(validateInsertedStep(step("acyclic", List.of("step:s1"), List.of())).passed())
        .isTrue();
  }

  @Test
  void claimCourtRepairConfigurationRejectsEveryInvalidLimit() {
    assertThatThrownBy(() -> new ClaimCourtConfig(-1, 1, 0.5d, 1, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ClaimCourtConfig(1, -1, 0.5d, 1, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ClaimCourtConfig(1, 1, Double.NaN, 1, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ClaimCourtConfig(1, 1, -0.1d, 1, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ClaimCourtConfig(1, 1, 1.1d, 1, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ClaimCourtConfig(1, 1, 0.5d, -1, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ClaimCourtConfig.defaults().requireBlindAdjudication()).isTrue();
  }

  private static ClaimProofPatchValidator.ValidationResult sizeLimitedValidation(
      int maxChangedSteps, double fraction, int maxInsertedSteps) {
    List<ProofStep> steps =
        List.of(
            step("s1", List.of(), List.of()),
            step("s2", List.of(), List.of()),
            step("s3", List.of(), List.of()));
    var claim =
        ClaimCourtTestFixtures.claim(
            "size-limit-" + maxChangedSteps + "-" + maxInsertedSteps,
            "Size limit claim",
            "Conclusion",
            List.of(),
            steps);
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimProofRevisionRecord base = ClaimCourtRepairTestFixtures.original(frozen, claim);
    List<ProofAuditIssue> issues =
        List.of(
            issue(claim.claimId(), "s1", "i1", ProofRepairability.LOCAL_PATCH, false),
            issue(claim.claimId(), "s2", "i2", ProofRepairability.LOCAL_PATCH, false));
    ClaimProofPatch patch =
        patch(
            frozen,
            base,
            List.of("i1", "i2"),
            List.of("s1", "s2"),
            List.of(
                operation(
                    "insert-1",
                    ClaimProofPatchOperationType.INSERT_STEP_AFTER,
                    "s1",
                    null,
                    null,
                    step("new-1", List.of(), List.of()),
                    null,
                    null),
                operation(
                    "insert-2",
                    ClaimProofPatchOperationType.INSERT_STEP_AFTER,
                    "s2",
                    null,
                    null,
                    step("new-2", List.of(), List.of()),
                    null,
                    null)),
            List.of("i1", "i2"));
    return new ClaimProofPatchValidator(
            new ClaimCourtConfig(1, maxChangedSteps, fraction, maxInsertedSteps, true))
        .validate(
            frozen,
            base,
            audit(claim.claimId(), ClaimProofAuditVerdict.INVALID_REPAIRABLE, issues),
            patch,
            Set.of());
  }

  private static ClaimProofPatchValidator.ValidationResult validateInsertedStep(
      ProofStep inserted) {
    var claim =
        ClaimCourtTestFixtures.claim(
            "topology-" + inserted.stepId(),
            "Topology claim",
            "Conclusion",
            List.of(),
            List.of(step("s1", List.of(), List.of())));
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimProofRevisionRecord base = ClaimCourtRepairTestFixtures.original(frozen, claim);
    ClaimProofPatch patch =
        patch(
            frozen,
            base,
            List.of("issue"),
            List.of("s1"),
            List.of(
                operation(
                    "insert",
                    ClaimProofPatchOperationType.INSERT_STEP_AFTER,
                    "s1",
                    null,
                    null,
                    inserted,
                    null,
                    null)),
            List.of("issue"));
    return new ClaimProofPatchValidator(new ClaimCourtConfig(1, 1, 1.0d, 1, true))
        .validate(
            frozen,
            base,
            audit(
                claim.claimId(),
                ClaimProofAuditVerdict.INVALID_REPAIRABLE,
                List.of(
                    issue(
                        claim.claimId(),
                        "s1",
                        "issue",
                        ProofRepairability.LOCAL_PATCH,
                        false))),
            patch,
            Set.of());
  }

  private static ProofStep step(
      String id, List<String> dependencies, List<EvidenceRef> evidenceRefs) {
    return new ProofStep(
        null,
        List.of(),
        evidenceRefs,
        List.of(),
        List.of(),
        0.8d,
        dependencies,
        List.of(),
        true,
        "Justification for " + id,
        "Statement for " + id,
        id,
        "derivation");
  }

  private static ProofAuditIssue issue(
      String claimId,
      String stepId,
      String issueId,
      ProofRepairability repairability,
      boolean touchesStatement) {
    return new ProofAuditIssue(
        issueId,
        claimId,
        stepId,
        "premise",
        "conclusion",
        ProofIssueKind.MISSING_JUSTIFICATION,
        repairability,
        List.of(),
        touchesStatement,
        "repair boundary");
  }

  private static ClaimProofAuditDecision audit(
      String claimId, ClaimProofAuditVerdict verdict, List<ProofAuditIssue> issues) {
    return new ClaimProofAuditDecision(claimId, verdict, issues, "audit result");
  }

  private static ClaimProofPatchOperation operation(
      String id,
      ClaimProofPatchOperationType type,
      String target,
      String replacementStatement,
      String replacementJustification,
      ProofStep inserted,
      String dependencyClaimId,
      EvidenceRef evidenceRef) {
    return new ClaimProofPatchOperation(
        id,
        type,
        target,
        replacementStatement,
        replacementJustification,
        inserted,
        dependencyClaimId,
        evidenceRef);
  }

  private static ClaimProofPatch patch(
      FrozenClaimSnapshot frozen,
      ClaimProofRevisionRecord base,
      List<String> issueIds,
      List<String> changedStepIds,
      List<ClaimProofPatchOperation> operations,
      List<String> resolvedIssueIds) {
    return new ClaimProofPatch(
        "patch-" + frozen.claimId(),
        frozen.claimId(),
        frozen.claimSemanticHash(),
        base.revisionId(),
        base.proofHash(),
        issueIds,
        changedStepIds,
        operations,
        resolvedIssueIds);
  }

  private static ClaimProofPatch copyPatch(
      ClaimProofPatch source,
      String claimId,
      String semanticHash,
      String revisionId,
      String proofHash) {
    return new ClaimProofPatch(
        source.patchId(),
        claimId == null ? source.claimId() : claimId,
        semanticHash == null ? source.claimSemanticHash() : semanticHash,
        revisionId == null ? source.baseProofRevisionId() : revisionId,
        proofHash == null ? source.baseProofHash() : proofHash,
        source.issueIds(),
        source.changedStepIds(),
        source.operations(),
        source.expectedResolvedIssueIds());
  }
}
