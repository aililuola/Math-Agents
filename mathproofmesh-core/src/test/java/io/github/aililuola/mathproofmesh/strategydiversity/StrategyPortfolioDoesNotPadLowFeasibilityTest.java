package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrategyPortfolioDoesNotPadLowFeasibilityTest {
  @Test
  void positiveButUnacceptableScoresCannotPadTheRequestedSize() {
    List<StrategyPortfolioCandidate> candidates =
        List.of(
            candidate("high-extremal", "Choose a longest path", "Claim A.", 0.81d),
            candidate("high-induction", "Apply induction after deleting one element", "Claim B.", 0.80d),
            candidate("low-counting", "Use a counting identity", "Claim C.", 0.01d),
            candidate("low-duality", "Pass to the dual representation", "Claim D.", 0.02d));

    StrategyPortfolioDecision decision =
        new StrategyPortfolioOptimizer()
            .optimize("quality-gate", candidates, new StrategyPortfolioConstraint(4, 2, 20, Set.of(), Set.of()));

    assertThat(decision.selectedStrategyIds())
        .containsExactlyInAnyOrder("high-extremal", "high-induction");
    assertThat(decision.nonSelectionReasons())
        .containsEntry("low-counting", "NOT_SELECTED_LOW_FEASIBILITY")
        .containsEntry("low-duality", "NOT_SELECTED_LOW_FEASIBILITY");
  }

  private static StrategyPortfolioCandidate candidate(
      String id, String mechanism, String claim, double score) {
    StrategyCard strategy =
        StrategyDiversityTestFixtures.strategy(id, id, mechanism, claim, 0.5d);
    return StrategyDiversityTestFixtures.candidate(
        strategy,
        StrategyDiversityTestFixtures.report(
            strategy, CriticalClaimPreflightStatus.VERIFIED_SUPPORTED),
        score);
  }
}
