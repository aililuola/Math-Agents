package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import org.junit.jupiter.api.Test;

class ForgedCandidatePeriodCertificateRejectedTest {
  @Test
  void fabricatedPeriodMismatchCannotGainCounterexampleAuthority() {
    var receipt =
        NativeComputationVerifierForgerySupport.forgedCounterexample(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[1,2,1,2],\"candidate_period\":2}",
            ComputationJson.object()
                .put("index", 2)
                .put("prior_index", 0)
                .put("value", "9")
                .put("prior_value", "1")
                .put("candidate_period", 2));

    assertThat(receipt.valid()).isFalse();
  }
}
