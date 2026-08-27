package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.MechanismOperationDeclaration;
import io.github.aililuola.mathproofmesh.contract.MechanismOperationKind;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyMechanismOperationDeclarationValidationTest {
  @Test
  void serverRejectsUnknownNodesAndContradictoryOperationKinds() {
    StrategyCard source =
        StrategyDiversityTestFixtures.strategy(
            "invalid-operation", "Invalid", "Presentation text.", "P holds.", 0.7d);
    StrategyMechanismAnalyzer analyzer = new StrategyMechanismAnalyzer();
    var blueprint = StrategyDiversityTestFixtures.blueprint(source).blueprint();
    String root = blueprint.rootEntryNodeIds().getFirst();

    assertThat(
            signature(
                    analyzer,
                    withOperations(
                        source,
                        List.of(
                            new MechanismOperationDeclaration(
                                "selector-roots-to-main",
                                MechanismOperationKind.REDUCTION,
                                List.of("@roots"),
                                List.of("@main_goal")))))
                .operationGraphKnown())
        .isTrue();
    assertThat(
            signature(
                    analyzer,
                    withOperations(
                        source,
                        List.of(
                            new MechanismOperationDeclaration(
                                "selector-intermediates",
                                MechanismOperationKind.REDUCTION,
                                List.of("@all_intermediates"),
                                List.of("@main_goal")))))
                .operationGraphKnown())
        .isTrue();
    assertThat(
            signature(
                    analyzer,
                    withOperations(
                        source,
                        List.of(
                            new MechanismOperationDeclaration(
                                "explicit-node",
                                MechanismOperationKind.REDUCTION,
                                List.of(root),
                                List.of("@direct_targets")))))
                .operationGraphKnown())
        .isTrue();

    assertThatThrownBy(
            () ->
                signature(
                    analyzer,
                    withOperations(
                        source,
                        List.of(
                            new MechanismOperationDeclaration(
                                "unknown-node",
                                MechanismOperationKind.REDUCTION,
                                List.of("missing-node"),
                                List.of("@direct_targets"))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown blueprint node");

    assertThatThrownBy(
            () ->
                signature(
                    analyzer,
                    withOperations(
                        source,
                        List.of(
                            new MechanismOperationDeclaration(
                                "reversed",
                                MechanismOperationKind.REDUCTION,
                                List.of("@main_goal"),
                                List.of("@roots"))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot reach an output");

    assertThatThrownBy(
            () ->
                signature(
                    analyzer,
                    withOperations(
                        source,
                        List.of(
                            new MechanismOperationDeclaration(
                                "first",
                                MechanismOperationKind.REDUCTION,
                                List.of("@roots"),
                                List.of("@direct_targets")),
                            new MechanismOperationDeclaration(
                                "second",
                                MechanismOperationKind.COUNTING,
                                List.of("@roots"),
                                List.of("@direct_targets"))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conflicting kinds");

    assertThatThrownBy(
            () ->
                signature(
                    analyzer,
                    withOperations(
                        source,
                        List.of(
                            new MechanismOperationDeclaration(
                                "duplicate-id",
                                MechanismOperationKind.REDUCTION,
                                List.of("@roots"),
                                List.of("@direct_targets")),
                            new MechanismOperationDeclaration(
                                "duplicate-id",
                                MechanismOperationKind.REDUCTION,
                                List.of("@roots"),
                                List.of("@direct_targets"))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate mechanism operation");
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

  private static StrategyCard withOperations(
      StrategyCard source, List<MechanismOperationDeclaration> declarations) {
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
        declarations,
        source.criticalClaimContextBindings());
  }
}
