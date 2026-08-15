package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerArtifactUtilitySnapshotTest {
  @Test
  void verifiedUtilityRestoresWithoutReplay() {
    var scenario = BrokerArtifactTestFixtures.delivered(2.0d);
    var receipt =
        scenario
            .broker()
            .acknowledge(
                "provider-request-1",
                BrokerArtifactTestFixtures.useManifest(
                    scenario.artifact(), BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP),
                Set.of("downstream-step"))
            .getFirst();
    scenario
        .broker()
        .verifyEffect(
            receipt.deliveryId(),
            new BrokerArtifactEffectObservation(
                Set.of("downstream-step"), Set.of(), Set.of(), Set.of(), Set.of(), null, null,
                null, null, false, 1.0d));
    BrokerArtifactUtilitySnapshot snapshot = scenario.broker().utilitySnapshot();
    BrokerArtifactUtilityLedger restored = new BrokerArtifactUtilityLedger();
    restored.restore(snapshot);
    assertThat(CanonicalJson.stableHash(restored.snapshot()))
        .isEqualTo(CanonicalJson.stableHash(snapshot));
    assertThat(restored.records()).hasSize(1);
  }
}
