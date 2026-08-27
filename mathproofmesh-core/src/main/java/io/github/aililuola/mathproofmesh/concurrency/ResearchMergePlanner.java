package io.github.aililuola.mathproofmesh.concurrency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ResearchMergePlanner {
  public ResearchMergePlan plan(
      FrozenResearchSnapshot snapshot,
      List<ResearchWorkItem> workItems,
      List<ResearchWorkResultEnvelope> results) {
    Objects.requireNonNull(snapshot, "snapshot");
    Map<String, ResearchWorkItem> items = new LinkedHashMap<>();
    for (ResearchWorkItem item : workItems) {
      if (!item.epochId().equals(snapshot.epochId())
          || !item.snapshotHash().equals(snapshot.snapshotHash())) {
        throw new IllegalArgumentException("work item is not bound to the frozen snapshot");
      }
      if (items.put(item.workItemId(), item) != null) {
        throw new IllegalArgumentException("duplicate work item identity");
      }
    }
    List<ResearchMergeDecision> decisions = new ArrayList<>();
    Map<String, ResearchWorkResultEnvelope> uniqueResults = new LinkedHashMap<>();
    for (ResearchWorkResultEnvelope result : results) {
      ResearchWorkItem item = items.get(result.workItemId());
      if (item == null) {
        throw new IllegalArgumentException("result has no planned work item");
      }
      if (uniqueResults.put(result.workItemId(), result) != null) {
        throw new IllegalArgumentException("duplicate result for work item " + result.workItemId());
      }
      boolean bound =
          result.epochId().equals(snapshot.epochId())
              && result.snapshotHash().equals(snapshot.snapshotHash());
      boolean accepted = bound && result.status() == ResearchWorkResultStatus.SUCCEEDED;
      decisions.add(
          new ResearchMergeDecision(
              item.workItemId(),
              result.resultHash(),
              accepted,
              accepted ? "accepted" : bound ? "result_not_successful" : "stale_snapshot",
              item.stableOrdinal(),
              item.routeId(),
              item.claimId(),
              item.obligationId()));
    }
    if (!uniqueResults.keySet().equals(items.keySet())) {
      throw new IllegalStateException("ALL_SETTLED barrier requires one durable result per work item");
    }
    decisions.sort(
        Comparator.comparingInt(ResearchMergeDecision::stableOrdinal)
            .thenComparing(ResearchMergeDecision::routeId)
            .thenComparing(ResearchMergeDecision::claimId)
            .thenComparing(ResearchMergeDecision::obligationId)
            .thenComparing(ResearchMergeDecision::workItemId));
    return new ResearchMergePlan(snapshot.epochId(), snapshot.snapshotHash(), decisions);
  }
}
