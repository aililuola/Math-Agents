package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrategyCanonicalTargetIdentityTest {
  @Test
  void targetIdentityComesFromTheFrozenRootRatherThanLemmaWording() {
    StrategyCard first =
        StrategyDiversityTestFixtures.strategy(
            "target-a", "A", "Apply induction", "Removing a leaf preserves a tree.", 0.7d);
    StrategyCard second =
        StrategyDiversityTestFixtures.strategy(
            "target-b", "B", "Apply induction", "Deleting a pendant vertex preserves a tree.", 0.7d);
    StrategyMechanismAnalyzer analyzer = new StrategyMechanismAnalyzer();

    StrategyMechanismSignature left = signature(analyzer, first);
    StrategyMechanismSignature right = signature(analyzer, second);

    assertThat(left.targetCanonicalIds())
        .containsExactly("main-goal:" + StrategyDiversityTestFixtures.ROOT_HASH)
        .isEqualTo(right.targetCanonicalIds());
  }

  @Test
  void productionCanBindTheIssue005CanonicalProofGraphTarget() {
    StrategyCard strategy =
        StrategyDiversityTestFixtures.strategy(
            "canonical-target", "Canonical", "Apply induction", "Bridge statement.", 0.7d);
    StrategyMechanismAnalyzer analyzer = new StrategyMechanismAnalyzer();

    StrategyMechanismSignature signature =
        analyzer.signature(
            StrategyDiversityTestFixtures.PROBLEM_HASH,
            StrategyDiversityTestFixtures.ROOT_HASH,
            strategy,
            StrategyDiversityTestFixtures.control(strategy),
            StrategyDiversityTestFixtures.blueprint(strategy),
            Map.of(),
            Set.of("canonical_issue005_root"));

    assertThat(signature.targetCanonicalIds()).containsExactly("canonical_issue005_root");
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
