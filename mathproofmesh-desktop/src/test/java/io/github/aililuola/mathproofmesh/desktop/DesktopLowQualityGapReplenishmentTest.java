package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyDiversityConfig;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopLowQualityGapReplenishmentTest {
  @TempDir Path temp;

  @Test
  void qualityShortfallTriggersExactlyOneBoundedReplenishment() throws Exception {
    List<StrategyCard> initial =
        DesktopStrategyPortfolioTestHarness.fourIndependent("initial-quality");
    List<StrategyCard> supplement =
        DesktopStrategyPortfolioTestHarness.fourIndependent("supplement-quality");
    StrategySet first = new StrategySet("Initial four.", List.of(), initial);
    StrategySet second = new StrategySet("One-shot supplement.", List.of(), supplement);
    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(
            temp, "low-quality-replenishment", List.of(first, second))) {
      harness.freeze();
      harness.setDiversityConfig(
          StrategyDiversityConfig.defaults().withQualityGate(0.60d, 0.20d, 0.0d));
      DesktopPortfolioShortfallPreservedTest.support(harness, initial.get(0));
      DesktopPortfolioShortfallPreservedTest.support(harness, initial.get(1));
      harness.generateAndAdmit();

      int selected = harness.admittedStrategies().size();
      long replenishments = harness.replenishmentProviderCalls();
      int secondCalls = Math.max(0, harness.providerStrategyCalls() - 2);
      System.out.println("REPLENISHMENT_REQUESTS=" + replenishments);
      System.out.println("SECOND_REPLENISHMENT_CALLS=" + secondCalls);
      System.out.println("FINAL_SELECTED=" + selected);
      assertThat(replenishments).isEqualTo(1L);
      assertThat(secondCalls).isZero();
      assertThat(selected).isEqualTo(2);
    }
  }
}
