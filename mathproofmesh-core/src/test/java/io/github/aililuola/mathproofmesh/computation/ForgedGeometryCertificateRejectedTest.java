package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import org.junit.jupiter.api.Test;

class ForgedGeometryCertificateRejectedTest {
  @Test
  void fabricatedCoordinateCounterexampleCannotGainAuthority() {
    var receipt =
        NativeComputationVerifierForgerySupport.forgedCounterexample(
            ComputationMethod.EXACT_GEOMETRY,
            "{\"points\":{\"A\":[0,0],\"B\":[1,1],\"C\":[2,2]},"
                + "\"assertion\":{\"kind\":\"collinear\",\"points\":[\"A\",\"B\",\"C\"]}}",
            ComputationJson.object().put("determinant", "17"));

    assertThat(receipt.valid()).isFalse();
  }
}
