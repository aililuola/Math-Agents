package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DesktopSustainedConcurrencyBlackBoxTest {
  @Test
  void concurrencyContinuesBeyondInitialExploration() {
    List<ResearchWorkKind> stages =
        List.of(
            ResearchWorkKind.ROUTE_EXPLORATION,
            ResearchWorkKind.ROUTE_REVIEW,
            ResearchWorkKind.CLAIM_PROOF_AUDIT,
            ResearchWorkKind.FOCUSED_REPROVER);
    for (ResearchWorkKind stage : stages) {
      var run = DesktopResearchConcurrencyTestSupport.run(stage, 4, ignored -> 30L);
      assertThat(run.maximumConcurrency()).as(stage.name()).isEqualTo(4);
      assertThat(run.results()).as(stage.name()).hasSize(4);
    }
  }
}
