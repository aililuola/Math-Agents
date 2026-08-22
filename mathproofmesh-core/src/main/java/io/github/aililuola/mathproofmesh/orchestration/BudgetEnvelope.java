package io.github.aililuola.mathproofmesh.orchestration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;

/** Durable action-level reservation. */
public record BudgetEnvelope(
    BudgetEnvelopeId envelopeId,
    String runId,
    String epochId,
    String workItemId,
    String actionDecisionId,
    BudgetBucket bucket,
    BudgetResourceVector allocated,
    BudgetResourceVector consumed,
    BudgetEnvelopeStatus status,
    long version,
    String contentHash) {

  public BudgetEnvelope {
    envelopeId = Objects.requireNonNull(envelopeId, "envelopeId");
    runId = required(runId, "runId");
    epochId = required(epochId, "epochId");
    workItemId = required(workItemId, "workItemId");
    actionDecisionId = required(actionDecisionId, "actionDecisionId");
    bucket = Objects.requireNonNull(bucket, "bucket");
    allocated = Objects.requireNonNull(allocated, "allocated");
    consumed = consumed == null ? BudgetResourceVector.zero() : consumed;
    status = Objects.requireNonNull(status, "status");
    if (!consumed.fitsWithin(allocated) && status != BudgetEnvelopeStatus.OVERRUN) {
      throw new IllegalArgumentException("envelope consumption exceeds allocation without overrun");
    }
    if (version < 0) {
      throw new IllegalArgumentException("envelope version must not be negative");
    }
    String expected = hash(envelopeId, runId, epochId, workItemId, actionDecisionId, bucket,
        allocated, consumed, status, version);
    contentHash = contentHash == null || contentHash.isBlank() ? expected : contentHash.strip();
    if (!sameHash(expected, contentHash)) {
      throw new IllegalArgumentException("budget envelope content hash mismatch");
    }
  }

  public BudgetResourceVector remaining() {
    return consumed.fitsWithin(allocated)
        ? allocated.minus(consumed)
        : BudgetResourceVector.zero();
  }

  private static String hash(
      BudgetEnvelopeId id,
      String runId,
      String epochId,
      String workItemId,
      String actionDecisionId,
      BudgetBucket bucket,
      BudgetResourceVector allocated,
      BudgetResourceVector consumed,
      BudgetEnvelopeStatus status,
      long version) {
    return CanonicalJson.stableHash(
        Map.ofEntries(
            Map.entry("envelope_id", id.value()),
            Map.entry("run_id", runId),
            Map.entry("epoch_id", epochId),
            Map.entry("work_item_id", workItemId),
            Map.entry("action_decision_id", actionDecisionId),
            Map.entry("bucket", bucket.name()),
            Map.entry("allocated", allocated),
            Map.entry("consumed", consumed),
            Map.entry("status", status.name()),
            Map.entry("version", version)));
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }

  private static boolean sameHash(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }
}
