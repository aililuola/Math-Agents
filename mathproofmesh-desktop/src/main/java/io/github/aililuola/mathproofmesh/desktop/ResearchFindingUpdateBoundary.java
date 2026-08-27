package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingDisposition;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingDispositionAction;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingUpdateBatch;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointLedger;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointSnapshot;
import io.github.aililuola.mathproofmesh.research.ResearchFindingAuditEvent;
import io.github.aililuola.mathproofmesh.research.ResearchFindingRecord;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Isolates optional model-side finding dispositions from the authoritative research ledger. */
final class ResearchFindingUpdateBoundary {
  static final String REJECT_UNKNOWN_ACTION = "reject_unknown_finding_update";

  private ResearchFindingUpdateBoundary() {}

  static ResearchCheckpointLedger apply(
      ResearchCheckpointLedger ledger,
      String routeId,
      String campaignRouteId,
      String stage,
      String providerCallId,
      ResearchFindingUpdateBatch batch) {
    ResearchCheckpointLedger source = Objects.requireNonNull(ledger, "ledger");
    ResearchCheckpointSnapshot snapshot = source.snapshot();
    List<ResearchFindingAuditEvent> audit = new ArrayList<>(snapshot.audit());
    Set<String> rejectionKeys = new LinkedHashSet<>();
    audit.stream()
        .filter(event -> REJECT_UNKNOWN_ACTION.equals(event.action()))
        .map(ResearchFindingUpdateBoundary::rejectionKey)
        .forEach(rejectionKeys::add);

    ResearchFindingUpdateBatch updates =
        batch == null ? ResearchFindingUpdateBatch.empty() : batch;
    List<ResearchFindingDisposition> routeOwned = new ArrayList<>();
    for (ResearchFindingDisposition disposition : updates.dispositions()) {
      ResearchFindingRecord finding = snapshot.findings().get(disposition.findingId());
      if (finding == null) {
        appendUnknownRejection(
            audit, rejectionKeys, disposition.findingId(), routeId, stage, providerCallId);
      } else if (finding.routeId().equals(routeId)) {
        routeOwned.add(disposition);
      } else if (!(finding.routeId().equals(campaignRouteId)
          && disposition.action() == ResearchFindingDispositionAction.KEEP_ACTIVE)) {
        throw new IllegalArgumentException(
            "CROSS_ROUTE_FINDING_MUTATION_FORBIDDEN: "
                + disposition.findingId()
                + " is owned by "
                + finding.routeId());
      }
    }

    ResearchCheckpointLedger audited = source;
    if (audit.size() != snapshot.audit().size()) {
      audited =
          ResearchCheckpointLedger.restore(
              new ResearchCheckpointSnapshot(
                  ResearchCheckpointSnapshot.CURRENT_SCHEMA_VERSION,
                  snapshot.checkpoints(),
                  snapshot.findings(),
                  audit));
    }
    audited.applyUpdates(routeId, new ResearchFindingUpdateBatch(routeOwned));
    return audited;
  }

  static boolean mergeable(ResearchFindingAuditEvent event, Set<String> changedFindingIds) {
    return changedFindingIds.contains(event.findingId())
        || REJECT_UNKNOWN_ACTION.equals(event.action());
  }

  private static void appendUnknownRejection(
      List<ResearchFindingAuditEvent> audit,
      Set<String> rejectionKeys,
      String findingId,
      String routeId,
      String stage,
      String providerCallId) {
    String reason =
        "UNKNOWN_RESEARCH_FINDING_UPDATE: finding_updates may reference only exact IDs "
            + "already active on the current route; route="
            + routeId
            + "; stage="
            + stage
            + "; provider_call_id="
            + providerCallId;
    ResearchFindingAuditEvent rejection =
        new ResearchFindingAuditEvent(
            audit.size(), findingId, REJECT_UNKNOWN_ACTION, null, null, reason);
    if (rejectionKeys.add(rejectionKey(rejection))) {
      audit.add(rejection);
    }
  }

  private static String rejectionKey(ResearchFindingAuditEvent event) {
    return CanonicalJson.stableHash(
        List.of(event.findingId(), event.action(), event.reason()));
  }
}
