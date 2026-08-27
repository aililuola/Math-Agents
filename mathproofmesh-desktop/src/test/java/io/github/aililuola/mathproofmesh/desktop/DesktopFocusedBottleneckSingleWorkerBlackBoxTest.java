package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import org.junit.jupiter.api.Test;

final class DesktopFocusedBottleneckSingleWorkerBlackBoxTest {
  @Test
  void focusedAttackWorkUsesFourSlots() {
    var run =
        DesktopResearchConcurrencyTestSupport.run(
            ResearchWorkKind.FOCUSED_FALSIFIER, 4, ignored -> 35L);
    assertThat(run.maximumConcurrency()).isEqualTo(4);
  }
}
