package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtRolePolicy;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtStageExecutionLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimFreezeService;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimProofRevisionLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.FrozenClaimSemanticContext;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.FrozenClaimSnapshot;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimCourtWorkerMergeAtomicityTest {
  @TempDir Path temporaryDirectory;

  @Test
  void revisionConflictCannotPartiallyCommitTheCourtFrontier() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("worker-merge-atomicity"),
            "claim-court-worker-merge-atomicity")) {
      harness.freezeAndCreateRoute();
      ClaimFreezeService freezer = new ClaimFreezeService();
      FrozenClaimSnapshot first = freeze(freezer, emptyClaim("first-claim", "First claim."));
      FrozenClaimSnapshot second = freeze(freezer, emptyClaim("second-claim", "Second claim."));
      FrozenClaimSnapshot colliding = withRevisionId(second, first.initialProofRevisionId());
      ClaimCourtRolePolicy.Assignment roles = roles();

      harness.claimCourt().open(first, roles);
      harness.claimProofRevisions().createOriginal(first, List.of(), List.of());
      String courtHashBefore = harness.claimCourt().stableHash();
      String revisionHashBefore = harness.claimProofRevisions().stableHash();
      String executionHashBefore = harness.claimCourtExecutions().stableHash();

      ClaimCourtLedger candidateCourt = new ClaimCourtLedger();
      var candidateRecord = candidateCourt.open(colliding, roles);
      ClaimProofRevisionLedger candidateRevisions = new ClaimProofRevisionLedger();
      var candidateRevision =
          candidateRevisions.createOriginal(colliding, List.of(), List.of());
      ClaimCourtStageExecutionLedger candidateExecutions =
          new ClaimCourtStageExecutionLedger();

      assertThatThrownBy(
              () ->
                  harness.mergeClaimCourtWorkerDraft(
                      candidateRecord,
                      candidateRevision,
                      candidateCourt.snapshot(),
                      candidateRevisions.snapshot(),
                      candidateExecutions.snapshot()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("conflicting revision");

      assertThat(harness.claimCourt().stableHash()).isEqualTo(courtHashBefore);
      assertThat(harness.claimProofRevisions().stableHash()).isEqualTo(revisionHashBefore);
      assertThat(harness.claimCourtExecutions().stableHash()).isEqualTo(executionHashBefore);
    }
  }

  private static FrozenClaimSnapshot freeze(ClaimFreezeService freezer, ClaimCard claim) {
    return freezer.freeze(
        DesktopClaimSalvageTestHarness.PROBLEM_HASH,
        DesktopClaimSalvageTestHarness.PROBLEM_HASH,
        "route-1",
        claim,
        FrozenClaimSemanticContext.root(List.of("global")));
  }

  private static ClaimCard emptyClaim(String claimId, String statement) {
    return new ClaimCard(
        List.of(), claimId, statement, "", "bounded", List.of(), List.of(), List.of(),
        List.of(), List.of("global"), 0.4d, "claim-author", "attempt-1", null, statement,
        ClaimStatus.PROPOSED, List.of("local_lemma"), null);
  }

  private static FrozenClaimSnapshot withRevisionId(
      FrozenClaimSnapshot source, String revisionId) {
    return new FrozenClaimSnapshot(
        source.courtCaseId(),
        source.problemHash(),
        source.rootGoalHash(),
        source.claimId(),
        source.claimStatementHash(),
        source.claimSemanticHash(),
        source.statement(),
        source.conclusion(),
        source.assumptions(),
        source.quantifiers(),
        source.variableBindings(),
        source.scopeLimitations(),
        source.polarity(),
        source.dependencyClaimIds(),
        source.dependencySnapshotHash(),
        revisionId,
        source.sourceAttemptId(),
        source.sourceRouteId(),
        source.authorAgentId());
  }

  private static ClaimCourtRolePolicy.Assignment roles() {
    return new ClaimCourtRolePolicy.Assignment(
        "claim-author", "falsifier", "auditor", "repairer", "blind-adjudicator");
  }
}
