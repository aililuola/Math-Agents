package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationVerifiedAuthority;
import org.junit.jupiter.api.Test;

class ComputationCertificateVerifierTest {
  @Test
  void nativeCertificateIsIndependentlyVerifiedWithoutProducerReplay() {
    var broker = ComputationFixtures.broker("certificate-verifier");
    var outcome = ComputationIssue010TestSupport.run(broker, ComputationIssue010TestSupport.linearAlgebraSpec());
    assertThat(outcome.verificationReceipt().valid()).isTrue();
    assertThat(outcome.verificationReceipt().authority())
        .isEqualTo(ComputationVerifiedAuthority.FINITE_DOMAIN_CERTIFICATE);
    assertThat(broker.executionService().executions().records().getFirst().verifierExecutions())
        .isEqualTo(1);
  }
}
