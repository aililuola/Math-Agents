package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.MechanismOperationDeclaration;
import io.github.aililuola.mathproofmesh.contract.MechanismOperationKind;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyMechanismOutOfVocabularyParaphraseTest {
  @Test
  void typedOperationGraphMakesUnseenParaphrasesStructurallyIdentical() {
    List<String> prose =
        List.of(
            "Select a non-extendable geodesic and inspect its boundary points.",
            "Fix a route whose continuation set is empty and study its termini.",
            "Take a chain maximal under inclusion and compare its two boundary objects.",
            "Choose a walk admitting no strict prolongation and analyze both ends.",
            "Work with an inclusion-saturated simple route and inspect its extremities.",
            "Anchor a geodesic outside the image of every extension map and use its endpoints.");
    StrategyMechanismAnalyzer analyzer = new StrategyMechanismAnalyzer();
    List<StrategyMechanismSignature> signatures =
        java.util.stream.IntStream.range(0, prose.size())
            .mapToObj(
                index ->
                    declared(
                        StrategyDiversityTestFixtures.strategy(
                            "oov-" + index,
                            "Presentation " + index,
                            prose.get(index),
                            "The two boundary vertices are leaves.",
                            0.7d)))
            .map(strategy -> signature(analyzer, strategy))
            .toList();

    assertThat(signatures).allMatch(StrategyMechanismSignature::operationGraphKnown);
    assertThat(signatures)
        .extracting(StrategyMechanismSignature::structuralSignatureHash)
        .containsOnly(signatures.getFirst().structuralSignatureHash());
  }

  private static StrategyCard declared(StrategyCard source) {
    return new StrategyCard(
        source.assignedAgentId(),
        source.bottleneck(),
        source.calculationChecks(),
        source.calculationEvidenceRefs(),
        source.computationHints(),
        source.coreIdea(),
        source.criticalClaims(),
        source.estimatedCost(),
        source.estimatedSuccess(),
        source.expectedLemmas(),
        source.falsificationTest(),
        source.independenceBasis(),
        source.inspirationProposalId(),
        source.keyOriginalStep(),
        source.parentStrategyIds(),
        source.prerequisites(),
        source.strategyId(),
        source.tags(),
        source.title(),
        List.of(
            new MechanismOperationDeclaration(
                "boundary-selection",
                MechanismOperationKind.EXTREMAL_SELECTION,
                List.of("@roots"),
                List.of("@direct_targets"))),
        List.of());
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
