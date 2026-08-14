package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofgraph.CanonicalObligationSchedulingState;
import io.github.aililuola.mathproofmesh.proofgraph.DeferredExpansionStatus;
import io.github.aililuola.mathproofmesh.proofgraph.FocusedRecoveryActionType;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphControlMode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopFocusedDeferredReactivationRestoreTest {
  private static final int RESTORE_ROUND = 2;

  @Test
  void focusedDeferralSurvivesRestoreAndReactivatesOnceOnAuthoritativeProgress(
      @TempDir Path directory) throws Exception {
    DesktopResearchCheckpointBlackBoxHarness harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-focused-deferred-restore",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused");
    try {
      harness.prepareProductionRoute();
      DesktopDeferredReactivationTestSupport.enterFocusedWithSelectedAndUnselectedTargets(harness);
      var deferred =
          DesktopDeferredReactivationTestSupport.addControlledTarget(
              harness,
              "focused-deferred-target",
              "focused-deferred-unselected-family",
              FocusedRecoveryActionType.NEW_STRATEGY,
              2);
      var record =
          DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().stream()
              .filter(item -> item.obligationId().equals(deferred.obligationId()))
              .findFirst()
              .orElseThrow();
      assertThat(record.status()).isEqualTo(DeferredExpansionStatus.DEFERRED);
      long focusedDeferredRecords =
          DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().stream()
              .filter(item -> item.obligationId().equals(deferred.obligationId()))
              .count();
      String rootHash = harness.rootHash();
      String negativeHash = harness.negativeHash();
      long baselineFacts = harness.directFactPromotions();
      int baselineClaims = harness.directClaimVerifications();
      int baselineMainGoalClosures = harness.mainGoalClosures();
      DesktopProofGraphIssue005BlackBoxSupport.setRound(harness, RESTORE_ROUND);
      DesktopSolveCheckpoint checkpoint = harness.checkpointRoundTrip();
      DesktopResearchCheckpointBlackBoxHarness restored = harness.restored(checkpoint);
      harness.close();
      harness = restored;
      assertThat(DesktopProofGraphIssue005BlackBoxSupport.controlMode(harness))
          .isEqualTo(ProofGraphControlMode.FOCUSED_RECOVERY.name());
      DeferredExpansionStatus restoredStatus =
          DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().stream()
              .filter(item -> item.deferredId().equals(record.deferredId()))
              .findFirst()
              .orElseThrow()
              .status();
      int postRestoreReactivationLosses =
          restoredStatus == DeferredExpansionStatus.DEFERRED ? 0 : 1;
      assertThat(postRestoreReactivationLosses).isZero();

      var graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
      double globalBefore = graph.globalCanonicalProofDebt();
      DesktopProofGraphIssue005BlackBoxSupport.addVerifiedLocalClaim(
          harness, "focused-deferred-authoritative-progress");
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 3);
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 4);

      var finalRecord =
          DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().stream()
              .filter(item -> item.deferredId().equals(record.deferredId()))
              .findFirst()
              .orElseThrow();
      var target = graph.canonicalTargetForObligation(deferred.obligationId()).orElseThrow();
      long tasks =
          DesktopProofGraphIssue005BlackBoxSupport.pendingTasks(harness).stream()
              .map(DesktopSolveCheckpoint.ScheduledProofTask.class::cast)
              .filter(task -> task.source().equals("deferred-reactivation:" + record.deferredId()))
              .count();
      long finalMatchingRecords =
          DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().stream()
              .filter(item -> item.deferredId().equals(record.deferredId()))
              .count();
      long postRestoreDuplicateReactivations = Math.max(0L, finalMatchingRecords - 1L);
      int postCooldownReactivated =
          finalRecord.status() == DeferredExpansionStatus.REACTIVATED ? 1 : 0;
      int rootChanges = harness.rootHash().equals(rootHash) ? 0 : 1;
      int negativeChanges = harness.negativeHash().equals(negativeHash) ? 0 : 1;
      int unauthorizedFacts = (int) (harness.directFactPromotions() - baselineFacts);
      int unauthorizedClaims = harness.directClaimVerifications() - baselineClaims;
      int mainGoalClosures = harness.mainGoalClosures() - baselineMainGoalClosures;
      int falseDebtDecreases = graph.globalCanonicalProofDebt() == globalBefore ? 0 : 1;

      assertThat(finalRecord.status()).isEqualTo(DeferredExpansionStatus.REACTIVATED);
      assertThat(focusedDeferredRecords).isEqualTo(1);
      assertThat(postRestoreDuplicateReactivations).isZero();
      assertThat(target.schedulingState()).isEqualTo(CanonicalObligationSchedulingState.ACTIVE);
      assertThat(tasks).isEqualTo(1);
      assertThat(rootChanges).isZero();
      assertThat(negativeChanges).isZero();
      assertThat(unauthorizedFacts).isZero();
      assertThat(unauthorizedClaims).isZero();
      assertThat(mainGoalClosures).isZero();
      assertThat(falseDebtDecreases).isZero();

      System.out.println("FOCUSED DEFERRED REACTIVATION DIAGNOSTIC");
      System.out.println("---------------------------------------------------------------");
      print("FOCUSED_DEFERRED_RECORDS", focusedDeferredRecords);
      print("RESTORE_ROUND", RESTORE_ROUND);
      print("POST_COOLDOWN_REACTIVATED", postCooldownReactivated);
      print("POST_RESTORE_REACTIVATION_LOSSES", postRestoreReactivationLosses);
      print("POST_RESTORE_DUPLICATE_REACTIVATIONS", postRestoreDuplicateReactivations);
      print("POST_RESTORE_DUPLICATE_TASKS", tasks - 1);
      print("GLOBAL_DEBT_FALSE_DECREASES", falseDebtDecreases);
      print("UNAUTHORIZED_FACT_PROMOTIONS", unauthorizedFacts);
      print("UNAUTHORIZED_CLAIM_VERIFICATIONS", unauthorizedClaims);
      print("MAIN_GOAL_CLOSURES", mainGoalClosures);
      print("ROOT_HASH_CHANGES", rootChanges);
      print("NEGATIVE_REGISTRY_HASH_CHANGES", negativeChanges);
      System.out.println("RESULT=PASS");
      System.out.println("---------------------------------------------------------------");
    } finally {
      harness.close();
    }
  }

  private static void print(String name, long value) {
    System.out.println(name + "=" + value);
  }
}
