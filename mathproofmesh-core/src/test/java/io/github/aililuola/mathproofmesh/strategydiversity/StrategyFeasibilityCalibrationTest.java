package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import org.junit.jupiter.api.Test;

class StrategyFeasibilityCalibrationTest {
  @Test
  void modelPriorIsWeakAndCannotOverrideRefutedRequiredClaim() {
    StrategyCard refuted =
        StrategyDiversityTestFixtures.strategy(
            "a", "A", "Shortcut", "Refuted required claim.", 0.99d);
    StrategyCard supported =
        StrategyDiversityTestFixtures.strategy(
            "b", "B", "Independent proof", "Verified required claim.", 0.55d);
    StrategyFeasibilityCalibrator calibrator =
        new StrategyFeasibilityCalibrator(StrategyDiversityConfig.defaults());
    StrategyFeasibilityScore scoreA =
        calibrator.calibrate(
            refuted,
            StrategyDiversityTestFixtures.blueprint(refuted),
            StrategyDiversityTestFixtures.report(
                refuted, CriticalClaimPreflightStatus.VERIFIED_REFUTED),
            1.0d,
            1.0d,
            1.0d,
            0.0d);
    StrategyFeasibilityScore scoreB =
        calibrator.calibrate(
            supported,
            StrategyDiversityTestFixtures.blueprint(supported),
            StrategyDiversityTestFixtures.report(
                supported, CriticalClaimPreflightStatus.VERIFIED_SUPPORTED),
            1.0d,
            1.0d,
            1.0d,
            0.0d);

    assertThat(scoreA.total()).isZero();
    assertThat(scoreB.total()).isGreaterThan(scoreA.total());
    assertThat(scoreB.modelPriorContribution() / scoreB.total()).isLessThanOrEqualTo(0.10d);
  }
}
