package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DesktopGenericFailureRecordBroadcastBlackBoxTest {
  @Test
  void genericFailureControlRecordNeverEntersCrossRouteMathematicalBroker() {
    DesktopBrokerLegacyBlackBoxFixture fixture = new DesktopBrokerLegacyBlackBoxFixture();
    fixture.broker.publish(fixture.genericFailure("generic-failure"), "referee-a", 0);
    long admitted = fixture.repository.snapshot().messages().size();
    long inPrompt =
        fixture.broker.consumeForPrompt("route-b", "generic-failure-request", 0, 8).messages().size();
    System.out.println("GENERIC_FAILURE_BROADCAST_ATTEMPTS=1");
    System.out.println("GENERIC_FAILURE_MESSAGES_ADMITTED=" + admitted);
    System.out.println("TARGET_PROMPT_GENERIC_FAILURE_MESSAGES=" + inPrompt);
    System.out.println("EXPECTED=0");
    assertThat(admitted).isZero();
    assertThat(inPrompt).isZero();
  }
}
