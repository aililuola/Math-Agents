package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopPortfolioGlobalSelectionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void globallyOptimizesWithoutSelectingTwoRoutesFromOneUnresolvedClaimGroup()
      throws Exception {
    String common = "A fixed endpoint-deletion operation preserves every required invariant.";
    List<StrategyCard> candidates = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      candidates.add(
          DesktopStrategyPortfolioTestHarness.strategy(
              "common-" + index,
              "Common-mode presentation " + index,
              "Surface transformation " + index,
              common,
              0.90d + index * 0.01d));
    }
    candidates.addAll(DesktopStrategyPortfolioTestHarness.fourIndependent("independent"));

    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(
            temporaryDirectory.resolve("global"), "portfolio-global-selection")) {
      harness.freeze();
      harness.setStrategies(candidates);
      harness.generateAndAdmit();

      List<StrategyCard> selected = harness.admittedStrategies();
      assertThat(
              selected.stream()
                  .filter(strategy -> strategy.strategyId().startsWith("common-")))
          .hasSize(1);
      assertThat(selected)
          .extracting(StrategyCard::strategyId)
          .doesNotHaveDuplicates();
      assertThat(harness.portfolios().snapshot().decisions().values())
          .singleElement()
          .satisfies(
              decision ->
                  assertThat(decision.nonSelectionReasons())
                      .containsValue("SHARED_UNRESOLVED_REQUIRED_CLAIM"));
    }
  }
}
