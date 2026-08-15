package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DesktopBrokerNotUsedReceiptTest {
  @Test
  void omissionFromUseManifestCreatesDurableNotUsedReceipt() {
    DesktopBrokerArtifactFixture fixture = new DesktopBrokerArtifactFixture();
    var artifact = fixture.artifact("not-used", "source-route");
    fixture.broker.publish(artifact, List.of(fixture.related("target-route", "not-used")), 0, 8);
    fixture.broker.consumeForPrompt(
        "target-route", "not-used-request", 0, 8, 1.0d, Set.of(), Set.of(), Set.of(),
        "strategy-target", null);

    fixture.broker.acknowledge("not-used-request", null, Set.of("unrelated-step"));

    assertThat(fixture.broker.receipts())
        .singleElement()
        .extracting(receipt -> receipt.status())
        .isEqualTo(BrokerArtifactReceiptStatus.NOT_USED);
    assertThat(fixture.broker.lineage()).isEmpty();
    assertThat(fixture.broker.utilities()).isEmpty();
  }
}
