package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProofGraphConvergenceProtectedAuthorityTest {
  @Test
  void convergenceControlCannotMutateProtectedMathematicalAuthority(@TempDir Path directory)
      throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-convergence-protected-authority",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      DesktopProofGraphIssue005BlackBoxSupport.enterFocusedRecovery(harness);

      String rootHash = harness.rootHash();
      String negativeHash = harness.negativeHash();
      String attemptHash = harness.attemptArtifactHash();
      String claimHash = harness.claimLifecycleHash();
      String researchHash = harness.researchLedger().ledgerHash();
      String canonicalizationHash =
          DesktopProofGraphIssue005BlackBoxSupport.canonicalizationHash(harness);
      long facts = harness.directFactPromotions();
      int claims = harness.directClaimVerifications();
      int negatives = harness.permanentNegativeRegistrations();
      int mainGoalClosures = harness.mainGoalClosures();
      int tasks = DesktopProofGraphIssue005BlackBoxSupport.pendingTaskCount(harness);
      int routes = DesktopProofGraphIssue005BlackBoxSupport.routeCount(harness);

      assertThat(DesktopProofGraphIssue005BlackBoxSupport.widenRoutes(harness)).isFalse();
      for (String source :
          List.of(
              "generic-inspiration-protected",
              "representation-switch-protected",
              "structural-analogy-protected",
              "new-strategy-protected",
              "unscoped-bridge-protected")) {
        assertThat(
                DesktopProofGraphIssue005BlackBoxSupport.enqueue(
                    harness, source, "route-1", "unrelated-protected-target", "DEEPEN"))
            .isFalse();
      }

      assertThat(harness.rootHash()).isEqualTo(rootHash);
      assertThat(harness.negativeHash()).isEqualTo(negativeHash);
      assertThat(harness.attemptArtifactHash()).isEqualTo(attemptHash);
      assertThat(harness.claimLifecycleHash()).isEqualTo(claimHash);
      assertThat(harness.researchLedger().ledgerHash()).isEqualTo(researchHash);
      assertThat(DesktopProofGraphIssue005BlackBoxSupport.canonicalizationHash(harness))
          .isEqualTo(canonicalizationHash);
      assertThat(harness.directFactPromotions()).isEqualTo(facts);
      assertThat(harness.directClaimVerifications()).isEqualTo(claims);
      assertThat(harness.permanentNegativeRegistrations()).isEqualTo(negatives);
      assertThat(harness.mainGoalClosures()).isEqualTo(mainGoalClosures);
      assertThat(DesktopProofGraphIssue005BlackBoxSupport.pendingTaskCount(harness))
          .isEqualTo(tasks);
      assertThat(DesktopProofGraphIssue005BlackBoxSupport.routeCount(harness)).isEqualTo(routes);
      assertThat(DesktopProofGraphIssue005BlackBoxSupport.convergence(harness)
              .genericExpansionLeaks())
          .isZero();
    }
  }
}
