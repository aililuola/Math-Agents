package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticPivotLedgerFailureBoundaryTest {
  @Test
  void rejectedAuditAndReviewStatesAreMonotonic() {
    SemanticPivotLedger rejectedAudit = new SemanticPivotLedger();
    PivotDelta textOnly = SemanticPivotTestFixtures.textOnlyDelta();
    rejectedAudit.propose(textOnly, "proposer");
    PivotDeltaAudit failed =
        new SemanticPivotDeterministicAuditor()
            .audit(
                textOnly,
                SemanticPivotTestFixtures.sourceSignature(),
                SemanticPivotTestFixtures.textOnlySignature(),
                SemanticPivotTestFixtures.authority());
    SemanticPivotRecord first = rejectedAudit.recordDeterministicAudit(failed);
    SemanticPivotRecord duplicate = rejectedAudit.recordDeterministicAudit(failed);
    assertThat(first.status()).isEqualTo(PivotDeltaStatus.DETERMINISTICALLY_REJECTED);
    assertThat(duplicate).isEqualTo(first);
    assertThatThrownBy(
            () ->
                rejectedAudit.recordReview(
                    textOnly.pivotId(),
                    "reviewer",
                    SemanticPivotTestFixtures.review(
                            textOnly, "reviewer", VerificationVerdict.FAIL, 0.99d, true)
                        .decisions()
                        .getFirst(),
                    false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not awaiting");

    SemanticPivotLedger rejectedReview = awaitingReviewLedger();
    PivotDelta valid = SemanticPivotTestFixtures.validDelta();
    assertThatThrownBy(
            () ->
                rejectedReview.recordReview(
                    valid.pivotId(),
                    "proposer",
                    SemanticPivotTestFixtures.acceptedReview(valid).decisions().getFirst(),
                    true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differ");
    SemanticPivotRecord reviewRejected =
        rejectedReview.recordReview(
            valid.pivotId(),
            "reviewer",
            SemanticPivotTestFixtures.review(
                    valid, "reviewer", VerificationVerdict.FAIL, 0.99d, true)
                .decisions()
                .getFirst(),
            false);
    assertThat(reviewRejected.status()).isEqualTo(PivotDeltaStatus.REVIEW_REJECTED);
    assertThatThrownBy(() -> rejectedReview.stageApply(valid.pivotId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("admitted");
  }

  @Test
  void applyReceiptAndGainEvaluationRejectEveryInvalidTransition() {
    PivotDelta delta = SemanticPivotTestFixtures.validDelta();
    SemanticPivotLedger admitted = admittedLedger();
    SemanticPivotApplyReceipt receipt =
        SemanticPivotApplyReceipt.applied(
            delta, List.of(SemanticPivotTestFixtures.NEW_OBLIGATION), List.of("task-1"), 4);

    assertThatThrownBy(() -> admitted.commitApply(receipt))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid semantic pivot apply receipt");
    assertThatThrownBy(
            () ->
                admitted.evaluate(
                    delta.pivotId(),
                    ProofControlModels.MetaPivotEffect.EFFECTIVE,
                    "too early"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("only an applied pivot");

    SemanticPivotRecord staged = admitted.stageApply(delta.pivotId());
    assertThat(admitted.stageApply(delta.pivotId())).isEqualTo(staged);
    SemanticPivotApplyReceipt notApplied =
        new SemanticPivotApplyReceipt(
            null,
            delta.pivotId(),
            delta.structuralDeltaHash(),
            delta.routeId(),
            delta.sourceStrategyId(),
            delta.proposedStrategyId(),
            List.of(),
            List.of(),
            4,
            false);
    assertThatThrownBy(() -> admitted.commitApply(notApplied))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid semantic pivot apply receipt");

    SemanticPivotApplyReceipt wrongHash =
        new SemanticPivotApplyReceipt(
            null,
            delta.pivotId(),
            "wrong-hash",
            delta.routeId(),
            delta.sourceStrategyId(),
            delta.proposedStrategyId(),
            List.of(),
            List.of(),
            4,
            true);
    assertThatThrownBy(() -> admitted.commitApply(wrongHash))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid semantic pivot apply receipt");

    SemanticPivotRecord applied = admitted.commitApply(receipt);
    assertThat(admitted.stageApply(delta.pivotId())).isEqualTo(applied);
    assertThat(admitted.commitApply(receipt)).isEqualTo(applied);
    SemanticPivotApplyReceipt differentReceipt =
        SemanticPivotApplyReceipt.applied(
            delta, List.of(SemanticPivotTestFixtures.NEW_OBLIGATION), List.of("task-2"), 5);
    assertThatThrownBy(() -> admitted.commitApply(differentReceipt))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("different receipt");
    assertThatThrownBy(
            () ->
                admitted.evaluate(
                    delta.pivotId(),
                    ProofControlModels.MetaPivotEffect.PROPOSAL_ONLY,
                    "invalid effect"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("only an applied pivot");
    SemanticPivotRecord evaluated =
        admitted.evaluate(
            delta.pivotId(), ProofControlModels.MetaPivotEffect.EFFECTIVE, "verified gain");
    assertThat(admitted.evaluate(
            delta.pivotId(), ProofControlModels.MetaPivotEffect.EFFECTIVE, "duplicate"))
        .isEqualTo(evaluated);
    assertThat(admitted.stageApply(delta.pivotId())).isEqualTo(evaluated);
  }

  @Test
  void authorityRejectionRestoreAndUnknownIdsRemainAuditable() {
    SemanticPivotLedger ledger = awaitingReviewLedger();
    PivotDelta delta = SemanticPivotTestFixtures.validDelta();
    SemanticPivotRecord rejected =
        ledger.rejectAdmission(
            delta.pivotId(),
            "reviewer",
            SemanticPivotTestFixtures.acceptedReview(delta).decisions().getFirst(),
            null);
    assertThat(rejected.status()).isEqualTo(PivotDeltaStatus.FAILED);
    assertThat(ledger.rejectAdmission(
            delta.pivotId(),
            "reviewer",
            SemanticPivotTestFixtures.acceptedReview(delta).decisions().getFirst(),
            List.of("late")))
        .isEqualTo(rejected);

    SemanticPivotLedger empty = new SemanticPivotLedger();
    empty.restore(null);
    assertThat(empty.records()).isEmpty();
    assertThatThrownBy(() -> empty.get("unknown-pivot"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown semantic pivot");

    Map<String, SemanticPivotRecord> wrongKey = new LinkedHashMap<>();
    wrongKey.put("wrong-key", rejected);
    SemanticPivotSnapshot invalid =
        new SemanticPivotSnapshot(
            SemanticPivotSnapshot.CURRENT_SCHEMA_VERSION, wrongKey, List.of());
    assertThatThrownBy(() -> empty.restore(invalid))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("key mismatch");
  }

  @Test
  void materializedNoGainIsAValidFinalEvaluation() {
    PivotDelta delta = SemanticPivotTestFixtures.validDelta();
    SemanticPivotLedger ledger = admittedLedger();
    ledger.stageApply(delta.pivotId());
    ledger.commitApply(SemanticPivotApplyReceipt.applied(delta, List.of(), List.of(), 0));

    assertThat(
            ledger
                .evaluate(
                    delta.pivotId(),
                    ProofControlModels.MetaPivotEffect.MATERIALIZED_NO_GAIN,
                    "no mathematical gain yet")
                .effect())
        .isEqualTo(ProofControlModels.MetaPivotEffect.MATERIALIZED_NO_GAIN);
  }

  private static SemanticPivotLedger awaitingReviewLedger() {
    SemanticPivotLedger ledger = new SemanticPivotLedger();
    PivotDelta delta = SemanticPivotTestFixtures.validDelta();
    ledger.propose(delta, "proposer");
    ledger.recordDeterministicAudit(
        new SemanticPivotDeterministicAuditor()
            .audit(
                delta,
                SemanticPivotTestFixtures.sourceSignature(),
                SemanticPivotTestFixtures.proposedSignature(),
                SemanticPivotTestFixtures.authority()));
    return ledger;
  }

  private static SemanticPivotLedger admittedLedger() {
    SemanticPivotLedger ledger = awaitingReviewLedger();
    PivotDelta delta = SemanticPivotTestFixtures.validDelta();
    ledger.recordReview(
        delta.pivotId(),
        "reviewer",
        SemanticPivotTestFixtures.acceptedReview(delta).decisions().getFirst(),
        true);
    return ledger;
  }
}
