package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProofGraphConvergenceMetricsTest {
  @Test
  void computesConfiguredScoreFromAuthoritativeDeltas() {
    ProofGraphConvergenceConfig config = ProofGraphConvergenceConfig.defaults();
    ProofGraphRoundMetrics previous =
        ProofGraphConvergenceTestFixtures.metrics(0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 10.0d);
    ProofGraphRoundMetrics current =
        ProofGraphConvergenceTestFixtures.metrics(1, 4, 0, 1, 1, 2, 1, 1, 1, 1, 8.0d);

    assertThat(config.score(current, previous)).isEqualTo(3.0d);
    assertThat(current.authoritativeProgress()).isTrue();
    assertThat(current.totalCanonicalTargets()).isEqualTo(5);
  }
}
