package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.communication.MessageDelivery;
import io.github.aililuola.mathproofmesh.communication.MessageStoreSnapshot;
import io.github.aililuola.mathproofmesh.communication.MessageUtilityRecord;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.MessagePriority;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopLegacyConsumedDeliveryNoAutoUtilityTest {
  @TempDir Path temporaryDirectory;

  @Test
  void restoredLegacyPromptConsumptionRemainsAuditOnly() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory, "legacy-broker-isolation")) {
      harness.freezeAndCreateRoute();
      DesktopSolveCheckpoint checkpoint = harness.checkpointRoundTrip();
      var message = new DesktopBrokerLegacyBlackBoxFixture().genericFailure("legacy-consumed");
      MessageDelivery delivery =
          MessageDelivery.queued(
                  message.messageId(), "route-1", MessagePriority.HIGH, 0, "legacy-token")
              .consume("legacy-provider-request");
      MessageUtilityRecord legacyUtility =
          new MessageUtilityRecord(
              delivery.deliveryKey(), List.of("legacy-step"), List.of(), List.of(), List.of(),
              List.of(), false, 0.0d, 1.0d);
      MessageStoreSnapshot legacyStore =
          new MessageStoreSnapshot(
              Map.of(message.messageId(), message),
              Map.of(),
              Map.of(delivery.deliveryKey(), delivery),
              Map.of(),
              Map.of(delivery.deliveryKey(), legacyUtility),
              Map.of("legacy-provider-request", List.of(delivery.deliveryKey())),
              Map.of(),
              Map.of());
      ObjectNode tree = (ObjectNode) ContractObjectMapper.toTree(checkpoint);
      tree.set("messageStore", ContractObjectMapper.toTree(legacyStore));
      DesktopSolveCheckpoint restored = ContractObjectMapper.read(tree, DesktopSolveCheckpoint.class);

      harness.restore(restored);
      harness.installSingleClaimRound(1, "legacy-attempt-claim", "A harmless local claim.");
      int receiptsBefore = harness.legacyMessageStore().receipts().size();
      harness.acknowledgeLegacyConsumedMessages();
      int legacyAutoAcceptedReceipts =
          harness.legacyMessageStore().receipts().size() - receiptsBefore;
      int schedulerActiveUtilities =
          harness.schedulerBrokerUtility("route-1") == 0.0d ? 0 : 1;

      System.out.println("LEGACY_AUTO_ACCEPTED_RECEIPTS=" + legacyAutoAcceptedReceipts);
      System.out.println("LEGACY_SCHEDULER_ACTIVE_UTILITIES=" + schedulerActiveUtilities);
      assertThat(legacyAutoAcceptedReceipts).isZero();
      assertThat(schedulerActiveUtilities).isZero();
    }
  }
}
