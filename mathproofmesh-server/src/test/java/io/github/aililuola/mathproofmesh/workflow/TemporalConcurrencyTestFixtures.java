package io.github.aililuola.mathproofmesh.workflow;

import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseClass;
import io.github.aililuola.mathproofmesh.concurrency.FrozenResearchSnapshot;
import io.github.aililuola.mathproofmesh.concurrency.ResearchAuthorityAnchor;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkConflictSet;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkItem;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkReadSet;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkResultEnvelope;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkResultStatus;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TemporalConcurrencyTestFixtures {
  private TemporalConcurrencyTestFixtures() {}

  static FrozenResearchSnapshot snapshot() {
    return new FrozenResearchSnapshot(
        "temporal-epoch",
        new ResearchAuthorityAnchor(
            "problem",
            "root",
            "negative",
            "attempt",
            "claim",
            "research",
            "graph",
            "canonical",
            "convergence",
            "pivot",
            "portfolio",
            "court",
            "broker",
            "computation",
            "run"),
        Map.of());
  }

  static ResearchWorkItem item(FrozenResearchSnapshot snapshot, int ordinal) {
    String route = "route-" + ordinal;
    return new ResearchWorkItem(
        "work-" + ordinal,
        snapshot.epochId(),
        snapshot.snapshotHash(),
        ResearchWorkKind.ROUTE_EXPLORATION,
        route,
        "claim-" + ordinal,
        "obligation-" + ordinal,
        "target-" + ordinal,
        "explorer",
        AgentLeaseClass.RESEARCH,
        Set.of(),
        ResearchWorkReadSet.empty(),
        ResearchWorkConflictSet.empty(),
        "artifact://input/" + ordinal,
        "schema",
        ordinal);
  }

  static ResearchWorkResultEnvelope result(ResearchWorkItem item, String agent) {
    return new ResearchWorkResultEnvelope(
        item.workItemId(),
        item.epochId(),
        item.snapshotHash(),
        agent,
        "request-" + item.workItemId(),
        ResearchWorkResultStatus.SUCCEEDED,
        Map.of("ordinal", item.stableOrdinal()),
        List.of(),
        List.of(),
        List.of());
  }
}
