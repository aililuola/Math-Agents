package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BoundedNonRefutationBoundaryTest {
  @Test
  void finiteSearchWithoutCounterexampleNeverBecomesVerifiedSupport() {
    StrategyCard strategy =
        StrategyDiversityTestFixtures.strategy(
            "bounded", "Bounded", "Exhaustive finite search", "Universal finite-set claim U.", 0.99d);
    StrategyPreflightReport report =
        new StrategyCriticalClaimPreflight(
                new CriticalClaimKeyCompiler(),
                List.of(
                    (key, spec) ->
                        Optional.of(
                            new CriticalClaimPreflightEvidence(
                                CriticalClaimPreflightStatus.NOT_REFUTED_IN_BOUNDED_SCOPE,
                                "registered-computation-replay",
                                List.of("experiment://finite-search"),
                                "NO_COUNTEREXAMPLE_UP_TO_BOUND"))))
            .evaluate(StrategyDiversityTestFixtures.PROBLEM_HASH, strategy);
    StrategyFeasibilityScore score =
        new StrategyFeasibilityCalibrator(StrategyDiversityConfig.defaults())
            .calibrate(
                strategy,
                StrategyDiversityTestFixtures.blueprint(strategy),
                report,
                1.0d,
                1.0d,
                1.0d,
                0.0d);

    assertThat(report.requiredClaimEvidenceCoverage()).isZero();
    assertThat(report.claims().getFirst().status())
        .isEqualTo(CriticalClaimPreflightStatus.NOT_REFUTED_IN_BOUNDED_SCOPE);
    assertThat(score.total()).isLessThanOrEqualTo(0.45d);
  }
}
