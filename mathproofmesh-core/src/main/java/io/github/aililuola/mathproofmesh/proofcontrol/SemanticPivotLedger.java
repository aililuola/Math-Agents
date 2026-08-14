package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewDecision;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable, monotonic, exactly-once lifecycle for semantic pivot state. */
public final class SemanticPivotLedger {
  private final Map<String, SemanticPivotRecord> records = new LinkedHashMap<>();
  private final List<SemanticPivotAuditEvent> audit = new ArrayList<>();

  public synchronized SemanticPivotRecord propose(PivotDelta delta, String proposerAgentId) {
    java.util.Objects.requireNonNull(delta, "delta");
    SemanticPivotRecord prior = records.get(delta.pivotId());
    if (prior != null) {
      if (!sameSemanticIdentity(prior.delta(), delta)) {
        throw new IllegalStateException("pivot identity collision");
      }
      return prior;
    }
    SemanticPivotRecord record =
        new SemanticPivotRecord(
            delta.pivotId(),
            delta,
            PivotDeltaStatus.PROPOSED,
            proposerAgentId,
            null,
            null,
            null,
            null,
            null,
            0L,
            List.of("proposed"));
    records.put(record.pivotId(), record);
    audit.add(
        new SemanticPivotAuditEvent(
            record.pivotId(), null, record.status(), "proposal compiled", record.version()));
    return record;
  }

  private static boolean sameSemanticIdentity(PivotDelta left, PivotDelta right) {
    return left.problemHash().equals(right.problemHash())
        && left.rootGoalHash().equals(right.rootGoalHash())
        && left.routeId().equals(right.routeId())
        && left.sourceStrategyId().equals(right.sourceStrategyId())
        && left.obstructionRefs().equals(right.obstructionRefs())
        && left.structuralDeltaHash().equals(right.structuralDeltaHash());
  }

  public synchronized SemanticPivotRecord recordDeterministicAudit(PivotDeltaAudit result) {
    SemanticPivotRecord current = required(result.pivotId());
    if (current.status() != PivotDeltaStatus.PROPOSED) {
      return current;
    }
    return transition(
        current,
        result.passed()
            ? PivotDeltaStatus.AWAITING_REVIEW
            : PivotDeltaStatus.DETERMINISTICALLY_REJECTED,
        result,
        null,
        null,
        null,
        result.passed() ? "deterministic audit passed" : "deterministic audit rejected");
  }

  public synchronized SemanticPivotRecord recordReview(
      String pivotId,
      String reviewerAgentId,
      SemanticPivotReviewDecision decision,
      boolean accepted) {
    SemanticPivotRecord current = required(pivotId);
    if (current.status() == PivotDeltaStatus.ADMITTED
        || current.status() == PivotDeltaStatus.APPLYING
        || current.status() == PivotDeltaStatus.APPLIED
        || current.status() == PivotDeltaStatus.EVALUATED) {
      return current;
    }
    if (current.status() != PivotDeltaStatus.AWAITING_REVIEW) {
      throw new IllegalStateException("pivot is not awaiting independent review");
    }
    if (current.proposerAgentId().equals(reviewerAgentId)) {
      throw new IllegalArgumentException("pivot reviewer must differ from proposer");
    }
    return transition(
        current,
        accepted ? PivotDeltaStatus.ADMITTED : PivotDeltaStatus.REVIEW_REJECTED,
        current.deterministicAudit(),
        decision,
        null,
        null,
        accepted ? "independent review admitted" : "independent review rejected",
        reviewerAgentId);
  }

  public synchronized SemanticPivotRecord stageApply(String pivotId) {
    SemanticPivotRecord current = required(pivotId);
    if (current.status() == PivotDeltaStatus.APPLYING
        || current.status() == PivotDeltaStatus.APPLIED
        || current.status() == PivotDeltaStatus.EVALUATED) {
      return current;
    }
    if (current.status() != PivotDeltaStatus.ADMITTED) {
      throw new IllegalStateException("pivot must be admitted before apply");
    }
    return transition(
        current,
        PivotDeltaStatus.APPLYING,
        current.deterministicAudit(),
        current.reviewDecision(),
        null,
        null,
        "atomic apply staged");
  }

  public synchronized SemanticPivotRecord rejectAdmission(
      String pivotId,
      String reviewerAgentId,
      SemanticPivotReviewDecision reviewDecision,
      List<String> failureCodes) {
    SemanticPivotRecord current = required(pivotId);
    if (current.status() != PivotDeltaStatus.AWAITING_REVIEW) {
      return current;
    }
    String detail =
        "existing authority gates rejected: "
            + String.join(",", failureCodes == null ? List.of() : failureCodes);
    return transition(
        current,
        PivotDeltaStatus.FAILED,
        current.deterministicAudit(),
        reviewDecision,
        null,
        null,
        detail,
        reviewerAgentId);
  }

