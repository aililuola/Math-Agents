package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import org.junit.jupiter.api.Test;

final class DesktopRouteReviewBatchProductionTest {
  @Test
  void independentRouteReviewsRunAsOneFrozenBatch() {
    var run =
        DesktopResearchConcurrencyTestSupport.run(
            ResearchWorkKind.ROUTE_REVIEW, 4, ignored -> 40L);
    assertThat(run.maximumConcurrency()).isEqualTo(4);
    assertThat(run.results()).hasSize(4);
    assertThat(run.results()).allMatch(result -> result.snapshotHash().equals(run.snapshot().snapshotHash()));
  }
}
