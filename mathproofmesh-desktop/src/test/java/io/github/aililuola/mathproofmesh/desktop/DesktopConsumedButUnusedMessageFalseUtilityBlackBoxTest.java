package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DesktopConsumedButUnusedMessageFalseUtilityBlackBoxTest {
  @Test
  void consumedButUnreferencedArtifactCannotReceiveUtility() {
    DesktopBrokerArtifactFixture fixture = new DesktopBrokerArtifactFixture();
    BrokerArtifactEnvelope artifact = fixture.artifact("unused", "route-a");
    fixture.broker.publish(artifact, List.of(fixture.related("route-b", "unused")), 0, 8);
    fixture.broker.consumeForPrompt(
        "route-b", "unused-request", 0, 8, 1.0d, Set.of("target-unused"),
        Set.of(), Set.of(), "strategy-route-b", "target-unused");

    var receipts = fixture.broker.acknowledge("unused-request", null, Set.of("unrelated-step"));
    int explicitUses = fixture.broker.lineage().size();
    int acceptedUsedReceipts =
        (int)
            receipts.stream()
                .filter(
                    receipt ->
                        receipt.status() == BrokerArtifactReceiptStatus.USED_PENDING_EFFECT
                            || receipt.status()
                                == BrokerArtifactReceiptStatus.USED_EFFECT_VERIFIED)
                .count();
    int falseUtilities = fixture.broker.utilities().size();

    System.out.println("DELIVERED_MESSAGES=" + fixture.broker.deliveries().size());
    System.out.println("EXPLICIT_MESSAGE_USES=" + explicitUses);
    System.out.println("ACCEPTED_USED_RECEIPTS=" + acceptedUsedReceipts);
    System.out.println("FALSE_UTILITY_RECORDS=" + falseUtilities);
    System.out.println("EXPECTED_FALSE_UTILITY_RECORDS=0");
    assertThat(receipts)
        .singleElement()
        .extracting(receipt -> receipt.status())
        .isEqualTo(BrokerArtifactReceiptStatus.NOT_USED);
    assertThat(explicitUses).isZero();
    assertThat(acceptedUsedReceipts).isZero();
    assertThat(falseUtilities).isZero();
  }
}
