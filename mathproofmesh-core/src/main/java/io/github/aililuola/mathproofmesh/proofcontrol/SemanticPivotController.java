package io.github.aililuola.mathproofmesh.proofcontrol;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewBatch;
import java.util.List;

/** Coordinates deterministic audit, independent review, and rollback-safe semantic apply. */
public final class SemanticPivotController {
  private final SemanticPivotDeterministicAuditor auditor;
  private final SemanticPivotReviewValidator reviews;
  private final SemanticPivotLedger ledger;

  public SemanticPivotController() {
    this(
        new SemanticPivotDeterministicAuditor(),
        new SemanticPivotReviewValidator(),
        new SemanticPivotLedger());
  }

  public SemanticPivotController(
      SemanticPivotDeterministicAuditor auditor,
      SemanticPivotReviewValidator reviews,
      SemanticPivotLedger ledger) {
    this.auditor = java.util.Objects.requireNonNull(auditor, "auditor");
    this.reviews = java.util.Objects.requireNonNull(reviews, "reviews");
    this.ledger = java.util.Objects.requireNonNull(ledger, "ledger");
  }

  public synchronized Preparation prepare(
      PivotDelta delta,
      PivotStructuralSignature sourceSignature,
      PivotStructuralSignature proposedSignature,
      PivotAuthorityContext authority,
      String proposerAgentId,
      SemanticPivotReviewBatch review,
      double reviewThreshold) {
    return prepare(
        delta,
        sourceSignature,
        proposedSignature,
        authority,
        proposerAgentId,
        review,
        reviewThreshold,
        List::of);
  }

  public synchronized Preparation prepare(
      PivotDelta delta,
      PivotStructuralSignature sourceSignature,
      PivotStructuralSignature proposedSignature,
      PivotAuthorityContext authority,
      String proposerAgentId,
      SemanticPivotReviewBatch review,
      double reviewThreshold,
      ExternalGate externalGate) {
    SemanticPivotRecord proposed = ledger.propose(delta, proposerAgentId);
    if (proposed.status() != PivotDeltaStatus.PROPOSED) {
      return preparationFromRecord(proposed);
    }
    PivotDeltaAudit deterministic =
        auditor.audit(delta, sourceSignature, proposedSignature, authority);
    SemanticPivotRecord audited = ledger.recordDeterministicAudit(deterministic);
    if (!deterministic.passed()) {
      return new Preparation(null, audited, deterministic.failureCodes());
    }
    SemanticPivotReviewValidator.ReviewValidation validation =
        reviews.validate(delta.pivotId(), proposerAgentId, review, reviewThreshold);
    if (!validation.accepted()) {
      SemanticPivotRecord reviewed =
          ledger.recordReview(
              delta.pivotId(),
              review == null ? "missing-reviewer" : review.reviewerAgentId(),
              validation.decision(),
              false);
      return new Preparation(null, reviewed, validation.failureCodes());
    }
    List<String> gateFailures =
        java.util.Objects.requireNonNull(externalGate, "externalGate").evaluate();
    if (gateFailures != null && !gateFailures.isEmpty()) {
      SemanticPivotRecord rejected =
          ledger.rejectAdmission(
              delta.pivotId(),
              review.reviewerAgentId(),
              validation.decision(),
              gateFailures);
      return new Preparation(null, rejected, gateFailures);
    }
    SemanticPivotRecord reviewed =
        ledger.recordReview(
            delta.pivotId(),
            review.reviewerAgentId(),
            validation.decision(),
            true);
    List<String> obligations =
        delta.obligationChanges().stream()
            .filter(change -> change.action() == PivotObligationAction.ADD_NEW_OBLIGATION)
            .map(PivotObligationChange::obligationId)
            .toList();
    SemanticPivotApplyPlan plan =
        new SemanticPivotApplyPlan(
            null,
            delta,
            deterministic,
            validation.decision(),
            proposerAgentId,
            review.reviewerAgentId(),
            obligations,
            delta.proposedStrategyId());
    return new Preparation(plan, reviewed, List.of());
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification = "Atomic apply restores the ledger snapshot before propagating mutation failure.")
  public synchronized SemanticPivotRecord apply(
      SemanticPivotApplyPlan plan, AtomicMutation mutation) {
    java.util.Objects.requireNonNull(plan, "plan");
    java.util.Objects.requireNonNull(mutation, "mutation");
    SemanticPivotRecord current = ledger.get(plan.delta().pivotId());
    if (current.status() == PivotDeltaStatus.APPLIED
        || current.status() == PivotDeltaStatus.EVALUATED) {
      return current;
    }
    SemanticPivotSnapshot before = ledger.snapshot();
    try {
      ledger.stageApply(plan.delta().pivotId());
      SemanticPivotApplyReceipt receipt = mutation.apply(plan);
      return ledger.commitApply(receipt);
    } catch (RuntimeException exception) {
      ledger.restore(before);
      throw exception;
    }
  }

  public SemanticPivotLedger ledger() {
    return ledger;
  }

  private static Preparation preparationFromRecord(SemanticPivotRecord record) {
    if (record.status() == PivotDeltaStatus.ADMITTED
        && record.deterministicAudit() != null
        && record.reviewDecision() != null) {
      List<String> obligations =
          record.delta().obligationChanges().stream()
              .filter(change -> change.action() == PivotObligationAction.ADD_NEW_OBLIGATION)
              .map(PivotObligationChange::obligationId)
              .toList();
      return new Preparation(
          new SemanticPivotApplyPlan(
              null,
              record.delta(),
              record.deterministicAudit(),
              record.reviewDecision(),
              record.proposerAgentId(),
              record.reviewerAgentId(),
              obligations,
              record.delta().proposedStrategyId()),
          record,
          List.of());
    }
    return new Preparation(null, record, List.of("PIVOT_ALREADY_FINALIZED"));
  }

  @FunctionalInterface
  public interface AtomicMutation {
    SemanticPivotApplyReceipt apply(SemanticPivotApplyPlan plan);
  }

  @FunctionalInterface
  public interface ExternalGate {
    List<String> evaluate();
  }

  public record Preparation(
      SemanticPivotApplyPlan plan, SemanticPivotRecord record, List<String> failureCodes) {
    public Preparation {
      record = java.util.Objects.requireNonNull(record, "record");
      failureCodes = PivotValues.copy(failureCodes);
    }

    public boolean admitted() {
      return plan != null && failureCodes.isEmpty();
    }

    @Override
    public List<String> failureCodes() {
      return List.copyOf(failureCodes);
    }
  }
}
