package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BrokerArtifactDeliveryBaselineTest {
  @Test
  void baselineIsCapturedWhenArtifactEntersPrompt() {
    var scenario = BrokerArtifactTestFixtures.delivered(3.5d);
    var delivery = scenario.prompt().deliveries().getFirst();
    BrokerDeliveryBaseline baseline =
        scenario.broker().deliverySnapshot().baselines().get(delivery.deliveryId());
    assertThat(baseline.canonicalProofDebtBefore()).isEqualTo(3.5d);
    assertThat(baseline.providerRequestId()).isEqualTo("provider-request-1");
    assertThat(baseline.openCanonicalTargetIdsBefore()).containsExactly("target-tree");
  }
}
