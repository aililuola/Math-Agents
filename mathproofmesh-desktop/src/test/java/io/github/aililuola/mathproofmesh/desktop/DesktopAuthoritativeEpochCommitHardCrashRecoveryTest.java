package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochStatus;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.proofcontrol.ClaimLifecycleController;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopAuthoritativeEpochCommitHardCrashRecoveryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void everyAuthorityCommitCrashWindowRestoresExactlyOnceFromAWholeCheckpoint()
      throws Exception {
    Set<DesktopSolveCoordinator.AuthoritativeConcurrencyFailurePoint> crashPoints =
        EnumSet.of(
            DesktopSolveCoordinator.AuthoritativeConcurrencyFailurePoint
                .AFTER_FIRST_AUTHORITY_RESULT_APPLIED,
            DesktopSolveCoordinator.AuthoritativeConcurrencyFailurePoint
                .AFTER_ALL_AUTHORITY_RESULTS_APPLIED_BEFORE_EPOCH_COMMIT,
            DesktopSolveCoordinator.AuthoritativeConcurrencyFailurePoint
                .AFTER_EPOCH_MARKED_COMMITTED_BEFORE_CHECKPOINT,
            DesktopSolveCoordinator.AuthoritativeConcurrencyFailurePoint
                .AFTER_ATOMIC_CHECKPOINT_MOVE);
    Counters counters = new Counters();
    List<Integer> mutationReceiptCounts = new ArrayList<>();
    List<Integer> mergeReceiptCounts = new ArrayList<>();
    List<Long> committedEpochCounts = new ArrayList<>();
    List<Long> projectedAuthorityMutationCounts = new ArrayList<>();

    for (DesktopSolveCoordinator.AuthoritativeConcurrencyFailurePoint crashPoint : crashPoints) {
      ScenarioResult result = executeCrashScenario(crashPoint);
      counters.add(result);
      mutationReceiptCounts.add(result.authorityMutationReceipts());
      mergeReceiptCounts.add(result.mergeReceipts());
      committedEpochCounts.add(result.committedEpochs());
      projectedAuthorityMutationCounts.add(result.projectedAuthorityMutations());
    }

    assertThat(counters.partialAuthorityCheckpoints).isZero();
    assertThat(counters.mergePreparedWithAdvancedAuthority).isZero();
    assertThat(counters.staleRestoredAuthorityErrors).isZero();
    assertThat(mutationReceiptCounts).containsOnly(1);
    assertThat(mergeReceiptCounts).containsOnly(1);
    assertThat(committedEpochCounts).containsOnly(1L);
    assertThat(projectedAuthorityMutationCounts).containsOnly((long) expectedClaimIds().size());
    assertThat(counters.duplicateProviderCalls).isZero();
    assertThat(counters.duplicateAuthorityMutations).isZero();
    assertThat(counters.duplicateFacts).isZero();
    assertThat(counters.duplicateRefutations).isZero();
    assertThat(counters.duplicateClaimProjections).isZero();
    assertThat(counters.lostFacts).isZero();
    assertThat(counters.lostRefutations).isZero();
    assertThat(counters.lostOpenClaims).isZero();
    assertThat(counters.postSecondRestoreStateChanges).isZero();
    assertThat(counters.postSecondRestoreProviderCalls).isZero();
    assertThat(counters.rootHashChanges).isZero();
    assertThat(counters.negativeRegistryHashChanges).isZero();

    System.out.println("AUTHORITATIVE EPOCH COMMIT CRASH DIAGNOSTIC");
    System.out.println("HARD_CRASH_POINTS=" + crashPoints.size());
    System.out.println("CLAIM_CASES=" + expectedClaimIds().size());
    System.out.println(
        "EXPECTED_AUTHORITY_MUTATIONS=" + projectedAuthorityMutationCounts.getFirst());
    System.out.println(
        "PARTIAL_AUTHORITY_CHECKPOINTS=" + counters.partialAuthorityCheckpoints);
    System.out.println(
        "MERGE_PREPARED_WITH_ADVANCED_AUTHORITY="
            + counters.mergePreparedWithAdvancedAuthority);
    System.out.println(
        "STALE_RESTORED_EPOCH_AUTHORITY_ERRORS="
            + counters.staleRestoredAuthorityErrors);
    System.out.println("AUTHORITY_MUTATION_RECEIPTS=" + mutationReceiptCounts.getFirst());
    System.out.println("MERGE_RECEIPTS=" + mergeReceiptCounts.getFirst());
    System.out.println("COMMITTED_EPOCHS=" + committedEpochCounts.getFirst());
    System.out.println("DUPLICATE_PROVIDER_CALLS=" + counters.duplicateProviderCalls);
    System.out.println(
        "DUPLICATE_AUTHORITY_MUTATIONS=" + counters.duplicateAuthorityMutations);
    System.out.println("DUPLICATE_FACTS=" + counters.duplicateFacts);
    System.out.println("DUPLICATE_REFUTATIONS=" + counters.duplicateRefutations);
    System.out.println(
        "DUPLICATE_CLAIM_PROJECTIONS=" + counters.duplicateClaimProjections);
    System.out.println("LOST_FACTS=" + counters.lostFacts);
    System.out.println("LOST_REFUTATIONS=" + counters.lostRefutations);
    System.out.println("LOST_OPEN_CLAIMS=" + counters.lostOpenClaims);
    System.out.println(
        "POST_SECOND_RESTORE_STATE_CHANGES=" + counters.postSecondRestoreStateChanges);
    System.out.println(
        "POST_SECOND_RESTORE_PROVIDER_CALLS=" + counters.postSecondRestoreProviderCalls);
    System.out.println("ROOT_HASH_CHANGES=" + counters.rootHashChanges);
    System.out.println(
        "NEGATIVE_REGISTRY_HASH_CHANGES=" + counters.negativeRegistryHashChanges);
    System.out.println("RESULT=PASS");
  }

  private ScenarioResult executeCrashScenario(
      DesktopSolveCoordinator.AuthoritativeConcurrencyFailurePoint crashPoint)
      throws Exception {
    String suffix = crashPoint.name().toLowerCase(java.util.Locale.ROOT);
    Path runDirectory = temporaryDirectory.resolve(suffix);
    String runId = "authority-commit-crash-" + suffix;
    DesktopSolveCheckpoint crashCheckpoint;
    String rootHash;
    int providerCallsBeforeCrash;

    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      harness.prepareMixedClaimCourtBatch();
      harness.setClaimCourtDelays(
          java.util.Map.of(
              "crash-claim-0", 0L, "crash-claim-1", 0L, "crash-claim-2", 0L));
      rootHash = harness.rootGoal().sourceStatementHash();
      harness.setAuthoritativeConcurrencyFailurePoint(crashPoint);
      assertThatThrownBy(harness::integrateInstalledRound)
          .isInstanceOf(
              DesktopSolveCoordinator.SimulatedAuthoritativeConcurrencyProcessTermination.class);
      providerCallsBeforeCrash = harness.providerCallCount();
      crashCheckpoint = harness.readPersistedCheckpoint();
    }

    long preparedEpochs =
        crashCheckpoint.researchEpochs().epochs().stream()
            .filter(epoch -> epoch.status() == ResearchEpochStatus.MERGE_PREPARED)
            .count();
    long crashProjectedClaims = crashClaimProjectionCount(crashCheckpoint);
    long crashFacts = crashFactCount(crashCheckpoint);
    long partialCheckpoint =
        preparedEpochs > 0L && (crashProjectedClaims > 0L || crashFacts > 0L) ? 1L : 0L;
    long staleErrors = 0L;
    DesktopSolveCheckpoint committed;
    String committedNegativeHash;
    int duplicateProviderCalls;

    try (DesktopClaimSalvageTestHarness restored =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      restored.restore(crashCheckpoint);
      try {
        restored.integrateInstalledRound();
      } catch (IllegalStateException exception) {
        if (exception.getMessage() != null
            && exception.getMessage().contains("STALE_RESTORED_EPOCH_AUTHORITY")) {
          staleErrors++;
        }
        throw exception;
      }
      duplicateProviderCalls = restored.providerCallCount();
      committed = restored.readPersistedCheckpoint();
      committedNegativeHash = restored.negativeRegistryHash();
      assertThat(restored.rootGoal().sourceStatementHash()).isEqualTo(rootHash);
    }

    long projectedClaims = crashClaimProjectionCount(committed);
    int expectedClaims = expectedClaimIds().size();
    long facts = crashFactCount(committed);
    long refutations =
        committed.claimLifecycle().entries().values().stream()
            .filter(entry -> entry.claimId().startsWith("crash-claim-"))
            .filter(entry -> entry.state() == ClaimLifecycleController.State.REJECTED)
            .count();
    long openClaims =
        committed.claimLifecycle().entries().values().stream()
            .filter(entry -> entry.claimId().equals("crash-claim-2"))
            .filter(entry -> !entry.proofAuditIds().isEmpty())
            .count();
    int mutationReceipts =
        committed.researchAuthorityMutations().authorityMutations().size();
    int mergeReceipts = committed.researchAuthorityMutations().mergeReceipts().size();
    long committedEpochs =
        committed.researchEpochs().epochs().stream()
            .filter(epoch -> epoch.status() == ResearchEpochStatus.COMMITTED)
            .count();
    assertThat(committed.researchAuthorityMutations().authorityMutations())
        .singleElement()
        .satisfies(
            receipt -> {
              assertThat(receipt.projectedClaimIds())
                  .containsExactlyElementsOf(expectedClaimIds().stream().sorted().toList());
              assertThat(receipt.factMessageIds()).hasSize(1);
            });

    long postSecondRestoreChanges;
    int postSecondRestoreProviderCalls;
    long rootHashChanges;
    long negativeHashChanges;
    try (DesktopClaimSalvageTestHarness secondRestore =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      secondRestore.restore(committed);
      DesktopClaimSalvageTestHarness.ConcurrencyAuthorityHashes before =
          secondRestore.concurrencyAuthorityHashes();
      secondRestore.integrateInstalledRound();
      DesktopClaimSalvageTestHarness.ConcurrencyAuthorityHashes after =
          secondRestore.concurrencyAuthorityHashes();
      postSecondRestoreChanges = before.equals(after) ? 0L : 1L;
      postSecondRestoreProviderCalls = secondRestore.providerCallCount();
      rootHashChanges = secondRestore.rootGoal().sourceStatementHash().equals(rootHash) ? 0L : 1L;
      negativeHashChanges =
          secondRestore.negativeRegistryHash().equals(committedNegativeHash) ? 0L : 1L;
    }

    return new ScenarioResult(
        partialCheckpoint,
        partialCheckpoint,
        staleErrors,
        mutationReceipts,
        mergeReceipts,
        committedEpochs,
        projectedClaims,
        duplicateProviderCalls,
        Math.max(0L, projectedClaims - expectedClaims),
        Math.max(0L, facts - 1L),
        Math.max(0L, refutations - 1L),
        Math.max(0L, projectedClaims - expectedClaims),
        Math.max(0L, 1L - facts),
        Math.max(0L, 1L - refutations),
        Math.max(0L, 1L - openClaims),
        postSecondRestoreChanges,
        postSecondRestoreProviderCalls,
        rootHashChanges,
        negativeHashChanges,
        providerCallsBeforeCrash);
  }

  private static long crashClaimProjectionCount(DesktopSolveCheckpoint checkpoint) {
    return checkpoint.claimLifecycle().entries().keySet().stream()
        .filter(id -> id.startsWith("crash-claim-"))
        .count();
  }

  private static Set<String> expectedClaimIds() {
    return Set.of("crash-claim-0", "crash-claim-1", "crash-claim-2");
  }

  private static long crashFactCount(DesktopSolveCheckpoint checkpoint) {
    return checkpoint.typedMemory().tiers().entrySet().stream()
        .filter(entry -> entry.getKey().startsWith("crash-claim-"))
        .filter(entry -> entry.getValue() == MemoryTier.FACT)
        .count();
  }

  private record ScenarioResult(
      long partialAuthorityCheckpoints,
      long mergePreparedWithAdvancedAuthority,
      long staleRestoredAuthorityErrors,
      int authorityMutationReceipts,
      int mergeReceipts,
      long committedEpochs,
      long projectedAuthorityMutations,
      long duplicateProviderCalls,
      long duplicateAuthorityMutations,
      long duplicateFacts,
      long duplicateRefutations,
      long duplicateClaimProjections,
      long lostFacts,
      long lostRefutations,
      long lostOpenClaims,
      long postSecondRestoreStateChanges,
      long postSecondRestoreProviderCalls,
      long rootHashChanges,
      long negativeRegistryHashChanges,
      int providerCallsBeforeCrash) {}

  private static final class Counters {
    private long partialAuthorityCheckpoints;
    private long mergePreparedWithAdvancedAuthority;
    private long staleRestoredAuthorityErrors;
    private long duplicateProviderCalls;
    private long duplicateAuthorityMutations;
    private long duplicateFacts;
    private long duplicateRefutations;
    private long duplicateClaimProjections;
    private long lostFacts;
    private long lostRefutations;
    private long lostOpenClaims;
    private long postSecondRestoreStateChanges;
    private long postSecondRestoreProviderCalls;
    private long rootHashChanges;
    private long negativeRegistryHashChanges;

    private void add(ScenarioResult result) {
      assertThat(result.providerCallsBeforeCrash()).isPositive();
      partialAuthorityCheckpoints += result.partialAuthorityCheckpoints();
      mergePreparedWithAdvancedAuthority += result.mergePreparedWithAdvancedAuthority();
      staleRestoredAuthorityErrors += result.staleRestoredAuthorityErrors();
      duplicateProviderCalls += result.duplicateProviderCalls();
      duplicateAuthorityMutations += result.duplicateAuthorityMutations();
      duplicateFacts += result.duplicateFacts();
      duplicateRefutations += result.duplicateRefutations();
      duplicateClaimProjections += result.duplicateClaimProjections();
      lostFacts += result.lostFacts();
      lostRefutations += result.lostRefutations();
      lostOpenClaims += result.lostOpenClaims();
      postSecondRestoreStateChanges += result.postSecondRestoreStateChanges();
      postSecondRestoreProviderCalls += result.postSecondRestoreProviderCalls();
      rootHashChanges += result.rootHashChanges();
      negativeRegistryHashChanges += result.negativeRegistryHashChanges();
    }
  }
}
