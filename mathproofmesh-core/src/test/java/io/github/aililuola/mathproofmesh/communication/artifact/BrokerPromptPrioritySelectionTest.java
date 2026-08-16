package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.VerifiedClaimPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerPromptPrioritySelectionTest {
  @Test
  void exactCounterexampleCannotBeEvictedByLowerPriorityQueuedArtifacts() {
    MathematicalArtifactBroker broker = new MathematicalArtifactBroker();
    List<BrokerArtifactEnvelope> lowerPriority = new ArrayList<>();
    for (int index = 0; index < 9; index++) {
      BrokerArtifactEnvelope artifact = lowerPriorityArtifact(index);
      lowerPriority.add(artifact);
      broker.publish(artifact, List.of(BrokerArtifactTestFixtures.related("route-b")), 0, 8);
    }
    BrokerArtifactEnvelope highPriority = BrokerArtifactTestFixtures.counterexample();
    broker.publish(highPriority, List.of(BrokerArtifactTestFixtures.related("route-b")), 0, 8);

    var batch = broker.consumeForPrompt(
        "route-b", "priority-request", 0, 8, 1.0d, Set.of("target-tree"), Set.of(),
        Set.of(), "strategy-1", "target-tree");
    int highPriorityEvictions =
        batch.artifacts().stream().anyMatch(item -> item.artifactId().equals(highPriority.artifactId()))
            ? 0 : 1;

    System.out.println("HIGH_PRIORITY_ARTIFACT_EVICTIONS=" + highPriorityEvictions);
    assertThat(highPriorityEvictions).isZero();
    assertThat(batch.artifacts()).hasSize(8);
  }

  private static BrokerArtifactEnvelope lowerPriorityArtifact(int index) {
    var context = BrokerArtifactTestFixtures.context("forall", "global-" + index, "positive");
    return new BrokerArtifactCompiler()
        .compile(
            BrokerArtifactTestFixtures.request(
                BrokerArtifactType.VERIFIED_CLAIM,
                new VerifiedClaimPayload(context),
                BrokerArtifactSourceKind.CLAIM_COURT_VERIFIED,
                "route-low-" + index,
                "claim-low-" + index,
                "revision-low-" + index,
                true))
        .artifact();
  }
}
