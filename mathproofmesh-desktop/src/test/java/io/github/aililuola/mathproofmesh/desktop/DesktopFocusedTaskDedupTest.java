package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopFocusedTaskDedupTest {
  @Test
  void repeatedSamplingDoesNotStackFocusedFamilyTasks(@TempDir Path directory) throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-focused-task-dedup",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      DesktopProofGraphIssue005BlackBoxSupport.enterFocusedRecovery(harness);
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 2);
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 3);

      long focusedTasks =
          DesktopProofGraphIssue005BlackBoxSupport.pendingTasks(harness).stream()
              .map(Object::toString)
              .filter(value -> value.contains("focused-recovery"))
              .count();

      assertThat(focusedTasks).isEqualTo(1);
    }
  }
}
