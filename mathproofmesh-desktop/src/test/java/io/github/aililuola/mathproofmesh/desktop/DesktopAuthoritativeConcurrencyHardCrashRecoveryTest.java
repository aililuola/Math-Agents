package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochStatus;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtStageExecutionStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopAuthoritativeConcurrencyHardCrashRecoveryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void durableAllSettledResultsResumeWithoutProviderReplayOrDuplicateAuthority()
      throws Exception {
    Path runDirectory = temporaryDirectory.resolve("hard-crash");
    String runId = "authoritative-concurrency-hard-crash";
    DesktopSolveCheckpoint crashCheckpoint;
    String rootHash;
    String permanentNegativeHash;
    int providerCallsBeforeCrash;

    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      harness.prepareIndependentClaimCourtBatch(2);
      rootHash = harness.rootGoal().sourceStatementHash();
      permanentNegativeHash = harness.permanentNegativeHash();
      harness.setAuthoritativeConcurrencyFailurePoint(
          DesktopSolveCoordinator.AuthoritativeConcurrencyFailurePoint
              .AFTER_RESULTS_DURABLE_BEFORE_COMMIT);

      assertThatThrownBy(harness::integrateInstalledRound)
          .isInstanceOf(
              DesktopSolveCoordinator.SimulatedAuthoritativeConcurrencyProcessTermination.class);

      providerCallsBeforeCrash = harness.providerCallCount();
      crashCheckpoint = harness.readPersistedCheckpoint();
      assertThat(providerCallsBeforeCrash).isPositive();
      assertThat(crashCheckpoint.researchResults().artifacts()).hasSize(2);
      assertThat(crashCheckpoint.researchEpochs().epochs())
          .singleElement()
          .extracting(epoch -> epoch.status())
          .isEqualTo(ResearchEpochStatus.MERGE_PREPARED);
      assertThat(crashCheckpoint.typedMemory().tiers().values())
          .noneMatch(tier -> tier == MemoryTier.FACT);
      assertThat(crashCheckpoint.claimLifecycle().entries()).isEmpty();
    }

    try (DesktopClaimSalvageTestHarness restored =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      restored.restore(crashCheckpoint);
      restored.integrateInstalledRound();

      var diagnostics = restored.concurrencyDiagnostics();
      long facts =
          restored.typedMemory().facts().stream()
              .filter(fact -> fact.messageId().startsWith("parallel-claim-"))
              .count();
      long lifecycleClaims =
          restored.lifecycleClaimIds().stream()
              .filter(claim -> claim.startsWith("parallel-claim-"))
              .count();
      long graphClaims =
          restored.graphClaimMessageIds().stream()
              .filter(claim -> claim.startsWith("parallel-claim-"))
              .count();
      long incompleteCourtExecutions =
          restored.claimCourtExecutions().records().stream()
              .filter(record -> record.status() != ClaimCourtStageExecutionStatus.COMPLETED)
              .count();
      DesktopSolveCheckpoint finalCheckpoint = restored.readPersistedCheckpoint();
      long committedEpochs =
          finalCheckpoint.researchEpochs().epochs().stream()
              .filter(epoch -> epoch.status() == ResearchEpochStatus.COMMITTED)
              .count();
      long duplicateFacts = Math.max(0L, facts - 2L);
      long duplicateClaims = Math.max(0L, lifecycleClaims - 2L);
      long duplicateGraphClaims = Math.max(0L, graphClaims - 2L);
      long rootHashChanges =
          rootHash.equals(restored.rootGoal().sourceStatementHash()) ? 0L : 1L;
      long negativeRegistryHashChanges =
          permanentNegativeHash.equals(restored.permanentNegativeHash()) ? 0L : 1L;

      assertThat(restored.providerCallCount()).isZero();
      assertThat(committedEpochs).isEqualTo(1L);
      assertThat(diagnostics.liveMergeReceiptCount()).isEqualTo(1);
      assertThat(facts).isEqualTo(2L);
      assertThat(lifecycleClaims).isEqualTo(2L);
      assertThat(graphClaims).isEqualTo(2L);
      assertThat(duplicateFacts).isZero();
      assertThat(duplicateClaims).isZero();
      assertThat(duplicateGraphClaims).isZero();
      assertThat(incompleteCourtExecutions).isZero();
      assertThat(restored.productionState().pendingTaskCount()).isZero();
      assertThat(rootHashChanges).isZero();
      assertThat(negativeRegistryHashChanges).isZero();

      System.out.println("AUTHORITATIVE CONCURRENCY HARD CRASH DIAGNOSTIC");
      System.out.println("HARD_CRASHES_INJECTED=1");
      System.out.println("DURABLE_RESULT_ARTIFACTS_BEFORE_CRASH=2");
      System.out.println("PROVIDER_CALLS_BEFORE_CRASH=" + providerCallsBeforeCrash);
      System.out.println("DUPLICATE_PROVIDER_CALLS=" + restored.providerCallCount());
      System.out.println("PIVOT_FREE_MERGE_COMMITS=" + committedEpochs);
      System.out.println("DUPLICATE_MERGES=" + Math.max(0L, committedEpochs - 1L));
      System.out.println(
          "DUPLICATE_AUTHORITY_MUTATIONS="
              + (duplicateFacts + duplicateClaims + duplicateGraphClaims));
      System.out.println("PARTIAL_CLAIM_WRITES=" + duplicateClaims);
      System.out.println("PARTIAL_GRAPH_WRITES=" + duplicateGraphClaims);
      System.out.println("TASK_LEASE_LEAKS=" + incompleteCourtExecutions);
      System.out.println(
          "PENDING_TASK_LEAKS=" + restored.productionState().pendingTaskCount());
      System.out.println("ROOT_HASH_CHANGES=" + rootHashChanges);
      System.out.println(
          "NEGATIVE_REGISTRY_HASH_CHANGES=" + negativeRegistryHashChanges);
      System.out.println("RESULT=PASS");
    }
  }
}
