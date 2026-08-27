package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import org.junit.jupiter.api.Test;

class StrategyMechanismSignatureTest {
  @Test
  void presentationMetadataDoesNotCreateANewHardMechanism() {
    StrategyCard first =
        StrategyDiversityTestFixtures.strategy(
            "route-a", "Leaf deletion", "Delete a leaf and apply induction", "Leaf deletion preserves the invariant.", 0.2d);
    StrategyCard renamed =
        StrategyDiversityTestFixtures.strategy(
            "route-b", "Totally different title", "Delete a leaf and apply induction", "Leaf deletion preserves the invariant.", 0.99d);
    StrategyMechanismAnalyzer analyzer = new StrategyMechanismAnalyzer();

    StrategyMechanismSignature left =
        analyzer.signature(
            StrategyDiversityTestFixtures.PROBLEM_HASH,
            StrategyDiversityTestFixtures.ROOT_HASH,
            first,
            StrategyDiversityTestFixtures.control(first),
            StrategyDiversityTestFixtures.blueprint(first));
    StrategyMechanismSignature right =
        analyzer.signature(
            StrategyDiversityTestFixtures.PROBLEM_HASH,
            StrategyDiversityTestFixtures.ROOT_HASH,
            renamed,
            StrategyDiversityTestFixtures.control(renamed),
            StrategyDiversityTestFixtures.blueprint(renamed));

    assertThat(left.structuralSignatureHash()).isEqualTo(right.structuralSignatureHash());
  }

  @Test
  void sameTitleCanCarryDifferentStructuralMechanisms() {
    StrategyCard induction =
        StrategyDiversityTestFixtures.strategy(
            "induction", "Proof", "Delete a leaf and apply induction", "Leaf deletion preserves the invariant.", 0.5d);
    StrategyCard extremal =
        StrategyDiversityTestFixtures.strategy(
            "extremal", "Proof", "Choose a longest path and inspect its endpoints", "Endpoints of a longest tree path are leaves.", 0.5d);
    StrategyMechanismAnalyzer analyzer = new StrategyMechanismAnalyzer();
    assertThat(signature(analyzer, induction).structuralSignatureHash())
        .isNotEqualTo(signature(analyzer, extremal).structuralSignatureHash());
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
