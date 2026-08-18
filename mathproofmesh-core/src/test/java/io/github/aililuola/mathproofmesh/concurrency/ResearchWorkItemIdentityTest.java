package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class ResearchWorkItemIdentityTest {
  @Test
  void identityIsStableAndBindsLogicalTarget() {
    FrozenResearchSnapshot snapshot = ConcurrencyTestFixtures.snapshot();
    ResearchWorkItem first = ConcurrencyTestFixtures.item(snapshot, 0, "route-a", null);
    ResearchWorkItem same = ConcurrencyTestFixtures.item(snapshot, 0, "route-a", null);
    ResearchWorkItem other = ConcurrencyTestFixtures.item(snapshot, 0, "route-b", null);
    assertThat(first.workItemId()).isEqualTo(same.workItemId()).isNotEqualTo(other.workItemId());
  }
}
