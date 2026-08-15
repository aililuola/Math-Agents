package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerArtifactInvalidationTest {
  @Test
  void invalidatedArtifactStopsFutureDeliveryAndKeepsAudit() {
    MathematicalArtifactBroker broker = new MathematicalArtifactBroker();
    var artifact = BrokerArtifactTestFixtures.verifiedClaim();
    broker.publish(artifact, List.of(BrokerArtifactTestFixtures.related("route-b")), 0, 8);
    broker.consumeForPrompt(
        "route-b", "provider-request-1", 0, 8, 1.0d, Set.of("target-tree"), Set.of(),
        Set.of(), "strategy-1", "target-tree");
    broker.acknowledge(
        "provider-request-1",
        BrokerArtifactTestFixtures.useManifest(
            artifact, BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP),
        Set.of("downstream-step"));
    var invalidation = broker.invalidate(artifact.artifactId(), "court-case-1", "revision revoked", 1);
    var prompt =
        broker.consumeForPrompt(
            "route-b", "request-after-invalidation", 1, 8, 1.0d, Set.of("target-tree"),
            Set.of(), Set.of(), "strategy-1", "target-tree");
    assertThat(prompt.artifacts()).isEmpty();
    assertThat(invalidation.revalidationTaskId()).startsWith("broker-revalidation-");
    assertThat(broker.invalidations()).hasSize(1);
  }
}
