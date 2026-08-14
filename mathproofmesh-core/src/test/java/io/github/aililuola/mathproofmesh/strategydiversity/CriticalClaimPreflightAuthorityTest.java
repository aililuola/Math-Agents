package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CriticalClaimPreflightAuthorityTest {
  @Test
  void verifiedRefutationHardRejectsRegardlessOfModelStatusOrPrior() {
    StrategyCard raw =
        StrategyDiversityTestFixtures.strategy(
            "refuted", "Refuted", "Shortcut", "Every connected finite graph is Hamiltonian.", 0.99d);
    CriticalClaim modelClaimsVerified =
        new CriticalClaim(
            raw.criticalClaims().getFirst().claimId(),
            List.of(),
            raw.criticalClaims().getFirst().falsificationTest(),
            "required",
            null,
            raw.criticalClaims().getFirst().statement(),
            "verified");
    StrategyCard strategy = replaceClaim(raw, modelClaimsVerified);
    StrategyCriticalClaimPreflight preflight =
        new StrategyCriticalClaimPreflight(
            new CriticalClaimKeyCompiler(),
            List.of(
                (key, spec) ->
                    Optional.of(
                        new CriticalClaimPreflightEvidence(
                            CriticalClaimPreflightStatus.VERIFIED_REFUTED,
                            "independent-computation-replay",
                            List.of("experiment://path-p4"),
                            "EXACT_COUNTEREXAMPLE"))));

    StrategyPreflightReport report =
        preflight.evaluate(StrategyDiversityTestFixtures.PROBLEM_HASH, strategy);

    assertThat(report.hardRejected()).isTrue();
    assertThat(report.claims()).singleElement()
        .extracting(CriticalClaimPreflightResult::status)
        .isEqualTo(CriticalClaimPreflightStatus.VERIFIED_REFUTED);
  }

  @Test
  void modelVerifiedStatusAloneHasNoAuthority() {
    StrategyCard raw =
        StrategyDiversityTestFixtures.strategy(
            "untrusted", "Untrusted", "Direct", "Unreviewed load-bearing claim.", 0.99d);
    StrategyCard strategy =
        replaceClaim(
            raw,
            new CriticalClaim(
                raw.criticalClaims().getFirst().claimId(),
                List.of(),
                raw.falsificationTest(),
                "required",
                null,
                raw.criticalClaims().getFirst().statement(),
                "verified"));
    StrategyPreflightReport report =
        new StrategyCriticalClaimPreflight(new CriticalClaimKeyCompiler(), List.of())
            .evaluate(StrategyDiversityTestFixtures.PROBLEM_HASH, strategy);
    assertThat(report.requiredClaimEvidenceCoverage()).isZero();
    assertThat(report.claims().getFirst().status())
        .isEqualTo(CriticalClaimPreflightStatus.UNKNOWN);
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
