package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtStageExecutionStatus;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimCourtAtomicityTest {
  private static final EnumSet<ClaimCourtFailurePoint> FAILURE_POINTS =
      EnumSet.of(
          ClaimCourtFailurePoint.AFTER_CLAIM_FREEZE,
          ClaimCourtFailurePoint.AFTER_STATEMENT_RESULT_DURABLE,
          ClaimCourtFailurePoint.AFTER_PROOF_AUDIT_RESULT,
          ClaimCourtFailurePoint.AFTER_REPAIR_PATCH_VALIDATION,
          ClaimCourtFailurePoint.AFTER_REPAIRED_REVISION_WRITE,
          ClaimCourtFailurePoint.AFTER_BLIND_RESULT_DURABLE,
          ClaimCourtFailurePoint.AFTER_FINAL_OUTCOME_BEFORE_PROJECTION,
          ClaimCourtFailurePoint.AFTER_LEMMA_MEMORY_PROJECTION,
          ClaimCourtFailurePoint.AFTER_FACT_PROJECTION_BEFORE_PERSIST,
          ClaimCourtFailurePoint.AFTER_FINAL_CHECKPOINT_PERSIST);

  @TempDir Path temporaryDirectory;

  @Test
  void everyInjectedFailureResumesWithoutPartialAuthorityOrDuplicateMutation()
      throws Exception {
    long partialCourtRecords = 0L;
    long partialProofRevisions = 0L;
    long partialClaimStatusWrites = 0L;
    long partialFactWrites = 0L;
    long partialProofGraphWrites = 0L;
    long taskLeaseLeaks = 0L;
    long pendingTaskLeaks = 0L;

    for (ClaimCourtFailurePoint point : FAILURE_POINTS) {
      String suffix = point.name().toLowerCase(java.util.Locale.ROOT);
      try (DesktopClaimSalvageTestHarness harness =
          DesktopClaimSalvageTestHarness.open(
              temporaryDirectory.resolve(suffix), "claim-court-atomic-" + suffix)) {
        harness.freezeAndCreateRoute();
        String claimId = "atomic-repairable-" + suffix;
        harness.installSingleClaimRound(
            0,
            claimId,
            "REPAIRABLE_ATOMIC: a finite surjection between equal-size sets is bijective.");
        harness.setFailurePoint(point);

        assertThatThrownBy(harness::integrateInstalledRound)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("simulated Claim Court failure");

        partialClaimStatusWrites +=
            harness.lemmaMemory().claims().stream()
                .filter(claim -> claim.claimId().equals(claimId))
                .filter(claim -> claim.status() == ClaimStatus.REJECTED)
                .count();
        boolean verifiedCourtProjection =
            harness.claimCourt().records().stream()
                .anyMatch(
                    record ->
                        record.frozenClaim().claimId().equals(claimId)
                            && record.outcome() == ClaimCourtOutcome.VERIFIED);
        if (!verifiedCourtProjection) {
          partialFactWrites +=
              harness.typedMemory().facts().stream()
                  .filter(fact -> fact.messageId().equals(claimId))
                  .count();
          partialProofGraphWrites +=
              harness.proofGraph().claimNodes().stream()
                  .filter(claim -> claim.messageId().equals(claimId))
                  .count();
        }
        pendingTaskLeaks += harness.productionState().pendingTaskCount();

        harness.integrateInstalledRound();

        long courtCases =
            harness.claimCourt().records().stream()
                .filter(record -> record.frozenClaim().claimId().equals(claimId))
                .count();
        long revisions = harness.claimProofRevisions().recordsForClaim(claimId).size();
        long facts =
            harness.typedMemory().facts().stream()
                .filter(fact -> fact.messageId().equals(claimId))
                .count();
        long graphClaims =
            harness.proofGraph().claimNodes().stream()
                .filter(claim -> claim.messageId().equals(claimId))
                .count();
        long incompleteExecutions =
            harness.claimCourtExecutions().records().stream()
                .filter(
                    execution ->
                        execution.status() != ClaimCourtStageExecutionStatus.COMPLETED)
                .count();

        partialCourtRecords += Math.max(0L, courtCases - 1L);
        partialProofRevisions += Math.max(0L, revisions - 2L);
        partialFactWrites += Math.max(0L, facts - 1L);
        partialProofGraphWrites += Math.max(0L, graphClaims - 1L);
        taskLeaseLeaks += incompleteExecutions;
        pendingTaskLeaks += harness.productionState().pendingTaskCount();

        assertThat(harness.claimCourt().records())
            .filteredOn(record -> record.frozenClaim().claimId().equals(claimId))
            .singleElement()
            .extracting(record -> record.outcome())
            .isEqualTo(ClaimCourtOutcome.VERIFIED);
        assertThat(harness.lemmaMemory().claims())
            .filteredOn(claim -> claim.claimId().equals(claimId))
            .singleElement()
            .extracting(claim -> claim.status())
            .isEqualTo(ClaimStatus.VERIFIED);
        assertThat(harness.attemptArtifacts().records())
            .filteredOn(artifact -> artifact.claimId().equals(claimId))
            .singleElement()
            .extracting(artifact -> artifact.status())
            .isEqualTo(AttemptArtifactStatus.PROMOTED_FACT);
        assertThat(
                harness.typedMemory().facts().stream()
                    .collect(
                        Collectors.groupingBy(
                            fact -> fact.contentHash(), Collectors.counting())))
            .allSatisfy((ignored, count) -> assertThat(count).isEqualTo(1L));
      }
    }

    assertThat(partialCourtRecords).isZero();
    assertThat(partialProofRevisions).isZero();
    assertThat(partialClaimStatusWrites).isZero();
    assertThat(partialFactWrites).isZero();
    assertThat(partialProofGraphWrites).isZero();
    assertThat(taskLeaseLeaks).isZero();
    assertThat(pendingTaskLeaks).isZero();

    System.out.println("CLAIM COURT ATOMICITY DIAGNOSTIC");
    System.out.println("FAILURE_POINTS=" + FAILURE_POINTS.size());
    System.out.println("PARTIAL_COURT_RECORDS=" + partialCourtRecords);
    System.out.println("PARTIAL_PROOF_REVISIONS=" + partialProofRevisions);
    System.out.println("PARTIAL_CLAIM_STATUS_WRITES=" + partialClaimStatusWrites);
    System.out.println("PARTIAL_FACT_WRITES=" + partialFactWrites);
    System.out.println("PARTIAL_PROOFGRAPH_WRITES=" + partialProofGraphWrites);
    System.out.println("TASK_LEASE_LEAKS=" + taskLeaseLeaks);
    System.out.println("PENDING_TASK_LEAKS=" + pendingTaskLeaks);
    System.out.println("RESULT=PASS");
  }
}
