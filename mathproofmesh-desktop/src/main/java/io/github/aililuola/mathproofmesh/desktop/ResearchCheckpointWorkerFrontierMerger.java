package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointRecord;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointSnapshot;
import io.github.aililuola.mathproofmesh.research.ResearchFindingAuditEvent;
import io.github.aililuola.mathproofmesh.research.ResearchFindingRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Merges append-only worker research while preserving one stable disposition writer. */
final class ResearchCheckpointWorkerFrontierMerger {
  private ResearchCheckpointWorkerFrontierMerger() {}

  static ResearchCheckpointSnapshot merge(
      ResearchCheckpointSnapshot current,
      ResearchCheckpointSnapshot worker,
      String routeId,
      Set<String> frozenFindingIds,
      boolean dispositionAuthority) {
    ResearchCheckpointSnapshot base = Objects.requireNonNull(current, "current");
    ResearchCheckpointSnapshot incoming = Objects.requireNonNull(worker, "worker");
    String route = Objects.requireNonNull(routeId, "routeId");
    Set<String> frozenIds = Set.copyOf(Objects.requireNonNull(frozenFindingIds, "frozenFindingIds"));

    Map<String, ResearchCheckpointRecord> checkpointsById =
        new LinkedHashMap<>(base.checkpoints());
    incoming.checkpoints().values().stream()
        .filter(record -> record.routeId().equals(route))
        .forEach(
            record -> {
              ResearchCheckpointRecord prior =
                  checkpointsById.putIfAbsent(record.checkpointId(), record);
              if (prior != null && !prior.equals(record)) {
                throw new IllegalStateException("research checkpoint worker result conflicted");
              }
            });

    Map<String, ResearchFindingRecord> findingsById = new LinkedHashMap<>(base.findings());
    Set<String> changedFindings = new LinkedHashSet<>();
    incoming.findings().values().stream()
        .filter(record -> record.routeId().equals(route))
        .forEach(
            record -> {
              ResearchFindingRecord prior = findingsById.get(record.findingId());
              if (prior == null) {
                findingsById.put(record.findingId(), record);
                changedFindings.add(record.findingId());
                return;
              }
              if (!dispositionAuthority && frozenIds.contains(record.findingId())) {
                return;
              }
              if (prior.version() < record.version()) {
                findingsById.put(record.findingId(), record);
                changedFindings.add(record.findingId());
              } else if (prior.version() == record.version() && !prior.equals(record)) {
                throw new IllegalStateException("research finding worker result conflicted");
              }
            });

    List<ResearchFindingAuditEvent> audit = new ArrayList<>(base.audit());
    Set<String> auditHashes =
        audit.stream().map(CanonicalJson::stableHash).collect(Collectors.toSet());
    incoming.audit().stream()
        .filter(event -> ResearchFindingUpdateBoundary.mergeable(event, changedFindings))
        .filter(event -> auditHashes.add(CanonicalJson.stableHash(event)))
        .forEach(
            event ->
                audit.add(
                    new ResearchFindingAuditEvent(
                        audit.size(),
                        event.findingId(),
                        event.action(),
                        event.priorStatus(),
                        event.nextStatus(),
                        event.reason())));
    return new ResearchCheckpointSnapshot(
        ResearchCheckpointSnapshot.CURRENT_SCHEMA_VERSION,
        checkpointsById,
        findingsById,
        audit);
  }
}
