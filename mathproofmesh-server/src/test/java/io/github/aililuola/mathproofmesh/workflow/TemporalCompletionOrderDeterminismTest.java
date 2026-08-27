package io.github.aililuola.mathproofmesh.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchMergePlanner;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TemporalCompletionOrderDeterminismTest {
  @Test
  void childCompletionOrderDoesNotChangeMergeHash() {
    var snapshot = TemporalConcurrencyTestFixtures.snapshot();
    var first = TemporalConcurrencyTestFixtures.item(snapshot, 0);
    var second = TemporalConcurrencyTestFixtures.item(snapshot, 1);
    var firstResult = TemporalConcurrencyTestFixtures.result(first, "agent-0");
    var secondResult = TemporalConcurrencyTestFixtures.result(second, "agent-1");
    ResearchMergePlanner planner = new ResearchMergePlanner();
    assertThat(
            planner
                .plan(snapshot, List.of(first, second), List.of(firstResult, secondResult))
                .mergePlanHash())
        .isEqualTo(
            planner
                .plan(snapshot, List.of(first, second), List.of(secondResult, firstResult))
                .mergePlanHash());
  }
}
