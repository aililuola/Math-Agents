package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import org.junit.jupiter.api.Test;

class BrokerArtifactSnapshotTest {
  @Test
  void allModernStoresRoundTripWithStableHashes() {
    var scenario = BrokerArtifactTestFixtures.delivered(2.0d);
    String before = CanonicalJson.stableHash(scenario.broker().registrySnapshot());
    MathematicalArtifactBroker restored = new MathematicalArtifactBroker();
    restored.restore(
        scenario.broker().registrySnapshot(),
        scenario.broker().publicationSnapshot(),
        scenario.broker().deliverySnapshot(),
        scenario.broker().receiptSnapshot(),
        scenario.broker().useSnapshot(),
        scenario.broker().utilitySnapshot(),
        scenario.broker().invalidationSnapshot());
    assertThat(CanonicalJson.stableHash(restored.registrySnapshot())).isEqualTo(before);
    assertThat(restored.deliveries()).hasSize(1);
  }
}
