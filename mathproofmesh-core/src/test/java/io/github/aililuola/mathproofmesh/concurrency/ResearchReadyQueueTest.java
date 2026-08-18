package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ResearchReadyQueueTest {
  @Test
  void skipsHeadConflictAndKeepsIndependentWorkFlowing() {
    FrozenResearchSnapshot snapshot = ConcurrencyTestFixtures.snapshot();
    ResearchWorkItem running = ConcurrencyTestFixtures.item(snapshot, 0, "running", "shared");
    ResearchWorkItem blocked = ConcurrencyTestFixtures.item(snapshot, 1, "blocked", "shared");
    ResearchWorkItem ready = ConcurrencyTestFixtures.item(snapshot, 2, "ready", null);
    ResearchReadyQueue queue = new ResearchReadyQueue();
    queue.addAll(List.of(blocked, ready));
    assertThat(queue.pollCompatible(List.of(running)).orElseThrow().workItemId())
        .isEqualTo(ready.workItemId());
  }
}
