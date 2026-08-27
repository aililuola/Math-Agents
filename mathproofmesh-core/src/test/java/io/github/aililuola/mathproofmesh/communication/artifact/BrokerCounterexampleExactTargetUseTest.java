package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseClaim;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseManifest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerCounterexampleExactTargetUseTest {
  @Test
  void counterexampleUseMustBindItsExactClaimAndObligations() {
    int wrongClaimAccepts = receiptFor("wrong-claim-route", "wrong-claim-request",
        List.of("claim-other"), List.of("target-tree"));
    int wrongObligationAccepts = receiptFor("wrong-obligation-route", "wrong-obligation-request",
        List.of("claim-tree"), List.of("target-other"));

    System.out.println("COUNTEREXAMPLE_WRONG_TARGET_USE_ACCEPTS=" + wrongClaimAccepts);
    System.out.println("COUNTEREXAMPLE_WRONG_OBLIGATION_USE_ACCEPTS=" + wrongObligationAccepts);
    assertThat(wrongClaimAccepts).isZero();
    assertThat(wrongObligationAccepts).isZero();
    assertThat(receiptFor("exact-route", "exact-request",
        List.of("claim-tree"), List.of("target-tree"))).isEqualTo(1);
  }

  private static int receiptFor(
      String routeId, String requestId, List<String> claimIds, List<String> obligationIds) {
    MathematicalArtifactBroker broker = new MathematicalArtifactBroker();
    var artifact = BrokerArtifactTestFixtures.counterexample();
    broker.publish(artifact, List.of(BrokerArtifactTestFixtures.related(routeId)), 0, 8);
    broker.consumeForPrompt(
        routeId, requestId, 0, 8, 1.0d, Set.of("target-tree"), Set.of(), Set.of(),
        "strategy-1", "target-tree");
    var receipt = broker.acknowledge(
        requestId,
        new BrokerArtifactUseManifest(
            requestId,
            List.of(
                new BrokerArtifactUseClaim(
                    artifact.artifactId(), BrokerArtifactUseKind.REFUTES_CLAIM, List.of(),
                    claimIds, obligationIds,
                    ((io.github.aililuola.mathproofmesh.contract.VerifiedCounterexamplePayload)
                            artifact.payload())
                        .targetSemanticHash(),
                    "The witness refutes the exact target."))),
        Set.of()).getFirst();
    return receipt.status() == BrokerArtifactReceiptStatus.USED_PENDING_EFFECT ? 1 : 0;
  }
}
