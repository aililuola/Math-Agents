package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopRouteTheoremRepairCannotBypassRouteValidationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void repairedLocalClaimDoesNotCloseMainGoalOfFailedRoute() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("route-boundary"), "claim-court-route-boundary")) {
      harness.freezeAndCreateRoute();
      harness.runSingleLegacyClaimRound(
          0,
          "repairable-local-only",
          "FALSE_LOCAL_REPAIRABLE: A local finite-set bridge can be repaired, but the route theorem remains false.");

      assertThat(harness.typedMemory().facts())
          .anyMatch(fact -> fact.messageId().equals("repairable-local-only"));
      assertThat(harness.proofGraph().getObligation("main-goal").status()).isEqualTo("open");
      assertThat(harness.productionState().routeClaimCount()).isEqualTo(1);
    }
  }
}
