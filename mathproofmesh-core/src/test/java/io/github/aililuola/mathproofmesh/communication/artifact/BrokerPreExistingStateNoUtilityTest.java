package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseClaim;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseManifest;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.VerifiedCounterexamplePayload;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerPreExistingStateNoUtilityTest {
  @Test
  void preExistingAuthoritativeStateCannotBeClaimedAsArtifactUtility() {
    int verifiedUtilities = verifyPreExistingClaim();
    int refutedUtilities = verifyPreExistingRefutation();
    int closedUtilities = verifyPreExistingClosure();

    System.out.println("PREEXISTING_VERIFIED_CLAIM_UTILITIES=" + verifiedUtilities);
    System.out.println("PREEXISTING_REFUTED_CLAIM_UTILITIES=" + refutedUtilities);
    System.out.println("PREEXISTING_CLOSED_OBLIGATION_UTILITIES=" + closedUtilities);
    assertThat(verifiedUtilities).isZero();
    assertThat(refutedUtilities).isZero();
    assertThat(closedUtilities).isZero();
  }

  private static int verifyPreExistingClaim() {
    MathematicalArtifactBroker broker = new MathematicalArtifactBroker();
    BrokerArtifactEnvelope artifact = BrokerArtifactTestFixtures.verifiedClaim();
    String request = "preexisting-verified";
    String delivery = deliver(
        broker, artifact, request, Set.of(), Set.of("downstream-claim"), Set.of());
    acknowledge(
        broker,
        request,
        artifact,
        BrokerArtifactUseKind.SUPPORTS_CLAIM,
        List.of("downstream-claim"),
        List.of());
    return broker.verifyEffect(
            delivery,
            observation(Set.of("downstream-claim"), Set.of(), Set.of()))
        .isPresent() ? 1 : 0;
  }

  private static int verifyPreExistingRefutation() {
    MathematicalArtifactBroker broker = new MathematicalArtifactBroker();
    BrokerArtifactEnvelope artifact = BrokerArtifactTestFixtures.counterexample();
    String request = "preexisting-refuted";
    String delivery = deliver(
        broker, artifact, request, Set.of("target-tree"), Set.of(), Set.of("claim-tree"));
    acknowledge(
        broker,
        request,
        artifact,
        BrokerArtifactUseKind.REFUTES_CLAIM,
        List.of("claim-tree"),
        List.of("target-tree"));
    return broker.verifyEffect(
            delivery,
            observation(Set.of(), Set.of("claim-tree"), Set.of()))
        .isPresent() ? 1 : 0;
  }

  private static int verifyPreExistingClosure() {
    MathematicalArtifactBroker broker = new MathematicalArtifactBroker();
    BrokerArtifactEnvelope artifact = BrokerArtifactTestFixtures.verifiedClaim();
    String request = "preexisting-closed";
    String delivery = deliver(broker, artifact, request, Set.of(), Set.of(), Set.of());
    acknowledge(
        broker,
        request,
        artifact,
        BrokerArtifactUseKind.SUPPORTS_CLAIM,
        List.of(),
        List.of("target-tree"));
    return broker.verifyEffect(
            delivery,
            observation(Set.of(), Set.of(), Set.of("target-tree")))
        .isPresent() ? 1 : 0;
  }

  private static String deliver(
      MathematicalArtifactBroker broker,
      BrokerArtifactEnvelope artifact,
      String request,
      Set<String> open,
      Set<String> verified,
      Set<String> refuted) {
    broker.publish(artifact, List.of(BrokerArtifactTestFixtures.related("route-b")), 0, 8);
    return broker.consumeForPrompt(
            "route-b", request, 0, 8, 2.0d, open, verified, refuted, "strategy-1", null)
        .deliveries()
        .getFirst()
        .deliveryId();
  }

  private static void acknowledge(
      MathematicalArtifactBroker broker,
      String request,
      BrokerArtifactEnvelope artifact,
      BrokerArtifactUseKind kind,
      List<String> claims,
      List<String> obligations) {
    String semanticHash =
        artifact.payload() instanceof VerifiedCounterexamplePayload counterexample
            ? counterexample.targetSemanticHash()
            : null;
    var receipt = broker.acknowledge(
        request,
        new BrokerArtifactUseManifest(
            request,
            List.of(
                new BrokerArtifactUseClaim(
                    artifact.artifactId(), kind, List.of(), claims, obligations, semanticHash,
                    "exact use"))),
        Set.of()).getFirst();
    assertThat(receipt.status()).isEqualTo(BrokerArtifactReceiptStatus.USED_PENDING_EFFECT);
  }

  private static BrokerArtifactEffectObservation observation(
      Set<String> verified, Set<String> refuted, Set<String> closed) {
    return new BrokerArtifactEffectObservation(
        Set.of(), verified, refuted, closed, Set.of(), null, null, null, null, false, 2.0d);
  }
}
