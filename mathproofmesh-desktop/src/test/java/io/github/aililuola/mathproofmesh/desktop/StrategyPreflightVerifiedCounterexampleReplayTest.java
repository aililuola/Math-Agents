package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimPreflightStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StrategyPreflightVerifiedCounterexampleReplayTest {
  @TempDir Path temp;

  @Test
  void onlyIndependentlyReplayedCounterexampleRefutesRequiredClaim() throws Exception {
    StrategyCard candidate = DesktopRegisteredContractPreflightExecutionTest.falsifiableStrategy();
    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(temp, "verified-counterexample-replay")) {
      harness.freeze();
      harness.setStrategies(List.of(candidate));
      harness.generateAndAdmit();

      var claim =
          harness.preflights().find(candidate.strategyId()).orElseThrow().claims().getFirst();
      var evidence = claim.evidence().getFirst();
      int replayed =
          claim.status() == CriticalClaimPreflightStatus.VERIFIED_REFUTED
                  && "registered-computation-replay".equals(evidence.authority())
              ? 1
              : 0;
      int unreplayed =
          claim.status() == CriticalClaimPreflightStatus.VERIFIED_REFUTED
                  && !"registered-computation-replay".equals(evidence.authority())
              ? 1
              : 0;
      System.out.println("INDEPENDENTLY_REPLAYED_COUNTEREXAMPLES=" + replayed);
      System.out.println("UNREPLAYED_COUNTEREXAMPLE_AUTHORITIES=" + unreplayed);
      assertThat(claim.status()).isEqualTo(CriticalClaimPreflightStatus.VERIFIED_REFUTED);
      assertThat(evidence.authority()).isEqualTo("registered-computation-replay");
      assertThat(evidence.evidenceRefs()).hasSize(2);
      assertThat(evidence.detail()).isEqualTo("INDEPENDENTLY_REPLAYED_COUNTEREXAMPLE");
      assertThat(harness.admittedStrategies()).isEmpty();
    }
  }
}
