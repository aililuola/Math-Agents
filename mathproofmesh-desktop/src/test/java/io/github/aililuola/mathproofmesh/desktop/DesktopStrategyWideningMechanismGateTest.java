package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopStrategyWideningMechanismGateTest {
  @TempDir Path temporaryDirectory;

  @Test
  void wideningComparesAgainstTheWholeActivePortfolioThroughTheSameStructuralGate()
      throws Exception {
    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(
            temporaryDirectory.resolve("widen"), "strategy-widening-mechanism")) {
      harness.freeze();
      harness.setStrategies(DesktopStrategyPortfolioTestHarness.fourIndependent("base"));
      harness.generateAndAdmit();
      StrategyCard active = harness.admittedStrategies().getFirst();
      int routesBefore = harness.routeStrategyIds().size();

      StrategyCard duplicate =
          DesktopStrategyPortfolioTestHarness.strategy(
              "widen-duplicate",
              "Fresh title over an old mechanism",
              active.coreIdea(),
              active.criticalClaims().getFirst().statement(),
              0.99d);
      harness.queueWideningCandidate(duplicate);
      boolean duplicateAdded = harness.widen();

      assertThat(duplicateAdded).isFalse();
      assertThat(harness.routeStrategyIds()).doesNotContain(duplicate.strategyId());
      assertThat(harness.routeStrategyIds()).hasSize(routesBefore);

      StrategyCard independent =
          DesktopStrategyPortfolioTestHarness.strategy(
              "widen-independent",
              "Independent decomposition",
              "Split the tree at a branching vertex and combine the component leaf counts",
              "Each component attached to a branching vertex contains an endpoint leaf.",
              0.50d);
      harness.queueWideningCandidate(independent);
      boolean independentAdded = harness.widen();

      assertThat(independentAdded).isTrue();
      assertThat(harness.routeStrategyIds()).contains(independent.strategyId());
      assertThat(harness.routeStrategyIds()).doesNotHaveDuplicates();
    }
  }
}
