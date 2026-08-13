package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClaimSalvageProjectionAtomicityTest {
  @TempDir Path temporaryDirectory;

  @Test
  void negativeKnowledgeBlockOccursBeforeEveryPositiveProjection() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("atomic-projection"), "claim-salvage-atomicity")) {
      harness.freezeAndCreateRoute();
      String rootHash = harness.rootGoal().sourceStatementHash();
      String permanentNegativeHash = harness.permanentNegativeHash();
      DesktopClaimSalvageTestHarness.ProductionState before = harness.productionState();
      int graphClaimsBefore = harness.proofGraph().claimNodes().size();

      String blockedAlias = DesktopNegativeKnowledgeTestHarness.alias(0);
      harness.runForcedPassRound(0, blockedAlias);

      DesktopClaimSalvageTestHarness.ProductionState after = harness.productionState();
      assertThat(after).isEqualTo(before);
      assertThat(harness.proofGraph().claimNodes()).hasSize(graphClaimsBefore);
      assertThat(harness.typedMemory().facts())
          .noneSatisfy(fact -> assertThat(fact.statement()).isEqualTo(blockedAlias));
      assertThat(harness.attemptArtifacts().recordsForAttempt("failed-attempt-0"))
          .singleElement()
          .satisfies(
              artifact -> assertThat(artifact.status()).isEqualTo(AttemptArtifactStatus.REJECTED));
      assertThat(harness.proofGraph().getObligation("main-goal").status()).isEqualTo("open");
      assertThat(harness.permanentNegativeHash()).isEqualTo(permanentNegativeHash);
      assertThat(harness.rootGoal().sourceStatementHash()).isEqualTo(rootHash);
      assertThat(harness.exactStatement()).isEqualTo(DesktopClaimSalvageTestHarness.SOURCE);
    }
  }
}
