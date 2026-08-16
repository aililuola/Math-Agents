package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NativeComputationTimeoutTest {
  @Test
  void oneNativeCallCannotOutliveItsCapabilityDeadline() {
    var envelope =
        new ComputationResourceEnvelope(100, 0.01d, 1_000_000L, 1_000);
    long started = System.nanoTime();

    assertThatThrownBy(
            () ->
                ComputationResourceGuard.callWithin(
                    () -> {
                      try {
                        Thread.sleep(5_000L);
                      } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                      }
                      return "late";
                    },
                    envelope))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("COMPUTATION_TIMEOUT");

    assertThat((System.nanoTime() - started) / 1_000_000_000.0d).isLessThan(1.0d);
  }
}
