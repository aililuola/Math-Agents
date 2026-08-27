package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DesktopBrokerExplicitUseReceiptTest {
  @Test
  void deliveredArtifactNeedsAnExplicitValidatedUseManifest() {
    DesktopBrokerArtifactFixture fixture = new DesktopBrokerArtifactFixture();
    var artifact = fixture.artifact("explicit-use", "source-route");
    fixture.broker.publish(
        artifact, List.of(fixture.related("target-route", "explicit-use")), 0, 8);
    fixture.broker.consumeForPrompt(
        "target-route", "explicit-request", 0, 8, 1.0d, Set.of(), Set.of(), Set.of(),
        "strategy-target", null);

    var receipts =
        fixture.broker.acknowledge(
            "explicit-request",
            fixture.use(
                "explicit-request", artifact, BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP),
            Set.of("step-use"));

    assertThat(receipts)
        .singleElement()
        .extracting(receipt -> receipt.status())
        .isEqualTo(BrokerArtifactReceiptStatus.USED_PENDING_EFFECT);
    assertThat(fixture.broker.lineage())
        .singleElement()
        .satisfies(
            lineage -> {
              assertThat(lineage.artifactId()).isEqualTo(artifact.artifactId());
              assertThat(lineage.downstreamProofStepIds()).containsExactly("step-use");
              assertThat(lineage.effectVerified()).isFalse();
            });
  }
}
