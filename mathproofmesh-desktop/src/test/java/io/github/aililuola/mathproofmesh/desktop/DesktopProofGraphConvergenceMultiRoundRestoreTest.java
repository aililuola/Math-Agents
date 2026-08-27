package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.proofgraph.BottleneckRelationType;
import io.github.aililuola.mathproofmesh.proofgraph.FocusedRecoveryActionType;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationCreationContext;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationOccurrenceSchedulingState;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationSourceType;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphControlMode;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphConvergenceMonitor;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphRoundClassification;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphRoundMetrics;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopProofGraphConvergenceMultiRoundRestoreTest {
  private static final int ROUNDS = 20;
  private static final int RESTORE_ROUND = 10;

  @Test
  void twentyRoundsPreserveFocusedControlAndResumeWithoutExpansionLeaks(
      @TempDir Path directory) throws Exception {
    DesktopResearchCheckpointBlackBoxHarness harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-convergence-multiround",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused");
    int rawProposals = 0;
    int postRestoreStateChanges = 0;
    int postRestoreDeferredReconciliations = 0;
    int rootHashChanges = 0;
    int negativeHashChanges = 0;
    int attemptHashChanges = 0;
    int claimHashChanges = 0;
    int researchHashChanges = 0;
    int canonicalizationRestoreHashChanges = 0;
    int maxActivePerRoute = 0;
    int maxActiveCampaign = 0;
    Set<String> selectedFamilies = new LinkedHashSet<>();
    String convergenceHashBeforeRestore = "";
    String convergenceHashAfterRestore = "";
    try {
      harness.prepareProductionRoute();
      String rootHash = harness.rootHash();
      String negativeHash = harness.negativeHash();
      String attemptHash = harness.attemptArtifactHash();
      String claimHash = harness.claimLifecycleHash();
      String researchHash = harness.researchLedger().ledgerHash();
      long baselineFacts = harness.directFactPromotions();
      int baselineClaims = harness.directClaimVerifications();
      int baselineNegatives = harness.permanentNegativeRegistrations();
      int baselineMainGoalClosures = harness.mainGoalClosures();
      ProofGraphStore initialGraph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
      int initialOccurrences = initialGraph.rawObligationOccurrences().size();
      int initialCanonicalTargets = initialGraph.allCanonicalTargets().size();

      for (int target = 0; target < 2; target++) {
        String id = "convergence-seed-" + target;
        boolean admitted =
            DesktopProofGraphIssue005BlackBoxSupport.addControlledObligation(
                harness,
                DesktopProofGraphIssue005BlackBoxSupport.obligation(
                    id,
                    "route-1",
                    "Resolve focused convergence seed " + target + ".",
                    "resolve focused convergence seed " + target,
                    "convergence-selected-family",
                    "seed-plan-" + target),
                context(
                    id,
                    "convergence-selected-family",
                    "convergence-selected-family",
                    0),
                FocusedRecoveryActionType.FOCUSED_PROVER);
        assertThat(admitted).isTrue();
        rawProposals++;
      }

      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 0);
      DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, 0);
      ProofGraphConvergenceMonitor monitor =
          DesktopProofGraphIssue005BlackBoxSupport.convergence(harness);
      assertThat(monitor.controlMode()).isEqualTo(ProofGraphControlMode.FOCUSED_RECOVERY);
      assertThat(monitor.focusedRecoveryPlan()).isPresent();
      assertThat(monitor.focusedRecoveryPlan().orElseThrow().selectedFamilyId()).isNotBlank();
      selectedFamilies.add(monitor.focusedRecoveryPlan().orElseThrow().selectedFamilyId());

      for (int round = 0; round < ROUNDS; round++) {
        DesktopProofGraphIssue005BlackBoxSupport.setRound(harness, round);
        if (round < 6) {
          for (int duplicate = 0; duplicate < 4; duplicate++) {
            String id = "convergence-duplicate-" + round + "-" + duplicate;
            ProofObligation duplicateObligation =
                DesktopProofGraphIssue005BlackBoxSupport.obligation(
                    id,
                    "route-1",
                    "Resolve focused convergence seed 0.",
                    "resolve focused convergence seed 0",
                    "convergence-selected-family",
                    "duplicate-plan-" + round + "-" + duplicate);
            ObligationCreationContext duplicateContext =
                context(
                    id,
                    "convergence-selected-family",
                    "convergence-selected-family",
                    round);
            ProofGraphStore currentGraph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
            String candidateCanonical =
                currentGraph
                    .existingCanonicalTargetId(duplicateObligation, duplicateContext)
                    .orElse("");
            boolean admitted =
                DesktopProofGraphIssue005BlackBoxSupport.addControlledObligation(
                    harness, duplicateObligation, duplicateContext,
                    FocusedRecoveryActionType.FOCUSED_PROVER);
            assertThat(monitor.selectsCurrentBinding("", candidateCanonical)).isFalse();
            assertThat(admitted).isFalse();
            rawProposals++;
          }
          for (int candidate = 0; candidate < 2; candidate++) {
            String id = "convergence-new-" + round + "-" + candidate;
            boolean admitted =
                DesktopProofGraphIssue005BlackBoxSupport.addControlledObligation(
                    harness,
                    DesktopProofGraphIssue005BlackBoxSupport.obligation(
                        id,
                        "route-1",
                        "Investigate unrelated convergence candidate "
                            + round
                            + "-"
                            + candidate
                            + ".",
                        "investigate unrelated convergence candidate "
                            + round
                            + "-"
                            + candidate,
                        "unrelated-family-" + round + "-" + candidate,
                        "new-plan-" + round + "-" + candidate),
                    context(
                        id,
                        "unrelated-family-" + round + "-" + candidate,
                        "unrelated-family-" + round + "-" + candidate,
                        round),
                    FocusedRecoveryActionType.GENERIC_INSPIRATION);
            assertThat(admitted).isFalse();
            rawProposals++;
          }
        } else if (round < RESTORE_ROUND || round >= 16) {
          attemptGenericExpansionBatch(harness, round);
        } else if (round == 11) {
          DesktopProofGraphIssue005BlackBoxSupport.addVerifiedLocalClaim(
              harness, "convergence-reviewed-claim");
        } else if (round == 12) {
          DesktopProofGraphIssue005BlackBoxSupport.refuteFirstCanonicalTarget(harness);
        } else if (round == 13) {
          DesktopProofGraphIssue005BlackBoxSupport.closeFirstCanonicalTarget(harness);
        }

        if (round == RESTORE_ROUND) {
          String canonicalizationHash =
              DesktopProofGraphIssue005BlackBoxSupport.canonicalizationHash(harness);
          String deferredHash = DesktopProofGraphIssue005BlackBoxSupport.deferredHash(harness);
          convergenceHashBeforeRestore =
              DesktopProofGraphIssue005BlackBoxSupport.convergenceHash(harness);
          DesktopSolveCheckpoint checkpoint = harness.checkpointRoundTrip();
          assertThat(checkpoint.schemaVersion())
              .isEqualTo(DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION);
          assertThat(checkpoint.roundIndex()).isEqualTo(RESTORE_ROUND);
          DesktopResearchCheckpointBlackBoxHarness restored = harness.restored(checkpoint);
          harness.close();
          harness = restored;
          convergenceHashAfterRestore =
              DesktopProofGraphIssue005BlackBoxSupport.convergenceHash(harness);
          postRestoreStateChanges +=
              convergenceHashAfterRestore.equals(convergenceHashBeforeRestore) ? 0 : 1;
          postRestoreDeferredReconciliations +=
              DesktopProofGraphIssue005BlackBoxSupport.deferredHash(harness).equals(deferredHash)
                  ? 0
                  : 1;
          canonicalizationRestoreHashChanges +=
              DesktopProofGraphIssue005BlackBoxSupport.canonicalizationHash(harness)
                      .equals(canonicalizationHash)
                  ? 0
                  : 1;
          rootHashChanges += harness.rootHash().equals(rootHash) ? 0 : 1;
          negativeHashChanges += harness.negativeHash().equals(negativeHash) ? 0 : 1;
          attemptHashChanges += harness.attemptArtifactHash().equals(attemptHash) ? 0 : 1;
          claimHashChanges += harness.claimLifecycleHash().equals(claimHash) ? 0 : 1;
          researchHashChanges +=
              harness.researchLedger().ledgerHash().equals(researchHash) ? 0 : 1;
          assertThat(DesktopProofGraphIssue005BlackBoxSupport.controlMode(harness))
              .isEqualTo(ProofGraphControlMode.FOCUSED_RECOVERY.name());
        }

        DesktopProofGraphIssue005BlackBoxSupport.sampleSchedulerRound(harness, round);
        ProofGraphStore graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
        maxActivePerRoute =
            Math.max(maxActivePerRoute, graph.activeCanonicalTargetCount("route-1"));
        maxActiveCampaign = Math.max(maxActiveCampaign, graph.activeCanonicalTargetCount());
        DesktopProofGraphIssue005BlackBoxSupport.convergence(harness)
            .focusedRecoveryPlan()
            .map(plan -> plan.selectedFamilyId())
            .filter(id -> !id.isBlank())
            .ifPresent(selectedFamilies::add);
      }

      ProofGraphStore graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
      monitor = DesktopProofGraphIssue005BlackBoxSupport.convergence(harness);
      List<ProofGraphRoundMetrics> metrics = monitor.roundHistory();
      List<ProofGraphRoundClassification> classifications = monitor.roundClassifications();
      int duplicateOccurrences = metrics.stream().mapToInt(ProofGraphRoundMetrics::duplicateOccurrences).sum();
      int canonicalTargetsCreated = graph.allCanonicalTargets().size() - initialCanonicalTargets;
      int occurrenceDelta = graph.rawObligationOccurrences().size() - initialOccurrences;
      int capacityHardDeletions = Math.max(0, rawProposals - occurrenceDelta);
      long capacityDeferred =
          DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(harness).records().stream()
              .filter(record -> record.schedulingState().name().equals("DEFERRED_CAPACITY"))
              .count();
      int verifiedClaimGains = metrics.stream().mapToInt(ProofGraphRoundMetrics::verifiedClaimGains).sum();
      int exactRefutationGains =
          metrics.stream().mapToInt(ProofGraphRoundMetrics::exactRefutationGains).sum();
      int closedTargetGains =
          metrics.stream().mapToInt(ProofGraphRoundMetrics::newlyClosedCanonicalTargets).sum();
      int debtDecreaseEvents = debtDecreaseEvents(metrics, monitor.config().debtEpsilon());
      int falseDebtDecreases = falseDebtDecreases(metrics, monitor.config().debtEpsilon());
      int duplicateProgress =
          duplicatesCountedAsProgress(metrics, classifications, monitor.config().debtEpsilon());
      List<DesktopSolveCheckpoint.ScheduledProofTask> tasks =
          DesktopProofGraphIssue005BlackBoxSupport.pendingTasks(harness).stream()
              .map(DesktopSolveCheckpoint.ScheduledProofTask.class::cast)
              .toList();
      List<DesktopSolveCheckpoint.ScheduledProofTask> focusedTasks =
          tasks.stream().filter(task -> task.source().startsWith("focused-recovery:")).toList();
      List<DesktopSolveCheckpoint.ScheduledProofTask> recoveryTasks =
          tasks.stream().filter(task -> recoveryTaskSource(task.source())).toList();
      long duplicateFocusedTasks =
          focusedTasks.size()
              - focusedTasks.stream()
                  .map(
                      task ->
                          task.source()
                              + ":"
                              + task.familyId()
                              + ":"
                              + task.actionKey())
                  .distinct()
                  .count();
      long unrelatedFamilyTasks =
          recoveryTasks.stream()
              .filter(task -> !task.source().contains("exact-falsification"))
              .filter(task -> !selectedFamilies.contains(task.familyId()))
              .count();
      int postRestoreGenericLeaks = monitor.genericExpansionLeaks();
      int postRestoreDuplicateFocusedTasks = (int) duplicateFocusedTasks;
      int directFactPromotions = (int) (harness.directFactPromotions() - baselineFacts);
      int directClaimVerifications = harness.directClaimVerifications() - baselineClaims;
      int directNegativeRegistrations =
          harness.permanentNegativeRegistrations() - baselineNegatives;
      int mainGoalClosures = harness.mainGoalClosures() - baselineMainGoalClosures;
      rootHashChanges += harness.rootHash().equals(rootHash) ? 0 : 1;
      negativeHashChanges += harness.negativeHash().equals(negativeHash) ? 0 : 1;
      attemptHashChanges += harness.attemptArtifactHash().equals(attemptHash) ? 0 : 1;
      claimHashChanges += harness.claimLifecycleHash().equals(claimHash) ? 0 : 1;
      researchHashChanges +=
          harness.researchLedger().ledgerHash().equals(researchHash) ? 0 : 1;

      assertThat(rawProposals).isEqualTo(38);
      assertThat(occurrenceDelta).isEqualTo(rawProposals);
      assertThat(monitor.stagnationEpisodes()).isEqualTo(1);
      assertThat(monitor.focusedRecoveryEntries()).isEqualTo(2);
      assertThat(monitor.focusedRecoveryExits()).isEqualTo(1);
      assertThat(monitor.recoveryCooldownEntries()).isEqualTo(1);
      assertThat(monitor.genericExpansionAttempts()).isPositive();
      assertThat(monitor.genericExpansionBlocks()).isPositive();
      assertThat(monitor.genericExpansionLeaks()).isZero();
      assertThat(focusedTasks).hasSize(2);
      assertThat(duplicateFocusedTasks).isZero();
      assertThat(unrelatedFamilyTasks).isZero();
      assertThat(maxActivePerRoute).isLessThanOrEqualTo(8);
      assertThat(maxActiveCampaign).isLessThanOrEqualTo(20);
      assertThat(capacityHardDeletions).isZero();
      assertThat(verifiedClaimGains).isEqualTo(1);
      assertThat(exactRefutationGains).isEqualTo(1);
      assertThat(closedTargetGains).isGreaterThanOrEqualTo(2);
      assertThat(debtDecreaseEvents).isGreaterThanOrEqualTo(2);
      assertThat(falseDebtDecreases).isZero();
      assertThat(duplicateProgress).isZero();
      assertThat(postRestoreStateChanges).isZero();
      assertThat(postRestoreDeferredReconciliations).isEqualTo(1);
      assertThat(postRestoreGenericLeaks).isZero();
      assertThat(postRestoreDuplicateFocusedTasks).isZero();
      assertThat(rootHashChanges).isZero();
      assertThat(negativeHashChanges).isZero();
      assertThat(attemptHashChanges).isZero();
      assertThat(claimHashChanges).isZero();
      assertThat(researchHashChanges).isZero();
      assertThat(canonicalizationRestoreHashChanges).isZero();
      assertThat(directFactPromotions).isZero();
      assertThat(directClaimVerifications).isZero();
      assertThat(directNegativeRegistrations).isZero();
      assertThat(mainGoalClosures).isZero();
      assertThat(graph.deferredCanonicalProofDebt()).isPositive();
      assertThat(graph.globalCanonicalProofDebt())
          .isGreaterThanOrEqualTo(graph.deferredCanonicalProofDebt());

      System.out.println("PROOF GRAPH CONVERGENCE DIAGNOSTIC");
      System.out.println("----------------------------------------------------------------");
      print("ROUNDS", ROUNDS);
      print("RESTORE_ROUND", RESTORE_ROUND);
      print("RAW_PROPOSALS", rawProposals);
      print("RAW_OCCURRENCES_RECORDED", occurrenceDelta);
      print("CANONICAL_TARGETS_CREATED", canonicalTargetsCreated);
      print("DUPLICATE_OCCURRENCES", duplicateOccurrences);
      print("STAGNATION_EPISODES", monitor.stagnationEpisodes());
      print("DIVERGENCE_EPISODES", monitor.divergenceEpisodes());
      print("FOCUSED_RECOVERY_ENTRIES", monitor.focusedRecoveryEntries());
      print("FOCUSED_RECOVERY_EXITS", monitor.focusedRecoveryExits());
      print("RECOVERY_COOLDOWN_ENTRIES", monitor.recoveryCooldownEntries());
      print("GENERIC_EXPANSION_ATTEMPTS", monitor.genericExpansionAttempts());
      print("GENERIC_EXPANSION_BLOCKS", monitor.genericExpansionBlocks());
      print("GENERIC_EXPANSION_LEAKS", monitor.genericExpansionLeaks());
      print("FOCUSED_FAMILY_TASKS_CREATED", focusedTasks.size());
      print("DUPLICATE_FOCUSED_FAMILY_TASKS", duplicateFocusedTasks);
      print("UNRELATED_FAMILY_TASKS_CREATED", unrelatedFamilyTasks);
      print("MAX_ACTIVE_CANONICAL_TARGETS_PER_ROUTE", maxActivePerRoute);
      print("MAX_ACTIVE_CANONICAL_TARGETS_CAMPAIGN", maxActiveCampaign);
      print("CAPACITY_DEFERRED_PROPOSALS", capacityDeferred);
      print("CAPACITY_HARD_DELETIONS", capacityHardDeletions);
      print("VERIFIED_CLAIM_GAINS", verifiedClaimGains);
      print("EXACT_REFUTATION_GAINS", exactRefutationGains);
      print("CLOSED_CANONICAL_TARGET_GAINS", closedTargetGains);
      print("CANONICAL_DEBT_DECREASE_EVENTS", debtDecreaseEvents);
      print("CANONICAL_DEBT_FALSE_DECREASES", falseDebtDecreases);
      print("RAW_DUPLICATES_COUNTED_AS_PROGRESS", duplicateProgress);
      print("POST_RESTORE_STATE_CHANGES", postRestoreStateChanges);
      print("POST_RESTORE_DEFERRED_RECONCILIATIONS", postRestoreDeferredReconciliations);
      print("POST_RESTORE_GENERIC_EXPANSION_LEAKS", postRestoreGenericLeaks);
      print("POST_RESTORE_DUPLICATE_FOCUSED_TASKS", postRestoreDuplicateFocusedTasks);
      print("ROOT_HASH_CHANGES", rootHashChanges);
      print("NEGATIVE_REGISTRY_HASH_CHANGES", negativeHashChanges);
      print("ATTEMPT_ARTIFACT_LEDGER_UNAUTHORIZED_CHANGES", attemptHashChanges);
      print("CLAIM_LIFECYCLE_UNAUTHORIZED_CHANGES", claimHashChanges);
      print("RESEARCH_CHECKPOINT_LEDGER_HASH_CHANGES", researchHashChanges);
      print("CANONICALIZATION_REGISTRY_HASH_CHANGES", canonicalizationRestoreHashChanges);
      print("DIRECT_FACT_PROMOTIONS", directFactPromotions);
      print("DIRECT_CLAIM_VERIFICATIONS", directClaimVerifications);
      print("DIRECT_NEGATIVE_REGISTRATIONS", directNegativeRegistrations);
      print("MAIN_GOAL_CLOSURES", mainGoalClosures);
      System.out.println("CONVERGENCE_HASH_BEFORE_RESTORE=" + convergenceHashBeforeRestore);
      System.out.println("CONVERGENCE_HASH_AFTER_RESTORE=" + convergenceHashAfterRestore);
      System.out.println("ACTIVE_CANONICAL_DEBT=" + graph.activeCanonicalProofDebt());
      System.out.println("DEFERRED_CANONICAL_DEBT=" + graph.deferredCanonicalProofDebt());
      System.out.println("GLOBAL_CANONICAL_DEBT=" + graph.globalCanonicalProofDebt());
      System.out.println("RESULT=PASS");
      System.out.println("----------------------------------------------------------------");
    } finally {
      harness.close();
    }
  }

  private static ObligationCreationContext context(
      String id, String bottleneckKey, String bottleneckLabel, int round) {
    return new ObligationCreationContext(
        DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH,
        "route-1",
        "issue-005-convergence-strategy",
        ObligationSourceType.STRATEGY_BLUEPRINT,
        "blueprint://issue-005-convergence/" + id,
        List.of("global"),
        "positive",
        Map.of(),
        bottleneckKey,
        bottleneckLabel,
        BottleneckRelationType.SHARES_UPSTREAM_BOTTLENECK,
        ObligationOccurrenceSchedulingState.ACTIVE,
        round);
  }

  private static void attemptGenericExpansionBatch(
      DesktopResearchCheckpointBlackBoxHarness harness, int round) throws Exception {
    for (String source :
        List.of(
            "generic-inspiration",
            "representation-switch",
            "structural-analogy",
            "new-strategy",
            "unscoped-bridge")) {
      assertThat(
              DesktopProofGraphIssue005BlackBoxSupport.enqueue(
                  harness,
                  source + "-" + round,
                  "route-1",
                  "unrelated-" + source + "-" + round,
                  "DEEPEN"))
          .isFalse();
    }
  }

  private static boolean recoveryTaskSource(String source) {
    String normalized = source == null ? "" : source.toLowerCase(java.util.Locale.ROOT);
    return normalized.contains("focused-recovery")
        || normalized.contains("proof-debt")
        || normalized.contains("meta-review")
        || normalized.contains("family-bridge")
        || normalized.contains("focused-prover")
        || normalized.contains("focused-skeptic")
        || normalized.contains("exact-falsification");
  }

  private static int debtDecreaseEvents(List<ProofGraphRoundMetrics> metrics, double epsilon) {
    int result = 0;
    for (int index = 1; index < metrics.size(); index++) {
      if (metrics.get(index - 1).globalCanonicalProofDebt()
              - metrics.get(index).globalCanonicalProofDebt()
          > epsilon) {
        result++;
      }
    }
    return result;
  }

  private static int falseDebtDecreases(List<ProofGraphRoundMetrics> metrics, double epsilon) {
    int result = 0;
    for (int index = 1; index < metrics.size(); index++) {
      ProofGraphRoundMetrics current = metrics.get(index);
      boolean decreased =
          metrics.get(index - 1).globalCanonicalProofDebt()
                  - current.globalCanonicalProofDebt()
              > epsilon;
      if (decreased && !current.authoritativeProgress()) {
        result++;
      }
    }
    return result;
  }

  private static int duplicatesCountedAsProgress(
      List<ProofGraphRoundMetrics> metrics,
      List<ProofGraphRoundClassification> classifications,
      double epsilon) {
    int result = 0;
    for (int index = 1; index < metrics.size(); index++) {
      ProofGraphRoundMetrics current = metrics.get(index);
      boolean debtDecrease =
          metrics.get(index - 1).globalCanonicalProofDebt()
                  - current.globalCanonicalProofDebt()
              > epsilon;
      if (current.duplicateOccurrences() > 0
          && classifications.get(index) == ProofGraphRoundClassification.PROGRESSING
          && !current.authoritativeProgress()
          && !debtDecrease) {
        result++;
      }
    }
    return result;
  }

  private static void print(String name, long value) {
    System.out.println(name + "=" + value);
  }
}
