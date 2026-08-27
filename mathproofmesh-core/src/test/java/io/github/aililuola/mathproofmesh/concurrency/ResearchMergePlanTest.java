package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ResearchMergePlanTest {
  @Test
  void allSettledBarrierRejectsMissingResults() {
    FrozenResearchSnapshot snapshot = ConcurrencyTestFixtures.snapshot();
    ResearchWorkItem a = ConcurrencyTestFixtures.item(snapshot, 0, "a", null);
    ResearchWorkItem b = ConcurrencyTestFixtures.item(snapshot, 1, "b", null);
    assertThatThrownBy(
            () -> new ResearchMergePlanner().plan(snapshot, List.of(a, b), List.of(ConcurrencyTestFixtures.result(a, "agent"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ALL_SETTLED");
  }

  @Test
  void acceptedHashesFollowStableMathematicalOrder() {
    FrozenResearchSnapshot snapshot = ConcurrencyTestFixtures.snapshot();
    ResearchWorkItem a = ConcurrencyTestFixtures.item(snapshot, 0, "a", null);
    ResearchWorkItem b = ConcurrencyTestFixtures.item(snapshot, 1, "b", null);
    ResearchWorkResultEnvelope ra = ConcurrencyTestFixtures.result(a, "agent-a");
    ResearchWorkResultEnvelope rb = ConcurrencyTestFixtures.result(b, "agent-b");
    ResearchMergePlan plan = new ResearchMergePlanner().plan(snapshot, List.of(b, a), List.of(rb, ra));
    assertThat(plan.acceptedResultHashes()).containsExactly(ra.resultHash(), rb.resultHash());
  }
}
