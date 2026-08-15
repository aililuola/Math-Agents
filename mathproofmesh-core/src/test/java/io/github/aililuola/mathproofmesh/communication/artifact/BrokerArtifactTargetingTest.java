package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BrokerArtifactTargetingTest {
  @Test
  void exactServerOwnedNeedEdgesSelectOnlyRelevantRoute() {
    var artifact = BrokerArtifactTestFixtures.verifiedClaim();
    BrokerArtifactTargetingService targeting = new BrokerArtifactTargetingService();
    assertThat(targeting.decide(artifact, BrokerArtifactTestFixtures.related("route-b")).relevant())
        .isTrue();
    assertThat(
            targeting.decide(artifact, BrokerArtifactTestFixtures.unrelated("route-c")).relevant())
        .isFalse();
  }
}
