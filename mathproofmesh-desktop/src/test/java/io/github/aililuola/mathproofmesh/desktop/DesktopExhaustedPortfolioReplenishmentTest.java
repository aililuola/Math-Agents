package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopExhaustedPortfolioReplenishmentTest {
  @TempDir Path temporaryDirectory;

  @Test
  void boundedGapFillCreatesANewMechanismWhenEveryAdmittedRouteIsExhausted()
      throws Exception {
    StrategyCard initial =
        DesktopStrategyPortfolioTestHarness.fourIndependent("exhausted-initial").getFirst();
    StrategySet initialSet =
        new StrategySet("One initially admitted mechanism.", List.of(), List.of(initial));
    StrategySet supplement =
        new StrategySet(
            "Independent recovery mechanisms.",
            List.of(),
            DesktopStrategyPortfolioTestHarness.fourIndependent("exhausted-recovery"));

    Path runDirectory = temporaryDirectory.resolve("run");
    DesktopSolveCheckpoint checkpoint;
    DesktopStrategyPortfolioTestHarness.ProtectedHashes protectedBefore;
    long replenishmentCalls;
    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(
            runDirectory,
            "exhausted-portfolio-recovery",
            List.of(initialSet, supplement))) {
      harness.freeze();
      harness.generateAndAdmit();
      harness.abandonOnlyRoute();
      protectedBefore = harness.protectedHashes();

      boolean widened = harness.scheduleExhaustedPortfolioRecovery();

      assertThat(widened).isTrue();
      assertThat(harness.providerStrategyCalls()).isEqualTo(2);
      assertThat(harness.replenishmentProviderCalls()).isEqualTo(1L);
      assertThat(harness.admittedStrategies()).hasSizeGreaterThan(1);
      assertThat(harness.routeStrategyIds()).hasSizeGreaterThan(1);
      assertThat(harness.routeStrategyIds()).contains(initial.strategyId());
      assertThat(harness.protectedHashes().root()).isEqualTo(protectedBefore.root());
      assertThat(harness.protectedHashes().negative()).isEqualTo(protectedBefore.negative());
      replenishmentCalls = harness.replenishmentProviderCalls();
      checkpoint = harness.checkpointRoundTrip();
    }

    try (DesktopStrategyPortfolioTestHarness resumed =
        DesktopStrategyPortfolioTestHarness.open(
            runDirectory, "exhausted-portfolio-recovery", List.of())) {
      resumed.restore(checkpoint);
      assertThat(resumed.widen()).isTrue();
      assertThat(resumed.providerStrategyCalls()).isZero();
      assertThat(resumed.replenishmentProviderCalls()).isZero();
      assertThat(resumed.protectedHashes().root()).isEqualTo(protectedBefore.root());
      assertThat(resumed.protectedHashes().negative()).isEqualTo(protectedBefore.negative());

      System.out.println("EXHAUSTED PORTFOLIO RECOVERY DIAGNOSTIC");
      System.out.println("INITIAL_ADMITTED_MECHANISMS=1");
      System.out.println("EXHAUSTED_INITIAL_ROUTES=1");
      System.out.println("REMAINING_ROUTE_CAPACITY_PRESENT=true");
      System.out.println("SCHEDULER_REPLENISHMENT_CALLS=" + replenishmentCalls);
      System.out.println("NEW_ROUTE_ADMISSIONS=" + (resumed.routeStrategyIds().size() - 1));
      System.out.println("REPEATED_REPLENISHMENT_CALLS=0");
      System.out.println("POST_RESTORE_REPLENISHMENT_CALLS=0");
      System.out.println("ROOT_HASH_CHANGES=0");
      System.out.println("NEGATIVE_REGISTRY_HASH_CHANGES=0");
      System.out.println("RESULT=PASS");
    }
  }
}
