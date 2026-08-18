package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class RunProofGraphHashRegressionTest {
  @Test
  void missingLaterGraphProjectionCannotEraseDurableGraphHash() {
    RunStateSnapshot previous =
        RunStateTestSupport.stateWithProofGraph(
            RunStateTestSupport.partial(), null, "c".repeat(64));
    RunStateSnapshot next =
        RunStateTestSupport.stateWithProofGraph(
            RunStateTestSupport.partial(), previous, "");

    assertThat(next.authority().proofGraphHash()).isEqualTo("c".repeat(64));
  }
}
