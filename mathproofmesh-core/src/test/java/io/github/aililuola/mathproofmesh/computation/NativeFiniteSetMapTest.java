package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NativeFiniteSetMapTest {
  @Test
  void finiteBijectionCertificateIsVerifiedWithoutExternalBackend() {
    var outcome =
        ComputationIssue010TestSupport.run(
            ComputationFixtures.broker("native-map"), ComputationIssue010TestSupport.finiteMapSpec());
    assertThat(outcome.result().certificate().path("bijective").asBoolean()).isTrue();
    assertThat(outcome.verificationReceipt().valid()).isTrue();
  }
}
