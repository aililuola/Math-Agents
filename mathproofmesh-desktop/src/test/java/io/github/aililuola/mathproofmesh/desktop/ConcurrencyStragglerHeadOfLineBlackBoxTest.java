package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import org.junit.jupiter.api.Test;

final class ConcurrencyStragglerHeadOfLineBlackBoxTest {
  @Test
  void readyWorkStartsBeforeTheStragglerCompletes() {
    var run =
        DesktopResearchConcurrencyTestSupport.run(
            ResearchWorkKind.ROUTE_EXPLORATION,
            8,
            work -> work.equals(DesktopResearchConcurrencyTestSupport
                    .items(DesktopResearchConcurrencyTestSupport.snapshot("epoch-route_exploration"), ResearchWorkKind.ROUTE_EXPLORATION, 8)
                    .getFirst()
                    .workItemId())
                ? 180L
                : 20L);
    String slow = run.workItems().getFirst().workItemId();
    int slowCompletion = run.completedOrder().indexOf(slow);

    assertThat(run.maximumConcurrency()).isEqualTo(4);
    assertThat(slowCompletion).isGreaterThanOrEqualTo(4);
    assertThat(run.startedOrder()).hasSize(8);
  }
}
