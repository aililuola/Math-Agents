package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StrategyPreflightRunningFrontierQuarantineTest {
  @Test
  void uncertainExecutionFrontierIsQuarantinedWithoutClaimingMathematicalRefutation() {
    var strategy =
        StrategyDiversityTestFixtures.strategy(
            "running-frontier",
            "Running frontier",
            "Use a registered bounded check.",
            "The bounded bridge holds.",
            0.7d);
    StrategyCriticalClaimPreflight preflight =
        new StrategyCriticalClaimPreflight(
            new CriticalClaimKeyCompiler(),
            List.of(
                (key, spec) ->
                    Optional.of(
                        new CriticalClaimPreflightEvidence(
                            CriticalClaimPreflightStatus.EXECUTION_QUARANTINED,
                            "registered-computation-preflight-frontier",
                            List.of("execution-running"),
                            "PREFLIGHT_EXECUTION_STATE_UNCERTAIN"))));

    StrategyPreflightReport report =
        preflight.evaluate(StrategyDiversityTestFixtures.PROBLEM_HASH, strategy);

    assertThat(report.hardRejected()).isFalse();
    assertThat(report.claims().getFirst().status())
        .isEqualTo(CriticalClaimPreflightStatus.EXECUTION_QUARANTINED);
    assertThat(report.unresolvedRequiredClaimKeys()).hasSize(1);
  }
}
