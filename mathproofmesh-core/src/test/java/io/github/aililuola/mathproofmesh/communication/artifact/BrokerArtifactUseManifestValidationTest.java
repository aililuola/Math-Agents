package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseClaim;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseManifest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerArtifactUseManifestValidationTest {
  @Test
  void useMustReferenceAnArtifactDeliveredToTheSameProviderRequest() {
    var scenario = BrokerArtifactTestFixtures.delivered(1.0d);
    BrokerArtifactUseManifest forged =
        new BrokerArtifactUseManifest(
            "provider-request-1",
            List.of(
                new BrokerArtifactUseClaim(
                    "not-delivered",
                    BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP,
                    List.of("downstream-step"),
                    List.of(),
                    List.of(),
                    "forged use")));
    assertThatThrownBy(
            () ->
                scenario
                    .broker()
                    .acknowledge("provider-request-1", forged, Set.of("downstream-step")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ARTIFACT_NOT_DELIVERED_TO_REQUEST");
    assertThat(scenario.broker().receipts()).isEmpty();
    assertThat(scenario.broker().lineage()).isEmpty();
  }
}
