package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BoundedObservationPayload;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactAuthority;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.ExactExamplePayload;
import org.junit.jupiter.api.Test;

class BrokerArtifactAuthorityMatrixTest {
  @Test
  void authorityIsDerivedFromTrustedSourceKind() {
    BrokerArtifactAuthorityResolver resolver = new BrokerArtifactAuthorityResolver();
    assertThat(
            resolver.resolve(
                BrokerArtifactTestFixtures.request(
                    BrokerArtifactType.VERIFIED_CLAIM,
                    new io.github.aililuola.mathproofmesh.contract.VerifiedClaimPayload(
                        BrokerArtifactTestFixtures.context("forall", "global", "positive")),
                    BrokerArtifactSourceKind.CLAIM_COURT_VERIFIED,
                    "failed-route",
                    "claim-tree",
                    "revision-tree",
                    true)))
        .contains(BrokerArtifactAuthority.VERIFIED);
    assertThat(
            resolver.resolve(
                BrokerArtifactTestFixtures.request(
                    BrokerArtifactType.EXACT_EXAMPLE,
                    new ExactExamplePayload(
                        "the path P4 has no Hamiltonian cycle",
                        BrokerArtifactTestFixtures.context("forall", "bounded:P4", "negative")),
                    BrokerArtifactSourceKind.BOUNDED_EVIDENCE,
                    "failed-route",
                    "claim-hamiltonian",
                    "revision-hamiltonian",
                    true)))
        .contains(BrokerArtifactAuthority.BOUNDED);
    assertThat(
            resolver.resolve(
                BrokerArtifactTestFixtures.request(
                    BrokerArtifactType.BOUNDED_OBSERVATION,
                    new BoundedObservationPayload(
                        "checked all graphs on four vertices",
                        BrokerArtifactTestFixtures.context("forall", "vertices<=4", "positive")),
                    BrokerArtifactSourceKind.MODEL_DECLARATION,
                    "failed-route",
                    "claim-small",
                    "revision-small",
                    true)))
        .isEmpty();
  }
}
