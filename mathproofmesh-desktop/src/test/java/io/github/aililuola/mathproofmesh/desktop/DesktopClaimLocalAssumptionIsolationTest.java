package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimKeyCompiler;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopClaimLocalAssumptionIsolationTest {
  @TempDir Path temp;

  @Test
  void factUnderLocalAssumptionCannotSupportUnconditionalClaim() throws Exception {
    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(temp, "claim-assumption-isolation")) {
      harness.freeze();
      var contexts = harness.productionClaimContexts(DesktopClaimContextTestSupport.strategy());
      var keys = DesktopClaimContextTestSupport.keys(contexts);
      boolean falseSupport =
          new CriticalClaimKeyCompiler()
              .exactEvidenceMatch(
                  keys.get("without-h"),
                  DesktopClaimContextTestSupport.STATEMENT,
                  contexts.get("under-h"));

      System.out.println("LOCAL_ASSUMPTION_FALSE_SUPPORTS=" + (falseSupport ? 1 : 0));
      assertThat(falseSupport).isFalse();
    }
  }
}
