package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StrategyPreflightUnknownContractTest {
  @Test
  void unregisteredPreferredToolIsUntestableAndNeverExecuted() {
    StrategyCard source =
        StrategyDiversityTestFixtures.strategy(
            "unknown-tool", "Unknown", "Direct route", "Required bridge U.", 0.7d);
    CriticalClaim original = source.criticalClaims().getFirst();
    CriticalClaim unknown =
        new CriticalClaim(
            original.claimId(),
            List.of("missing-request"),
            original.falsificationTest(),
            original.necessity(),
            "arbitrary_shell",
            original.statement(),
            original.status());
    StrategyCard strategy = replaceClaim(source, unknown);
    var plan =
        new StrategyPreflightPlanCompiler()
            .compile(StrategyDiversityTestFixtures.PROBLEM_HASH, strategy);

    StrategyPreflightReport report =
        new StrategyCriticalClaimPreflight(new CriticalClaimKeyCompiler(), List.of())
            .evaluate(
                StrategyDiversityTestFixtures.PROBLEM_HASH,
                strategy,
                Map.of(),
                plan);

    assertThat(plan.claimPlans().getFirst().computationContractId()).isBlank();
    assertThat(report.claims().getFirst().status())
        .isEqualTo(CriticalClaimPreflightStatus.UNTESTABLE);
  }

  private static StrategyCard replaceClaim(StrategyCard source, CriticalClaim claim) {
    return new StrategyCard(
        source.assignedAgentId(),
        source.bottleneck(),
        source.calculationChecks(),
        source.calculationEvidenceRefs(),
        source.computationHints(),
        source.coreIdea(),
        List.of(claim),
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
        source.title());
  }
}
