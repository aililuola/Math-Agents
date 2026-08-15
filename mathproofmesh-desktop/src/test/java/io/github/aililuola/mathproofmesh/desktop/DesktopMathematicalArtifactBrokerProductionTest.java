package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactAuthority;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopMathematicalArtifactBrokerProductionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void courtFactTravelsThroughCoordinatorCompilerBrokerAndRealPrompt() throws Exception {
    String claimId = "tree-leaf-production";
    String statement = "Every finite tree with at least two vertices has a leaf.";
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory, "broker-production")) {
      harness.freezeAndCreateRouteForClaim("source-strategy", claimId, statement);
      harness.runSingleLegacyClaimRound(0, claimId, statement);
      harness.addRelatedRouteForClaim(claimId, statement);
      harness.distributeBrokerArtifacts();

      var artifact = harness.mathematicalArtifactBroker().artifacts().getFirst();
      var promptArtifactIds = harness.exploreUnstartedRoutesAndCaptureBrokerArtifactIds();

      assertThat(artifact.authority()).isEqualTo(BrokerArtifactAuthority.VERIFIED);
      assertThat(artifact.problemHash()).isEqualTo(DesktopClaimSalvageTestHarness.PROBLEM_HASH);
      assertThat(artifact.rootGoalHash()).isEqualTo(harness.rootGoal().sourceStatementHash());
      assertThat(promptArtifactIds).containsExactly(artifact.artifactId());
      assertThat(harness.mathematicalArtifactBroker().deliveries())
          .singleElement()
          .extracting(delivery -> delivery.targetRouteId())
          .isEqualTo("route-2");
    }
  }
}
