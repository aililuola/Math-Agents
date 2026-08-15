package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DesktopBrokerControlMessageIsolationTest {
  @Test
  void legacyFailureAndSchedulingTextCannotEnterCrossRoutePrompt() {
    DesktopBrokerLegacyBlackBoxFixture fixture = new DesktopBrokerLegacyBlackBoxFixture();
    fixture.broker.publish(fixture.genericFailure("failure-control"), "referee-a", 0);

    assertThat(fixture.repository.snapshot().messages()).isEmpty();
    assertThat(
            fixture.broker.consumeForPrompt("route-b", "control-request", 0, 8).messages())
        .isEmpty();
  }
}
