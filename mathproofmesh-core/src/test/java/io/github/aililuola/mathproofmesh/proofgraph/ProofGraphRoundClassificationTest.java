package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProofGraphRoundClassificationTest {
  @Test
  void separatesProgressStagnationAndDivergence() {
    ProofGraphConvergenceMonitor monitor = new ProofGraphConvergenceMonitor();
    ProofGraphRoundMetrics prior =
        ProofGraphConvergenceTestFixtures.metrics(0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 5.0d);
    ProofGraphRoundMetrics progress =
        ProofGraphConvergenceTestFixtures.metrics(1, 2, 0, 0, 0, 0, 0, 1, 0, 0, 5.0d);
    ProofGraphRoundMetrics stagnant =
        ProofGraphConvergenceTestFixtures.metrics(1, 2, 0, 0, 0, 1, 0, 0, 0, 0, 5.0d);
    ProofGraphRoundMetrics diverging =
        ProofGraphConvergenceTestFixtures.metrics(1, 3, 0, 0, 1, 0, 0, 0, 0, 0, 6.0d);

    assertThat(monitor.classify(progress, prior))
        .isEqualTo(ProofGraphRoundClassification.PROGRESSING);
    assertThat(monitor.classify(stagnant, prior))
        .isEqualTo(ProofGraphRoundClassification.STAGNATING);
    assertThat(monitor.classify(diverging, prior))
        .isEqualTo(ProofGraphRoundClassification.DIVERGING);
  }
}
