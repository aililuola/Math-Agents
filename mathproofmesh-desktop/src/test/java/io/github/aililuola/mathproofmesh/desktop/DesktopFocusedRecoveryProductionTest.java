package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphControlMode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopFocusedRecoveryProductionTest {
  @Test
  void focusedModeBlocksNewWideningWithoutCancellingExistingWork(@TempDir Path directory)
      throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-focused-production",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      DesktopProofGraphIssue005BlackBoxSupport.enterFocusedRecovery(harness);
      int routesBefore = DesktopProofGraphIssue005BlackBoxSupport.routeCount(harness);
      int tasksBefore = DesktopProofGraphIssue005BlackBoxSupport.pendingTaskCount(harness);
      int callsBefore = harness.providerCallCount();

      boolean widened = DesktopProofGraphIssue005BlackBoxSupport.widenRoutes(harness);
      boolean unrelatedTask =
          DesktopProofGraphIssue005BlackBoxSupport.enqueue(
              harness,
              "generic-inspiration-production",
              "route-1",
              "unrelated-production-obligation",
              "DEEPEN");

      assertThat(DesktopProofGraphIssue005BlackBoxSupport.controlMode(harness))
          .isEqualTo(ProofGraphControlMode.FOCUSED_RECOVERY.name());
      assertThat(widened).isFalse();
      assertThat(unrelatedTask).isFalse();
      assertThat(DesktopProofGraphIssue005BlackBoxSupport.routeCount(harness))
          .isEqualTo(routesBefore);
      assertThat(DesktopProofGraphIssue005BlackBoxSupport.pendingTaskCount(harness))
          .isEqualTo(tasksBefore);
      assertThat(harness.providerCallCount()).isEqualTo(callsBefore);
    }
  }
}
