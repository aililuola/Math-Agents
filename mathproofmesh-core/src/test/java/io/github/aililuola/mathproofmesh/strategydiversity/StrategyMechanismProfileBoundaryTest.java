package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import org.junit.jupiter.api.Test;

class StrategyMechanismProfileBoundaryTest {
  @Test
  void softProfileAddsCoverageButNeverChangesHardIdentity() {
    StrategyCard first =
        StrategyDiversityTestFixtures.strategy(
            "first", "First", "Contradiction through a minimal counterexample", "Required bridge U.", 0.5d);
    StrategyCard renamed =
        StrategyDiversityTestFixtures.strategy(
            "renamed", "Renamed", "Contradiction through a minimal counterexample", "Required bridge U.", 0.5d);
    StrategyMechanismAnalyzer analyzer = new StrategyMechanismAnalyzer();
    StrategyMechanismProfile profile =
        analyzer.profile(first, StrategyDiversityTestFixtures.blueprint(first));

    assertThat(profile.primitives())
        .contains(
            StrategyMechanismPrimitive.CONTRADICTION,
            StrategyMechanismPrimitive.MINIMAL_COUNTEREXAMPLE);
    assertThat(signature(analyzer, first).structuralSignatureHash())
        .isEqualTo(signature(analyzer, renamed).structuralSignatureHash());
  }

  private static StrategyMechanismSignature signature(
      StrategyMechanismAnalyzer analyzer, StrategyCard strategy) {
    return analyzer.signature(
        StrategyDiversityTestFixtures.PROBLEM_HASH,
        StrategyDiversityTestFixtures.ROOT_HASH,
        strategy,
        StrategyDiversityTestFixtures.control(strategy),
        StrategyDiversityTestFixtures.blueprint(strategy));
  }
}
