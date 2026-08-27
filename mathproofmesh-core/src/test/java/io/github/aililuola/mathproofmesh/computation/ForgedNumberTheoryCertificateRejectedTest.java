package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import org.junit.jupiter.api.Test;

class ForgedNumberTheoryCertificateRejectedTest {
  @Test
  void fabricatedPrimalityCounterexampleCannotGainAuthority() {
    var receipt =
        NativeComputationVerifierForgerySupport.forgedCounterexample(
            ComputationMethod.NUMBER_THEORY_CHECK,
            "{\"operation\":\"is_prime\",\"n\":17,\"claimed\":true}",
            ComputationJson.object()
                .put("operation", "is_prime")
                .put("n", 17)
                .put("is_prime", false));

    assertThat(receipt.valid()).isFalse();
  }
}
