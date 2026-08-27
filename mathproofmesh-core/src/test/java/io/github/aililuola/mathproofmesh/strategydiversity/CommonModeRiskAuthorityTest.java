package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommonModeRiskAuthorityTest {
  @Test
  void onlySharedUnresolvedRequiredClaimsFormCommonModeGroups() {
    StrategyCard first = withSharedClaims("first");
    StrategyCard second = withSharedClaims("second");
    StrategyCriticalClaimPreflight preflight =
        new StrategyCriticalClaimPreflight(
            new CriticalClaimKeyCompiler(),
            List.of(
                (key, spec) ->
                    spec.claim().statement().contains("Verified base")
                        ? java.util.Optional.of(
                            new CriticalClaimPreflightEvidence(
                                CriticalClaimPreflightStatus.VERIFIED_SUPPORTED,
                                "trusted-test-proof",
                                List.of("fact-v"),
                                "EXACT_VERIFIED_SUPPORT"))
                        : java.util.Optional.empty()));
    CommonModeRiskRegistry registry = new CommonModeRiskRegistry();
    registry.observe(preflight.evaluate(StrategyDiversityTestFixtures.PROBLEM_HASH, first));
    registry.observe(preflight.evaluate(StrategyDiversityTestFixtures.PROBLEM_HASH, second));

    assertThat(registry.records()).singleElement()
        .satisfies(
            record -> {
              assertThat(record.necessity()).isEqualTo("required");
              assertThat(record.affectedStrategyIds()).containsExactlyInAnyOrder("first", "second");
            });
  }

  private static StrategyCard withSharedClaims(String id) {
    StrategyCard base =
        StrategyDiversityTestFixtures.strategy(
            id, id, "Independent mechanism " + id, "Shared unresolved U.", 0.5d);
    return new StrategyCard(
        base.assignedAgentId(),
        base.bottleneck(),
        base.calculationChecks(),
        base.calculationEvidenceRefs(),
        base.computationHints(),
        base.coreIdea(),
        List.of(
            StrategyDiversityTestFixtures.claim(id + "-u", "Shared unresolved U.", "required"),
            StrategyDiversityTestFixtures.claim(id + "-v", "Verified base V.", "required"),
            StrategyDiversityTestFixtures.claim(id + "-s", "Shared supporting S.", "supporting")),
        base.estimatedCost(),
        base.estimatedSuccess(),
        base.expectedLemmas(),
        base.falsificationTest(),
        base.independenceBasis(),
        base.inspirationProposalId(),
        base.keyOriginalStep(),
        base.parentStrategyIds(),
        base.prerequisites(),
        base.strategyId(),
        base.tags(),
        base.title());
  }
}
