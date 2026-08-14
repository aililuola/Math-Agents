package io.github.aililuola.mathproofmesh.proofgraph;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Monotonic audit ledger for proof-graph expansions that remain mathematically unresolved. */
@SuppressFBWarnings(
    value = {"USO_UNSAFE_METHOD_SYNCHRONIZATION", "IS2_INCONSISTENT_SYNC"},
    justification =
        "Public operations serialize ledger state; restore writes occur only before publication.")
public final class DeferredExpansionLedger {
  private final Map<String, DeferredExpansionRecord> records = new LinkedHashMap<>();
  private long version;

  public DeferredExpansionLedger() {}

  public static DeferredExpansionLedger restore(DeferredExpansionSnapshot snapshot) {
    DeferredExpansionLedger result = new DeferredExpansionLedger();
    DeferredExpansionSnapshot safe =
        snapshot == null ? DeferredExpansionSnapshot.empty() : snapshot;
    result.records.putAll(safe.records());
    result.version = safe.version();
    return result;
  }

  public synchronized DeferredExpansionRecord record(
      String problemHash,
      int round,
      String routeId,
      String obligationId,
      String canonicalTargetId,
      FocusedRecoveryActionType actionType,
      FocusedExpansionDecision decision) {
    java.util.Objects.requireNonNull(actionType, "actionType");
    java.util.Objects.requireNonNull(decision, "decision");
    if (!decision.deferred()) {
      throw new IllegalArgumentException("only deferred decisions belong in this ledger");
    }
    String deferredId =
        "deferred_"
            + CanonicalJson.stableHash(
                Map.of(
                    "problem_hash", normalize(problemHash),
                    "round", round,
                    "route_id", normalize(routeId),
                    "obligation_id", normalize(obligationId),
                    "canonical_target_id", normalize(canonicalTargetId),
                    "action", actionType.name(),
                    "state", decision.schedulingState().name()))
                .substring(0, 24);
    DeferredExpansionRecord existing = records.get(deferredId);
    if (existing != null) {
      return existing;
    }
    DeferredExpansionRecord created =
        new DeferredExpansionRecord(
            deferredId,
            problemHash,
            round,
            routeId,
            obligationId,
            canonicalTargetId,
            actionType,
            decision.schedulingState(),
            decision.code(),
            0L);
    records.put(deferredId, created);
    version++;
    return created;
  }

  public synchronized List<DeferredExpansionRecord> records() {
    return List.copyOf(records.values());
  }

  public synchronized long version() {
    return version;
  }

  public synchronized DeferredExpansionSnapshot snapshot() {
    return new DeferredExpansionSnapshot(records, version);
  }

  public synchronized String stableHash() {
    return CanonicalJson.stableHash(snapshot());
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip();
  }
}
