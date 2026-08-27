package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class ResearchEpochPlanner {
  private final ResearchWorkConflictPolicy conflicts = new ResearchWorkConflictPolicy();

  public Plan plan(FrozenResearchSnapshot snapshot, List<ResearchWorkItem> candidates, int limit) {
    Objects.requireNonNull(snapshot, "snapshot");
    for (ResearchWorkItem item : candidates) {
      if (!item.epochId().equals(snapshot.epochId())
          || !item.snapshotHash().equals(snapshot.snapshotHash())) {
        throw new IllegalArgumentException("candidate is not bound to the frozen epoch");
      }
    }
    List<ResearchWorkItem> selected =
        conflicts.maximumStableIndependentSet(candidates, limit).stream()
            .sorted(
                Comparator.comparingInt(ResearchWorkItem::stableOrdinal)
                    .thenComparing(ResearchWorkItem::workItemId))
            .toList();
    return new Plan(snapshot, selected);
  }

  public record Plan(FrozenResearchSnapshot snapshot, List<ResearchWorkItem> workItems) {
    public Plan {
      snapshot = Objects.requireNonNull(snapshot, "snapshot");
      workItems = workItems == null ? List.of() : List.copyOf(workItems);
    }

    @Override
    public List<ResearchWorkItem> workItems() {
      return List.copyOf(workItems);
    }
  }
}
