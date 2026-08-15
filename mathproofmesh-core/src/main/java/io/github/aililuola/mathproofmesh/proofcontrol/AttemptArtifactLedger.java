package io.github.aililuola.mathproofmesh.proofcontrol;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewDecision;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Monotonic, checkpointable authority ledger for harvested attempt artifacts. */
@SuppressFBWarnings(
    value = "USO_UNSAFE_METHOD_SYNCHRONIZATION",
    justification = "The ledger monitor intentionally serializes its in-memory projection.")
public final class AttemptArtifactLedger {
  private final Map<String, AttemptArtifactRecord> records = new LinkedHashMap<>();
  private final Map<String, String> attemptReviewReportIds = new LinkedHashMap<>();

  public synchronized List<AttemptArtifactRecord> addAll(
      Collection<AttemptArtifactRecord> candidates) {
    List<AttemptArtifactRecord> result = new ArrayList<>();
    for (AttemptArtifactRecord candidate : candidates) {
      Objects.requireNonNull(candidate, "candidate");
      AttemptArtifactRecord existing = records.get(candidate.artifactId());
      if (existing == null) {
        records.put(candidate.artifactId(), candidate);
        result.add(candidate);
      } else {
        if (!existing.contentHash().equals(candidate.contentHash())
            || existing.kind() != candidate.kind()
            || !existing.sourceAttemptId().equals(candidate.sourceAttemptId())) {
          throw new IllegalArgumentException("attempt artifact ID collision");
        }
        result.add(existing);
      }
    }
    return List.copyOf(result);
  }

  public synchronized List<AttemptArtifactRecord> markReviewPending(String attemptId) {
    if (attemptReviewReportIds.containsKey(required(attemptId, "attemptId"))) {
      return recordsForAttempt(attemptId);
    }
    List<AttemptArtifactRecord> changed = new ArrayList<>();
    for (AttemptArtifactRecord record : recordsForAttempt(attemptId)) {
      if (record.status() == AttemptArtifactStatus.HARVESTED) {
        changed.add(transition(record, AttemptArtifactStatus.REVIEW_PENDING, null, null,
            "claim review requested"));
      }
    }
    return List.copyOf(changed);
  }

  public synchronized AttemptArtifactRecord markUncertain(String artifactId, String reason) {
    AttemptArtifactRecord record = requireRecord(artifactId);
    return transition(
        record,
        AttemptArtifactStatus.UNCERTAIN,
        null,
        null,
        required(reason, "reason"));
  }

