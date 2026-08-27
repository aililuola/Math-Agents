package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NativeHypergraphTransversalTest {
  @Test
  void minimalTransversalCertificateIsRecomputedIndependently() {
    var outcome =
        ComputationIssue010TestSupport.run(
            ComputationFixtures.broker("native-hypergraph"), ComputationIssue010TestSupport.hypergraphSpec());
    assertThat(outcome.result().certificate().path("is_minimal_hitting_set").asBoolean()).isTrue();
    assertThat(outcome.verificationReceipt().valid()).isTrue();
  }
}
