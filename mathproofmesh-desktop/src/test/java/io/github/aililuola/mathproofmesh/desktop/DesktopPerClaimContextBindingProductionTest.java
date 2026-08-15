package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopPerClaimContextBindingProductionTest {
  @TempDir Path temp;

  @Test
  void productionBuilderCreatesOneDistinctSemanticContextPerClaim() throws Exception {
    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(temp, "per-claim-production")) {
      harness.freeze();
      var strategy = DesktopClaimContextTestSupport.strategy();
      var blueprint = harness.productionBlueprint(strategy);
      var contexts = harness.productionClaimContexts(strategy);
      var keys = DesktopClaimContextTestSupport.keys(contexts);
      int distinct =
          new LinkedHashSet<>(
                  keys.values().stream().map(value -> value.semanticKey()).toList())
              .size();
      long claimNodes =
          blueprint.blueprint().nodes().stream()
              .filter(node -> "critical_claim".equals(node.sourceField()))
              .count();

      System.out.println("PER_CLAIM_CONTEXTS=" + contexts.size());
      System.out.println("DISTINCT_CONTEXT_KEYS=" + distinct);
      System.out.println("CLAIM_BLUEPRINT_BINDINGS=" + claimNodes);
      assertThat(contexts).hasSize(5);
      assertThat(distinct).isEqualTo(5);
      assertThat(claimNodes).isEqualTo(5L);
    }
  }
}
