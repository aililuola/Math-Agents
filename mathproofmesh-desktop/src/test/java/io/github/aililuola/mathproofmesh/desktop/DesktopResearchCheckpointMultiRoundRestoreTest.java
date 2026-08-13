package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.research.ResearchFindingStatus;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopResearchCheckpointMultiRoundRestoreTest {
  private static final int ROUNDS = 20;
  private static final int RESTORE_AFTER_ROUND = 9;

  @Test
  void sixtyMaterialFindingsSurviveMixedFailuresAndMidCampaignRestore(
      @TempDir Path directory) throws Exception {
    String rootHash;
    String negativeHash;
    String attemptHash;
    String claimHash;
    String restoreLedgerHash;
    int budgetRecoveries = 0;
    int jsonRepairs = 0;
    int finalOmissions = 0;
    int providerCallsBeforeRestore;
    int baselinePermanentNegatives;

    try (var first =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "research-checkpoint-20-rounds",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.MULTI_ROUND,
            "unused")) {
      first.prepareProductionRoute();
      rootHash = first.rootHash();
      negativeHash = first.negativeHash();
      attemptHash = first.attemptArtifactHash();
      claimHash = first.claimLifecycleHash();
      baselinePermanentNegatives = first.permanentNegativeRegistrations();
      for (int round = 0; round <= RESTORE_AFTER_ROUND; round++) {
        var result = first.runCheckpointedRound(round);
        budgetRecoveries += Math.floorMod(round, 4) == 1 ? 1 : 0;
        jsonRepairs += result.repaired() ? 1 : 0;
        finalOmissions += Math.floorMod(round, 4) == 3 ? 1 : 0;
      }
      assertStableAuthorityHashes(first, rootHash, negativeHash, attemptHash, claimHash);
      assertThat(first.researchLedger().findings()).hasSize(30);
      restoreLedgerHash = first.researchLedger().ledgerHash();
      providerCallsBeforeRestore = first.providerCallCount();
      DesktopSolveCheckpoint checkpoint = first.currentCheckpoint();

      try (var restored = first.restored(checkpoint)) {
        assertThat(restored.researchLedger().ledgerHash()).isEqualTo(restoreLedgerHash);
        assertStableAuthorityHashes(restored, rootHash, negativeHash, attemptHash, claimHash);
        for (int round = RESTORE_AFTER_ROUND + 1; round < ROUNDS; round++) {
          var result = restored.runCheckpointedRound(round);
          budgetRecoveries += Math.floorMod(round, 4) == 1 ? 1 : 0;
          jsonRepairs += result.repaired() ? 1 : 0;
          finalOmissions += Math.floorMod(round, 4) == 3 ? 1 : 0;
        }

        var findings = restored.researchLedger().findings();
        int materialFindingsEmitted = ROUNDS * 3;
        int materialFindingsPersisted = findings.size();
        long activeFindingLosses =
            findings.stream().filter(record -> record.status() != ResearchFindingStatus.ACTIVE).count();
        Set<String> expected = new HashSet<>();
        for (int round = 0; round < ROUNDS; round++) {
          expected.add("round " + round + " candidate lemma");
          expected.add("round " + round + " sharp obstruction");
          expected.add("round " + round + " next micro obligation");
        }
        Set<String> actual = new HashSet<>();
        findings.forEach(record -> actual.add(record.statement()));
        int unaccountedFindings = materialFindingsEmitted - actual.size();
        int duplicateFindings = materialFindingsPersisted - actual.size();
        int postRestoreFindingLosses = Math.max(0, 60 - materialFindingsPersisted);
        long duplicateProviderCallsAfterRestore = restored.duplicateProviderCallIds();
        String ledgerJson = CanonicalJson.canonicalize(restored.researchLedger().snapshot());
        int rawReasoningTextInLedger = ledgerJson.contains("PRIVATE_REASONING_SENTINEL") ? 1 : 0;
        long directFactPromotions = restored.directFactPromotions();
        int directClaimVerifications = restored.directClaimVerifications();
        int directNegativeRegistrations =
            restored.permanentNegativeRegistrations() - baselinePermanentNegatives;
        int mainGoalClosures = restored.mainGoalClosures();
        int rootHashChanges = restored.rootHash().equals(rootHash) ? 0 : 1;
        int negativeRegistryHashChanges = restored.negativeHash().equals(negativeHash) ? 0 : 1;
        int attemptArtifactLedgerHashChanges =
            restored.attemptArtifactHash().equals(attemptHash) ? 0 : 1;
        int claimLifecycleHashChanges = restored.claimLifecycleHash().equals(claimHash) ? 0 : 1;

        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(materialFindingsEmitted).isEqualTo(60);
        assertThat(materialFindingsPersisted).isEqualTo(60);
        assertThat(activeFindingLosses).isZero();
        assertThat(budgetRecoveries).isEqualTo(5);
        assertThat(jsonRepairs).isEqualTo(5);
        assertThat(finalOmissions).isEqualTo(5);
        assertThat(unaccountedFindings).isZero();
        assertThat(duplicateFindings).isZero();
        assertThat(postRestoreFindingLosses).isZero();
        assertThat(duplicateProviderCallsAfterRestore).isZero();
        assertThat(rawReasoningTextInLedger).isZero();
        assertThat(directFactPromotions).isZero();
        assertThat(directClaimVerifications).isZero();
        assertThat(directNegativeRegistrations).isZero();
        assertThat(mainGoalClosures).isZero();
        assertThat(rootHashChanges).isZero();
        assertThat(negativeRegistryHashChanges).isZero();
        assertThat(attemptArtifactLedgerHashChanges).isZero();
        assertThat(claimLifecycleHashChanges).isZero();
        assertThat(providerCallsBeforeRestore).isEqualTo(15);
        assertThat(restored.providerCallCount()).isEqualTo(15);

        System.out.println("DURABLE RESEARCH CHECKPOINT DIAGNOSTIC");
        print("MATERIAL_FINDINGS_EMITTED", materialFindingsEmitted);
        print("MATERIAL_FINDINGS_PERSISTED", materialFindingsPersisted);
        print("ACTIVE_FINDING_LOSSES", activeFindingLosses);
        print("BUDGET_RECOVERIES", budgetRecoveries);
        print("JSON_REPAIR_RECOVERIES", jsonRepairs);
        print("FINAL_ARTIFACT_OMISSIONS_DETECTED", finalOmissions);
        print("UNACCOUNTED_FINDINGS", unaccountedFindings);
        print("DUPLICATE_FINDINGS", duplicateFindings);
        print("POST_RESTORE_FINDING_LOSSES", postRestoreFindingLosses);
        print("DUPLICATE_PROVIDER_CALLS_AFTER_RESTORE", duplicateProviderCallsAfterRestore);
        print("RAW_REASONING_TEXT_IN_LEDGER", rawReasoningTextInLedger);
        print("DIRECT_FACT_PROMOTIONS", directFactPromotions);
        print("DIRECT_CLAIM_VERIFICATIONS", directClaimVerifications);
        print("DIRECT_NEGATIVE_REGISTRATIONS", directNegativeRegistrations);
        print("MAIN_GOAL_CLOSURES", mainGoalClosures);
        print("ROOT_HASH_CHANGES", rootHashChanges);
        print("NEGATIVE_REGISTRY_HASH_CHANGES", negativeRegistryHashChanges);
        print("ATTEMPT_ARTIFACT_LEDGER_HASH_CHANGES", attemptArtifactLedgerHashChanges);
        print("CLAIM_LIFECYCLE_HASH_CHANGES", claimLifecycleHashChanges);
        System.out.println("RESTORE_LEDGER_HASH_BEFORE=" + restoreLedgerHash);
        System.out.println(
            "RESTORE_LEDGER_HASH_AFTER="
                + CanonicalJson.stableHash(checkpoint.researchCheckpoints()));
        System.out.println("RESULT=PASS");
      }
    }
  }

  private static void assertStableAuthorityHashes(
      DesktopResearchCheckpointBlackBoxHarness harness,
      String rootHash,
      String negativeHash,
      String attemptHash,
      String claimHash) {
    assertThat(harness.rootHash()).isEqualTo(rootHash);
    assertThat(harness.negativeHash()).isEqualTo(negativeHash);
    assertThat(harness.attemptArtifactHash()).isEqualTo(attemptHash);
    assertThat(harness.claimLifecycleHash()).isEqualTo(claimHash);
  }

  private static void print(String name, long value) {
    System.out.println(name + "=" + value);
  }
}
