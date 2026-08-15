package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactEffectObservation;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DesktopBrokerArtifactInvalidationTest {
  @Test
  void invalidatedSourceStopsDeliveryAndRetainsAuditedLineage() {
    DesktopBrokerArtifactFixture fixture = new DesktopBrokerArtifactFixture();
    var artifact = fixture.artifact("invalidate", "source-route");
    fixture.broker.publish(
        artifact, List.of(fixture.related("target-route", "invalidate")), 0, 8);
    var batch =
        fixture.broker.consumeForPrompt(
            "target-route", "invalidate-request", 0, 8, 1.0d, Set.of(), Set.of(), Set.of(),
            "strategy-target", null);
    fixture.broker.acknowledge(
        "invalidate-request",
        fixture.use(
            "invalidate-request", artifact, BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP),
        Set.of("step-use"));
    fixture.broker.verifyEffect(
        batch.deliveries().getFirst().deliveryId(),
        new BrokerArtifactEffectObservation(
            Set.of("step-use"), Set.of(), Set.of(), Set.of(), Set.of(), null, null, null, null,
            false, 0.5d));

    var invalidation = fixture.broker.invalidate(artifact.artifactId(), "court-case", "reopened", 1);
    fixture.broker.publish(
        artifact, List.of(fixture.related("later-route", "invalidate")), 1, 8);
    var later =
        fixture.broker.consumeForPrompt(
            "later-route", "later-request", 1, 8, 1.0d, Set.of(), Set.of(), Set.of(),
            "strategy-later", null);

    assertThat(later.artifacts()).isEmpty();
    assertThat(invalidation.revalidationTaskId()).isNotBlank();
    assertThat(fixture.broker.receipts().getFirst().status())
        .isEqualTo(BrokerArtifactReceiptStatus.INVALIDATED);
    assertThat(fixture.broker.utilities().getFirst().invalidated()).isTrue();
    assertThat(fixture.broker.lineage()).hasSize(1);
  }
}
