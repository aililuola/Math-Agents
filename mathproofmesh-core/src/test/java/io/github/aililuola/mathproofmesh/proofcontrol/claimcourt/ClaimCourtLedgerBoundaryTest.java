package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ClaimBlindAdjudicationVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementAssessment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ClaimCourtLedgerBoundaryTest {
  @Test
  void decisionServiceKeepsStatementTruthSeparateFromProofQuality() {
    ClaimCourtDecisionService decisions = new ClaimCourtDecisionService();

    assertThat(
            decisions.afterStatementAndAudit(
                ClaimStatementAssessment.REFUTED_BY_VERIFIED_EVIDENCE,
                ClaimProofAuditVerdict.VALID))
        .isEqualTo(ClaimCourtOutcome.REFUTED);
    assertThat(
            decisions.afterStatementAndAudit(
                ClaimStatementAssessment.INCONCLUSIVE, ClaimProofAuditVerdict.VALID))
        .isEqualTo(ClaimCourtOutcome.INCONCLUSIVE);
    assertThat(
            decisions.afterStatementAndAudit(
                ClaimStatementAssessment.OPEN_NO_VERIFIED_REFUTATION,
                ClaimProofAuditVerdict.INCONCLUSIVE))
        .isEqualTo(ClaimCourtOutcome.INCONCLUSIVE);
    assertThat(
            decisions.afterStatementAndAudit(
                ClaimStatementAssessment.OPEN_NO_VERIFIED_REFUTATION,
                ClaimProofAuditVerdict.INVALID_UNREPAIRABLE))
        .isEqualTo(ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN);
    assertThat(
            decisions.afterStatementAndAudit(
                ClaimStatementAssessment.OPEN_NO_VERIFIED_REFUTATION,
                ClaimProofAuditVerdict.VALID))
        .isNull();
    assertThat(
            decisions.afterStatementAndAudit(
                ClaimStatementAssessment.OPEN_NO_VERIFIED_REFUTATION,
                ClaimProofAuditVerdict.INVALID_REPAIRABLE))
        .isNull();

    assertThat(decisions.afterBlindAdjudication(ClaimBlindAdjudicationVerdict.PASS, false))
        .isEqualTo(ClaimCourtOutcome.VERIFIED);
    assertThat(decisions.afterBlindAdjudication(ClaimBlindAdjudicationVerdict.FAIL_PROOF, false))
        .isEqualTo(ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN);
    assertThat(decisions.afterBlindAdjudication(ClaimBlindAdjudicationVerdict.FAIL_PROOF, true))
        .isEqualTo(ClaimCourtOutcome.REPAIR_EXHAUSTED);
    assertThat(decisions.afterBlindAdjudication(ClaimBlindAdjudicationVerdict.INCONCLUSIVE, false))
        .isEqualTo(ClaimCourtOutcome.INCONCLUSIVE);
    assertThat(
            decisions.afterBlindAdjudication(
                ClaimBlindAdjudicationVerdict.COUNTEREXAMPLE_CANDIDATE, false))
        .isEqualTo(ClaimCourtOutcome.INCONCLUSIVE);
    assertThatThrownBy(() -> decisions.afterBlindAdjudication(null, false))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void ledgerExercisesEverySupportedTerminalPath() {
    assertThat(statementRefuted().outcome()).isEqualTo(ClaimCourtOutcome.REFUTED);
    assertThat(statementInconclusive().outcome()).isEqualTo(ClaimCourtOutcome.INCONCLUSIVE);
    assertThat(auditOnly(ClaimProofAuditVerdict.INVALID_UNREPAIRABLE).outcome())
        .isEqualTo(ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN);
    assertThat(auditOnly(ClaimProofAuditVerdict.INCONCLUSIVE).outcome())
        .isEqualTo(ClaimCourtOutcome.INCONCLUSIVE);

    assertThat(blindOutcome(ClaimBlindAdjudicationVerdict.PASS).outcome())
        .isEqualTo(ClaimCourtOutcome.VERIFIED);
    assertThat(blindOutcome(ClaimBlindAdjudicationVerdict.FAIL_PROOF).outcome())
        .isEqualTo(ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN);
    assertThat(blindOutcome(ClaimBlindAdjudicationVerdict.INCONCLUSIVE).outcome())
        .isEqualTo(ClaimCourtOutcome.INCONCLUSIVE);
    assertThat(blindOutcome(ClaimBlindAdjudicationVerdict.COUNTEREXAMPLE_CANDIDATE).outcome())
        .isEqualTo(ClaimCourtOutcome.INCONCLUSIVE);

    ClaimCourtLedger repaired = openAuditCase("repair-success");
    String repairedCaseId = repaired.records().getFirst().courtCaseId();
    repaired.recordProofAudit(
        repairedCaseId, "audit-repair", audit("repair-success", ClaimProofAuditVerdict.INVALID_REPAIRABLE));
    repaired.beginRepair(repairedCaseId, ClaimCourtConfig.defaults());
    repaired.recordRepairedRevision(repairedCaseId, "revision-repaired");
    repaired.beginBlindAdjudication(repairedCaseId);
    assertThat(repaired.recordBlindAdjudication(repairedCaseId, ClaimBlindAdjudicationVerdict.PASS).outcome())
        .isEqualTo(ClaimCourtOutcome.VERIFIED);

    ClaimCourtLedger repairedRejected = openAuditCase("repair-rejected");
    String rejectedCaseId = repairedRejected.records().getFirst().courtCaseId();
    repairedRejected.recordProofAudit(
        rejectedCaseId,
        "audit-repair-rejected",
        audit("repair-rejected", ClaimProofAuditVerdict.INVALID_REPAIRABLE));
    repairedRejected.beginRepair(rejectedCaseId, ClaimCourtConfig.defaults());
    repairedRejected.recordRepairedRevision(rejectedCaseId, "revision-rejected");
    repairedRejected.beginBlindAdjudication(rejectedCaseId);
    assertThat(
            repairedRejected
                .recordBlindAdjudication(rejectedCaseId, ClaimBlindAdjudicationVerdict.FAIL_PROOF)
                .outcome())
        .isEqualTo(ClaimCourtOutcome.REPAIR_EXHAUSTED);

    ClaimCourtLedger exhaustedAtAdmission = openAuditCase("repair-zero");
    String exhaustedCaseId = exhaustedAtAdmission.records().getFirst().courtCaseId();
    exhaustedAtAdmission.recordProofAudit(
        exhaustedCaseId,
        "audit-zero",
        audit("repair-zero", ClaimProofAuditVerdict.INVALID_REPAIRABLE));
    ClaimCourtRecord exhausted =
        exhaustedAtAdmission.beginRepair(
            exhaustedCaseId, new ClaimCourtConfig(0, 3, 0.35d, 3, true));
    assertThat(exhausted.outcome()).isEqualTo(ClaimCourtOutcome.REPAIR_EXHAUSTED);
    assertThat(
            exhaustedAtAdmission.beginRepair(
                exhaustedCaseId, new ClaimCourtConfig(0, 3, 0.35d, 3, true)))
        .isSameAs(exhausted);

    assertThat(repairFailure(false).outcome())
        .isEqualTo(ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN);
    assertThat(repairFailure(true).outcome()).isEqualTo(ClaimCourtOutcome.REPAIR_EXHAUSTED);
  }

  @Test
  void ledgerIsIdempotentCheckpointableAndFailClosedAtItsBoundaries() {
    var claim = ClaimCourtTestFixtures.linearClaim();
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimCourtLedger ledger = new ClaimCourtLedger();
    ClaimCourtRecord opened = ledger.open(frozen, ClaimCourtTestFixtures.roles());
    assertThat(ledger.open(frozen, ClaimCourtTestFixtures.roles())).isSameAs(opened);
    assertThat(ledger.records()).containsExactly(opened);
    assertThat(ledger.get(frozen.courtCaseId())).isSameAs(opened);
    assertThat(ledger.stableHash()).isNotBlank();

    ClaimCourtRecord deferred = ledger.defer(frozen.courtCaseId(), "provider role unavailable");
    assertThat(ledger.defer(frozen.courtCaseId(), "ignored repeat")).isSameAs(deferred);
    assertThatThrownBy(() -> ledger.beginStatementScreening(frozen.courtCaseId()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ledger.get("missing-case"))
        .isInstanceOf(IllegalArgumentException.class);

    ClaimCourtLedger unavailable = new ClaimCourtLedger();
    ClaimCourtRecord unavailableRecord = unavailable.deferIndependence(frozen);
    assertThat(unavailable.deferIndependence(frozen)).isSameAs(unavailableRecord);

    FrozenClaimSnapshot changed = withSemanticHash(frozen, "changed-semantic-hash");
    assertThatThrownBy(() -> ledger.open(changed, ClaimCourtTestFixtures.roles()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("identity collision");
    assertThatThrownBy(() -> unavailable.deferIndependence(changed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("identity collision");

    ClaimCourtLedger restored = new ClaimCourtLedger();
    restored.restore(ledger.snapshot());
    assertThat(restored.stableHash()).isEqualTo(ledger.stableHash());
    restored.restore(null);
    assertThat(restored.records()).isEmpty();

    Map<String, ClaimCourtRecord> wrongKey = new LinkedHashMap<>();
    wrongKey.put("wrong-key", opened);
    ClaimCourtSnapshot malformed =
        new ClaimCourtSnapshot(ClaimCourtSnapshot.CURRENT_SCHEMA_VERSION, wrongKey, List.of());
    assertThatThrownBy(() -> restored.restore(malformed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("key mismatch");
  }

  @Test
  void independentlyVerifiedRefutationMustExactlyMatchTheFrozenClaim() {
    var claim = ClaimCourtTestFixtures.linearClaim();
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimCourtLedger ledger = new ClaimCourtLedger();
    ledger.open(frozen, ClaimCourtTestFixtures.roles());

    assertThatThrownBy(
            () -> ledger.recordVerifiedRefutation(frozen.courtCaseId(), List.of(), "empty"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ledger.recordVerifiedRefutation(
                    frozen.courtCaseId(), List.of(evidence(frozen, false, true)), "untrusted"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ledger.recordVerifiedRefutation(
                    frozen.courtCaseId(), List.of(evidence(frozen, true, false)), "not replayed"))
        .isInstanceOf(IllegalArgumentException.class);

    ClaimCourtRecord refuted =
        ledger.recordVerifiedRefutation(
            frozen.courtCaseId(), List.of(evidence(frozen, true, true)), "verified witness");
    assertThat(refuted.outcome()).isEqualTo(ClaimCourtOutcome.REFUTED);
    assertThat(refuted.refutationEvidenceIds()).containsExactly("evidence-1");
    assertThat(
            ledger.recordVerifiedRefutation(
                frozen.courtCaseId(), List.of(evidence(frozen, true, true)), "repeat"))
        .isSameAs(refuted);
  }

  private static ClaimCourtRecord statementRefuted() {
    String claimId = "statement-refuted";
    ClaimCourtLedger ledger = openScreeningCase(claimId);
    return ledger.recordStatementAssessment(
        ledger.records().getFirst().courtCaseId(),
        new ClaimStatementAuthorityService.Result(
            ClaimStatementAssessment.REFUTED_BY_VERIFIED_EVIDENCE,
            List.of("evidence-1"),
            "verified"));
  }

  private static ClaimCourtRecord statementInconclusive() {
    String claimId = "statement-inconclusive";
    ClaimCourtLedger ledger = openScreeningCase(claimId);
    return ledger.recordStatementAssessment(
        ledger.records().getFirst().courtCaseId(),
        new ClaimStatementAuthorityService.Result(
            ClaimStatementAssessment.INCONCLUSIVE, List.of(), "inconclusive"));
  }

  private static ClaimCourtRecord auditOnly(ClaimProofAuditVerdict verdict) {
    String claimId = "audit-" + verdict.name().toLowerCase(java.util.Locale.ROOT);
    ClaimCourtLedger ledger = openAuditCase(claimId);
    return ledger.recordProofAudit(
        ledger.records().getFirst().courtCaseId(), "audit-id", audit(claimId, verdict));
  }

  private static ClaimCourtRecord blindOutcome(ClaimBlindAdjudicationVerdict verdict) {
    String claimId = "blind-" + verdict.name().toLowerCase(java.util.Locale.ROOT);
    ClaimCourtLedger ledger = openAuditCase(claimId);
    String caseId = ledger.records().getFirst().courtCaseId();
    ledger.recordProofAudit(caseId, "audit-valid", audit(claimId, ClaimProofAuditVerdict.VALID));
    ledger.beginBlindAdjudication(caseId);
    return ledger.recordBlindAdjudication(caseId, verdict);
  }

  private static ClaimCourtRecord repairFailure(boolean exhausted) {
    String claimId = "repair-failure-" + exhausted;
    ClaimCourtLedger ledger = openAuditCase(claimId);
    String caseId = ledger.records().getFirst().courtCaseId();
    ledger.recordProofAudit(
        caseId, "audit-repair", audit(claimId, ClaimProofAuditVerdict.INVALID_REPAIRABLE));
    ledger.beginRepair(caseId, ClaimCourtConfig.defaults());
    return ledger.recordRepairFailure(caseId, exhausted, "repair failed");
  }

  private static ClaimCourtLedger openScreeningCase(String claimId) {
    ClaimCourtLedger ledger = new ClaimCourtLedger();
    FrozenClaimSnapshot frozen = frozen(claimId);
    ledger.open(frozen, ClaimCourtTestFixtures.roles());
    ledger.beginStatementScreening(frozen.courtCaseId());
    return ledger;
  }

  private static ClaimCourtLedger openAuditCase(String claimId) {
    ClaimCourtLedger ledger = openScreeningCase(claimId);
    ledger.recordStatementAssessment(
        ledger.records().getFirst().courtCaseId(),
        new ClaimStatementAuthorityService.Result(
            ClaimStatementAssessment.OPEN_NO_VERIFIED_REFUTATION,
            List.of(),
            "statement remains open"));
    return ledger;
  }

  private static FrozenClaimSnapshot frozen(String claimId) {
    var claim =
        ClaimCourtTestFixtures.claim(
            claimId,
            "Statement for " + claimId,
            "Conclusion for " + claimId,
            List.of("assumption"),
            List.of(ClaimCourtTestFixtures.step("step-" + claimId, "result", "reason")));
    return ClaimCourtTestFixtures.freeze(claim);
  }

  private static ClaimProofAuditDecision audit(String claimId, ClaimProofAuditVerdict verdict) {
    return new ClaimProofAuditDecision(
        claimId,
        verdict,
        verdict == ClaimProofAuditVerdict.INVALID_REPAIRABLE
                || verdict == ClaimProofAuditVerdict.INVALID_UNREPAIRABLE
            ? List.of(ClaimCourtRepairTestFixtures.issue(claimId, "step-" + claimId, "issue-1"))
            : List.of(),
        "audit result");
  }

  private static ClaimRefutationEvidence evidence(
      FrozenClaimSnapshot frozen, boolean trusted, boolean replayValid) {
    return new ClaimRefutationEvidence(
        "evidence-1",
        ClaimRefutationEvidenceType.REPLAYED_COMPUTATION,
        frozen.claimId(),
        frozen.claimStatementHash(),
        frozen.claimSemanticHash(),
        "witness",
        "artifact://witness",
        replayValid,
        trusted);
  }

  private static FrozenClaimSnapshot withSemanticHash(
      FrozenClaimSnapshot frozen, String semanticHash) {
    return new FrozenClaimSnapshot(
        frozen.courtCaseId(),
        frozen.problemHash(),
        frozen.rootGoalHash(),
        frozen.claimId(),
        frozen.claimStatementHash(),
        semanticHash,
        frozen.statement(),
        frozen.conclusion(),
        frozen.assumptions(),
        frozen.quantifiers(),
        frozen.variableBindings(),
        frozen.scopeLimitations(),
        frozen.polarity(),
        frozen.dependencyClaimIds(),
        frozen.dependencySnapshotHash(),
        frozen.initialProofRevisionId(),
        frozen.sourceAttemptId(),
        frozen.sourceRouteId(),
        frozen.authorAgentId());
  }
}
