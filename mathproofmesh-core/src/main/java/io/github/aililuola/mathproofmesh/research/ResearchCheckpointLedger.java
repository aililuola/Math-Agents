package io.github.aililuola.mathproofmesh.research;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ResearchCheckpointFrame;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingDisposition;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingDispositionAction;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingDraft;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingKind;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingUpdateBatch;
import io.github.aililuola.mathproofmesh.topology.SparseTopologyRouter;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exactly-once ledger for public, non-authoritative research checkpoints. */
@SuppressFBWarnings(
    value = "USO_UNSAFE_METHOD_SYNCHRONIZATION",
    justification = "One monitor serializes the checkpoint, finding, and audit projections.")
public final class ResearchCheckpointLedger {
  private static final SparseTopologyRouter MATH_NORMALIZER = new SparseTopologyRouter();
  private final Map<String, ResearchCheckpointRecord> checkpoints = new LinkedHashMap<>();
  private final Map<String, ResearchFindingRecord> findings = new LinkedHashMap<>();
  private final List<ResearchFindingAuditEvent> audit = new ArrayList<>();

  public synchronized List<ResearchCheckpointRecord> appendTraceFrames(
      String problemHash,
      String routeId,
      String stage,
      String providerCallId,
      String reasoningTraceCallId,
      String reasoningTraceTaskId,
      Collection<ResearchCheckpointTraceSpan> spans) {
    List<ResearchCheckpointRecord> result = new ArrayList<>();
    for (ResearchCheckpointTraceSpan span : Objects.requireNonNull(spans, "spans")) {
      result.add(
          append(
              problemHash,
              routeId,
              stage,
              providerCallId,
              reasoningTraceCallId,
              reasoningTraceTaskId,
              span.frame(),
              span.traceSha256(),
              span.markerStart(),
              span.markerEnd(),
              "reasoning_trace"));
    }
    return List.copyOf(result);
  }

  public synchronized ResearchCheckpointRecord appendEnvelopeFrame(
      String problemHash,
      String routeId,
      String stage,
      String providerCallId,
      ResearchCheckpointFrame frame) {
    return append(
        problemHash,
        routeId,
        stage,
        providerCallId,
        null,
        null,
        Objects.requireNonNull(frame, "frame"),
        null,
        null,
        null,
        "final_envelope");
  }

  public synchronized List<ResearchFindingRecord> applyUpdates(
      String routeId, ResearchFindingUpdateBatch batch) {
    String route = required(routeId, "routeId");
    ResearchFindingUpdateBatch updates =
        batch == null ? ResearchFindingUpdateBatch.empty() : batch;
    List<PendingTransition> pending = new ArrayList<>();
    for (ResearchFindingDisposition disposition : updates.dispositions()) {
      ResearchFindingRecord current = requireFinding(disposition.findingId());
      if (!current.routeId().equals(route)) {
        throw new IllegalArgumentException("finding update crossed route boundary");
      }
      ResearchFindingStatus next = statusFor(current, disposition);
      validateTransition(current, next, disposition.supersededByFindingId());
      pending.add(new PendingTransition(current, next, disposition));
    }
    List<ResearchFindingRecord> changed = new ArrayList<>();
    for (PendingTransition item : pending) {
      if (item.next() == item.current().status()) {
        continue;
      }
      changed.add(
          transition(
              item.current(),
              item.next(),
              item.disposition().action().value(),
              item.disposition().reason(),
              item.disposition().supersededByFindingId()));
    }
    return List.copyOf(changed);
  }

  public synchronized List<ResearchFindingRecord> deferRouteEnd(String routeId) {
    List<ResearchFindingRecord> changed = new ArrayList<>();
    for (ResearchFindingRecord record : activeFindings(routeId)) {
      changed.add(
          transition(
              record,
              ResearchFindingStatus.DEFERRED,
              "deferred_route_end",
              "DEFERRED_ROUTE_END",
              null));
    }
    return List.copyOf(changed);
  }

  public synchronized List<ResearchFindingRecord> findings() {
    return findings.values().stream()
        .sorted(Comparator.comparing(ResearchFindingRecord::findingId))
        .toList();
  }

  public synchronized ResearchFindingRecord finding(String findingId) {
    return requireFinding(findingId);
  }

  public synchronized List<ResearchFindingRecord> activeFindings(String routeId) {
    String route = required(routeId, "routeId");
    return findings.values().stream()
        .filter(record -> record.routeId().equals(route))
        .filter(record -> record.status() == ResearchFindingStatus.ACTIVE)
        .sorted(Comparator.comparing(ResearchFindingRecord::findingId))
        .toList();
  }

