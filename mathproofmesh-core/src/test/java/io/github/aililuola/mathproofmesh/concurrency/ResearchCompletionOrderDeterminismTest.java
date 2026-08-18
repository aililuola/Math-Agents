package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ResearchCompletionOrderDeterminismTest {
  @Test
  void reversedCompletionOrderProducesSameMergeHash() {
    FrozenResearchSnapshot snapshot = ConcurrencyTestFixtures.snapshot();
    ResearchWorkItem a = ConcurrencyTestFixtures.item(snapshot, 0, "a", null);
    ResearchWorkItem b = ConcurrencyTestFixtures.item(snapshot, 1, "b", null);
    ResearchWorkResultEnvelope ra = ConcurrencyTestFixtures.result(a, "agent-a");
    ResearchWorkResultEnvelope rb = ConcurrencyTestFixtures.result(b, "agent-b");
    ResearchMergePlanner planner = new ResearchMergePlanner();
    assertThat(planner.plan(snapshot, List.of(a, b), List.of(ra, rb)).mergePlanHash())
        .isEqualTo(planner.plan(snapshot, List.of(a, b), List.of(rb, ra)).mergePlanHash());
  }
}
