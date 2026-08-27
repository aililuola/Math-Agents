package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactKind;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.ClaimLifecycleController;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimSalvageNoMainGoalClosureTest {
  @TempDir Path temporaryDirectory;

  @Test
  void failedRouteArtifactsProjectWithoutClosingAnyPositiveObligation() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("no-main-closure"), "claim-salvage-no-main-closure")) {
      harness.freezeAndCreateRoute();
      harness.addCounterexampleTargets();
      harness.runFailedRound(0);

      assertThat(harness.proofGraph().getObligation("main-goal").status()).isEqualTo("open");
      assertThat(harness.proofGraph().obligations())
          .noneSatisfy(obligation -> assertThat(obligation.status()).isEqualTo("closed"));
      assertThat(
              harness
                  .proofGraph()
                  .getObligation(DesktopClaimSalvageTestHarness.counterTarget(0))
                  .status())
          .isEqualTo("refuted");
      assertThat(harness.proofGraph().obligations())
          .filteredOn(
              obligation ->
                  obligation.obligationId().startsWith("counter-target-")
                      && !obligation.obligationId().equals("counter-target-0"))
          .allSatisfy(obligation -> assertThat(obligation.status()).isEqualTo("open"));

      assertThat(harness.attemptArtifacts().recordsForAttempt("failed-attempt-0"))
          .anySatisfy(
              artifact -> {
                assertThat(artifact.claimId()).isEqualTo("correct-local-0");
                assertThat(artifact.kind()).isEqualTo(AttemptArtifactKind.LOCAL_LEMMA);
                assertThat(artifact.status()).isEqualTo(AttemptArtifactStatus.PROMOTED_FACT);
              })
          .anySatisfy(
              artifact -> {
                assertThat(artifact.claimId()).isEqualTo("counterexample-0");
                assertThat(artifact.kind()).isEqualTo(AttemptArtifactKind.COUNTEREXAMPLE);
                assertThat(artifact.status())
                    .isEqualTo(AttemptArtifactStatus.APPLIED_COUNTEREXAMPLE);
              })
          .noneSatisfy(
              artifact -> assertThat(artifact.kind()).isEqualTo(AttemptArtifactKind.ROUTE_THEOREM));
      assertThat(harness.claimLifecycle().get("correct-local-0").state())
          .isEqualTo(ClaimLifecycleController.State.EXTERNALLY_ADMITTED_FACT);
      assertThat(harness.claimLifecycle().get("counterexample-0").state())
          .isEqualTo(ClaimLifecycleController.State.EXTERNALLY_ADMITTED_FACT);
      assertThat(harness.proofGraph().claimNodes())
          .anySatisfy(node -> assertThat(node.statement()).contains("CORRECT_LOCAL_R0"))
          .anySatisfy(
              node -> {
                assertThat(node.statement()).contains("EXACT_COUNTEREXAMPLE_R0");
                assertThat(node.messageType()).isEqualTo(MessageType.COUNTEREXAMPLE);
                assertThat(node.evidenceType()).isEqualTo(EvidenceType.COUNTEREXAMPLE);
                assertThat(node.memoryTier()).isEqualTo(MemoryTier.FACT);
              })
          .noneSatisfy(node -> assertThat(node.statement()).contains("FALSE_ROUTE_THEOREM_R0"));
    }
  }
}
