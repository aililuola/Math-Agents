package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactDeliverySnapshot;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactEffectObservation;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactFailurePoint;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactInvalidationSnapshot;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactPublicationSnapshot;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactReceiptSnapshot;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactRegistrySnapshot;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactUseSnapshot;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactUtilitySnapshot;
import io.github.aililuola.mathproofmesh.communication.artifact.MathematicalArtifactBroker;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DesktopBrokerHardCrashRecoveryTest {
  @Test
  void durablePromptAndReceiptFrontiersResumeWithoutDuplicateSideEffects() {
    DesktopBrokerArtifactFixture original = new DesktopBrokerArtifactFixture();
    var artifact = original.artifact("hard-crash", "source-route");
    original.broker.publish(
        artifact, List.of(original.related("target-route", "hard-crash")), 0, 8);
    var consumed =
        original.broker.consumeForPrompt(
            "target-route", "crash-request", 0, 8, 2.0d, Set.of(), Set.of(), Set.of(),
            "strategy-target", null);

    MathematicalArtifactBroker afterPromptCrash = restore(roundTrip(snapshot(original.broker)));
    var replayed =
        afterPromptCrash.consumeForPrompt(
            "target-route", "crash-request", 0, 8, 2.0d, Set.of(), Set.of(), Set.of(),
            "strategy-target", null);
    assertThat(replayed.replayedRequest()).isTrue();
    assertThat(replayed.artifacts()).hasSize(1);
    assertThat(replayed.deliveries()).extracting(delivery -> delivery.deliveryId())
        .containsExactly(consumed.deliveries().getFirst().deliveryId());

    DesktopBrokerArtifactFixture resumed =
        new DesktopBrokerArtifactFixture(
            afterPromptCrash,
            DesktopBrokerArtifactFixture.PROBLEM_HASH,
            DesktopBrokerArtifactFixture.ROOT_HASH);
    afterPromptCrash.acknowledge(
        "crash-request",
        resumed.use("crash-request", artifact, BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP),
        Set.of("step-use"));
    MathematicalArtifactBroker afterReceiptCrash = restore(roundTrip(snapshot(afterPromptCrash)));
    BrokerArtifactEffectObservation effect =
        new BrokerArtifactEffectObservation(
            Set.of("step-use"), Set.of(), Set.of(), Set.of(), Set.of(), null, null, null, null,
            false, 1.0d);
    BrokerCheckpoint durableReceipt = roundTrip(snapshot(afterReceiptCrash));
    afterReceiptCrash.setHardCrashPointForTest(BrokerArtifactFailurePoint.AFTER_UTILITY);
    assertThatThrownBy(
            () ->
                afterReceiptCrash.verifyEffect(
                    consumed.deliveries().getFirst().deliveryId(), effect))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("AFTER_UTILITY");
    MathematicalArtifactBroker afterUtilityCrash = restore(durableReceipt);
    afterUtilityCrash.verifyEffect(consumed.deliveries().getFirst().deliveryId(), effect);
    afterUtilityCrash.verifyEffect(consumed.deliveries().getFirst().deliveryId(), effect);

    MathematicalArtifactBroker finalRestore = restore(roundTrip(snapshot(afterUtilityCrash)));
    assertThat(finalRestore.artifacts()).hasSize(1);
    assertThat(finalRestore.deliveries()).hasSize(1);
    assertThat(finalRestore.receipts()).hasSize(1);
    assertThat(finalRestore.lineage()).hasSize(1);
    assertThat(finalRestore.utilities()).hasSize(1);
  }

  private static BrokerCheckpoint snapshot(MathematicalArtifactBroker broker) {
    return new BrokerCheckpoint(
        broker.registrySnapshot(), broker.publicationSnapshot(), broker.deliverySnapshot(),
        broker.receiptSnapshot(), broker.useSnapshot(), broker.utilitySnapshot(),
        broker.invalidationSnapshot());
  }

  private static BrokerCheckpoint roundTrip(BrokerCheckpoint checkpoint) {
    return ContractObjectMapper.read(
        ContractObjectMapper.write(checkpoint), BrokerCheckpoint.class);
  }

  private static MathematicalArtifactBroker restore(BrokerCheckpoint checkpoint) {
    MathematicalArtifactBroker broker = new MathematicalArtifactBroker();
    broker.restore(
        checkpoint.registry(), checkpoint.publication(), checkpoint.delivery(),
        checkpoint.receipt(), checkpoint.use(), checkpoint.utility(), checkpoint.invalidation());
    return broker;
  }

  private record BrokerCheckpoint(
      BrokerArtifactRegistrySnapshot registry,
      BrokerArtifactPublicationSnapshot publication,
      BrokerArtifactDeliverySnapshot delivery,
      BrokerArtifactReceiptSnapshot receipt,
      BrokerArtifactUseSnapshot use,
      BrokerArtifactUtilitySnapshot utility,
      BrokerArtifactInvalidationSnapshot invalidation) {}
}
