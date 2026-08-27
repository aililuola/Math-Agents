package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopCorrectedProofRestoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void correctedProofCanOpenAfterRestoreAndSurvivesASecondRestore() throws Exception {
    Path runDirectory = temporaryDirectory.resolve("corrected-proof-restore");
    String runId = "corrected-proof-restore";
    DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId);
    try {
      harness.freezeAndCreateRoute();
      String rootHash = harness.rootGoal().sourceStatementHash();
      harness.installSingleClaimProofRound(
          0,
          DesktopCorrectedProofReopensCourtTest.claimId(),
          DesktopCorrectedProofReopensCourtTest.statement(),
          "UNREPAIRABLE_PROOF: the conclusion is merely asserted.");
      harness.integrateInstalledRound();
      String negativeHash = harness.permanentNegativeHash();

      DesktopSolveCheckpoint afterV1 = harness.checkpointRoundTrip();
      harness.close();
      harness = DesktopClaimSalvageTestHarness.open(runDirectory, runId);
      harness.restore(afterV1);
      harness.installSingleClaimProofRound(
          1,
          DesktopCorrectedProofReopensCourtTest.claimId(),
          DesktopCorrectedProofReopensCourtTest.statement(),
          "CORRECTED_PROOF: injectivity permutes a finite set, hence is surjective.");
      harness.integrateInstalledRound();

      assertThat(DesktopCorrectedProofReopensCourtTest.casesForClaim(harness)).hasSize(2);
      assertThat(DesktopCorrectedProofReopensCourtTest.casesForClaim(harness))
          .extracting(record -> record.outcome())
          .containsExactlyInAnyOrder(
              ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN,
              ClaimCourtOutcome.VERIFIED);
      assertThat(harness.rootGoal().sourceStatementHash()).isEqualTo(rootHash);
      assertThat(harness.permanentNegativeHash()).isEqualTo(negativeHash);

      DesktopSolveCheckpoint afterV2 = harness.checkpointRoundTrip();
      harness.close();
      harness = DesktopClaimSalvageTestHarness.open(runDirectory, runId);
      harness.restore(afterV2);

      long postRestoreCaseLosses =
          2L - DesktopCorrectedProofReopensCourtTest.casesForClaim(harness).size();
      long postRestoreRevisionLosses =
          2L
              - harness
                  .claimProofRevisions()
                  .recordsForClaim(DesktopCorrectedProofReopensCourtTest.claimId())
                  .size();
      long postRestoreFactLosses =
          1L - DesktopCorrectedProofReopensCourtTest.factsForClaim(harness);

      assertThat(postRestoreCaseLosses).isZero();
      assertThat(postRestoreRevisionLosses).isZero();
      assertThat(postRestoreFactLosses).isZero();
      assertThat(harness.rootGoal().sourceStatementHash()).isEqualTo(rootHash);
      assertThat(harness.permanentNegativeHash()).isEqualTo(negativeHash);

      System.out.println("CORRECTED_PROOF_POST_RESTORE_CASE_LOSSES=" + postRestoreCaseLosses);
      System.out.println(
          "CORRECTED_PROOF_POST_RESTORE_REVISION_LOSSES=" + postRestoreRevisionLosses);
      System.out.println("CORRECTED_PROOF_POST_RESTORE_FACT_LOSSES=" + postRestoreFactLosses);
      System.out.println("ROOT_HASH_CHANGES=0");
      System.out.println("NEGATIVE_REGISTRY_HASH_CHANGES=0");
      System.out.println("RESULT=PASS");
    } finally {
      harness.close();
    }
  }
}
