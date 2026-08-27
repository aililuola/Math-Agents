package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ComputationProducerVerifierSeparationTest {
  @Test
  void authoritativeCapabilitiesUseIndependentProducerAndVerifierObjects() {
    for (var descriptor : ComputationIssue010TestSupport.registry().snapshot().descriptors()) {
      var capability = ComputationIssue010TestSupport.registry().capability(descriptor.method());
      assertThat(capability.producer()).isNotSameAs(capability.verifier());
      assertThat(descriptor.producerId()).isNotEqualTo(descriptor.verifierId());
    }
  }
}
