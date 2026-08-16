package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import org.junit.jupiter.api.Test;

class NativeExactLinearAlgebraTest {
  @Test
  void rankCertificateUsesExactRationalsAndIndependentVerification() {
    var outcome =
        ComputationIssue010TestSupport.run(
            ComputationFixtures.broker("native-linear"), ComputationIssue010TestSupport.linearAlgebraSpec());
    assertThat(outcome.result().outcome()).isEqualTo(ExperimentOutcome.CERTIFIED);
    assertThat(outcome.result().certificate().path("rank").asInt()).isEqualTo(1);
    assertThat(outcome.verificationReceipt().valid()).isTrue();
  }
}
