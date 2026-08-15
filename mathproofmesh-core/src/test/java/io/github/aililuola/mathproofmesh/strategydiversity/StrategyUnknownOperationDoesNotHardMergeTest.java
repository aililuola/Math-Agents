package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrategyUnknownOperationDoesNotHardMergeTest {
  @Test
  void absentTypedOperationGraphNeverCreatesHardEquivalence() {
    StrategyCard first =
        unknown(
            StrategyDiversityTestFixtures.strategy(
                "unknown-a", "Unknown A", "Use an unnamed transformation.", "P holds.", 0.7d));
    StrategyCard second =
        unknown(
            StrategyDiversityTestFixtures.strategy(
                "unknown-b", "Unknown B", "Use another unnamed transformation.", "P holds.", 0.7d));
    StrategyMechanismAnalyzer analyzer = new StrategyMechanismAnalyzer();
    var firstBlueprint = StrategyDiversityTestFixtures.blueprint(first);
    var secondBlueprint = StrategyDiversityTestFixtures.blueprint(second);
    StrategyMechanismSignature left =
        analyzer.signature(
            StrategyDiversityTestFixtures.PROBLEM_HASH,
            StrategyDiversityTestFixtures.ROOT_HASH,
            first,
            StrategyDiversityTestFixtures.control(first),
            firstBlueprint);
    StrategyMechanismSignature right =
        analyzer.signature(
            StrategyDiversityTestFixtures.PROBLEM_HASH,
            StrategyDiversityTestFixtures.ROOT_HASH,
            second,
            StrategyDiversityTestFixtures.control(second),
            secondBlueprint);

    assertThat(left.operationGraphKnown()).isFalse();
    assertThat(right.operationGraphKnown()).isFalse();
    assertThat(left.structuralSignatureHash()).isNotEqualTo(right.structuralSignatureHash());
    assertThat(
            analyzer.relation(
                left,
                right,
                Set.of(),
                analyzer.profile(first, firstBlueprint),
                analyzer.profile(second, secondBlueprint)))
        .isNotEqualTo(StrategyMechanismRelation.SAME_STRUCTURAL_MECHANISM);
  }

  private static StrategyCard unknown(StrategyCard source) {
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
        java.util.List.of(),
        java.util.List.of());
  }
}
