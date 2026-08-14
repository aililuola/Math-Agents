package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopProofDebtNotControllingSchedulerBlackBoxTest {
  @Test
  void sustainedNoProgressBlocksGenericExpansionAndEntersFocusedRecovery(
      @TempDir Path directory) throws Exception {
    int attempts = 0;
    int accepted = 0;
    int leaks = 0;
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-proof-debt-baseline",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      ProofGraphStore graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
      for (int round = 0; round < 3; round++) {
        graph.addObligation(
            DesktopProofGraphIssue005BlackBoxSupport.obligation(
                "stagnant-duplicate-" + round,
                "route-1",
                "Derive the same unresolved finite hitting-set reduction.",
                "derive the same unresolved finite hitting-set reduction",
                "stagnant-hitting-set",
                "round-plan-" + round));
        DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, round);
        boolean focused =
            "FOCUSED_RECOVERY".equals(
                DesktopProofGraphIssue005BlackBoxSupport.controlMode(harness));
        for (String source :
            List.of(
                "generic-inspiration-" + round,
                "representation-switch-" + round,
                "structural-analogy-" + round,
                "unscoped-bridge-" + round)) {
          attempts++;
          boolean admitted =
              DesktopProofGraphIssue005BlackBoxSupport.enqueue(
                  harness, source, "route-1", "obligation-route-1", "DEEPEN");
          if (admitted) {
            accepted++;
            if (focused) {
              leaks++;
            }
          }
        }
      }
      int blocks = attempts - accepted;
      int focusedEntries =
          DesktopProofGraphIssue005BlackBoxSupport.focusedRecoveryEntries(harness);

      System.out.println("PROOF DEBT SCHEDULER CONTROL BASELINE");
      System.out.println("CONSECUTIVE_NO_PROGRESS_ROUNDS=3");
      System.out.println("GENERIC_EXPANSION_ATTEMPTS=" + attempts);
      System.out.println("GENERIC_EXPANSION_BLOCKS=" + blocks);
      System.out.println("FOCUSED_RECOVERY_ENTRIES=" + focusedEntries);
      System.out.println("GENERIC_EXPANSION_LEAKS=" + leaks);

      assertThat(attempts).isPositive();
      assertThat(blocks).isPositive();
      assertThat(focusedEntries).isPositive();
      assertThat(leaks).isZero();
    }
  }
}
