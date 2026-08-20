package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DesktopFocusedAttackMatrixProductionTest {
  @Test
  void focusedRolesUseAllResearchSlots() {
    List<ResearchWorkKind> roles =
        List.of(
            ResearchWorkKind.FOCUSED_PROVER,
            ResearchWorkKind.FOCUSED_FALSIFIER,
            ResearchWorkKind.FOCUSED_REPROVER,
            ResearchWorkKind.DEPENDENCY_AUDITOR);
    int concurrent = 0;
    for (ResearchWorkKind role : roles) {
      var run = DesktopResearchConcurrencyTestSupport.run(role, 4, ignored -> 25L);
      concurrent = Math.max(concurrent, run.maximumConcurrency());
      assertThat(run.results()).hasSize(4);
    }
    assertThat(concurrent).isEqualTo(4);
  }
}
