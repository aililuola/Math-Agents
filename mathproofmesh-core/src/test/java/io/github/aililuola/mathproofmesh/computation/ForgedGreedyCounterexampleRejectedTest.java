package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import org.junit.jupiter.api.Test;

class ForgedGreedyCounterexampleRejectedTest {
  @Test
  void fabricatedGreedyMismatchCannotGainCounterexampleAuthority() {
    var receipt =
        NativeComputationVerifierForgerySupport.forgedCounterexample(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            "{\"initial_values\":[1],\"length\":3,\"candidate_min\":1,"
                + "\"candidate_max\":20,\"rule\":\"coprime_to_all\","
                + "\"claimed_values\":[1,2,3]}",
            ComputationJson.object().put("index", 1).put("generated", 99).put("claimed", 2));

    assertThat(receipt.valid()).isFalse();
  }
}
