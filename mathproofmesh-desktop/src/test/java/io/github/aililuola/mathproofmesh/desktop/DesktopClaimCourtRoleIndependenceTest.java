package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimCourtRoleIndependenceTest {
  @TempDir Path temporaryDirectory;

  @Test
  void productionAssignmentUsesFiveDistinctAgents() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("roles"), "claim-court-roles")) {
      harness.freezeAndCreateRoute();
      harness.runSingleLegacyClaimRound(
          0, "role-claim", "VALID_ROLE: Every finite path has at most two endpoints.");

      var assignment = harness.claimCourt().records().getFirst().roleAssignment();
      assertThat(
              List.of(
                  assignment.authorAgentId(),
                  assignment.falsifierAgentId(),
                  assignment.auditorAgentId(),
                  assignment.repairerAgentId(),
                  assignment.blindAdjudicatorAgentId()))
          .doesNotHaveDuplicates()
          .hasSize(5);
    }
  }
}
