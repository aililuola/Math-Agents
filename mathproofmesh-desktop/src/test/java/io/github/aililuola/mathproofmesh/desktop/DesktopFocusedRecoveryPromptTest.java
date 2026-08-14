package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopFocusedRecoveryPromptTest {
  @Test
  void projectsDeterministicFocusedBriefWithoutGrantingFamilyAuthority(@TempDir Path directory)
      throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-focused-prompt",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      DesktopProofGraphIssue005BlackBoxSupport.enterFocusedRecovery(harness);

      Map<String, Object> context =
          DesktopProofGraphIssue005BlackBoxSupport.firstRouteContext(harness);

      assertThat(context.get("proof_graph_control_mode")).isEqualTo("FOCUSED_RECOVERY");
      assertThat(context.get("focused_recovery_brief")).isNotNull();
      assertThat(context.get("selected_canonical_targets").toString()).isNotEqualTo("[]");
      assertThat(context.get("allowed_recovery_actions").toString())
          .contains("FOCUSED_PROVER", "EXACT_FALSIFICATION");
      assertThat(context.get("blocked_generic_actions").toString())
          .contains("GENERIC_INSPIRATION", "NEW_STRATEGY");
      assertThat(context.get("focused_recovery_authority_rule").toString())
          .contains("Do not treat family members as equivalent")
          .contains("Do not close or refute sibling targets");
      assertThat(context.get("immutable_problem")).isNotNull();
    }
  }
}
