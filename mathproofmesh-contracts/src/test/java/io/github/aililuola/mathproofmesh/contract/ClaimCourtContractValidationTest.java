package io.github.aililuola.mathproofmesh.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClaimCourtContractValidationTest {
  @Test
  void statementContractsDefaultSafelyAndRejectAmbiguousTargets() {
    StatementCounterexampleCandidate candidate = candidate(null, CLAIM_ID);
    assertNotNull(candidate.candidateId());
    assertEquals(List.of(), candidate.assumptions());
    assertEquals(List.of(), candidate.quantifiers());
    assertEquals(List.of(), candidate.scopeLimitations());
    assertEquals(List.of(), candidate.evidenceRefs());

    StatementCounterexampleCandidate explicit =
        new StatementCounterexampleCandidate(
            "candidate-explicit",
            CLAIM_ID,
            "statement-hash",
            "path graph on four vertices",
            List.of("finite graph"),
            List.of(new QuantifierSpec("G", "finite graphs", "forall", 0, List.of(), "G")),
            List.of("connected graphs"),
            "positive",
            List.of("artifact://counterexample"));
    assertEquals("candidate-explicit", explicit.candidateId());
    assertThrows(UnsupportedOperationException.class, () -> explicit.assumptions().add("late"));

    ClaimStatementFalsificationDecision open =
        new ClaimStatementFalsificationDecision(
            CLAIM_ID,
            StatementFalsificationDisposition.NO_COUNTEREXAMPLE_FOUND,
            null,
            "no witness found");
    ClaimStatementFalsificationDecision proposed =
        new ClaimStatementFalsificationDecision(
            CLAIM_ID,
            StatementFalsificationDisposition.COUNTEREXAMPLE_CANDIDATE,
            List.of(candidate),
            "candidate only");
    assertEquals(List.of(), open.counterexampleCandidates());
    assertEquals(List.of(candidate), proposed.counterexampleCandidates());
    assertThrows(
        ContractValidationException.class,
        () ->
            new ClaimStatementFalsificationDecision(
                CLAIM_ID,
                StatementFalsificationDisposition.COUNTEREXAMPLE_CANDIDATE,
                List.of(),
                "missing candidate"));
    assertThrows(
        ContractValidationException.class,
        () ->
            new ClaimStatementFalsificationDecision(
                CLAIM_ID,
                StatementFalsificationDisposition.INCONCLUSIVE,
                List.of(candidate("candidate-other", "other-claim")),
                "wrong target"));

    ClaimStatementFalsificationBatch generated =
        new ClaimStatementFalsificationBatch(
            null, "falsifier", "route", "attempt", List.of(open), null, null);
    ClaimStatementFalsificationBatch explicitBatch =
        new ClaimStatementFalsificationBatch(
            "statement-batch",
            "falsifier",
            "route",
            "attempt",
            List.of(proposed),
            "artifact://statement",
            new UsageRecord());
    assertNotNull(generated.batchId());
    assertEquals("statement-batch", explicitBatch.batchId());
    assertThrows(
        UnsupportedOperationException.class,
        () -> explicitBatch.decisions().add(open));
    assertThrows(
        ContractValidationException.class,
        () ->
            new ClaimStatementFalsificationBatch(
                "duplicate",
                "falsifier",
                "route",
                "attempt",
                List.of(open, proposed),
                null,
                new UsageRecord()));
  }

  @Test
  void proofAuditContractsRequireBoundIssuesAndExerciseEveryTaxonomyValue() {
    ProofAuditIssue issue = issue(null, CLAIM_ID, ProofRepairability.LOCAL_PATCH);
    ProofAuditIssue explicit =
        new ProofAuditIssue(
            "issue-explicit",
            CLAIM_ID,
            "step-1",
            "T(x)=T(y)",
            "x=y",
            ProofIssueKind.MISSING_JUSTIFICATION,
            ProofRepairability.VERIFIED_DEPENDENCY_PATCH,
            List.of("verified-kernel-claim"),
            true,
            "the implication omitted its kernel argument");
    assertNotNull(issue.issueId());
    assertFalse(issue.touchesClaimStatement());
    assertTrue(explicit.touchesClaimStatement());
    assertThrows(
        UnsupportedOperationException.class,
        () -> explicit.requiredVerifiedDependencyIds().add("late"));

    ClaimProofAuditDecision valid =
        new ClaimProofAuditDecision(CLAIM_ID, ClaimProofAuditVerdict.VALID, null, "valid");
    ClaimProofAuditDecision repairable =
        new ClaimProofAuditDecision(
            CLAIM_ID,
            ClaimProofAuditVerdict.INVALID_REPAIRABLE,
            List.of(issue),
            "local patch");
    ClaimProofAuditDecision unrepairable =
        new ClaimProofAuditDecision(
            CLAIM_ID,
            ClaimProofAuditVerdict.INVALID_UNREPAIRABLE,
            List.of(explicit),
            "rewrite required");
    assertEquals(List.of(), valid.issues());
    assertEquals(List.of(issue), repairable.issues());
    assertEquals(List.of(explicit), unrepairable.issues());
    assertThrows(
        ContractValidationException.class,
        () ->
            new ClaimProofAuditDecision(
                CLAIM_ID,
                ClaimProofAuditVerdict.INVALID_REPAIRABLE,
                List.of(),
                "missing issues"));
    assertThrows(
        ContractValidationException.class,
        () ->
            new ClaimProofAuditDecision(
                CLAIM_ID,
                ClaimProofAuditVerdict.INVALID_UNREPAIRABLE,
                List.of(issue("other-issue", "other-claim", ProofRepairability.NOT_REPAIRABLE)),
                "wrong claim"));

    ClaimProofAuditBatch generated =
        new ClaimProofAuditBatch(
            null, "auditor", "route", "attempt", List.of(valid), null, null);
    ClaimProofAuditBatch explicitBatch =
        new ClaimProofAuditBatch(
            "audit-batch",
            "auditor",
            "route",
            "attempt",
            List.of(repairable),
            "artifact://audit",
            new UsageRecord());
    assertNotNull(generated.batchId());
    assertEquals("audit-batch", explicitBatch.batchId());
    assertThrows(
        UnsupportedOperationException.class,
        () -> explicitBatch.decisions().add(unrepairable));
    assertThrows(
        ContractValidationException.class,
        () ->
            new ClaimProofAuditBatch(
                "duplicate",
                "auditor",
                "route",
                "attempt",
                List.of(repairable, unrepairable),
                null,
                new UsageRecord()));

    assertEquals(13, ProofIssueKind.values().length);
    assertEquals(6, ProofRepairability.values().length);
    assertEquals(4, ClaimProofAuditVerdict.values().length);
    assertEquals(5, ClaimProofStatus.values().length);
    assertEquals(2, ClaimStatementStatus.values().length);
    assertEquals(3, ClaimStatementAssessment.values().length);
    assertEquals(3, StatementFalsificationDisposition.values().length);
    assertEquals(6, ClaimCourtOutcome.values().length);
  }

  @Test
  void proofPatchContractsCoverEveryBoundedOperationAndRejectMalformedPatches() {
    ProofStep inserted = proofStep();
    EvidenceRef evidence = new EvidenceRef("artifact://proof", "hash", "section", "verified");
    List<ClaimProofPatchOperation> operations =
        List.of(
            operation(
                null,
                ClaimProofPatchOperationType.REPLACE_STEP_JUSTIFICATION,
                null,
                "kernel argument",
                null,
                null,
                null),
            operation(
                "replace-statement",
                ClaimProofPatchOperationType.REPLACE_STEP_STATEMENT,
                "T(x-y)=0",
                null,
                null,
                null,
                null),
            operation(
                "insert-before",
                ClaimProofPatchOperationType.INSERT_STEP_BEFORE,
                null,
                null,
                inserted,
                null,
                null),
            operation(
                "insert-after",
                ClaimProofPatchOperationType.INSERT_STEP_AFTER,
                null,
                null,
                inserted,
                null,
                null),
            operation(
                "delete",
                ClaimProofPatchOperationType.DELETE_REDUNDANT_STEP,
                null,
                null,
                null,
                null,
                null),
            operation(
                "rebind",
                ClaimProofPatchOperationType.REBIND_VERIFIED_DEPENDENCY,
                null,
                null,
                null,
                "verified-kernel-claim",
                null),
            operation(
                "evidence",
                ClaimProofPatchOperationType.ADD_VERIFIED_EVIDENCE_REF,
                null,
                null,
                null,
                null,
                evidence));
    ClaimProofPatch patch = patch(null, CLAIM_ID, operations);
    ClaimProofPatch explicit = patch("patch-explicit", CLAIM_ID, List.of(operations.get(0)));
    assertNotNull(patch.patchId());
    assertEquals("patch-explicit", explicit.patchId());
    assertThrows(UnsupportedOperationException.class, () -> patch.issueIds().add("late"));
    assertThrows(UnsupportedOperationException.class, () -> patch.changedStepIds().add("late"));
    assertThrows(UnsupportedOperationException.class, () -> patch.operations().add(operations.get(0)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> patch.expectedResolvedIssueIds().add("late"));

    assertThrows(
        ContractValidationException.class,
        () -> patch("empty", CLAIM_ID, List.of()));
    assertThrows(
        ContractValidationException.class,
        () ->
            new ClaimProofPatch(
                "duplicate-issues",
                CLAIM_ID,
                "semantic-hash",
                "revision-0",
                "proof-hash",
                List.of("issue-1", "issue-1"),
                List.of("step-1"),
                List.of(operations.get(0)),
                List.of("issue-1")));
    assertThrows(
        ContractValidationException.class,
        () ->
            new ClaimProofPatch(
                "duplicate-steps",
                CLAIM_ID,
                "semantic-hash",
                "revision-0",
                "proof-hash",
                List.of("issue-1"),
                List.of("step-1", "step-1"),
                List.of(operations.get(0)),
                List.of("issue-1")));
    assertThrows(
        ContractValidationException.class,
        () ->
            new ClaimProofPatch(
                "duplicate-resolved",
                CLAIM_ID,
                "semantic-hash",
                "revision-0",
                "proof-hash",
                List.of("issue-1"),
                List.of("step-1"),
                List.of(operations.get(0)),
                List.of("issue-1", "issue-1")));

    for (ClaimProofPatchOperationType type : ClaimProofPatchOperationType.values()) {
      assertNotNull(type);
    }
    assertMissingOperationPayload(ClaimProofPatchOperationType.REPLACE_STEP_JUSTIFICATION);
    assertMissingOperationPayload(ClaimProofPatchOperationType.REPLACE_STEP_STATEMENT);
    assertMissingOperationPayload(ClaimProofPatchOperationType.INSERT_STEP_BEFORE);
    assertMissingOperationPayload(ClaimProofPatchOperationType.INSERT_STEP_AFTER);
    assertMissingOperationPayload(ClaimProofPatchOperationType.REBIND_VERIFIED_DEPENDENCY);
    assertMissingOperationPayload(ClaimProofPatchOperationType.ADD_VERIFIED_EVIDENCE_REF);
  }

  @Test
  void repairBlindAndWitnessBatchesEnforceSingleClaimAuthority() {
    ClaimProofPatch patch =
        patch(
            "patch",
            CLAIM_ID,
            List.of(
                operation(
                    "replace",
                    ClaimProofPatchOperationType.REPLACE_STEP_JUSTIFICATION,
                    null,
                    "complete kernel argument",
                    null,
                    null,
                    null)));
    ClaimMinimalRepairDecision proposed =
        new ClaimMinimalRepairDecision(
            CLAIM_ID, ClaimMinimalRepairDisposition.PATCH_PROPOSED, patch, "bounded patch");
    ClaimMinimalRepairDecision declined =
        new ClaimMinimalRepairDecision(
            CLAIM_ID, ClaimMinimalRepairDisposition.REPAIR_DECLINED, null, "declined");
    assertThrows(
        ContractValidationException.class,
        () ->
            new ClaimMinimalRepairDecision(
                CLAIM_ID,
                ClaimMinimalRepairDisposition.PATCH_PROPOSED,
                null,
                "missing patch"));
    assertThrows(
        ContractValidationException.class,
        () ->
            new ClaimMinimalRepairDecision(
                "other-claim",
                ClaimMinimalRepairDisposition.PATCH_PROPOSED,
                patch,
                "wrong target"));

    ClaimMinimalRepairBatch generatedRepair =
        new ClaimMinimalRepairBatch(
            null, "repairer", "route", "attempt", List.of(proposed), null, null);
    ClaimMinimalRepairBatch explicitRepair =
        new ClaimMinimalRepairBatch(
            "repair-batch",
            "repairer",
            "route",
            "attempt",
            List.of(declined),
            "artifact://repair",
            new UsageRecord());
    assertNotNull(generatedRepair.batchId());
    assertEquals("repair-batch", explicitRepair.batchId());
    assertThrows(
        ContractValidationException.class,
        () ->
            new ClaimMinimalRepairBatch(
                "duplicate",
                "repairer",
                "route",
                "attempt",
                List.of(proposed, declined),
                null,
                new UsageRecord()));
    assertThrows(
        UnsupportedOperationException.class,
        () -> explicitRepair.decisions().add(proposed));

    StatementCounterexampleCandidate candidate = candidate("candidate", CLAIM_ID);
    ClaimBlindAdjudicationDecision pass =
        new ClaimBlindAdjudicationDecision(
            CLAIM_ID, ClaimBlindAdjudicationVerdict.PASS, null, "proof accepted");
    ClaimBlindAdjudicationDecision counterexample =
        new ClaimBlindAdjudicationDecision(
            CLAIM_ID,
            ClaimBlindAdjudicationVerdict.COUNTEREXAMPLE_CANDIDATE,
            List.of(candidate),
            "candidate requires witness review");
    assertThrows(
        ContractValidationException.class,
        () ->
            new ClaimBlindAdjudicationDecision(
                CLAIM_ID,
                ClaimBlindAdjudicationVerdict.COUNTEREXAMPLE_CANDIDATE,
                List.of(),
                "missing candidate"));
    assertThrows(
        ContractValidationException.class,
        () ->
            new ClaimBlindAdjudicationDecision(
                CLAIM_ID,
                ClaimBlindAdjudicationVerdict.INCONCLUSIVE,
                List.of(candidate("other", "other-claim")),
                "wrong target"));
    ClaimBlindAdjudicationBatch generatedBlind =
        new ClaimBlindAdjudicationBatch(
            null, "adjudicator", "route", "attempt", List.of(pass), null, null);
    ClaimBlindAdjudicationBatch explicitBlind =
        new ClaimBlindAdjudicationBatch(
            "blind-batch",
            "adjudicator",
            "route",
            "attempt",
            List.of(counterexample),
            "artifact://blind",
            new UsageRecord());
    assertNotNull(generatedBlind.batchId());
    assertEquals("blind-batch", explicitBlind.batchId());
    assertThrows(
        ContractValidationException.class,
        () ->
            new ClaimBlindAdjudicationBatch(
                "duplicate",
                "adjudicator",
                "route",
                "attempt",
                List.of(pass, counterexample),
                null,
                new UsageRecord()));
    assertThrows(
        UnsupportedOperationException.class,
        () -> explicitBlind.decisions().add(pass));

    ClaimCounterexampleWitnessReviewDecision accepted =
        witnessDecision(
            VerificationVerdict.PASS, true, true, true, true, true, true);
    assertTrue(accepted.exactWitnessAccepted());
    assertFalse(
        witnessDecision(
                VerificationVerdict.FAIL, true, true, true, true, true, true)
            .exactWitnessAccepted());
    assertFalse(
        witnessDecision(
                VerificationVerdict.PASS, false, true, true, true, true, true)
            .exactWitnessAccepted());
    assertFalse(
        witnessDecision(
                VerificationVerdict.PASS, true, false, true, true, true, true)
            .exactWitnessAccepted());
    assertFalse(
        witnessDecision(
                VerificationVerdict.PASS, true, true, false, true, true, true)
            .exactWitnessAccepted());
    assertFalse(
        witnessDecision(
                VerificationVerdict.PASS, true, true, true, false, true, true)
            .exactWitnessAccepted());
    assertFalse(
        witnessDecision(
                VerificationVerdict.PASS, true, true, true, true, false, true)
            .exactWitnessAccepted());
    assertFalse(
        witnessDecision(
                VerificationVerdict.PASS, true, true, true, true, true, false)
            .exactWitnessAccepted());

    ClaimCounterexampleWitnessReviewBatch generatedWitness =
        new ClaimCounterexampleWitnessReviewBatch(
            null, "witness-reviewer", List.of(accepted), null, null);
    ClaimCounterexampleWitnessReviewBatch explicitWitness =
        new ClaimCounterexampleWitnessReviewBatch(
            "witness-batch",
            "witness-reviewer",
            List.of(accepted),
            "artifact://witness",
            new UsageRecord());
    assertNotNull(generatedWitness.batchId());
    assertEquals("witness-batch", explicitWitness.batchId());
    assertThrows(
        ContractValidationException.class,
        () ->
            new ClaimCounterexampleWitnessReviewBatch(
                "duplicate",
                "witness-reviewer",
                List.of(accepted, accepted),
                null,
                new UsageRecord()));
    assertThrows(
        UnsupportedOperationException.class,
        () -> explicitWitness.decisions().add(accepted));

    assertEquals(5, ClaimMinimalRepairDisposition.values().length);
    assertEquals(4, ClaimBlindAdjudicationVerdict.values().length);
  }

  private static void assertMissingOperationPayload(ClaimProofPatchOperationType type) {
    assertThrows(
        ContractValidationException.class,
        () -> operation("missing-" + type.name(), type, null, null, null, null, null));
  }

  private static ClaimProofPatch patch(
      String patchId, String claimId, List<ClaimProofPatchOperation> operations) {
    return new ClaimProofPatch(
        patchId,
        claimId,
        "semantic-hash",
        "revision-0",
        "proof-hash",
        List.of("issue-1"),
        List.of("step-1"),
        operations,
        List.of("issue-1"));
  }

  private static ClaimProofPatchOperation operation(
      String operationId,
      ClaimProofPatchOperationType type,
      String statement,
      String justification,
      ProofStep inserted,
      String dependencyClaimId,
      EvidenceRef evidenceRef) {
    return new ClaimProofPatchOperation(
        operationId,
        type,
        "step-1",
        statement,
        justification,
        inserted,
        dependencyClaimId,
        evidenceRef);
  }

  private static ProofStep proofStep() {
    return new ProofStep(
        null,
        null,
        null,
        null,
        null,
        1.0d,
        null,
        null,
        true,
        "because the kernel is trivial",
        "x-y is in the kernel",
        "inserted-step",
        "derivation");
  }

  private static ProofAuditIssue issue(
      String issueId, String claimId, ProofRepairability repairability) {
    return new ProofAuditIssue(
        issueId,
        claimId,
        "step-1",
        "T(x)=T(y)",
        "x=y",
        ProofIssueKind.MISSING_JUSTIFICATION,
        repairability,
        null,
        null,
        "missing kernel argument");
  }

  private static StatementCounterexampleCandidate candidate(String candidateId, String claimId) {
    return new StatementCounterexampleCandidate(
        candidateId,
        claimId,
        "statement-hash",
        "path graph on four vertices",
        null,
        null,
        null,
        "positive",
        null);
  }

  private static ClaimCounterexampleWitnessReviewDecision witnessDecision(
      VerificationVerdict verdict,
      boolean witnessValid,
      boolean exactTarget,
      boolean assumptionsMatch,
      boolean quantifiersMatch,
      boolean scopeMatch,
      boolean polarityMatch) {
    return new ClaimCounterexampleWitnessReviewDecision(
        "candidate",
        CLAIM_ID,
        "statement-hash",
        verdict,
        witnessValid,
        exactTarget,
        assumptionsMatch,
        quantifiersMatch,
        scopeMatch,
        polarityMatch,
        "witness review");
  }

  private static final String CLAIM_ID = "claim-1";
}
