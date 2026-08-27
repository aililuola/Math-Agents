package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerArtifactUseCompatibilityTest {
  @Test
  void artifactTypeUseMatrixFailsClosed() {
    var scenario = BrokerArtifactTestFixtures.delivered(1.0d);
    var manifest =
        BrokerArtifactTestFixtures.useManifest(
            scenario.artifact(), BrokerArtifactUseKind.REFUTES_CLAIM);
    var receipt =
        scenario
            .broker()
            .acknowledge("provider-request-1", manifest, Set.of("downstream-step"))
            .getFirst();
    assertThat(receipt.status()).isEqualTo(BrokerArtifactReceiptStatus.REJECTED_INVALID_USE);
    assertThat(receipt.decisionCode()).isEqualTo("INCOMPATIBLE_ARTIFACT_USE");
    assertThat(scenario.broker().lineage()).isEmpty();
  }
}