  public synchronized SemanticPivotRecord commitApply(SemanticPivotApplyReceipt receipt) {
    SemanticPivotRecord current = required(receipt.pivotId());
    if (current.status() == PivotDeltaStatus.APPLIED
        || current.status() == PivotDeltaStatus.EVALUATED) {
      if (!receipt.equals(current.applyReceipt())) {
        throw new IllegalStateException("duplicate pivot apply used a different receipt");
      }
      return current;
    }
    if (current.status() != PivotDeltaStatus.APPLYING
        || !receipt.applied()
        || !current.delta().structuralDeltaHash().equals(receipt.structuralDeltaHash())) {
      throw new IllegalStateException("invalid semantic pivot apply receipt");
    }
    return transition(
        current,
        PivotDeltaStatus.APPLIED,
        current.deterministicAudit(),
        current.reviewDecision(),
        receipt,
        ProofControlModels.MetaPivotEffect.MATERIALIZED_NO_GAIN,
        "semantic state delta applied");
  }

  public synchronized SemanticPivotRecord evaluate(
      String pivotId, ProofControlModels.MetaPivotEffect effect, String reason) {
    SemanticPivotRecord current = required(pivotId);
    if (current.status() == PivotDeltaStatus.EVALUATED) {
      return current;
    }
    if (current.status() != PivotDeltaStatus.APPLIED
        || (effect != ProofControlModels.MetaPivotEffect.EFFECTIVE
            && effect != ProofControlModels.MetaPivotEffect.MATERIALIZED_NO_GAIN)) {
      throw new IllegalStateException("only an applied pivot can receive a gain evaluation");
    }
    return transition(
        current,
        PivotDeltaStatus.EVALUATED,
        current.deterministicAudit(),
        current.reviewDecision(),
        current.applyReceipt(),
        effect,
        PivotValues.required(reason, "reason"));
  }

  public synchronized SemanticPivotRecord get(String pivotId) {
    return required(pivotId);
  }

  public synchronized List<SemanticPivotRecord> records() {
    return records.values().stream()
        .sorted(java.util.Comparator.comparing(SemanticPivotRecord::pivotId))
        .toList();
  }

  public synchronized SemanticPivotSnapshot snapshot() {
    return new SemanticPivotSnapshot(
        SemanticPivotSnapshot.CURRENT_SCHEMA_VERSION, records, audit);
  }

  public synchronized String stableHash() {
    return CanonicalJson.stableHash(snapshot());
  }

  public synchronized void restore(SemanticPivotSnapshot snapshot) {
    SemanticPivotSnapshot source = snapshot == null ? SemanticPivotSnapshot.empty() : snapshot;
    source.records().forEach(
        (id, record) -> {
          if (!id.equals(record.pivotId())) {
            throw new IllegalArgumentException("semantic pivot snapshot key mismatch");
          }
        });
    records.clear();
    records.putAll(source.records());
    audit.clear();
    audit.addAll(source.audit());
  }

  private SemanticPivotRecord transition(
      SemanticPivotRecord current,
      PivotDeltaStatus status,
      PivotDeltaAudit deterministicAudit,
      SemanticPivotReviewDecision review,
      SemanticPivotApplyReceipt receipt,
      ProofControlModels.MetaPivotEffect effect,
      String detail) {
    return transition(
        current,
        status,
        deterministicAudit,
        review,
        receipt,
        effect,
        detail,
        current.reviewerAgentId());
  }

  private SemanticPivotRecord transition(
      SemanticPivotRecord current,
      PivotDeltaStatus status,
      PivotDeltaAudit deterministicAudit,
      SemanticPivotReviewDecision review,
      SemanticPivotApplyReceipt receipt,
      ProofControlModels.MetaPivotEffect effect,
      String detail,
      String reviewerAgentId) {
    long version = current.version() + 1L;
    List<String> history = new ArrayList<>(current.history());
    history.add(detail);
    SemanticPivotRecord updated =
        new SemanticPivotRecord(
            current.pivotId(),
            current.delta(),
            status,
            current.proposerAgentId(),
            reviewerAgentId,
            deterministicAudit,
            review,
            receipt,
            effect,
            version,
            history);
    records.put(updated.pivotId(), updated);
    audit.add(
        new SemanticPivotAuditEvent(
            updated.pivotId(), current.status(), status, detail, version));
    return updated;
  }

  private SemanticPivotRecord required(String pivotId) {
    SemanticPivotRecord record = records.get(pivotId);
    if (record == null) {
      throw new IllegalArgumentException("unknown semantic pivot: " + pivotId);
    }
    return record;
  }
}
