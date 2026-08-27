package io.github.aililuola.mathproofmesh.concurrency;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConcurrencyTestFixtures {
  private ConcurrencyTestFixtures() {}

  static ResearchAuthorityAnchor anchor() {
    return new ResearchAuthorityAnchor(
        "problem", "root", "negative", "attempt", "claim", "checkpoint", "graph",
        "canonical", "convergence", "pivot", "portfolio", "court", "broker",
        "computation", "run");
  }

  static FrozenResearchSnapshot snapshot() {
    String epoch = ResearchEpochId.deterministic("run", 1, anchor().stableHash());
    return new FrozenResearchSnapshot(epoch, anchor(), Map.of("problem", "problem://source"));
  }

  static ResearchWorkItem item(
      FrozenResearchSnapshot snapshot, int ordinal, String routeId, String conflictRoute) {
    ResearchWorkConflictSet conflicts =
        conflictRoute == null
            ? ResearchWorkConflictSet.empty()
            : new ResearchWorkConflictSet(
                Set.of(conflictRoute), Set.of(), Set.of(), Set.of(), Set.of());
    String id =
        ResearchWorkItem.deterministicId(
            snapshot.epochId(), ResearchWorkKind.ROUTE_REVIEW, routeId, "claim-" + ordinal, "", ordinal);
    return new ResearchWorkItem(
        id,
        snapshot.epochId(),
        snapshot.snapshotHash(),
        ResearchWorkKind.ROUTE_REVIEW,
        routeId,
        "claim-" + ordinal,
        "",
        "target-" + ordinal,
        "reviewer",
        AgentLeaseClass.ADVERSARIAL_REVIEW,
        Set.of("author-" + ordinal),
        new ResearchWorkReadSet(Set.of(snapshot.authority().stableHash()), Set.of("input-" + ordinal)),
        conflicts,
        "artifact://input-" + ordinal,
        "route-review-v1",
        ordinal);
  }

  static ResearchWorkResultEnvelope result(ResearchWorkItem item, String agent) {
    return new ResearchWorkResultEnvelope(
        item.workItemId(),
        item.epochId(),
        item.snapshotHash(),
        agent,
        "request-" + item.stableOrdinal(),
        ResearchWorkResultStatus.SUCCEEDED,
        Map.of("verdict", "accepted", "ordinal", item.stableOrdinal()),
        List.of(),
        List.of(),
        List.of("usage-" + item.stableOrdinal()));
  }
}
