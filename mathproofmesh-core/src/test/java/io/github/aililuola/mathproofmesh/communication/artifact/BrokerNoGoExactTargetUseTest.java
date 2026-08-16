package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseClaim;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseManifest;
import io.github.aililuola.mathproofmesh.contract.VerifiedNoGoPayload;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerNoGoExactTargetUseTest {
  @Test
  void noGoUseMustBindItsExactClaimHashAndObligation() {
    BrokerArtifactEnvelope artifact = noGo();

    assertThat(status(artifact, "valid", List.of("claim-tree"), List.of("target-tree"),
            semanticHash(artifact)))
        .isEqualTo(BrokerArtifactReceiptStatus.USED_PENDING_EFFECT);
    assertThat(status(artifact, "wrong-claim", List.of("claim-other"), List.of("target-tree"),
            semanticHash(artifact)))
        .isEqualTo(BrokerArtifactReceiptStatus.REJECTED_INVALID_USE);
    assertThat(status(artifact, "wrong-hash", List.of("claim-tree"), List.of("target-tree"),
            "semantic-other"))
        .isEqualTo(BrokerArtifactReceiptStatus.REJECTED_INVALID_USE);
    assertThat(status(artifact, "wrong-obligation", List.of("claim-tree"),
            List.of("target-other"), semanticHash(artifact)))
        .isEqualTo(BrokerArtifactReceiptStatus.REJECTED_INVALID_USE);
  }

  private static BrokerArtifactReceiptStatus status(
      BrokerArtifactEnvelope artifact,
      String suffix,
      List<String> claims,
      List<String> obligations,
      String semanticHash) {
    MathematicalArtifactBroker broker = new MathematicalArtifactBroker();
    String route = "route-" + suffix;
    String request = "request-" + suffix;
    broker.publish(artifact, List.of(BrokerArtifactTestFixtures.related(route)), 0, 8);
    broker.consumeForPrompt(
        route,
        request,
        0,
        8,
        1.0d,
        Set.of("target-tree"),
        Set.of(),
        Set.of(),
        "strategy-1",
        "target-tree");
    return broker
        .acknowledge(
            request,
            new BrokerArtifactUseManifest(
                request,
                List.of(
                    new BrokerArtifactUseClaim(
                        artifact.artifactId(),
                        BrokerArtifactUseKind.RETIRES_DEPENDENCY,
                        List.of(),
                        claims,
                        obligations,
                        semanticHash,
                        "retire only the exact blocked inference"))),
            Set.of())
        .getFirst()
        .status();
  }

  private static BrokerArtifactEnvelope noGo() {
    var context = BrokerArtifactTestFixtures.context("forall", "global", "positive");
    return new BrokerArtifactCompiler()
        .compile(
            BrokerArtifactTestFixtures.request(
                BrokerArtifactType.VERIFIED_NO_GO,
                new VerifiedNoGoPayload(
                    context, "claim-tree", "The proposed shortcut is invalid."),
                BrokerArtifactSourceKind.REFUTED_STATEMENT,
                "route-a",
                "claim-tree",
                "revision-tree",
                true))
        .artifact();
  }

  private static String semanticHash(BrokerArtifactEnvelope artifact) {
    return ((VerifiedNoGoPayload) artifact.payload()).targetClaim().claimSemanticHash();
  }
}
