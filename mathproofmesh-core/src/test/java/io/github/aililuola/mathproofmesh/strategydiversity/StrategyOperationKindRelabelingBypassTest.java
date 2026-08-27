package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.MechanismOperationDeclaration;
import io.github.aililuola.mathproofmesh.contract.MechanismOperationKind;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrategyOperationKindRelabelingBypassTest {
  @Test
  void unreviewedKindRelabelingCannotManufactureAnotherHardMechanism() {
    StrategyCard induction = strategy("kind-induction", MechanismOperationKind.INDUCTION);
    StrategyCard reduction = strategy("kind-reduction", MechanismOperationKind.REDUCTION);
    StrategyPortfolioCandidate first = candidate(induction);
    StrategyPortfolioCandidate second = candidate(reduction);
    StrategyPortfolioDecision decision =
        new StrategyPortfolioOptimizer()
            .optimize(
                "kind-relabeling",
                List.of(first, second),
                new StrategyPortfolioConstraint(2, 1, 8, Set.of(), Set.of(), 0.0d, 0.0d, 0.0d));

    int conflicts =
        first.signature().structuralSignatureHash()
                    .equals(second.signature().structuralSignatureHash())
                && induction.mechanismOperations().getFirst().kind()
                    != reduction.mechanismOperations().getFirst().kind()
            ? 1
            : 0;
    int distinctAdmissions = Math.max(0, decision.selectedStrategyIds().size() - 1);
    System.out.println("UNREVIEWED_KIND_CONFLICTS=" + conflicts);
    System.out.println("DISTINCT_MECHANISM_ADMISSIONS=" + distinctAdmissions);
    assertThat(conflicts).isOne();
    assertThat(decision.selectedStrategyIds()).hasSize(1);
    assertThat(distinctAdmissions).isZero();
    assertThat(decision.nonSelectionReasons().values())
        .contains("MECHANISM_DECLARATION_CONFLICT");
  }

  private static StrategyPortfolioCandidate candidate(StrategyCard strategy) {
    return StrategyDiversityTestFixtures.candidate(
        strategy,
        StrategyDiversityTestFixtures.report(
            strategy, CriticalClaimPreflightStatus.VERIFIED_SUPPORTED),
        0.9d);
  }

  private static StrategyCard strategy(String id, MechanismOperationKind kind) {
    StrategyCard source =
        StrategyDiversityTestFixtures.strategy(
            id,
            "Relabeled mechanism",
            "Transform the same dependency graph into the target.",
            "The common bridge statement holds.",
            0.9d);
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
                "same-subgraph", kind, List.of("@roots"), List.of("@direct_targets"))),
        source.criticalClaimContextBindings());
  }
}
