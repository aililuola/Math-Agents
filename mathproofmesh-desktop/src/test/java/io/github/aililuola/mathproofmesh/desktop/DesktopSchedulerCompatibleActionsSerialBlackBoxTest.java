package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import org.junit.jupiter.api.Test;

final class DesktopSchedulerCompatibleActionsSerialBlackBoxTest {
  @Test
  void compatibleRouteActionsFormOneConflictFreeBatch() {
    var run =
        DesktopResearchConcurrencyTestSupport.run(
            ResearchWorkKind.PIVOT_PROPOSAL, 4, ignored -> 35L);
    assertThat(run.maximumConcurrency()).isEqualTo(4);
    assertThat(run.mergePlan().decisions()).hasSize(4);
  }
}
