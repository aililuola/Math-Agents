package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopGenericExpansionGateTest {
  @Test
  void allGenericTaskSourcesUseTheSameFocusedRecoveryGate(@TempDir Path directory)
      throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-generic-gate",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      DesktopProofGraphIssue005BlackBoxSupport.enterFocusedRecovery(harness);
      int before = DesktopProofGraphIssue005BlackBoxSupport.pendingTaskCount(harness);
      int blocked = 0;
      for (String source :
          List.of(
              "generic-inspiration",
              "representation-switch",
              "structural-analogy",
              "new-strategy",
              "unscoped-bridge")) {
        if (!DesktopProofGraphIssue005BlackBoxSupport.enqueue(
            harness, source, "route-1", "unrelated-" + source, "DEEPEN")) {
          blocked++;
        }
      }

      assertThat(blocked).isEqualTo(5);
      assertThat(DesktopProofGraphIssue005BlackBoxSupport.pendingTaskCount(harness))
          .isEqualTo(before);
      assertThat(DesktopProofGraphIssue005BlackBoxSupport.convergence(harness)
              .genericExpansionLeaks())
          .isZero();
    }
  }
}
