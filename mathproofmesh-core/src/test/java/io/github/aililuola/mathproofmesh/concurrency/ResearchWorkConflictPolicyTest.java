package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ResearchWorkConflictPolicyTest {
  @Test
  void stableIndependentSetExcludesSharedMutationTargets() {
    FrozenResearchSnapshot snapshot = ConcurrencyTestFixtures.snapshot();
    ResearchWorkItem a = ConcurrencyTestFixtures.item(snapshot, 0, "a", "same");
    ResearchWorkItem b = ConcurrencyTestFixtures.item(snapshot, 1, "b", "same");
    ResearchWorkItem c = ConcurrencyTestFixtures.item(snapshot, 2, "c", null);
    assertThat(new ResearchWorkConflictPolicy().maximumStableIndependentSet(List.of(b, c, a), 3))
        .extracting(ResearchWorkItem::workItemId)
        .containsExactly(a.workItemId(), c.workItemId());
  }
}
