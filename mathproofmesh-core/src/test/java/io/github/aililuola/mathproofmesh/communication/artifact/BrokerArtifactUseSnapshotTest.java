package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerArtifactUseSnapshotTest {
  @Test
  void manifestsAndLineageRestoreExactlyOnce() {
    var scenario = BrokerArtifactTestFixtures.delivered(2.0d);
    scenario
        .broker()
        .acknowledge(
            "provider-request-1",
            BrokerArtifactTestFixtures.useManifest(
                scenario.artifact(), BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP),
            Set.of("downstream-step"));
    var snapshot = scenario.broker().useSnapshot();
    BrokerArtifactUseLedger restored = new BrokerArtifactUseLedger();
    restored.restore(snapshot);
    assertThat(CanonicalJson.stableHash(restored.snapshot()))
        .isEqualTo(CanonicalJson.stableHash(snapshot));
    assertThat(restored.records()).hasSize(1);
  }
}
