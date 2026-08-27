package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimKeyCompiler;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopClaimLocalQuantifierIsolationTest {
  @TempDir Path temp;

  @Test
  void existentialFactCannotSupportUniversallyBoundClaim() throws Exception {
    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(temp, "claim-quantifier-isolation")) {
      harness.freeze();
      var contexts = harness.productionClaimContexts(DesktopClaimContextTestSupport.strategy());
      var keys = DesktopClaimContextTestSupport.keys(contexts);
      boolean falseSupport =
          new CriticalClaimKeyCompiler()
              .exactEvidenceMatch(
                  keys.get("forall-x"),
                  DesktopClaimContextTestSupport.STATEMENT,
                  contexts.get("exists-x"));

      System.out.println("LOCAL_QUANTIFIER_FALSE_SUPPORTS=" + (falseSupport ? 1 : 0));
      assertThat(falseSupport).isFalse();
    }
  }
}
