package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactEffectObservation;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.BrokerVerifiedEffectType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DesktopBrokerVerifiedEffectUtilityTest {
  @Test
  void utilityRequiresVerifiedDownstreamStateAndUsesDeliveryBaseline() {
    DesktopBrokerArtifactFixture fixture = new DesktopBrokerArtifactFixture();
    var artifact = fixture.artifact("utility", "source-route");
    fixture.broker.publish(artifact, List.of(fixture.related("target-route", "utility")), 0, 8);
    var batch =
        fixture.broker.consumeForPrompt(
            "target-route", "utility-request", 0, 8, 3.0d, Set.of("target-utility"),
            Set.of(), Set.of(), "strategy-target", "target-utility");
    fixture.broker.acknowledge(
        "utility-request",
        fixture.use("utility-request", artifact, BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP),
        Set.of("step-use"));

    var utility =
        fixture.broker
            .verifyEffect(
                batch.deliveries().getFirst().deliveryId(),
                new BrokerArtifactEffectObservation(
                    Set.of("step-use"), Set.of(), Set.of(), Set.of("target-utility"), Set.of(),
                    null, null, null, null, false, 1.0d))
            .orElseThrow();

    assertThat(utility.verifiedEffectTypes())
        .contains(BrokerVerifiedEffectType.COMMITTED_STEP_REUSE);
    assertThat(utility.proofDebtBefore()).isEqualTo(3.0d);
    assertThat(utility.proofDebtAfter()).isEqualTo(1.0d);
    assertThat(utility.proofDebtReduction()).isEqualTo(2.0d);
    assertThat(fixture.broker.receipts().getFirst().status())
        .isEqualTo(BrokerArtifactReceiptStatus.USED_EFFECT_VERIFIED);
  }
}
