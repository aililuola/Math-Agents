package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimPreflightStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StrategyPreflightBoundedNonRefutationTest {
  @TempDir Path temp;

  @Test
  void boundedSearchWithoutCounterexampleDoesNotBecomeVerifiedSupport() throws Exception {
    StrategyCard candidate =
        DesktopRegisteredContractPreflightExecutionTest.registeredIntegerStrategy(
            "bounded-non-refutation-strategy",
            "For every integer x in {0,1}, x is at most 1.",
            "le");
    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(temp, "bounded-non-refutation")) {
      harness.freeze();
      harness.setStrategies(List.of(candidate));
      harness.generateAndAdmit();

      var claim =
          harness.preflights().find(candidate.strategyId()).orElseThrow().claims().getFirst();
      int nonRefutations =
          claim.status() == CriticalClaimPreflightStatus.NOT_REFUTED_IN_BOUNDED_SCOPE ? 1 : 0;
      int falseSupports =
          claim.status() == CriticalClaimPreflightStatus.VERIFIED_SUPPORTED ? 1 : 0;
      System.out.println("BOUNDED_NON_REFUTATIONS=" + nonRefutations);
      System.out.println(
          "BOUNDED_NON_REFUTATION_VERIFIED_SUPPORTS=" + falseSupports);
      assertThat(claim.status())
          .isEqualTo(CriticalClaimPreflightStatus.NOT_REFUTED_IN_BOUNDED_SCOPE);
      assertThat(
              harness
                  .preflights()
                  .find(candidate.strategyId())
                  .orElseThrow()
                  .requiredClaimEvidenceCoverage())
          .isZero();
      assertThat(harness.preflightExecutionCount()).isEqualTo(1);
    }
  }
}