  public synchronized List<ResearchCheckpointRecord> checkpointsForRoute(String routeId) {
    String route = required(routeId, "routeId");
    return checkpoints.values().stream()
        .filter(record -> record.routeId().equals(route))
        .sorted(Comparator.comparing(ResearchCheckpointRecord::checkpointId))
        .toList();
  }

  public synchronized List<ResearchFindingAuditEvent> audit() {
    return List.copyOf(audit);
  }

  public synchronized ResearchCheckpointSnapshot snapshot() {
    return new ResearchCheckpointSnapshot(
        ResearchCheckpointSnapshot.CURRENT_SCHEMA_VERSION, checkpoints, findings, audit);
  }

  public synchronized String ledgerHash() {
    return CanonicalJson.stableHash(snapshot());
  }

  public static ResearchCheckpointLedger restore(ResearchCheckpointSnapshot snapshot) {
    ResearchCheckpointSnapshot source =
        snapshot == null ? ResearchCheckpointSnapshot.empty() : snapshot;
    ResearchCheckpointLedger ledger = new ResearchCheckpointLedger();
    for (Map.Entry<String, ResearchCheckpointRecord> entry : source.checkpoints().entrySet()) {
      if (!entry.getKey().equals(entry.getValue().checkpointId())) {
        throw new IllegalArgumentException("research checkpoint snapshot key mismatch");
      }
      ledger.checkpoints.put(entry.getKey(), entry.getValue());
    }
    for (Map.Entry<String, ResearchFindingRecord> entry : source.findings().entrySet()) {
      if (!entry.getKey().equals(entry.getValue().findingId())) {
        throw new IllegalArgumentException("research finding snapshot key mismatch");
      }
      ledger.findings.put(entry.getKey(), entry.getValue());
    }
    ledger.audit.addAll(source.audit());
    return ledger;
  }

  private ResearchCheckpointRecord append(
      String problemHash,
      String routeId,
      String stage,
      String providerCallId,
      String reasoningTraceCallId,
      String reasoningTraceTaskId,
      ResearchCheckpointFrame frame,
      String traceSha256,
      Integer markerStart,
      Integer markerEnd,
      String source) {
    String problem = required(problemHash, "problemHash");
    String route = required(routeId, "routeId");
    String stageName = required(stage, "stage");
    String providerCall = required(providerCallId, "providerCallId");
    String frameHash = CanonicalJson.stableHash(frame);
    String checkpointId =
        "research_checkpoint_"
            + CanonicalJson.stableHash(
                    List.of(problem, route, stageName, providerCall, frame.frameSequence(), frameHash))
                .substring(0, 32);
    List<String> findingIds = new ArrayList<>();
    for (ResearchFindingDraft draft : frame.findings()) {
      String normalized = normalizeStatement(draft.statement());
      String findingId =
          "research_finding_"
              + CanonicalJson.stableHash(
                      List.of(
                          problem,
                          route,
                          stageName,
                          providerCall,
                          frame.frameSequence(),
                          draft.kind().value(),
                          normalized))
                  .substring(0, 32);
      ResearchFindingRecord candidate =
          new ResearchFindingRecord(
              findingId,
              problem,
              route,
              stageName,
              providerCall,
              checkpointId,
              frame.frameSequence(),
              draft.kind(),
              draft.statement(),
              normalized,
              draft.rationale(),
              draft.assumptions(),
              draft.scopeLimitations(),
              draft.targetObligationId(),
              ResearchFindingStatus.ACTIVE,
              null,
              null,
              0L);
      ResearchFindingRecord existing = findings.putIfAbsent(findingId, candidate);
      if (existing != null && !sameFinding(existing, candidate)) {
        throw new IllegalArgumentException("research finding ID collision");
      }
      if (existing == null) {
        audit.add(
            new ResearchFindingAuditEvent(
                audit.size(),
                findingId,
                "capture_and_activate",
                ResearchFindingStatus.CAPTURED,
                ResearchFindingStatus.ACTIVE,
                "complete public checkpoint"));
      }
      findingIds.add(findingId);
    }
    ResearchCheckpointRecord candidate =
        new ResearchCheckpointRecord(
            checkpointId,
            problem,
            route,
            stageName,
            providerCall,
            reasoningTraceCallId,
            reasoningTraceTaskId,
            frame.frameSequence(),
            frameHash,
            traceSha256,
            markerStart,
            markerEnd,
            findingIds,
            source);
    ResearchCheckpointRecord existing = checkpoints.putIfAbsent(checkpointId, candidate);
    if (existing != null && !sameCheckpoint(existing, candidate)) {
      throw new IllegalArgumentException("research checkpoint ID collision");
    }
    return existing == null ? candidate : existing;
  }

