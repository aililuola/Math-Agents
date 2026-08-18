package io.github.aililuola.mathproofmesh.concurrency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ResearchWorkConflictPolicy {
  public List<ResearchWorkItem> maximumStableIndependentSet(
      List<ResearchWorkItem> candidates, int limit) {
    if (limit < 0) {
      throw new IllegalArgumentException("limit must be nonnegative");
    }
    List<ResearchWorkItem> selected = new ArrayList<>();
    candidates.stream()
        .sorted(
            Comparator.comparingInt(ResearchWorkItem::stableOrdinal)
                .thenComparing(ResearchWorkItem::workItemId))
        .forEach(
            candidate -> {
              if (selected.size() < limit
                  && selected.stream()
                      .noneMatch(
                          existing ->
                              existing.conflictSet().conflictsWith(candidate.conflictSet()))) {
                selected.add(candidate);
              }
            });
    return List.copyOf(selected);
  }
}
