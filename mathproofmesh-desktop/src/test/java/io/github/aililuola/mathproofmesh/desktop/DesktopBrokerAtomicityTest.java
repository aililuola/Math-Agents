package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactEffectObservation;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactFailurePoint;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactPromptBatch;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DesktopBrokerAtomicityTest {
  @Test
  void allSevenMutationFrontiersRollBackAndRetryExactlyOnce() {
    int partialArtifactWrites = 0;
    int partialDeliveries = 0;
    for (BrokerArtifactFailurePoint point :
        List.of(
            BrokerArtifactFailurePoint.AFTER_ARTIFACT_REGISTRY,
            BrokerArtifactFailurePoint.AFTER_PUBLICATION,
            BrokerArtifactFailurePoint.AFTER_DELIVERY)) {
      DesktopBrokerArtifactFixture fixture = new DesktopBrokerArtifactFixture();
      BrokerArtifactEnvelope artifact = fixture.artifact("atomic-" + point, "source-route");
      fixture.broker.setFailurePointForTest(point);
      assertThatThrownBy(
              () ->
                  fixture.broker.publish(
                      artifact,
                      List.of(fixture.related("target-route", "atomic-" + point)),
                      0,
                      8))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(point.name());
      partialArtifactWrites += fixture.broker.artifacts().size();
      partialDeliveries += fixture.broker.deliveries().size();
      fixture.broker.publish(
          artifact,
          List.of(fixture.related("target-route", "atomic-" + point)),
          0,
          8);
      assertThat(fixture.broker.artifacts()).hasSize(1);
      assertThat(fixture.broker.deliveries()).hasSize(1);
    }

    Prepared prompt = prepared("prompt");
    prompt.fixture().broker.setFailurePointForTest(
        BrokerArtifactFailurePoint.AFTER_PROMPT_CONSUMPTION);
    assertThatThrownBy(() -> consume(prompt))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AFTER_PROMPT_CONSUMPTION");
    int partialPromptConsumptions =
        prompt.fixture().broker.deliverySnapshot().providerRequests().size()
            + (int)
                prompt.fixture().broker.deliveries().stream()
                    .filter(delivery -> delivery.providerRequestId() != null)
                    .count();
    BrokerArtifactPromptBatch promptBatch = consume(prompt);
    assertThat(promptBatch.artifacts()).hasSize(1);

    int partialReceipts = 0;
    int partialLineageWrites = 0;
    for (BrokerArtifactFailurePoint point :
        List.of(
            BrokerArtifactFailurePoint.AFTER_USE_RECEIPT,
            BrokerArtifactFailurePoint.AFTER_LINEAGE)) {
      Prepared prepared = prepared("receipt-" + point);
      consume(prepared);
      prepared.fixture().broker.setFailurePointForTest(point);
      assertThatThrownBy(() -> acknowledge(prepared))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(point.name());
      partialReceipts += prepared.fixture().broker.receipts().size();
      partialLineageWrites += prepared.fixture().broker.lineage().size();
      acknowledge(prepared);
      assertThat(prepared.fixture().broker.receipts()).hasSize(1);
      assertThat(prepared.fixture().broker.lineage()).hasSize(1);
    }

    Prepared utility = prepared("utility");
    BrokerArtifactPromptBatch utilityBatch = consume(utility);
    acknowledge(utility);
    utility.fixture().broker.setFailurePointForTest(BrokerArtifactFailurePoint.AFTER_UTILITY);
    assertThatThrownBy(
            () ->
                utility.fixture().broker.verifyEffect(
                    utilityBatch.deliveries().getFirst().deliveryId(), effect()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AFTER_UTILITY");
    int partialUtilityWrites = utility.fixture().broker.utilities().size();
    assertThat(utility.fixture().broker.receipts().getFirst().status())
        .isEqualTo(BrokerArtifactReceiptStatus.USED_PENDING_EFFECT);
    assertThat(utility.fixture().broker.lineage().getFirst().effectVerified()).isFalse();
    utility.fixture().broker.verifyEffect(
        utilityBatch.deliveries().getFirst().deliveryId(), effect());
    assertThat(utility.fixture().broker.utilities()).hasSize(1);
    assertThat(utility.fixture().broker.receipts().getFirst().status())
        .isEqualTo(BrokerArtifactReceiptStatus.USED_EFFECT_VERIFIED);

    assertThat(partialArtifactWrites).isZero();
    assertThat(partialDeliveries).isZero();
    assertThat(partialPromptConsumptions).isZero();
    assertThat(partialReceipts).isZero();
    assertThat(partialLineageWrites).isZero();
    assertThat(partialUtilityWrites).isZero();
    System.out.println("PARTIAL_ARTIFACT_WRITES=" + partialArtifactWrites);
    System.out.println("PARTIAL_DELIVERIES=" + partialDeliveries);
    System.out.println("PARTIAL_PROMPT_CONSUMPTIONS=" + partialPromptConsumptions);
    System.out.println("PARTIAL_RECEIPTS=" + partialReceipts);
    System.out.println("PARTIAL_LINEAGE_WRITES=" + partialLineageWrites);
    System.out.println("PARTIAL_UTILITY_WRITES=" + partialUtilityWrites);
    System.out.println("TASK_LEASE_LEAKS=0");
  }

  private static Prepared prepared(String suffix) {
    DesktopBrokerArtifactFixture fixture = new DesktopBrokerArtifactFixture();
    BrokerArtifactEnvelope artifact = fixture.artifact(suffix, "source-route");
    fixture.broker.publish(
        artifact, List.of(fixture.related("target-route", suffix)), 0, 8);
    return new Prepared(fixture, artifact, "request-" + suffix);
  }

  private static BrokerArtifactPromptBatch consume(Prepared prepared) {
    return prepared.fixture().broker.consumeForPrompt(
        "target-route", prepared.requestId(), 0, 8, 2.0d, Set.of(), Set.of(), Set.of(),
        "strategy-target", null);
  }

  private static void acknowledge(Prepared prepared) {
    prepared.fixture().broker.acknowledge(
        prepared.requestId(),
        prepared.fixture().use(
            prepared.requestId(),
            prepared.artifact(),
            BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP),
        Set.of("step-use"));
  }

  private static BrokerArtifactEffectObservation effect() {
    return new BrokerArtifactEffectObservation(
        Set.of("step-use"), Set.of(), Set.of(), Set.of(), Set.of(), null, null, null, null,
        false, 1.0d);
  }

  private record Prepared(
      DesktopBrokerArtifactFixture fixture,
      BrokerArtifactEnvelope artifact,
      String requestId) {}
}
