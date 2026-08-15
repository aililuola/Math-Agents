package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerArtifactReceiptStateMachineTest {
  @Test
  void receiptDistinguishesNotUsedPendingAndVerifiedEffect() {
    var unused = BrokerArtifactTestFixtures.delivered(2.0d);
    assertThat(
            unused
                .broker()
                .acknowledge("provider-request-1", null, Set.of("downstream-step"))
                .getFirst()
                .status())
        .isEqualTo(BrokerArtifactReceiptStatus.NOT_USED);

    var used = BrokerArtifactTestFixtures.delivered(2.0d);
    var receipt =
        used.broker()
            .acknowledge(
                "provider-request-1",
                BrokerArtifactTestFixtures.useManifest(
                    used.artifact(), BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP),
                Set.of("downstream-step"))
            .getFirst();
    assertThat(receipt.status()).isEqualTo(BrokerArtifactReceiptStatus.USED_PENDING_EFFECT);
    used.broker()
        .verifyEffect(
            receipt.deliveryId(),
            new BrokerArtifactEffectObservation(
                Set.of("downstream-step"),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                "target-tree",
                null,
                null,
                null,
                false,
                1.0d));
    assertThat(used.broker().receipts().getFirst().status())
        .isEqualTo(BrokerArtifactReceiptStatus.USED_EFFECT_VERIFIED);
  }
}
