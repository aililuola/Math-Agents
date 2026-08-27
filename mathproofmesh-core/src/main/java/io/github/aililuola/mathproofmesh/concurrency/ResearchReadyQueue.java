package io.github.aililuola.mathproofmesh.concurrency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class ResearchReadyQueue {
  private final List<ResearchWorkItem> ready = new ArrayList<>();

  public synchronized void addAll(List<ResearchWorkItem> items) {
    ready.addAll(items);
    ready.sort(order());
  }

  public synchronized Optional<ResearchWorkItem> pollCompatible(
      List<ResearchWorkItem> inFlight) {
    for (int index = 0; index < ready.size(); index++) {
      ResearchWorkItem candidate = ready.get(index);
      boolean conflict =
          inFlight.stream()
              .anyMatch(
                  item -> item.conflictSet().conflictsWith(candidate.conflictSet()));
      if (!conflict) {
        ready.remove(index);
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  public synchronized boolean isEmpty() {
    return ready.isEmpty();
  }

  public synchronized int size() {
    return ready.size();
  }

  private static Comparator<ResearchWorkItem> order() {
    return Comparator.comparingInt(ResearchWorkItem::stableOrdinal)
        .thenComparing(ResearchWorkItem::routeId)
        .thenComparing(ResearchWorkItem::claimId)
        .thenComparing(ResearchWorkItem::obligationId)
        .thenComparing(ResearchWorkItem::workItemId);
  }
}
