package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SalvagedClaimPublicationEligibilityTest {
  @Test
  void failedRouteIdentityDoesNotSuppressVerifiedLocalClaim() {
    var artifact = BrokerArtifactTestFixtures.verifiedClaim();
    assertThat(artifact.sourceRouteId()).isEqualTo("route-a");
    assertThat(new BrokerArtifactAuthorityResolver().compatible(artifact.artifactType(), artifact.authority()))
        .isTrue();
  }
}
