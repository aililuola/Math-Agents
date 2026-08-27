package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObligationCanonicalizationProtectedAuthorityTest {
  @Test
  void schedulingProjectionCannotCreateFactsNegativesOrMainGoalAuthority(@TempDir Path directory)
      throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-protected-authority",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      int factsBefore = (int) harness.directFactPromotions();
      int negativesBefore = harness.permanentNegativeRegistrations();
      int mainClosuresBefore = harness.mainGoalClosures();
      String rootBefore = harness.rootHash();
      var graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
      for (int index = 0; index < 10; index++) {
        graph.addObligation(
            DesktopProofGraphIssue005BlackBoxSupport.obligation(
                "authority-raw-" + index,
                "route-authority-" + index,
                "non-authoritative family target " + (index % 2),
                "non-authoritative family target " + (index % 2),
                "authority-family",
                "plan-" + index));
      }

      assertThat(harness.directFactPromotions()).isEqualTo(factsBefore);
      assertThat(harness.permanentNegativeRegistrations()).isEqualTo(negativesBefore);
      assertThat(harness.mainGoalClosures()).isEqualTo(mainClosuresBefore);
      assertThat(harness.rootHash()).isEqualTo(rootBefore);
      assertThat(graph.allBottleneckFamilies()).isNotEmpty();
    }
  }
}
