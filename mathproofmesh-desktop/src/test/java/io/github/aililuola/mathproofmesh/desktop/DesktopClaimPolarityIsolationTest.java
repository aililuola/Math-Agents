package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimKeyCompiler;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopClaimPolarityIsolationTest {
  @TempDir Path temp;

  @Test
  void positiveFactCannotSupportNegativeClaimUse() throws Exception {
    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(temp, "claim-polarity-isolation")) {
      harness.freeze();
      var contexts = harness.productionClaimContexts(DesktopClaimContextTestSupport.strategy());
      var keys = DesktopClaimContextTestSupport.keys(contexts);
      boolean falseSupport =
          new CriticalClaimKeyCompiler()
              .exactEvidenceMatch(
                  keys.get("negative-p"),
                  DesktopClaimContextTestSupport.STATEMENT,
                  contexts.get("without-h"));

      System.out.println("POLARITY_FALSE_SUPPORTS=" + (falseSupport ? 1 : 0));
      assertThat(falseSupport).isFalse();
    }
  }
}
