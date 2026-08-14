package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyMechanismGeneralizationCorpusTest {
  @Test
  void graphLinearAlgebraAndFiniteSetMechanismsStayDistinct() {
    List<StrategyCard> corpus =
        List.of(
            StrategyDiversityTestFixtures.strategy(
                "graph", "Proof", "Choose a longest path and use extremality", "Longest-path endpoints are leaves.", 0.5d),
            StrategyDiversityTestFixtures.strategy(
                "linear", "Proof", "Extend a basis of the kernel to the domain", "A kernel basis admits an extension.", 0.5d),
            StrategyDiversityTestFixtures.strategy(
                "sets", "Proof", "Apply pigeonhole counting to equal finite cardinalities", "Every fiber is nonempty.", 0.5d));
    StrategyMechanismAnalyzer analyzer = new StrategyMechanismAnalyzer();

    assertThat(
            corpus.stream()
                .map(
                    strategy ->
                        analyzer
                            .signature(
                                StrategyDiversityTestFixtures.PROBLEM_HASH,
                                StrategyDiversityTestFixtures.ROOT_HASH,
                                strategy,
                                StrategyDiversityTestFixtures.control(strategy),
                                StrategyDiversityTestFixtures.blueprint(strategy))
                            .structuralSignatureHash())
                .distinct())
        .hasSize(3);
  }
}
