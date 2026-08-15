package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BrokerArtifactRelevanceIsolationTest {
  @Test
  void lexicalSimilarityAloneCannotCreateDelivery() {
    MathematicalArtifactBroker broker = new MathematicalArtifactBroker();
    var result =
        broker.publish(
            BrokerArtifactTestFixtures.verifiedClaim(),
            List.of(BrokerArtifactTestFixtures.unrelated("route-c")),
            0,
            8);
    assertThat(result.deliveries()).isEmpty();
    assertThat(result.relevanceDecisions()).allMatch(decision -> !decision.relevant());
  }
}