  /** Legacy single-verdict adapter. New Claim Court production paths use applyCourtOutcome. */
  public synchronized List<AttemptArtifactRecord> applyReviewBatch(
      ClaimReviewBatch batch, double passThreshold) {
    Objects.requireNonNull(batch, "batch");
    if (!Double.isFinite(passThreshold) || passThreshold < 0.0d || passThreshold > 1.0d) {
      throw new IllegalArgumentException("passThreshold must be between zero and one");
    }
    String existingReport = attemptReviewReportIds.get(batch.attemptId());
    if (existingReport != null) {
      if (!existingReport.equals(batch.reportId())) {
        throw new IllegalStateException("an attempt may have only one claim review batch");
      }
      return recordsForAttempt(batch.attemptId());
    }

    List<AttemptArtifactRecord> candidates =
        recordsForAttempt(batch.attemptId()).stream()
            .filter(record -> record.status() == AttemptArtifactStatus.HARVESTED
                || record.status() == AttemptArtifactStatus.REVIEW_PENDING)
            .toList();
    if (candidates.isEmpty()) {
      throw new IllegalArgumentException("claim review batch has no pending attempt artifacts");
    }
    if (candidates.stream().anyMatch(record -> !record.routeId().equals(batch.routeId()))) {
      throw new IllegalArgumentException("claim review batch route does not match its artifacts");
    }
    if (candidates.stream().anyMatch(record -> record.authorAgentId().equals(batch.agentId()))) {
      throw new IllegalArgumentException("claim reviewer must differ from artifact author");
    }

    Map<String, ClaimReviewDecision> decisions = new LinkedHashMap<>();
    for (ClaimReviewDecision decision : batch.decisions()) {
      decisions.put(decision.claimId(), decision);
    }
    Set<String> candidateIds =
        candidates.stream()
            .map(AttemptArtifactRecord::claimId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    List<String> extras =
        decisions.keySet().stream().filter(id -> !candidateIds.contains(id)).sorted().toList();
    if (!extras.isEmpty()) {
      throw new IllegalArgumentException("claim review contains extra decisions: " + extras);
    }

    List<AttemptArtifactRecord> reviewed = new ArrayList<>();
    for (AttemptArtifactRecord record : candidates) {
      ClaimReviewDecision decision = decisions.get(record.claimId());
      AttemptArtifactStatus next;
      String event;
      if (decision == null) {
        next = AttemptArtifactStatus.UNCERTAIN;
        event = "claim review decision missing";
      } else if (decision.verdict() == VerificationVerdict.FAIL) {
        next = AttemptArtifactStatus.UNCERTAIN;
        event = "legacy proof failure preserved claim truth: " + decision.conciseFeedback();
      } else if (decision.verdict() == VerificationVerdict.PASS
          && decision.confidence() >= passThreshold
          && decision.authorityDimensionsValid()
          && (record.kind() != AttemptArtifactKind.COUNTEREXAMPLE
              || decision.witnessChecked())) {
        next = AttemptArtifactStatus.VERIFIED_LOCAL;
        event = "claim review verified: " + decision.conciseFeedback();
      } else {
        next = AttemptArtifactStatus.UNCERTAIN;
        event = "claim review remained uncertain: " + decision.conciseFeedback();
      }
      reviewed.add(transition(record, next, batch.reportId(), null, event));
    }
    attemptReviewReportIds.put(batch.attemptId(), batch.reportId());
    return List.copyOf(reviewed);
  }

  /** Applies a fully adjudicated Claim Court outcome to exactly one harvested artifact. */
  public synchronized AttemptArtifactRecord applyCourtOutcome(
      String artifactId,
      ClaimCourtOutcome outcome,
      String courtCaseId,
      String proofRevisionId,
      String detail) {
    AttemptArtifactRecord record = requireRecord(artifactId);
    ClaimCourtOutcome resolved = java.util.Objects.requireNonNull(outcome, "outcome");
    AttemptArtifactStatus next =
        switch (resolved) {
          case VERIFIED -> AttemptArtifactStatus.VERIFIED_LOCAL;
          case REFUTED -> AttemptArtifactStatus.REJECTED;
          case PROOF_INVALID_BUT_CLAIM_OPEN,
              REPAIR_EXHAUSTED,
              INCONCLUSIVE,
              DEFERRED_INDEPENDENCE_UNAVAILABLE -> AttemptArtifactStatus.UNCERTAIN;
        };
    if (record.status() == next) {
      return record;
    }
    if (record.status() == AttemptArtifactStatus.VERIFIED_LOCAL
        || record.status() == AttemptArtifactStatus.PROMOTED_FACT
        || record.status() == AttemptArtifactStatus.APPLIED_COUNTEREXAMPLE) {
      return record;
    }
    String courtRef =
        required(courtCaseId, "courtCaseId")
            + ":"
            + required(proofRevisionId, "proofRevisionId");
    return transition(
        record,
        next,
        courtRef,
        null,
        "claim court "
            + resolved.name().toLowerCase(java.util.Locale.ROOT)
            + ": "
            + required(detail, "detail"));
  }

  public synchronized AttemptArtifactRecord markPromoted(
      String artifactId, String messageId) {
    AttemptArtifactRecord record = requireRecord(artifactId);
    if (record.kind() == AttemptArtifactKind.COUNTEREXAMPLE) {
      throw new IllegalArgumentException("counterexamples must use markCounterexampleApplied");
    }
    return transition(
        record,
        AttemptArtifactStatus.PROMOTED_FACT,
        null,
        required(messageId, "messageId"),
        "promoted to Fact memory");
  }

  public synchronized AttemptArtifactRecord markCounterexampleApplied(
      String artifactId, String messageId) {
    AttemptArtifactRecord record = requireRecord(artifactId);
    if (record.kind() != AttemptArtifactKind.COUNTEREXAMPLE) {
      throw new IllegalArgumentException("only a counterexample artifact can refute an obligation");
    }
    return transition(
        record,
        AttemptArtifactStatus.APPLIED_COUNTEREXAMPLE,
        null,
        required(messageId, "messageId"),
        "applied to exact target obligation " + record.targetObligationId());
  }

  public synchronized AttemptArtifactRecord get(String artifactId) {
    return requireRecord(artifactId);
  }

  public synchronized List<AttemptArtifactRecord> records() {
    return records.values().stream()
        .sorted(Comparator.comparing(AttemptArtifactRecord::artifactId))
        .toList();
  }

  public synchronized List<AttemptArtifactRecord> recordsForAttempt(String attemptId) {
    return records.values().stream()
        .filter(record -> record.sourceAttemptId().equals(attemptId))
        .sorted(Comparator.comparing(AttemptArtifactRecord::artifactId))
        .toList();
  }

  public synchronized boolean reviewed(String attemptId) {
    return attemptReviewReportIds.containsKey(attemptId);
  }

  public synchronized AttemptArtifactSnapshot snapshot() {
    return new AttemptArtifactSnapshot(records, attemptReviewReportIds);
  }

  public synchronized String ledgerHash() {
    return CanonicalJson.stableHash(snapshot());
  }

  public static AttemptArtifactLedger restore(AttemptArtifactSnapshot snapshot) {
    AttemptArtifactLedger ledger = new AttemptArtifactLedger();
    AttemptArtifactSnapshot source = snapshot == null ? AttemptArtifactSnapshot.empty() : snapshot;
    ledger.records.putAll(source.records());
    ledger.attemptReviewReportIds.putAll(source.attemptReviewReportIds());
    return ledger;
  }

  private AttemptArtifactRecord transition(
      AttemptArtifactRecord current,
      AttemptArtifactStatus next,
      String reviewId,
      String promotedMessageId,
      String event) {
    if (current.status() == next) {
      return current;
    }
    if (!allowed(current.status(), next)) {
      throw new IllegalStateException(
          "attempt artifact status cannot move from " + current.status() + " to " + next);
    }
    List<String> reviews = appendDistinct(current.reviewIds(), reviewId);
    List<String> history = new ArrayList<>(current.history());
    history.add(event + ":" + next.name().toLowerCase(java.util.Locale.ROOT));
    AttemptArtifactRecord updated =
        new AttemptArtifactRecord(
            current.artifactId(),
            current.problemHash(),
            current.routeId(),
            current.sourceAttemptId(),
            current.sourceAttemptStatus(),
            current.sourceDeltaId(),
            current.sourceRouteStatus(),
            current.kind(),
            current.claimId(),
            current.contentHash(),
            current.statement(),
            current.authorAgentId(),
            current.sourceAttemptIncomplete(),
            current.targetObligationId(),
            next,
            reviews,
            current.evidenceRefs(),
            promotedMessageId == null ? current.promotedMessageId() : promotedMessageId,
            current.version() + 1L,
            history);
    records.put(updated.artifactId(), updated);
    return updated;
  }

  private AttemptArtifactRecord requireRecord(String artifactId) {
    AttemptArtifactRecord record = records.get(required(artifactId, "artifactId"));
    if (record == null) {
      throw new IllegalArgumentException("unknown attempt artifact: " + artifactId);
    }
    return record;
  }

  private static boolean allowed(AttemptArtifactStatus from, AttemptArtifactStatus to) {
    return switch (from) {
      case HARVESTED -> to == AttemptArtifactStatus.REVIEW_PENDING
          || to == AttemptArtifactStatus.VERIFIED_LOCAL
          || to == AttemptArtifactStatus.REJECTED
          || to == AttemptArtifactStatus.UNCERTAIN;
      case REVIEW_PENDING -> to == AttemptArtifactStatus.VERIFIED_LOCAL
          || to == AttemptArtifactStatus.REJECTED
          || to == AttemptArtifactStatus.UNCERTAIN;
      case VERIFIED_LOCAL -> to == AttemptArtifactStatus.PROMOTED_FACT
          || to == AttemptArtifactStatus.APPLIED_COUNTEREXAMPLE;
      case REJECTED, UNCERTAIN, PROMOTED_FACT, APPLIED_COUNTEREXAMPLE -> false;
    };
  }

  private static List<String> appendDistinct(List<String> values, String value) {
    if (value == null || value.isBlank() || values.contains(value)) {
      return values;
    }
    List<String> result = new ArrayList<>(values);
    result.add(value.strip());
    return List.copyOf(result);
  }

  private static String required(String value, String name) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return normalized;
  }
}
