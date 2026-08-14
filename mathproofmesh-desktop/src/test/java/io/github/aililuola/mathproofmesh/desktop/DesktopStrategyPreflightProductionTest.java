package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimPreflightStatus;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyCandidateStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopStrategyPreflightProductionTest {
  private static final String REFUTED =
      "Every connected finite graph has a Hamiltonian cycle.";

  @TempDir Path temporaryDirectory;

  @Test
  void trustedCounterexampleOverridesModelStatusAndSuccessPriorBeforeAdmission()
      throws Exception {
    StrategyCard rejected =
        DesktopStrategyPortfolioTestHarness.strategy(
            "refuted-route",
            "Model favorite",
            "Reduce the tree theorem to a Hamiltonian-cycle shortcut",
            REFUTED,
            0.99d);
    List<StrategyCard> candidates =
        new ArrayList<>(DesktopStrategyPortfolioTestHarness.fourIndependent("valid"));
    candidates.add(rejected);

    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(
            temporaryDirectory.resolve("preflight"), "strategy-preflight-production")) {
      harness.freeze();
      harness.registerVerifiedCounterexample(REFUTED, "path-p4-counterexample");
      harness.setStrategies(candidates);
      harness.generateAndAdmit();

      assertThat(harness.admittedStrategies())
          .extracting(StrategyCard::strategyId)
          .doesNotContain(rejected.strategyId());
      assertThat(harness.routeStrategyIds()).doesNotContain(rejected.strategyId());
      assertThat(harness.candidates().find(rejected.strategyId()))
          .hasValueSatisfying(
              record ->
                  assertThat(record.status())
                      .isEqualTo(StrategyCandidateStatus.REJECTED_REFUTED_REQUIRED_CLAIM));
      assertThat(harness.preflights().find(rejected.strategyId()))
          .hasValueSatisfying(
              report ->
                  assertThat(report.claims())
                      .anyMatch(
                          claim ->
                              claim.status()
                                  == CriticalClaimPreflightStatus.VERIFIED_REFUTED));
      assertThat(harness.state().archiveCount())
          .isEqualTo(harness.admittedStrategies().size());
    }
  }
}
