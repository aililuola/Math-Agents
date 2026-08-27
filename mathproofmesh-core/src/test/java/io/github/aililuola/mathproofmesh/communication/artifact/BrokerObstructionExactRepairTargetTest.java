package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseClaim;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseManifest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerObstructionExactRepairTargetTest {
  @Test
  void reviewedObstructionCannotTriggerRepairForAnUnrelatedTarget() {
    MathematicalArtifactBroker broker = new MathematicalArtifactBroker();
    var artifact = BrokerArtifactTestFixtures.obstruction();
    broker.publish(artifact, List.of(BrokerArtifactTestFixtures.related("route-b")), 0, 8);
    broker.consumeForPrompt(
        "route-b", "obstruction-request", 0, 8, 1.0d, Set.of("target-tree"), Set.of(),
        Set.of(), "strategy-1", "target-tree");
    var receipt = broker.acknowledge(
        "obstruction-request",
        new BrokerArtifactUseManifest(
            "obstruction-request",
            List.of(
                new BrokerArtifactUseClaim(
                    artifact.artifactId(), BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR,
                    List.of(), List.of(), List.of("target-other"),
                    "Repair an unrelated obligation."))),
        Set.of()).getFirst();
    int unrelatedRepairAccepts =
        receipt.status() == BrokerArtifactReceiptStatus.USED_PENDING_EFFECT ? 1 : 0;

    System.out.println("OBSTRUCTION_WRONG_REPAIR_TARGET_ACCEPTS=" + unrelatedRepairAccepts);
    assertThat(unrelatedRepairAccepts).isZero();

    MathematicalArtifactBroker exactBroker = new MathematicalArtifactBroker();
    exactBroker.publish(artifact, List.of(BrokerArtifactTestFixtures.related("route-c")), 0, 8);
    exactBroker.consumeForPrompt(
        "route-c", "exact-obstruction-request", 0, 8, 1.0d, Set.of("target-tree"),
        Set.of(), Set.of(), "strategy-1", "target-tree");
    var exactReceipt = exactBroker.acknowledge(
        "exact-obstruction-request",
        new BrokerArtifactUseManifest(
            "exact-obstruction-request",
            List.of(
                new BrokerArtifactUseClaim(
                    artifact.artifactId(), BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR,
                    List.of(), List.of(), List.of("target-tree"), "Repair the exact target."))),
        Set.of()).getFirst();
    assertThat(exactReceipt.status()).isEqualTo(BrokerArtifactReceiptStatus.USED_PENDING_EFFECT);
  }
}
