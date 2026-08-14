package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphControlMode;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphConvergenceMonitor;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopProofGraphConvergenceControllerTest {
  @Test
  void eightRoundsEnterFocusedRecoveryCooldownAndNormalExpansion(@TempDir Path directory)
      throws Exception {
    int genericAttempts = 0;
    int genericBlocks = 0;
    int genericLeaks = 0;
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-convergence-eight-rounds",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      ProofGraphStore graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
      for (int round = 0; round < 2; round++) {
        graph.addObligation(
            DesktopProofGraphIssue005BlackBoxSupport.obligation(
                "eight-round-duplicate-" + round,
                "route-1",
                "Derive the unresolved finite hitting-set reduction.",
                "derive the unresolved finite hitting-set reduction",
                "eight-round-bottleneck",
                "duplicate-plan-" + round));
        DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, round);
      }
      assertThat(DesktopProofGraphIssue005BlackBoxSupport.controlMode(harness))
          .isEqualTo(ProofGraphControlMode.FOCUSED_RECOVERY.name());

      for (int round = 2; round <= 4; round++) {
        DesktopProofGraphIssue005BlackBoxSupport.setRound(harness, round);
        for (String source :
            List.of(
                "generic-inspiration-" + round,
                "representation-switch-" + round,
                "structural-analogy-" + round,
                "unscoped-bridge-" + round)) {
          genericAttempts++;
          boolean admitted =
              DesktopProofGraphIssue005BlackBoxSupport.enqueue(
                  harness, source, "route-1", "unrelated-obligation", "DEEPEN");
          if (!admitted) {
            genericBlocks++;
          } else {
            genericLeaks++;
          }
        }
        DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, round);
      }

      DesktopProofGraphIssue005BlackBoxSupport.addVerifiedLocalClaim(
          harness, "focused-reviewed-local-claim");
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 5);
      assertThat(DesktopProofGraphIssue005BlackBoxSupport.controlMode(harness))
          .isEqualTo(ProofGraphControlMode.RECOVERY_COOLDOWN.name());
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 6);
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 7);

      ProofGraphConvergenceMonitor monitor =
          DesktopProofGraphIssue005BlackBoxSupport.convergence(harness);
      long focusedTasks =
          DesktopProofGraphIssue005BlackBoxSupport.pendingTasks(harness).stream()
              .map(Object::toString)
              .filter(value -> value.contains("focused-recovery"))
              .count();
      System.out.println("PROOF GRAPH CONVERGENCE 8-ROUND DIAGNOSTIC");
      System.out.println("ROUNDS=8");
      System.out.println("FOCUSED_RECOVERY_ENTRIES=" + monitor.focusedRecoveryEntries());
      System.out.println("RECOVERY_COOLDOWN_ENTRIES=" + monitor.recoveryCooldownEntries());
      System.out.println("GENERIC_EXPANSION_ATTEMPTS=" + genericAttempts);
      System.out.println("GENERIC_EXPANSION_BLOCKS=" + genericBlocks);
      System.out.println("GENERIC_EXPANSION_LEAKS=" + genericLeaks);
      System.out.println("FOCUSED_FAMILY_TASKS=" + focusedTasks);
      System.out.println("FINAL_CONTROL_MODE=" + monitor.controlMode());
      System.out.println("RESULT=PASS");

      assertThat(monitor.focusedRecoveryEntries()).isEqualTo(1);
      assertThat(monitor.recoveryCooldownEntries()).isEqualTo(1);
      assertThat(genericAttempts).isEqualTo(12);
      assertThat(genericBlocks).isEqualTo(12);
      assertThat(genericLeaks).isZero();
      assertThat(focusedTasks).isEqualTo(1);
      assertThat(monitor.controlMode()).isEqualTo(ProofGraphControlMode.NORMAL_EXPANSION);
    }
  }
}
