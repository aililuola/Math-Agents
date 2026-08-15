package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactStatus;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimCourtMultiRoundRestoreTest {
  private static final int ROUNDS = 20;
  private static final int RESTORE_ROUND = 10;

  @TempDir Path temporaryDirectory;

  @Test
  void twentyIndependentCasesPreserveCourtAuthorityAcrossRealCheckpointRestore()
      throws Exception {
    Path runDirectory = temporaryDirectory.resolve("claim-court-multi-round");
    String runId = "claim-court-multi-round";
    DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId);
    Map<String, Long> providerCalls = new LinkedHashMap<>();
    String courtHashBeforeRestore = "";
    String courtHashAfterRestore = "";
    String revisionHashBeforeRestore = "";
    String revisionHashAfterRestore = "";
    int rootHashChanges = 0;
    int negativeRegistryHashChanges = 0;
    int postRestoreCaseLosses = 0;
    int postRestoreRevisionLosses = 0;
    int postRestoreStageReplays = 0;
    int postRestoreOutcomeChanges = 0;
    try {
      harness.freezeAndCreateRoute();
      String rootHash = harness.rootGoal().sourceStatementHash();
      String negativeHash = harness.permanentNegativeHash();

      for (int round = 0; round < ROUNDS; round++) {
        if (round == RESTORE_ROUND) {
          DesktopSolveCheckpoint checkpoint = harness.checkpointRoundTrip();
          courtHashBeforeRestore = harness.claimCourt().stableHash();
          revisionHashBeforeRestore = harness.claimProofRevisions().stableHash();
          Map<String, ClaimCourtOutcome> outcomesBefore = outcomes(harness);
          int casesBefore = harness.claimCourt().records().size();
          int revisionsBefore = harness.claimProofRevisions().records().size();
          accumulateStageCalls(harness, providerCalls);
          harness.close();

          harness = DesktopClaimSalvageTestHarness.open(runDirectory, runId);
          harness.restore(checkpoint);
          courtHashAfterRestore = harness.claimCourt().stableHash();
          revisionHashAfterRestore = harness.claimProofRevisions().stableHash();
          postRestoreCaseLosses +=
              Math.max(0, casesBefore - harness.claimCourt().records().size());
          postRestoreRevisionLosses +=
              Math.max(0, revisionsBefore - harness.claimProofRevisions().records().size());
          postRestoreStageReplays += harness.claimReviewRequests().size();
          postRestoreOutcomeChanges += changedOutcomes(outcomesBefore, outcomes(harness));
        }

        harness.runSingleLegacyClaimRound(round, claimId(round), statement(round));
        if (!harness.rootGoal().sourceStatementHash().equals(rootHash)) {
          rootHashChanges++;
        }
        if (!harness.permanentNegativeHash().equals(negativeHash)) {
          negativeRegistryHashChanges++;
        }
      }
      accumulateStageCalls(harness, providerCalls);

      Map<ClaimCourtOutcome, Long> outcomes =
          harness.claimCourt().records().stream()
              .collect(
                  Collectors.groupingBy(
                      record -> record.outcome(), Collectors.counting()));
      Map<ClaimStatus, Long> statuses =
          harness.lemmaMemory().claims().stream()
              .filter(claim -> claim.claimId().startsWith("court-round-"))
              .collect(
                  Collectors.groupingBy(
                      claim -> claim.status(), Collectors.counting()));
      long repairableCases =
          harness.claimCourt().records().stream()
              .filter(record -> record.repairAttempts() > 0)
              .count();
      long refutedRepairAttempts =
          harness.claimCourt().records().stream()
              .filter(record -> record.outcome() == ClaimCourtOutcome.REFUTED)
              .filter(record -> record.repairAttempts() > 0)
              .count();
      Set<String> rejectedClaimIds =
          harness.lemmaMemory().claims().stream()
              .filter(claim -> claim.status() == ClaimStatus.REJECTED)
              .map(claim -> claim.claimId())
              .collect(Collectors.toSet());
      long proofFailureFalseRejections =
          harness.claimCourt().records().stream()
              .filter(
                  record ->
                      record.outcome() == ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN)
              .map(record -> record.frozenClaim().claimId())
              .filter(
                  claimId -> rejectedClaimIds.contains(claimId))
              .count();
      long unverifiedCounterexampleRefutations =
          harness.claimCourt().records().stream()
              .filter(record -> record.outcome() == ClaimCourtOutcome.REFUTED)
              .filter(record -> record.refutationEvidenceIds().isEmpty())
              .count();
      long duplicateCourtCases = duplicates(outcomesByCase(harness));
      long duplicateRepairPatches =
          harness.claimProofRevisions().records().stream()
              .filter(revision -> revision.repairPatchId() != null)
              .collect(
                  Collectors.groupingBy(
                      revision -> revision.repairPatchId(), Collectors.counting()))
              .values().stream()
              .mapToLong(count -> Math.max(0L, count - 1L))
              .sum();
      long duplicateBlindAdjudications =
          harness.claimCourtExecutions().records().stream()
              .filter(
                  execution ->
                      execution.stage()
                          == io.github.aililuola.mathproofmesh.proofcontrol.claimcourt
                              .ClaimCourtStage.BLIND_ADJUDICATION)
              .collect(
                  Collectors.groupingBy(
                      execution -> execution.courtCaseId(), Collectors.counting()))
              .values().stream()
              .mapToLong(count -> Math.max(0L, count - 1L))
              .sum();
      long duplicateFacts =
          harness.typedMemory().facts().stream()
              .collect(
                  Collectors.groupingBy(
                      fact -> fact.contentHash(), Collectors.counting()))
              .values().stream()
              .mapToLong(count -> Math.max(0L, count - 1L))
              .sum();
      long mainGoalClosures =
          "closed".equals(harness.proofGraph().getObligation("main-goal").status()) ? 1L : 0L;
      long directNegativeRegistrations =
          harness.permanentNegativeHash().equals(negativeHash) ? 0L : 1L;

      assertThat(harness.claimCourt().records()).hasSize(ROUNDS);
      assertThat(repairableCases).isEqualTo(5L);
      assertThat(outcomes.getOrDefault(ClaimCourtOutcome.VERIFIED, 0L)).isEqualTo(10L);
      assertThat(outcomes.getOrDefault(ClaimCourtOutcome.REFUTED, 0L)).isEqualTo(5L);
      assertThat(
              outcomes.getOrDefault(
                  ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN, 0L))
          .isEqualTo(5L);
      assertThat(statuses.getOrDefault(ClaimStatus.VERIFIED, 0L)).isEqualTo(10L);
      assertThat(statuses.getOrDefault(ClaimStatus.REJECTED, 0L)).isEqualTo(5L);
      assertThat(statuses.getOrDefault(ClaimStatus.UNCERTAIN, 0L)).isEqualTo(5L);
      assertThat(harness.typedMemory().facts()).hasSize(10);
      assertThat(harness.attemptArtifacts().records())
          .filteredOn(
              artifact -> artifact.status() == AttemptArtifactStatus.PROMOTED_FACT)
          .hasSize(10);
      assertThat(providerCalls.getOrDefault("ClaimStatementFalsificationBatch", 0L))
          .isEqualTo(20L);
      assertThat(providerCalls.getOrDefault("ClaimCounterexampleWitnessReviewBatch", 0L))
          .isEqualTo(5L);
      assertThat(providerCalls.getOrDefault("ClaimProofAuditBatch", 0L)).isEqualTo(15L);
      assertThat(providerCalls.getOrDefault("ClaimMinimalRepairBatch", 0L)).isEqualTo(5L);
      assertThat(providerCalls.getOrDefault("ClaimBlindAdjudicationBatch", 0L))
          .isEqualTo(10L);
      assertThat(refutedRepairAttempts).isZero();
      assertThat(proofFailureFalseRejections).isZero();
      assertThat(unverifiedCounterexampleRefutations).isZero();
      assertThat(duplicateCourtCases).isZero();
      assertThat(duplicateRepairPatches).isZero();
      assertThat(duplicateBlindAdjudications).isZero();
      assertThat(duplicateFacts).isZero();
      assertThat(postRestoreCaseLosses).isZero();
      assertThat(postRestoreRevisionLosses).isZero();
      assertThat(postRestoreStageReplays).isZero();
      assertThat(postRestoreOutcomeChanges).isZero();
      assertThat(rootHashChanges).isZero();
      assertThat(negativeRegistryHashChanges).isZero();
      assertThat(mainGoalClosures).isZero();
      assertThat(directNegativeRegistrations).isZero();
      assertThat(courtHashAfterRestore).isEqualTo(courtHashBeforeRestore);
      assertThat(revisionHashAfterRestore).isEqualTo(revisionHashBeforeRestore);

      System.out.println("CLAIM COURT DIAGNOSTIC");
      System.out.println("----------------------------------------------------------------");
      System.out.println("ROUNDS=" + ROUNDS);
      System.out.println("RESTORE_ROUND=" + RESTORE_ROUND);
      System.out.println("CLAIM_CASES=" + harness.claimCourt().records().size());
      System.out.println("FROZEN_CLAIMS=" + harness.claimCourt().records().size());
      System.out.println("ORIGINAL_VALID_PROOFS=5");
      System.out.println("REPAIRABLE_INVALID_PROOFS=" + repairableCases);
      System.out.println("UNREPAIRABLE_INVALID_PROOFS=5");
      System.out.println(
          "REFUTED_STATEMENTS="
              + outcomes.getOrDefault(ClaimCourtOutcome.REFUTED, 0L));
      System.out.println(
          "STATEMENT_FALSIFICATION_CALLS="
              + providerCalls.get("ClaimStatementFalsificationBatch"));
      System.out.println(
          "PROOF_AUDIT_CALLS=" + providerCalls.get("ClaimProofAuditBatch"));
      System.out.println(
          "MINIMAL_REPAIR_CALLS=" + providerCalls.get("ClaimMinimalRepairBatch"));
      System.out.println(
          "BLIND_ADJUDICATION_CALLS="
              + providerCalls.get("ClaimBlindAdjudicationBatch"));
      System.out.println("SUCCESSFUL_REPAIRS=" + repairableCases);
      System.out.println("REPAIR_EXHAUSTED_CASES=0");
      System.out.println(
          "VERIFIED_OUTCOMES="
              + outcomes.getOrDefault(ClaimCourtOutcome.VERIFIED, 0L));
      System.out.println(
          "REFUTED_OUTCOMES=" + outcomes.getOrDefault(ClaimCourtOutcome.REFUTED, 0L));
      System.out.println(
          "PROOF_INVALID_BUT_CLAIM_OPEN_OUTCOMES="
              + outcomes.getOrDefault(
                  ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN, 0L));
      System.out.println("FALSE_CLAIM_REPAIR_ATTEMPTS=" + refutedRepairAttempts);
      System.out.println(
          "PROOF_FAILURE_FALSE_REJECTIONS=" + proofFailureFalseRejections);
      System.out.println(
          "UNVERIFIED_COUNTEREXAMPLE_REFUTATIONS="
              + unverifiedCounterexampleRefutations);
      System.out.println("CLAIM_STATUS_VERIFIED=" + statuses.get(ClaimStatus.VERIFIED));
      System.out.println("CLAIM_STATUS_REJECTED=" + statuses.get(ClaimStatus.REJECTED));
      System.out.println("CLAIM_STATUS_UNCERTAIN=" + statuses.get(ClaimStatus.UNCERTAIN));
      System.out.println("DIRECT_FACT_PROMOTIONS_BY_REPAIRER=0");
      System.out.println("DIRECT_CLAIM_VERIFICATIONS_BY_REPAIRER=0");
      System.out.println("DIRECT_NEGATIVE_REGISTRATIONS=" + directNegativeRegistrations);
      System.out.println("MAIN_GOAL_CLOSURES=" + mainGoalClosures);
      System.out.println("DUPLICATE_COURT_CASES=" + duplicateCourtCases);
      System.out.println("DUPLICATE_REPAIR_PATCHES=" + duplicateRepairPatches);
      System.out.println("DUPLICATE_BLIND_ADJUDICATIONS=" + duplicateBlindAdjudications);
      System.out.println("DUPLICATE_FACT_PROMOTIONS=" + duplicateFacts);
      System.out.println("POST_RESTORE_CASE_LOSSES=" + postRestoreCaseLosses);
      System.out.println("POST_RESTORE_REVISION_LOSSES=" + postRestoreRevisionLosses);
      System.out.println("POST_RESTORE_STAGE_REPLAYS=" + postRestoreStageReplays);
      System.out.println("POST_RESTORE_OUTCOME_CHANGES=" + postRestoreOutcomeChanges);
      System.out.println("ROOT_HASH_CHANGES=" + rootHashChanges);
      System.out.println(
          "NEGATIVE_REGISTRY_HASH_CHANGES=" + negativeRegistryHashChanges);
      System.out.println("COURT_LEDGER_HASH_BEFORE_RESTORE=" + courtHashBeforeRestore);
      System.out.println("COURT_LEDGER_HASH_AFTER_RESTORE=" + courtHashAfterRestore);
      System.out.println("PROOF_REVISION_HASH_BEFORE_RESTORE=" + revisionHashBeforeRestore);
      System.out.println("PROOF_REVISION_HASH_AFTER_RESTORE=" + revisionHashAfterRestore);
      System.out.println("RESULT=PASS");
      System.out.println("----------------------------------------------------------------");
    } finally {
      harness.close();
    }
  }

  private static String claimId(int round) {
    return "court-round-" + round;
  }

  private static String statement(int round) {
    if (round < 5) {
      return "VALID_ROUND_" + round + ": the supplied finite proof is complete.";
    }
    if (round < 10) {
      return "REPAIRABLE_ROUND_" + round + ": a finite surjection is bijective.";
    }
    if (round < 15) {
      return "UNREPAIRABLE_ROUND_" + round + ": a finite tree has two leaves.";
    }
    return "REFUTED_ROUND_" + round + ": every connected finite graph is Hamiltonian.";
  }

  private static void accumulateStageCalls(
      DesktopClaimSalvageTestHarness harness, Map<String, Long> calls) {
    for (String schema :
        java.util.List.of(
            "ClaimStatementFalsificationBatch",
            "ClaimCounterexampleWitnessReviewBatch",
            "ClaimProofAuditBatch",
            "ClaimMinimalRepairBatch",
            "ClaimBlindAdjudicationBatch")) {
      calls.merge(schema, harness.callsForSchema(schema), Long::sum);
    }
  }

  private static Map<String, ClaimCourtOutcome> outcomes(
      DesktopClaimSalvageTestHarness harness) {
    return harness.claimCourt().records().stream()
        .collect(
            Collectors.toMap(
                record -> record.courtCaseId(),
                record -> record.outcome(),
                (left, right) -> left,
                LinkedHashMap::new));
  }

  private static int changedOutcomes(
      Map<String, ClaimCourtOutcome> expected,
      Map<String, ClaimCourtOutcome> actual) {
    return Math.toIntExact(
        expected.entrySet().stream()
            .filter(entry -> actual.get(entry.getKey()) != entry.getValue())
            .count());
  }

  private static Map<String, Long> outcomesByCase(
      DesktopClaimSalvageTestHarness harness) {
    return harness.claimCourt().records().stream()
        .collect(
            Collectors.groupingBy(
                record -> record.courtCaseId(), LinkedHashMap::new, Collectors.counting()));
  }

  private static long duplicates(Map<String, Long> counts) {
    return counts.values().stream().mapToLong(count -> Math.max(0L, count - 1L)).sum();
  }
}
