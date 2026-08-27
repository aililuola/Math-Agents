package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSalvagedVerifiedClaimNotSharedBlackBoxTest {
  @TempDir Path temp;

  @Test
  void verifiedLocalClaimFromFailedRouteRemainsPublishable() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(temp, "salvaged-claim-broadcast")) {
      harness.freezeAndCreateRouteForClaim(
          "claim-salvage-source",
          "tree-leaf",
          "Every finite tree with at least two vertices has a leaf.");
      harness.runSingleLegacyClaimRound(
          0, "tree-leaf", "Every finite tree with at least two vertices has a leaf.");
      long salvaged = harness.typedMemory().factsForRoute("route-1").size();
      harness.addRelatedRouteForClaim(
          "tree-leaf", "Every finite tree with at least two vertices has a leaf.");
      harness.distributeBrokerArtifacts();
      long published = harness.mathematicalArtifactBroker().artifacts().size();
      long received =
          harness.mathematicalArtifactBroker().deliveries().stream()
              .filter(delivery -> delivery.targetRouteId().equals("route-2"))
              .count();
      System.out.println("SALVAGED_VERIFIED_CLAIMS=" + salvaged);
      System.out.println("BROKER_ARTIFACTS_PUBLISHED=" + published);
      System.out.println("TARGET_ROUTE_RECEIVED=" + received);
      System.out.println("EXPECTED_TARGET_ROUTE_RECEIVED=1");
      assertThat(salvaged).isEqualTo(1);
      assertThat(published).isEqualTo(1);
      assertThat(received).isEqualTo(1);
    }
  }
}
