package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofcontrol.PivotClaimUseChange;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotClaimUsageAction;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotProposedClaimDraft;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopPivotProposedClaimRestoreExactlyOnceTest {
  @Test
  void proposedClaimSurvivesTwoCheckpointRestoresWithoutAuthorityOrDuplication(
      @TempDir Path directory) throws Exception {
    String claimId = "pivot-proposed-claim-restore";
    String statement = "A minimal global support has a bounded maximal-prime witness.";
    String statementHash = PivotProposedClaimDraft.statementHash(statement);
    String runId = "pivot-claim-restore";
    String rootHash;
    String negativeHash;
    DesktopSolveCheckpoint firstCheckpoint;
    try (DesktopSemanticPivotTestHarness harness =
        DesktopSemanticPivotTestHarness.open(directory, runId)) {
      var delta =
          harness.validDelta(
              401,
              List.of(
                  new PivotClaimUseChange(
                      claimId,
                      statementHash,
                      PivotClaimUsageAction.ADD_AS_PROPOSED_CLAIM,
                      "Create one reviewable candidate Claim.",
                      new PivotProposedClaimDraft(
                          claimId,
                          statement,
                          statementHash,
                          List.of("the global support is inclusion-minimal"),
                          List.of("artifact://semantic-pivot/restore"),
                          List.of(),
                          List.of("candidate:restore-boundary")))));
      harness.apply(delta);
      assertExactlyOneNonAuthoritativeProjection(harness, claimId);
      rootHash = harness.rootHash();
      negativeHash = harness.state().negativeHash();
      firstCheckpoint = harness.checkpoint();
    }

    DesktopSolveCheckpoint secondCheckpoint;
    try (DesktopSemanticPivotTestHarness restored =
        DesktopSemanticPivotTestHarness.restore(directory, runId, firstCheckpoint)) {
      assertExactlyOneNonAuthoritativeProjection(restored, claimId);
      assertThat(restored.rootHash()).isEqualTo(rootHash);
      assertThat(restored.state().negativeHash()).isEqualTo(negativeHash);
      secondCheckpoint = restored.checkpoint();
    }

    try (DesktopSemanticPivotTestHarness restoredAgain =
        DesktopSemanticPivotTestHarness.restore(directory, runId, secondCheckpoint)) {
      assertExactlyOneNonAuthoritativeProjection(restoredAgain, claimId);
      assertThat(restoredAgain.rootHash()).isEqualTo(rootHash);
      assertThat(restoredAgain.state().negativeHash()).isEqualTo(negativeHash);
    }

    System.out.println("POST_RESTORE_PROPOSED_CLAIM_LOSSES=0");
    System.out.println("POST_RESTORE_DUPLICATE_PROPOSED_CLAIMS=0");
  }

  private static void assertExactlyOneNonAuthoritativeProjection(
      DesktopSemanticPivotTestHarness harness, String claimId) throws Exception {
    assertThat(harness.proposedClaimCount(claimId)).isEqualTo(1L);
    assertThat(harness.proposedClaimLifecycleCount(claimId)).isEqualTo(1L);
    assertThat(harness.pendingPivotProposedClaimCount(claimId)).isEqualTo(1L);
    assertThat(harness.proposedClaimDirectlyVerified(claimId)).isFalse();
    assertThat(harness.proposedClaimDirectlyPromoted(claimId)).isFalse();
  }
}
