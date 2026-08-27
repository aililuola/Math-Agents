package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BrokerArtifactPublicationEligibilityTest {
  @Test
  void publicationDependsOnArtifactAuthorityNotRouteOutcome() {
    MathematicalArtifactBroker broker = new MathematicalArtifactBroker();
    var published =
        broker.publish(
            BrokerArtifactTestFixtures.verifiedClaim(),
            List.of(BrokerArtifactTestFixtures.related("route-b")),
            0,
            8);
    assertThat(published.artifact().sourceRouteId()).isEqualTo("route-a");
    assertThat(published.deliveries()).hasSize(1);
  }
}