  private ResearchFindingRecord transition(
      ResearchFindingRecord current,
      ResearchFindingStatus next,
      String action,
      String reason,
      String supersededBy) {
    validateTransition(current, next, supersededBy);
    ResearchFindingRecord updated =
        new ResearchFindingRecord(
            current.findingId(),
            current.problemHash(),
            current.routeId(),
            current.stage(),
            current.providerCallId(),
            current.checkpointId(),
            current.frameSequence(),
            current.kind(),
            current.statement(),
            current.normalizedStatement(),
            current.rationale(),
            current.assumptions(),
            current.scopeLimitations(),
            current.targetObligationId(),
            next,
            reason,
            supersededBy,
            current.version() + 1L);
    findings.put(updated.findingId(), updated);
    audit.add(
        new ResearchFindingAuditEvent(
            audit.size(),
            updated.findingId(),
            action,
            current.status(),
            next,
            reason));
    return updated;
  }

  private void validateTransition(
      ResearchFindingRecord current, ResearchFindingStatus next, String supersededBy) {
    if (!allowed(current.status(), next)) {
      throw new IllegalStateException(
          "research finding status cannot move from " + current.status() + " to " + next);
    }
    if (next == ResearchFindingStatus.SUPERSEDED) {
      ResearchFindingRecord replacement = requireFinding(supersededBy);
      if (!replacement.problemHash().equals(current.problemHash())
          || !replacement.routeId().equals(current.routeId())) {
        throw new IllegalArgumentException("superseding finding crossed problem or route boundary");
      }
    }
  }

  private static ResearchFindingStatus statusFor(
      ResearchFindingRecord current, ResearchFindingDisposition disposition) {
    return switch (disposition.action()) {
      case KEEP_ACTIVE -> ResearchFindingStatus.ACTIVE;
      case DEFER -> ResearchFindingStatus.DEFERRED;
      case PROMOTE_TO_PROPOSED_LEMMA -> {
        if (current.kind() != ResearchFindingKind.CANDIDATE_LEMMA) {
          throw new IllegalArgumentException("only candidate_lemma can become a proposed lemma");
        }
        yield ResearchFindingStatus.PROMOTED_TO_ATTEMPT_CANDIDATE;
      }
      case PROMOTE_TO_COUNTEREXAMPLE_CANDIDATE -> {
        if (current.kind() != ResearchFindingKind.COUNTEREXAMPLE_CANDIDATE
            || current.targetObligationId() == null) {
          throw new IllegalArgumentException(
              "counterexample promotion requires a candidate and exact target obligation");
        }
        yield ResearchFindingStatus.PROMOTED_TO_ATTEMPT_CANDIDATE;
      }
      case REJECT_WITH_REASON -> ResearchFindingStatus.REJECTED;
      case SUPERSEDE_WITH -> ResearchFindingStatus.SUPERSEDED;
    };
  }

  private ResearchFindingRecord requireFinding(String findingId) {
    ResearchFindingRecord record = findings.get(required(findingId, "findingId"));
    if (record == null) {
      throw new IllegalArgumentException("unknown research finding: " + findingId);
    }
    return record;
  }

  private static boolean allowed(ResearchFindingStatus from, ResearchFindingStatus to) {
    if (from == to) {
      return true;
    }
    return (from == ResearchFindingStatus.CAPTURED || from == ResearchFindingStatus.ACTIVE)
        && Set.of(
                ResearchFindingStatus.ACTIVE,
                ResearchFindingStatus.DEFERRED,
                ResearchFindingStatus.PROMOTED_TO_ATTEMPT_CANDIDATE,
                ResearchFindingStatus.REJECTED,
                ResearchFindingStatus.SUPERSEDED)
            .contains(to);
  }

  private static boolean sameFinding(
      ResearchFindingRecord left, ResearchFindingRecord right) {
    return left.problemHash().equals(right.problemHash())
        && left.checkpointId().equals(right.checkpointId())
        && left.kind() == right.kind()
        && left.normalizedStatement().equals(right.normalizedStatement());
  }

  private static boolean sameCheckpoint(
      ResearchCheckpointRecord left, ResearchCheckpointRecord right) {
    return left.problemHash().equals(right.problemHash())
        && left.providerCallId().equals(right.providerCallId())
        && left.frameHash().equals(right.frameHash())
        && new LinkedHashSet<>(left.findingIds()).equals(new LinkedHashSet<>(right.findingIds()));
  }

  @SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "Semantic keys intentionally apply NFKC followed by locale-independent ROOT casing and the existing math normalizer.")
  private static String normalizeStatement(String value) {
    String normalized =
        Normalizer.normalize(required(value, "statement"), Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ")
            .strip();
    return MATH_NORMALIZER.mathNormalize(normalized);
  }

  private static String required(String value, String name) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return normalized;
  }

  private record PendingTransition(
      ResearchFindingRecord current,
      ResearchFindingStatus next,
      ResearchFindingDisposition disposition) {}
}
