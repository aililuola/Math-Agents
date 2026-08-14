package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopStrategyMechanismProductionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void productionAdmissionUsesServerOwnedStructuralMechanismsBeforeActiveWrites()
      throws Exception {
    List<StrategyCard> candidates = new ArrayList<>();
    for (int index = 0; index < 6; index++) {
      candidates.add(
          DesktopStrategyPortfolioTestHarness.strategy(
              "presentation-" + index,
              "Different title " + index,
              "Delete a leaf and apply the same induction dependency DAG",
              "Deleting a leaf preserves the induction invariant.",
              0.80d + index * 0.01d));
    }
    candidates.addAll(DesktopStrategyPortfolioTestHarness.fourIndependent("independent"));

    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(
            temporaryDirectory.resolve("production"), "strategy-mechanism-production")) {
      harness.freeze();
      harness.setStrategies(candidates);
      harness.generateAndAdmit();

      List<StrategyCard> admitted = harness.admittedStrategies();
      long titleOnlyAdmissions =
          admitted.stream()
              .filter(strategy -> strategy.strategyId().startsWith("presentation-"))
              .count();
      DesktopStrategyPortfolioTestHarness.ProductionState state = harness.state();

      assertThat(titleOnlyAdmissions).isEqualTo(1L);
      assertThat(admitted).doesNotHaveDuplicates();
      assertThat(state.archiveCount()).isEqualTo(admitted.size());
      assertThat(state.blueprintCount()).isEqualTo(admitted.size());
      assertThat(state.goalLinkCount()).isEqualTo(admitted.size());
      assertThat(harness.mechanisms().snapshot().signatures()).hasSize(candidates.size());
      assertThat(harness.portfolios().snapshot().receipts()).hasSize(1);
      assertThat(harness.providerStrategyCalls()).isZero();
    }
  }
}
