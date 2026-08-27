package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.proofgraph.DeferredExpansionRecord;
import io.github.aililuola.mathproofmesh.proofgraph.DeferredExpansionStatus;
import io.github.aililuola.mathproofmesh.proofgraph.FocusedRecoveryActionType;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphControlMode;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopDeferredReactivationMultiRoundTest {
  private static final int ROUNDS = 12;
  private static final int RESTORE_ROUND = 6;

  @Test
  void twelveRoundsCoverReactivationSatisfactionRetirementAndRestore(
      @TempDir Path directory) throws Exception {
    DesktopResearchCheckpointBlackBoxHarness harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-deferred-lifecycle-multiround",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused");
    int earlyFocusedLeaks = 0;
    int postRestoreStatusChanges = 0;
    int postRestoreReactivationLosses = 0;
    int canonicalizationUnauthorizedChanges = 0;
    int rootChanges = 0;
    int negativeChanges = 0;
    int attemptChanges = 0;
    int claimChanges = 0;
    int researchChanges = 0;
    int globalDebtFalseDecreases = 0;
    String deferredHashBeforeRestore = "";
    String deferredHashAfterRestore = "";
    String convergenceHashBeforeRestore = "";
    String convergenceHashAfterRestore = "";
    try {
      harness.prepareProductionRoute();
      Map<String, String> controlledFamilies = new LinkedHashMap<>();
      for (String id : List.of("multi-binding-a-1", "multi-binding-a-2", "multi-binding-b-1")) {
        String family = id.contains("binding-a") ? "multi-family-a" : "multi-family-b";
        controlledFamilies.put(id, family);
        DesktopDeferredReactivationTestSupport.addControlledTarget(
            harness, id, family, FocusedRecoveryActionType.NEW_STRATEGY, 0);
      }
      DesktopDeferredReactivationTestSupport.fillRouteCapacity(harness, "multi-capacity");
      var graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
      ProofObligation capacityDeferred =
          DesktopDeferredReactivationTestSupport.addControlledTarget(
              harness,
              "multi-capacity-deferred-c",
              "multi-capacity-family-c",
              FocusedRecoveryActionType.NEW_STRATEGY,
              0);
      DeferredExpansionRecord capacityRecord = recordFor(harness, capacityDeferred.obligationId());
      String capacityCanonical =
          graph.canonicalTargetForObligation(capacityDeferred.obligationId())
              .orElseThrow()
              .canonicalTargetId();
      DesktopProofGraphIssue005BlackBoxSupport.closeFirstActiveCanonicalTargetExcept(
          harness, capacityCanonical);
      double capacityDebtBefore = graph.globalCanonicalProofDebt();
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 0);
      globalDebtFalseDecreases += graph.globalCanonicalProofDebt() == capacityDebtBefore ? 0 : 1;
      assertThat(recordFor(harness, capacityDeferred.obligationId()).status())
          .isEqualTo(DeferredExpansionStatus.REACTIVATED);

      DesktopProofGraphIssue005BlackBoxSupport.closeFirstActiveCanonicalTargetExcept(
          harness, capacityCanonical);
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 1);
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 2);
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 3);
      var monitor = DesktopProofGraphIssue005BlackBoxSupport.convergence(harness);
      assertThat(monitor.controlMode()).isEqualTo(ProofGraphControlMode.FOCUSED_RECOVERY);
      var plan = monitor.focusedRecoveryPlan().orElseThrow();

      ProofObligation focusedDeferred =
          DesktopDeferredReactivationTestSupport.addControlledTarget(
              harness,
              "multi-focused-deferred-d",
              "multi-unselected-family-d",
              FocusedRecoveryActionType.NEW_STRATEGY,
              3);
      DeferredExpansionRecord focusedRecord = recordFor(harness, focusedDeferred.obligationId());
      assertThat(focusedRecord.status()).isEqualTo(DeferredExpansionStatus.DEFERRED);

      var graphBeforeRestore = graph;
      String duplicateSourceId =
          controlledFamilies.keySet().stream()
              .filter(
                  id ->
                      graphBeforeRestore.canonicalTargetForObligation(id)
                          .map(target -> !plan.selects("", target.canonicalTargetId()))
                          .orElse(false))
              .findFirst()
              .orElseThrow();
      ProofObligation original = graphBeforeRestore.getObligation(duplicateSourceId);
      ProofObligation duplicate =
          DesktopProofGraphIssue005BlackBoxSupport.obligation(
              "multi-satisfied-duplicate",
              "route-1",
              original.statement(),
              original.normalizedStatement(),
              original.firstErrorFingerprint(),
              "multi-satisfied-alternative-plan");
      var duplicateContext =
          DesktopDeferredReactivationTestSupport.context(
              duplicate.obligationId(), controlledFamilies.get(duplicateSourceId), 3);
      assertThat(graph.existingCanonicalTargetId(duplicate, duplicateContext)).isPresent();
      assertThat(
              DesktopProofGraphIssue005BlackBoxSupport.addControlledObligation(
                  harness,
                  duplicate,
                  duplicateContext,
                  FocusedRecoveryActionType.NEW_STRATEGY))
          .isFalse();
      DeferredExpansionRecord satisfiedRecord = recordFor(harness, duplicate.obligationId());
      DesktopProofGraphIssue005BlackBoxSupport.reconsiderDeferredExpansions(harness);
      assertThat(recordFor(harness, duplicate.obligationId()).status())
          .isEqualTo(DeferredExpansionStatus.SATISFIED_BY_ACTIVE_TARGET);

      for (int round : List.of(4, 5)) {
        DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, round);
        earlyFocusedLeaks +=
            recordFor(harness, focusedDeferred.obligationId()).status()
                    == DeferredExpansionStatus.DEFERRED
                ? 0
                : 1;
      }

      DesktopProofGraphIssue005BlackBoxSupport.setRound(harness, RESTORE_ROUND);
      DesktopProofGraphIssue005BlackBoxSupport.reconsiderDeferredExpansions(harness);
      Map<String, DeferredExpansionStatus> statusesBeforeRestore = statuses(harness);
      deferredHashBeforeRestore = DesktopProofGraphIssue005BlackBoxSupport.deferredHash(harness);
      convergenceHashBeforeRestore =
          DesktopProofGraphIssue005BlackBoxSupport.convergenceHash(harness);
      String canonicalizationHashBeforeRestore =
          DesktopProofGraphIssue005BlackBoxSupport.canonicalizationHash(harness);
      String rootHash = harness.rootHash();
      String negativeHash = harness.negativeHash();
      String attemptHash = harness.attemptArtifactHash();
      String claimHash = harness.claimLifecycleHash();
      String researchHash = harness.researchLedger().ledgerHash();
      long baselineFacts = harness.directFactPromotions();
      int baselineClaims = harness.directClaimVerifications();
      int baselineNegatives = harness.permanentNegativeRegistrations();
      int baselineMainGoalClosures = harness.mainGoalClosures();
      Set<String> rawBeforeRestore =
          graph.rawObligationOccurrences().stream()
              .map(occurrence -> occurrence.occurrenceId())
              .collect(java.util.stream.Collectors.toSet());
      Map<String, String> canonicalByObligationBeforeRestore =
          canonicalByObligation(graph);
      DesktopSolveCheckpoint checkpoint = harness.checkpointRoundTrip();
      DesktopResearchCheckpointBlackBoxHarness restored = harness.restored(checkpoint);
      harness.close();
      harness = restored;
      graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
      deferredHashAfterRestore = DesktopProofGraphIssue005BlackBoxSupport.deferredHash(harness);
      convergenceHashAfterRestore =
          DesktopProofGraphIssue005BlackBoxSupport.convergenceHash(harness);
      Map<String, DeferredExpansionStatus> statusesAfterRestore = statuses(harness);
      postRestoreStatusChanges += statusesAfterRestore.equals(statusesBeforeRestore) ? 0 : 1;
      postRestoreReactivationLosses +=
          (int)
              statusesBeforeRestore.entrySet().stream()
                  .filter(entry -> entry.getValue() != DeferredExpansionStatus.DEFERRED)
                  .filter(entry -> statusesAfterRestore.get(entry.getKey()) != entry.getValue())
                  .count();
      canonicalizationUnauthorizedChanges +=
          DesktopProofGraphIssue005BlackBoxSupport.canonicalizationHash(harness)
                  .equals(canonicalizationHashBeforeRestore)
              ? 0
              : 1;
      assertThat(graph.rawObligationOccurrences())
          .extracting(occurrence -> occurrence.occurrenceId())
          .containsAll(rawBeforeRestore);
      assertThat(canonicalByObligation(graph)).containsAllEntriesOf(canonicalByObligationBeforeRestore);

      String focusedCanonical =
          graph.canonicalTargetForObligation(focusedDeferred.obligationId())
              .orElseThrow()
              .canonicalTargetId();
      DesktopProofGraphIssue005BlackBoxSupport.closeFirstActiveCanonicalTargetExcept(
          harness, focusedCanonical);
      double focusedDebtBefore = graph.globalCanonicalProofDebt();
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 7);
      globalDebtFalseDecreases += graph.globalCanonicalProofDebt() == focusedDebtBefore ? 0 : 1;
      assertThat(recordFor(harness, focusedDeferred.obligationId()).status())
          .isEqualTo(DeferredExpansionStatus.REACTIVATED);
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 8);
      DesktopProofGraphIssue005BlackBoxSupport.reconsiderDeferredExpansions(harness);

      DesktopDeferredReactivationTestSupport.fillRouteCapacity(harness, "multi-terminal-fill");
      ProofObligation terminalDeferred =
          DesktopDeferredReactivationTestSupport.addControlledTarget(
              harness,
              "multi-terminal-deferred-e",
              "multi-terminal-family-e",
              FocusedRecoveryActionType.NEW_STRATEGY,
              9);
      DeferredExpansionRecord terminalRecord = recordFor(harness, terminalDeferred.obligationId());
      graph.refuteObligation(terminalDeferred.obligationId(), null);
      DesktopProofGraphIssue005BlackBoxSupport.setRound(harness, 9);
      DesktopProofGraphIssue005BlackBoxSupport.reconsiderDeferredExpansions(harness);
      assertThat(recordFor(harness, terminalDeferred.obligationId()).status())
          .isEqualTo(DeferredExpansionStatus.RETIRED);
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 9);
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 10);
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 11);

      Map<String, DeferredExpansionRecord> finalRecords =
          DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      DeferredExpansionRecord::deferredId,
                      java.util.function.Function.identity()));
      List<DesktopSolveCheckpoint.ScheduledProofTask> allPendingTasks =
          DesktopProofGraphIssue005BlackBoxSupport.pendingTasks(harness).stream()
              .map(DesktopSolveCheckpoint.ScheduledProofTask.class::cast)
              .toList();
      List<DesktopSolveCheckpoint.ScheduledProofTask> reactivationTasks =
          allPendingTasks.stream()
              .filter(task -> task.source().startsWith("deferred-reactivation:"))
              .toList();
      long duplicateTasks =
          reactivationTasks.size()
              - reactivationTasks.stream()
                  .map(DesktopSolveCheckpoint.ScheduledProofTask::taskId)
                  .distinct()
                  .count();
      long duplicateReactivations =
          finalRecords.values().stream()
              .filter(record -> record.status() == DeferredExpansionStatus.REACTIVATED)
              .map(DeferredExpansionRecord::deferredId)
              .count()
              - finalRecords.values().stream()
                  .filter(record -> record.status() == DeferredExpansionStatus.REACTIVATED)
                  .map(DeferredExpansionRecord::reactivatedTaskId)
                  .distinct()
                  .count();
      Set<String> expectedTaskLeases =
          allPendingTasks.stream()
              .map(DesktopDeferredReactivationMultiRoundTest::taskLeaseKey)
              .collect(java.util.stream.Collectors.toSet());
      Set<String> actualTaskLeases = graph.canonicalizationSnapshot().taskLeaseKeys();
      long taskLeaseLeaks =
          actualTaskLeases.stream().filter(key -> !expectedTaskLeases.contains(key)).count()
              + expectedTaskLeases.stream().filter(key -> !actualTaskLeases.contains(key)).count();
      var finalGraph = graph;
      int rawLosses =
          rawBeforeRestore.stream()
                  .allMatch(
                      id ->
                          finalGraph.rawObligationOccurrences().stream()
                              .anyMatch(occurrence -> occurrence.occurrenceId().equals(id)))
              ? 0
              : 1;
      int canonicalLosses =
          canonicalByObligation(graph).entrySet().containsAll(canonicalByObligationBeforeRestore.entrySet())
              ? 0
              : 1;
      rootChanges += harness.rootHash().equals(rootHash) ? 0 : 1;
      negativeChanges += harness.negativeHash().equals(negativeHash) ? 0 : 1;
      attemptChanges += harness.attemptArtifactHash().equals(attemptHash) ? 0 : 1;
      claimChanges += harness.claimLifecycleHash().equals(claimHash) ? 0 : 1;
      researchChanges += harness.researchLedger().ledgerHash().equals(researchHash) ? 0 : 1;
      int directFacts = (int) (harness.directFactPromotions() - baselineFacts);
      int directClaims = harness.directClaimVerifications() - baselineClaims;
      int directNegatives = harness.permanentNegativeRegistrations() - baselineNegatives;
      int mainGoalClosures = harness.mainGoalClosures() - baselineMainGoalClosures;

      assertThat(earlyFocusedLeaks).isZero();
      assertThat(duplicateReactivations).isZero();
      assertThat(duplicateTasks).isZero();
      assertThat(taskLeaseLeaks).isZero();
      assertThat(postRestoreReactivationLosses).isZero();
      assertThat(postRestoreStatusChanges).isZero();
      assertThat(deferredHashAfterRestore).isEqualTo(deferredHashBeforeRestore);
      assertThat(convergenceHashAfterRestore).isEqualTo(convergenceHashBeforeRestore);
      assertThat(canonicalizationUnauthorizedChanges).isZero();
      assertThat(globalDebtFalseDecreases).isZero();
      assertThat(rawLosses).isZero();
      assertThat(canonicalLosses).isZero();
      assertThat(rootChanges).isZero();
      assertThat(negativeChanges).isZero();
      assertThat(attemptChanges).isZero();
      assertThat(claimChanges).isZero();
      assertThat(researchChanges).isZero();
      assertThat(directFacts).isZero();
      assertThat(directClaims).isZero();
      assertThat(directNegatives).isZero();
      assertThat(mainGoalClosures).isZero();

      System.out.println("DEFERRED EXPANSION LIFECYCLE DIAGNOSTIC");
      System.out.println("----------------------------------------------------------------");
      print("ROUNDS", ROUNDS);
      print("RESTORE_ROUND", RESTORE_ROUND);
      print("CAPACITY_DEFERRED", capacityRecord.status() == DeferredExpansionStatus.DEFERRED ? 1 : 0);
      print("FOCUSED_RECOVERY_DEFERRED", focusedRecord.status() == DeferredExpansionStatus.DEFERRED ? 1 : 0);
      print("REACTIVATED_AFTER_CAPACITY_RELEASE", statusIs(finalRecords, capacityRecord, DeferredExpansionStatus.REACTIVATED));
      print("REACTIVATED_AFTER_COOLDOWN", statusIs(finalRecords, focusedRecord, DeferredExpansionStatus.REACTIVATED));
      print("SATISFIED_BY_ACTIVE_TARGET", statusIs(finalRecords, satisfiedRecord, DeferredExpansionStatus.SATISFIED_BY_ACTIVE_TARGET));
      print("RETIRED_TERMINAL_TARGETS", statusIs(finalRecords, terminalRecord, DeferredExpansionStatus.RETIRED));
      print("EARLY_FOCUSED_REACTIVATION_LEAKS", earlyFocusedLeaks);
      print("DUPLICATE_REACTIVATIONS", duplicateReactivations);
      print("DUPLICATE_REACTIVATION_TASKS", duplicateTasks);
      print("TASK_LEASE_LEAKS", taskLeaseLeaks);
      print("POST_RESTORE_REACTIVATION_LOSSES", postRestoreReactivationLosses);
      print("POST_RESTORE_STATUS_CHANGES", postRestoreStatusChanges);
      print("POST_RESTORE_DUPLICATE_TASKS", duplicateTasks);
      print("GLOBAL_DEBT_FALSE_DECREASES", globalDebtFalseDecreases);
      print("RAW_OCCURRENCE_LOSSES", rawLosses);
      print("CANONICAL_TARGET_LOSSES", canonicalLosses);
      print("DIRECT_FACT_PROMOTIONS", directFacts);
      print("DIRECT_CLAIM_VERIFICATIONS", directClaims);
      print("DIRECT_NEGATIVE_REGISTRATIONS", directNegatives);
      print("MAIN_GOAL_CLOSURES", mainGoalClosures);
      print("ROOT_HASH_CHANGES", rootChanges);
      print("NEGATIVE_REGISTRY_HASH_CHANGES", negativeChanges);
      print("ATTEMPT_ARTIFACT_LEDGER_HASH_CHANGES", attemptChanges);
      print("CLAIM_LIFECYCLE_HASH_CHANGES", claimChanges);
      print("RESEARCH_CHECKPOINT_LEDGER_HASH_CHANGES", researchChanges);
      print(
          "CANONICALIZATION_REGISTRY_UNAUTHORIZED_CHANGES",
          canonicalizationUnauthorizedChanges);
      System.out.println("DEFERRED_HASH_BEFORE_RESTORE=" + deferredHashBeforeRestore);
      System.out.println("DEFERRED_HASH_AFTER_RESTORE=" + deferredHashAfterRestore);
      System.out.println("CONVERGENCE_HASH_BEFORE_RESTORE=" + convergenceHashBeforeRestore);
      System.out.println("CONVERGENCE_HASH_AFTER_RESTORE=" + convergenceHashAfterRestore);
      System.out.println("RESULT=PASS");
      System.out.println("----------------------------------------------------------------");
    } finally {
      harness.close();
    }
  }

  private static DeferredExpansionRecord recordFor(
      DesktopResearchCheckpointBlackBoxHarness harness, String obligationId) throws Exception {
    return DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().stream()
        .filter(record -> record.obligationId().equals(obligationId))
        .findFirst()
        .orElseThrow();
  }

  private static Map<String, DeferredExpansionStatus> statuses(
      DesktopResearchCheckpointBlackBoxHarness harness) throws Exception {
    return DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().stream()
        .collect(
            java.util.stream.Collectors.toMap(
                DeferredExpansionRecord::deferredId, DeferredExpansionRecord::status));
  }

  private static Map<String, String> canonicalByObligation(
      io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore graph) {
    Map<String, String> result = new LinkedHashMap<>();
    graph.rawObligationOccurrences().forEach(
        occurrence -> result.put(occurrence.obligationId(), occurrence.canonicalTargetId()));
    return Map.copyOf(result);
  }

  private static int statusIs(
      Map<String, DeferredExpansionRecord> records,
      DeferredExpansionRecord reference,
      DeferredExpansionStatus status) {
    DeferredExpansionRecord current = records.get(reference.deferredId());
    return current != null && current.status() == status ? 1 : 0;
  }

  private static String taskLeaseKey(DesktopSolveCheckpoint.ScheduledProofTask task) {
    String scopeId =
        switch (task.scope()) {
          case BOTTLENECK_FAMILY -> task.familyId();
          case CANONICAL_TARGET -> task.canonicalTargetId();
          case ROUTE_OCCURRENCE -> task.routeId() + ":" + task.obligationId();
        };
    return CanonicalJson.stableHash(
        Map.of(
            "scope", task.scope().name(),
            "scope_id", scopeId,
            "action_key", task.actionKey()));
  }

  private static void print(String name, long value) {
    System.out.println(name + "=" + value);
  }
}
