package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrategyPortfolioConstraintTest {
  @Test
  void wideningChecksTheWholeActivePortfolio() {
    StrategyCard candidate =
        StrategyDiversityTestFixtures.strategy(
            "widen", "Widen", "Quotient-space transformation", "Unresolved quotient bridge.", 0.8d);
    StrategyPortfolioCandidate compiled =
        StrategyDiversityTestFixtures.candidate(
            candidate,
            StrategyDiversityTestFixtures.report(candidate, CriticalClaimPreflightStatus.UNKNOWN),
            0.4d);
    String activeClaim = compiled.preflight().unresolvedRequiredClaimKeys().iterator().next();

    StrategyPortfolioDecision decision =
        new StrategyPortfolioOptimizer()
            .optimize(
                "widening",
                List.of(compiled),
                new StrategyPortfolioConstraint(1, 0, 20, Set.of(), Set.of(activeClaim)));

    assertThat(decision.selectedStrategyIds()).isEmpty();
    assertThat(decision.nonSelectionReasons().get("widen"))
        .isEqualTo("SHARED_UNRESOLVED_REQUIRED_CLAIM");
  }
}
