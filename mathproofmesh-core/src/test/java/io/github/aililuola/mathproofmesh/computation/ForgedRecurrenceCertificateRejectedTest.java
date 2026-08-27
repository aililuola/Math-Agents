package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import org.junit.jupiter.api.Test;

class ForgedRecurrenceCertificateRejectedTest {
  @Test
  void fabricatedRecurrenceMismatchCannotGainCounterexampleAuthority() {
    var receipt =
        NativeComputationVerifierForgerySupport.forgedCounterexample(
            ComputationMethod.RECURRENCE_CHECK,
            "{\"initial_values\":[1,1],\"coefficients\":[1,1],\"start_n\":0,"
                + "\"end_n\":4,\"claimed_expression\":\"1\"}",
            ComputationJson.object().put("n", 0).put("actual", "999").put("claimed", "1"));

    assertThat(receipt.valid()).isFalse();
  }
}
