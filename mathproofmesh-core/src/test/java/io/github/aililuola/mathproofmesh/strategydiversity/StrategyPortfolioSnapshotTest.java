package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StrategyPortfolioSnapshotTest {
  @Test
  void committedPortfolioIsExactlyOnceAcrossRestore() {
    StrategyPortfolioDecision decision =
        new StrategyPortfolioDecision(
            "episode",
            List.of("a", "b"),
            Map.of("c", "SAME_STRUCTURAL_MECHANISM"),
            21.0d,
            true,
            "decision-hash",
            List.of());
    StrategyPortfolioRegistry registry = new StrategyPortfolioRegistry();
    registry.record(decision);
    String before = registry.registryHash();

    StrategyPortfolioRegistry restored = StrategyPortfolioRegistry.restore(registry.snapshot());

    assertThat(restored.registryHash()).isEqualTo(before);
    assertThat(restored.record(decision)).isEqualTo(decision);
    assertThat(restored.snapshot().decisions()).hasSize(1);
  }
}
