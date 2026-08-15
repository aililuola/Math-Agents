package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import org.junit.jupiter.api.Test;

class StrategyMechanismParaphraseInvarianceTest {
  @Test
  void structuralInductionSurvivesOrdinaryMathematicalParaphrase() {
    StrategyCard deleteLeaf =
        StrategyDiversityTestFixtures.strategy(
            "delete-leaf",
            "Leaf deletion",
            "Delete a leaf and apply induction on the number of vertices",
            "Removing a terminal vertex leaves a smaller tree.",
            0.7d);
    StrategyCard removePendant =
        StrategyDiversityTestFixtures.strategy(
            "remove-pendant",
            "Pendant reduction",
            "Remove a pendant vertex and induct on the graph order",
            "Removing a terminal vertex leaves a smaller tree.",
            0.7d);
    StrategyMechanismAnalyzer analyzer = new StrategyMechanismAnalyzer();

    StrategyMechanismSignature first = signature(analyzer, deleteLeaf);
    StrategyMechanismSignature second = signature(analyzer, removePendant);

    assertThat(first.structuralSignatureHash()).isEqualTo(second.structuralSignatureHash());
    assertThat(
            analyzer.relation(
                first,
                second,
                java.util.Set.of(),
                analyzer.profile(deleteLeaf, StrategyDiversityTestFixtures.blueprint(deleteLeaf)),
                analyzer.profile(
                    removePendant, StrategyDiversityTestFixtures.blueprint(removePendant))))
        .isEqualTo(StrategyMechanismRelation.SAME_STRUCTURAL_MECHANISM);
  }

  @Test
  void freeFormMechanismProseDoesNotEnterTheHardSignature() {
    StrategyCard first =
        StrategyDiversityTestFixtures.strategy(
            "free-text-a",
            "A",
            "Delete a leaf and apply induction",
            "Removing a terminal vertex leaves a smaller tree.",
            0.7d);
    StrategyCard second =
        StrategyDiversityTestFixtures.strategy(
            "free-text-b",
            "B",
            "Pass recursively to a one-vertex-shorter object and lift the result",
            "Removing a terminal vertex leaves a smaller tree.",
            0.7d);
    StrategyMechanismAnalyzer analyzer = new StrategyMechanismAnalyzer();

    assertThat(signature(analyzer, first).structuralSignatureHash())
        .isEqualTo(signature(analyzer, second).structuralSignatureHash());
  }

  @Test
  void extremalAndCountingProofsRemainStructurallyDistinct() {
    StrategyCard extremal =
        StrategyDiversityTestFixtures.strategy(
            "extremal-proof",
            "Longest path",
            "Choose a longest path and use extremality",
            "Both endpoints of a longest path are leaves.",
            0.7d);
    StrategyCard counting =
        StrategyDiversityTestFixtures.strategy(
            "counting-proof",
            "Degree sum",
            "Apply the degree sum identity and count vertices",
            "The degree sum is twice the edge count.",
            0.7d);
    StrategyMechanismAnalyzer analyzer = new StrategyMechanismAnalyzer();

    assertThat(signature(analyzer, extremal).structuralSignatureHash())
        .isNotEqualTo(signature(analyzer, counting).structuralSignatureHash());
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
