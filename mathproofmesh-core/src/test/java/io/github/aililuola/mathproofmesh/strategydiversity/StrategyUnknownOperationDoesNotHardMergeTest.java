package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.MechanismOperationDeclaration;
import io.github.aililuola.mathproofmesh.contract.MechanismOperationKind;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrategyUnknownOperationDoesNotHardMergeTest {
  @Test
  void absentOrExplicitlyUnknownOperationGraphNeverCreatesHardEquivalence() {
    StrategyCard first =
        unknown(
            StrategyDiversityTestFixtures.strategy(
                "unknown-a", "Unknown A", "Use an unnamed transformation.", "P holds.", 0.7d));
    StrategyCard second =
        unknown(
            StrategyDiversityTestFixtures.strategy(
                "unknown-b", "Unknown B", "Use another unnamed transformation.", "P holds.", 0.7d),
            false);
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
    return unknown(source, true);
  }

  private static StrategyCard unknown(StrategyCard source, boolean empty) {
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
        empty
            ? List.of()
            : List.of(
                new MechanismOperationDeclaration(
                    "explicit-unknown",
                    MechanismOperationKind.UNKNOWN,
                    List.of("@roots"),
                    List.of("@direct_targets"))),
        List.of());
  }
}
