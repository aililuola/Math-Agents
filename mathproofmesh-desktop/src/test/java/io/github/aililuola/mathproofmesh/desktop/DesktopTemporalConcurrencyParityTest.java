package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchMergePlanner;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;

final class DesktopTemporalConcurrencyParityTest {
  @Test
  void desktopAndReplayOrderingProduceTheSameMergePlan() {
    var desktop =
        DesktopResearchConcurrencyTestSupport.run(
            ResearchWorkKind.ROUTE_EXPLORATION, 4, ignored -> 20L);
    var temporalCompletionOrder = new ArrayList<>(desktop.results());
    Collections.rotate(temporalCompletionOrder, 2);
    var temporal =
        new ResearchMergePlanner()
            .plan(desktop.snapshot(), desktop.workItems(), temporalCompletionOrder);
    assertThat(temporal.mergePlanHash()).isEqualTo(desktop.mergePlan().mergePlanHash());
  }
}
