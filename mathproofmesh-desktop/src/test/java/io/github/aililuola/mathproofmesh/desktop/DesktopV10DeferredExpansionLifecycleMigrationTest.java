package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.proofgraph.DeferredExpansionStatus;
import io.github.aililuola.mathproofmesh.proofgraph.FocusedRecoveryActionType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopV10DeferredExpansionLifecycleMigrationTest {
  @Test
  void legacyDeferredRecordsGainV11LifecycleWithoutAuthorityDriftOrProviderCalls(
      @TempDir Path directory) throws Exception {
    DesktopResearchCheckpointBlackBoxHarness harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-v10-deferred-migration",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused");
    try {
      harness.prepareProductionRoute();
      DesktopDeferredReactivationTestSupport.fillRouteCapacity(harness, "v10-migration");
      DesktopDeferredReactivationTestSupport.addControlledTarget(
          harness,
          "v10-deferred-target",
          "v10-deferred-family",
          FocusedRecoveryActionType.NEW_STRATEGY,
          0);
      String rootHash = harness.rootHash();
      String negativeHash = harness.negativeHash();
      String attemptHash = harness.attemptArtifactHash();
      String claimHash = harness.claimLifecycleHash();
      String researchHash = harness.researchLedger().ledgerHash();
      String canonicalHash = DesktopProofGraphIssue005BlackBoxSupport.canonicalizationHash(harness);
      String convergenceHash = DesktopProofGraphIssue005BlackBoxSupport.convergenceHash(harness);

      DesktopSolveCheckpoint current = harness.checkpointRoundTrip();
      ObjectNode legacyTree = (ObjectNode) ContractObjectMapper.toTree(current);
      legacyTree.put("schemaVersion", 10);
      ObjectNode records =
          (ObjectNode) legacyTree.path("deferredExpansions").path("records");
      records
          .properties()
          .forEach(
              entry -> {
                ObjectNode record = (ObjectNode) entry.getValue();
                record.remove("status");
                record.remove("lastEvaluatedRound");
                record.remove("reactivatedRound");
                record.remove("reactivationReason");
                record.remove("reactivatedTaskId");
                record.remove("retiredRound");
                record.remove("retirementReason");
              });
      DesktopSolveCheckpoint legacy =
          ContractObjectMapper.read(legacyTree, DesktopSolveCheckpoint.class);
      long legacyRecordsDefaultedToDeferred =
          legacy.deferredExpansions().records().values().stream()
              .filter(record -> record.status() == DeferredExpansionStatus.DEFERRED)
              .count();
      assertThat(legacy.schemaVersion()).isEqualTo(10);
      assertThat(legacy.deferredExpansions().records().values())
          .allMatch(record -> record.status() == DeferredExpansionStatus.DEFERRED)
          .allMatch(record -> record.lastEvaluatedRound() == record.round())
          .allMatch(record -> record.reactivatedRound() == -1)
          .allMatch(record -> record.retiredRound() == -1);

      DesktopResearchCheckpointBlackBoxHarness firstRestore = harness.restored(legacy);
      harness.close();
      harness = firstRestore;
      int providerCallsBefore = harness.providerCallCount();
      String firstMigratedDeferredHash =
          DesktopProofGraphIssue005BlackBoxSupport.deferredHash(harness);
      long firstAutomaticReactivationGuesses =
          DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().stream()
              .filter(record -> record.status() != DeferredExpansionStatus.DEFERRED)
              .count();
      int rootHashChanges = changed(harness.rootHash(), rootHash);
      int negativeHashChanges = changed(harness.negativeHash(), negativeHash);
      int attemptHashChanges = changed(harness.attemptArtifactHash(), attemptHash);
      int claimHashChanges = changed(harness.claimLifecycleHash(), claimHash);
      int researchHashChanges = changed(harness.researchLedger().ledgerHash(), researchHash);
      int canonicalHashChanges =
          changed(
              DesktopProofGraphIssue005BlackBoxSupport.canonicalizationHash(harness),
              canonicalHash);
      int convergenceHashChanges =
          changed(
              DesktopProofGraphIssue005BlackBoxSupport.convergenceHash(harness),
              convergenceHash);
      assertAuthorityHashes(
          harness,
          rootHash,
          negativeHash,
          attemptHash,
          claimHash,
          researchHash,
          canonicalHash,
          convergenceHash);

      DesktopSolveCheckpoint upgraded = harness.checkpointRoundTrip();
      assertThat(upgraded.schemaVersion()).isEqualTo(DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION);
      DesktopResearchCheckpointBlackBoxHarness secondRestore = harness.restored(upgraded);
      harness.close();
      harness = secondRestore;
      String secondMigratedDeferredHash =
          DesktopProofGraphIssue005BlackBoxSupport.deferredHash(harness);
      long secondAutomaticReactivationGuesses =
          DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().stream()
              .filter(record -> record.status() != DeferredExpansionStatus.DEFERRED)
              .count();
      rootHashChanges += changed(harness.rootHash(), rootHash);
      negativeHashChanges += changed(harness.negativeHash(), negativeHash);
      attemptHashChanges += changed(harness.attemptArtifactHash(), attemptHash);
      claimHashChanges += changed(harness.claimLifecycleHash(), claimHash);
      researchHashChanges += changed(harness.researchLedger().ledgerHash(), researchHash);
      canonicalHashChanges +=
          changed(
              DesktopProofGraphIssue005BlackBoxSupport.canonicalizationHash(harness),
              canonicalHash);
      convergenceHashChanges +=
          changed(
              DesktopProofGraphIssue005BlackBoxSupport.convergenceHash(harness),
              convergenceHash);
      assertAuthorityHashes(
          harness,
          rootHash,
          negativeHash,
          attemptHash,
          claimHash,
          researchHash,
          canonicalHash,
          convergenceHash);
      int providerCallsAfter = harness.providerCallCount();
      long automaticReactivationGuesses =
          firstAutomaticReactivationGuesses + secondAutomaticReactivationGuesses;

      assertThat(secondMigratedDeferredHash).isEqualTo(firstMigratedDeferredHash);
      assertThat(providerCallsAfter).isEqualTo(providerCallsBefore);
      assertThat(legacyRecordsDefaultedToDeferred)
          .isEqualTo(legacy.deferredExpansions().records().size());
      assertThat(automaticReactivationGuesses).isZero();
      assertThat(rootHashChanges).isZero();
      assertThat(negativeHashChanges).isZero();
      assertThat(attemptHashChanges).isZero();
      assertThat(claimHashChanges).isZero();
      assertThat(researchHashChanges).isZero();
      assertThat(canonicalHashChanges).isZero();
      assertThat(convergenceHashChanges).isZero();
      assertThat(
              DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().stream()
                  .map(record -> record.status())
                  .distinct())
          .containsExactly(DeferredExpansionStatus.DEFERRED);

      System.out.println("V10 TO V11 DEFERRED LIFECYCLE MIGRATION DIAGNOSTIC");
      print("LEGACY_RECORDS_MIGRATED", legacy.deferredExpansions().records().size());
      print("LEGACY_RECORDS_DEFAULTED_TO_DEFERRED", legacyRecordsDefaultedToDeferred);
      print("AUTOMATIC_REACTIVATION_GUESSES", automaticReactivationGuesses);
      print("ROOT_HASH_CHANGES", rootHashChanges);
      print("NEGATIVE_REGISTRY_HASH_CHANGES", negativeHashChanges);
      print("ATTEMPT_ARTIFACT_LEDGER_HASH_CHANGES", attemptHashChanges);
      print("CLAIM_LIFECYCLE_HASH_CHANGES", claimHashChanges);
      print("RESEARCH_CHECKPOINT_LEDGER_HASH_CHANGES", researchHashChanges);
      print("CANONICALIZATION_REGISTRY_HASH_CHANGES", canonicalHashChanges);
      print("CONVERGENCE_SNAPSHOT_HASH_CHANGES", convergenceHashChanges);
      print("PROVIDER_CALLS_DURING_MIGRATION", providerCallsAfter - providerCallsBefore);
      System.out.println("FIRST_MIGRATED_DEFERRED_HASH=" + firstMigratedDeferredHash);
      System.out.println("SECOND_MIGRATED_DEFERRED_HASH=" + secondMigratedDeferredHash);
      System.out.println("RESULT=PASS");
    } finally {
      harness.close();
    }
  }

  private static void assertAuthorityHashes(
      DesktopResearchCheckpointBlackBoxHarness harness,
      String root,
      String negative,
      String attempt,
      String claim,
      String research,
      String canonical,
      String convergence)
      throws Exception {
    assertThat(harness.rootHash()).isEqualTo(root);
    assertThat(harness.negativeHash()).isEqualTo(negative);
    assertThat(harness.attemptArtifactHash()).isEqualTo(attempt);
    assertThat(harness.claimLifecycleHash()).isEqualTo(claim);
    assertThat(harness.researchLedger().ledgerHash()).isEqualTo(research);
    assertThat(DesktopProofGraphIssue005BlackBoxSupport.canonicalizationHash(harness))
        .isEqualTo(canonical);
    assertThat(DesktopProofGraphIssue005BlackBoxSupport.convergenceHash(harness))
        .isEqualTo(convergence);
  }

  private static void print(String name, long value) {
    System.out.println(name + "=" + value);
  }

  private static int changed(String actual, String expected) {
    return actual.equals(expected) ? 0 : 1;
  }
}
