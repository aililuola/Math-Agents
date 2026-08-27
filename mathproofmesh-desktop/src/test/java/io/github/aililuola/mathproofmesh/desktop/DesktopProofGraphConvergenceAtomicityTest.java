package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopProofGraphConvergenceAtomicityTest {
  @Test
  void blockedGenericActionsDoNotMutateActiveCoordinatorState(@TempDir Path directory)
      throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-convergence-atomicity",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      DesktopProofGraphIssue005BlackBoxSupport.enterFocusedRecovery(harness);
      int routes = DesktopProofGraphIssue005BlackBoxSupport.routeCount(harness);
      int strategies = DesktopProofGraphIssue005BlackBoxSupport.admittedStrategyCount(harness);
      int tasks = DesktopProofGraphIssue005BlackBoxSupport.pendingTaskCount(harness);
      int rawOccurrences =
          DesktopProofGraphIssue005BlackBoxSupport.graph(harness)
              .rawObligationOccurrences()
              .size();
      int canonicalTargets =
          DesktopProofGraphIssue005BlackBoxSupport.graph(harness).allCanonicalTargets().size();

      assertThat(DesktopProofGraphIssue005BlackBoxSupport.widenRoutes(harness)).isFalse();
      assertThat(
              DesktopProofGraphIssue005BlackBoxSupport.enqueue(
                  harness,
                  "generic-inspiration-atomicity",
                  "route-1",
                  "unrelated-atomicity",
                  "DEEPEN"))
          .isFalse();

      assertThat(DesktopProofGraphIssue005BlackBoxSupport.routeCount(harness)).isEqualTo(routes);
      assertThat(DesktopProofGraphIssue005BlackBoxSupport.admittedStrategyCount(harness))
          .isEqualTo(strategies);
      assertThat(DesktopProofGraphIssue005BlackBoxSupport.pendingTaskCount(harness))
          .isEqualTo(tasks);
      assertThat(
              DesktopProofGraphIssue005BlackBoxSupport.graph(harness)
                  .rawObligationOccurrences())
          .hasSize(rawOccurrences);
      assertThat(
              DesktopProofGraphIssue005BlackBoxSupport.graph(harness).allCanonicalTargets())
          .hasSize(canonicalTargets);
    }
  }
}
