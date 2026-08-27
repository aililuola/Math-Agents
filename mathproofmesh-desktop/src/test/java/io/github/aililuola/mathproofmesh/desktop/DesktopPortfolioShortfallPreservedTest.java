package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyDiversityConfig;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopPortfolioShortfallPreservedTest {
  @TempDir Path temp;

  @Test
  void lowQualityCandidatesDoNotPadAProductionPortfolio() throws Exception {
    List<StrategyCard> candidates =
        DesktopStrategyPortfolioTestHarness.fourIndependent("quality-shortfall");
    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(temp, "quality-shortfall")) {
      harness.freeze();
      harness.setDiversityConfig(
          StrategyDiversityConfig.defaults().withQualityGate(0.60d, 0.20d, 0.0d));
      support(harness, candidates.get(0));
      support(harness, candidates.get(1));
      harness.setStrategies(candidates);
      harness.generateAndAdmit();

      List<String> selected =
          harness.admittedStrategies().stream().map(StrategyCard::strategyId).toList();
      long lowAdmissions =
          selected.stream()
              .filter(
                  id ->
                      id.equals(candidates.get(2).strategyId())
                          || id.equals(candidates.get(3).strategyId()))
              .count();
      System.out.println("SELECTED=" + selected.size());
      System.out.println("LOW_FEASIBILITY_ADMISSIONS=" + lowAdmissions);
      System.out.println("PORTFOLIO_SHORTFALL=" + (4 - selected.size()));
      assertThat(selected)
          .containsExactlyInAnyOrder(
              candidates.get(0).strategyId(), candidates.get(1).strategyId());
      assertThat(lowAdmissions).isZero();
    }
  }

  static void support(
      DesktopStrategyPortfolioTestHarness harness, StrategyCard strategy) {
    harness.registerVerifiedClaimForStrategy(
        strategy, "verified-" + strategy.strategyId());
  }
}
