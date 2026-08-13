package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeSurface;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactKind;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactRecord;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactStatus;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimSalvageMultiRoundRestoreTest {
  private static final int ROUNDS = 20;
  private static final int RESTORE_ROUND = 10;

  @TempDir Path temporaryDirectory;

  @Test
  void failedRoutesRetainOnlyIndependentlyVerifiedArtifactsAcrossRestore() throws Exception {
    Path runDirectory = temporaryDirectory.resolve("twenty-rounds");
    String runId = "claim-salvage-twenty-rounds";
    DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId);
    String ledgerHashBeforeRestore = "";
    String ledgerHashAfterRestore = "";
    String lifecycleHashBeforeRestore = "";
    String lifecycleHashAfterRestore = "";
    String negativeHashBeforeRestore = "";
    String negativeHashAfterRestore = "";
    int postRestoreArtifactLosses = 0;
    int postRestoreFactLosses = 0;
    int postRestoreStatusChanges = 0;
    int rootHashChanges = 0;
    int rootGoalReplacements = 0;
    int permanentNegativeHashChanges = 0;
    int permanentNegativeCountChanges = 0;
    int laterRouteFactVisibilityFailures = 0;
    int claimStatusRegressions = 0;
    Map<String, AttemptArtifactStatus> observedStatuses = new HashMap<>();
    Map<String, Integer> reviewCalls = new LinkedHashMap<>();
    try {
      harness.freezeAndCreateRoute();
      harness.addCounterexampleTargets();
      String rootHash = harness.rootGoal().sourceStatementHash();
      String permanentNegativeHash = harness.permanentNegativeHash();
      long permanentNegativeCount = harness.permanentNegativeCount();

      for (int round = 0; round < ROUNDS; round++) {
        if (round == RESTORE_ROUND) {
          DesktopSolveCheckpoint checkpoint = harness.checkpointRoundTrip();
          ledgerHashBeforeRestore = harness.attemptArtifacts().ledgerHash();
          lifecycleHashBeforeRestore = CanonicalJson.stableHash(harness.claimLifecycle().snapshot());
          negativeHashBeforeRestore = harness.negativeRegistryHash();
          Set<String> artifactsBefore = artifactIds(harness);
          Set<String> factsBefore = factIds(harness);
          Map<String, AttemptArtifactStatus> statusesBefore = artifactStatuses(harness);
          harness.close();

          harness = DesktopClaimSalvageTestHarness.open(runDirectory, runId);
          harness.restore(checkpoint);
          ledgerHashAfterRestore = harness.attemptArtifacts().ledgerHash();
          lifecycleHashAfterRestore = CanonicalJson.stableHash(harness.claimLifecycle().snapshot());
          negativeHashAfterRestore = harness.negativeRegistryHash();
          postRestoreArtifactLosses += differenceSize(artifactsBefore, artifactIds(harness));
          postRestoreFactLosses += differenceSize(factsBefore, factIds(harness));
          postRestoreStatusChanges += changedStatusCount(statusesBefore, artifactStatuses(harness));
          assertThat(harness.claimReviewRequests()).isEmpty();
          assertThat(ledgerHashAfterRestore).isEqualTo(ledgerHashBeforeRestore);
          assertThat(lifecycleHashAfterRestore).isEqualTo(lifecycleHashBeforeRestore);
          assertThat(negativeHashAfterRestore).isEqualTo(negativeHashBeforeRestore);
        }

        harness.runFailedRound(round);
        reviewCalls.put(
            DesktopClaimSalvageTestHarness.attemptId(round),
            harness.reviewCalls(DesktopClaimSalvageTestHarness.attemptId(round)));

        List<String> futureFacts = harness.futureRouteFactStatements();
        for (int expected = 0; expected <= round; expected++) {
          final int expectedRound = expected;
          if (futureFacts.stream()
              .noneMatch(value -> value.contains("CORRECT_LOCAL_R" + expectedRound))) {
            laterRouteFactVisibilityFailures++;
          }
        }
        laterRouteFactVisibilityFailures +=
            Math.toIntExact(
                futureFacts.stream()
                    .filter(
                        value ->
                            value.contains("FALSE_LOCAL_R")
                                || value.contains("UNSUPPORTED_LOCAL_R")
                                || value.contains("FALSE_ROUTE_THEOREM_R"))
                    .count());

        for (AttemptArtifactRecord artifact : harness.attemptArtifacts().records()) {
          AttemptArtifactStatus previous =
              observedStatuses.put(artifact.artifactId(), artifact.status());
          if (previous != null && terminal(previous) && previous != artifact.status()) {
            claimStatusRegressions++;
          }
        }
        if (!harness.rootGoal().sourceStatementHash().equals(rootHash)) {
          rootHashChanges++;
        }
        if (!harness.rootGoal().sourceStatement().equals(DesktopClaimSalvageTestHarness.SOURCE)
            || !harness.exactStatement().equals(DesktopClaimSalvageTestHarness.SOURCE)) {
          rootGoalReplacements++;
        }
        if (!harness.permanentNegativeHash().equals(permanentNegativeHash)) {
          permanentNegativeHashChanges++;
        }
        if (harness.permanentNegativeCount() != permanentNegativeCount) {
          permanentNegativeCountChanges++;
        }
      }

      List<AttemptArtifactRecord> artifacts = harness.attemptArtifacts().records();
      long harvestedLocalClaims = count(artifacts, AttemptArtifactKind.LOCAL_LEMMA, null);
      long verifiedLocalClaims =
          count(artifacts, AttemptArtifactKind.LOCAL_LEMMA, AttemptArtifactStatus.PROMOTED_FACT);
      long rejectedLocalClaims =
          count(artifacts, AttemptArtifactKind.LOCAL_LEMMA, AttemptArtifactStatus.REJECTED);
      long uncertainLocalClaims =
          count(artifacts, AttemptArtifactKind.LOCAL_LEMMA, AttemptArtifactStatus.UNCERTAIN);
      long harvestedCounterexamples = count(artifacts, AttemptArtifactKind.COUNTEREXAMPLE, null);
      long verifiedCounterexamples =
          count(
              artifacts,
              AttemptArtifactKind.COUNTEREXAMPLE,
              AttemptArtifactStatus.APPLIED_COUNTEREXAMPLE);
      long routeTheoremPromotions =
          count(
              artifacts,
              AttemptArtifactKind.ROUTE_THEOREM,
              AttemptArtifactStatus.PROMOTED_FACT);
      long exactObligationsRefuted =
          harness.proofGraph().obligations().stream()
              .filter(obligation -> obligation.obligationId().startsWith("counter-target-"))
              .filter(obligation -> "refuted".equals(obligation.status()))
              .count();
      long unrelatedObligationsRefuted =
          harness.proofGraph().obligations().stream()
              .filter(obligation -> !obligation.obligationId().startsWith("counter-target-"))
              .filter(obligation -> "refuted".equals(obligation.status()))
              .count();
      long mainGoalClosures =
          "closed".equals(harness.proofGraph().getObligation("main-goal").status()) ? 1L : 0L;
      long localFactPromotions = verifiedLocalClaims;
      long invalidFactPromotions =
          harness.typedMemory().facts().stream()
              .filter(
                  fact ->
                      fact.statement().contains("FALSE_LOCAL_R")
                          || fact.statement().contains("UNSUPPORTED_LOCAL_R")
                          || fact.statement().contains("FALSE_ROUTE_THEOREM_R"))
              .count();
      long failedRouteClaimLosses = failedRouteClaimLosses(harness);
      long duplicateFacts =
          harness.typedMemory().facts().stream()
              .collect(Collectors.groupingBy(value -> value.contentHash(), Collectors.counting()))
              .values().stream()
              .mapToLong(value -> Math.max(0L, value - 1L))
              .sum();
      int duplicateReviewCallsAfterRestore =
          reviewCalls.values().stream().mapToInt(value -> Math.max(0, value - 1)).sum();
      int negativeGateBypasses = negativeGateBypasses(harness, artifacts);

      assertThat(artifacts.stream().map(AttemptArtifactRecord::sourceAttemptId).distinct())
          .hasSize(ROUNDS);
      assertThat(harvestedLocalClaims).isEqualTo(60);
      assertThat(verifiedLocalClaims).isEqualTo(20);
      assertThat(rejectedLocalClaims).isEqualTo(20);
      assertThat(uncertainLocalClaims).isEqualTo(20);
      assertThat(harvestedCounterexamples).isEqualTo(5);
      assertThat(verifiedCounterexamples).isEqualTo(5);
      assertThat(exactObligationsRefuted).isEqualTo(5);
      assertThat(unrelatedObligationsRefuted).isZero();
      assertThat(routeTheoremPromotions).isZero();
      assertThat(mainGoalClosures).isZero();
      assertThat(localFactPromotions).isEqualTo(20);
      assertThat(invalidFactPromotions).isZero();
      assertThat(failedRouteClaimLosses).isZero();
      assertThat(duplicateFacts).isZero();
      assertThat(claimStatusRegressions).isZero();
      assertThat(laterRouteFactVisibilityFailures).isZero();
      assertThat(postRestoreArtifactLosses).isZero();
      assertThat(postRestoreFactLosses).isZero();
      assertThat(postRestoreStatusChanges).isZero();
      assertThat(duplicateReviewCallsAfterRestore).isZero();
      assertThat(rootHashChanges).isZero();
      assertThat(rootGoalReplacements).isZero();
      assertThat(permanentNegativeHashChanges).isZero();
      assertThat(permanentNegativeCountChanges).isZero();
      assertThat(negativeGateBypasses).isZero();

      System.out.println("CLAIM SALVAGE LIFECYCLE DIAGNOSTIC");
      System.out.println("ROUNDS=" + ROUNDS);
      System.out.println("RESTORE_ROUND=" + RESTORE_ROUND);
      System.out.println("FAILED_ROUTE_ATTEMPTS=" + ROUNDS);
      System.out.println("HARVESTED_LOCAL_CLAIMS=" + harvestedLocalClaims);
      System.out.println("VERIFIED_LOCAL_CLAIMS=" + verifiedLocalClaims);
      System.out.println("REJECTED_LOCAL_CLAIMS=" + rejectedLocalClaims);
      System.out.println("UNCERTAIN_LOCAL_CLAIMS=" + uncertainLocalClaims);
      System.out.println("HARVESTED_COUNTEREXAMPLES=" + harvestedCounterexamples);
      System.out.println("VERIFIED_COUNTEREXAMPLES=" + verifiedCounterexamples);
      System.out.println("EXACT_OBLIGATIONS_REFUTED=" + exactObligationsRefuted);
      System.out.println("UNRELATED_OBLIGATIONS_REFUTED=" + unrelatedObligationsRefuted);
      System.out.println("ROUTE_THEOREM_PROMOTIONS=" + routeTheoremPromotions);
      System.out.println("MAIN_GOAL_CLOSURES=" + mainGoalClosures);
      System.out.println("LOCAL_FACT_PROMOTIONS=" + localFactPromotions);
      System.out.println("INVALID_FACT_PROMOTIONS=" + invalidFactPromotions);
      System.out.println("FAILED_ROUTE_CLAIM_LOSSES=" + failedRouteClaimLosses);
      System.out.println("DUPLICATE_FACTS=" + duplicateFacts);
      System.out.println("CLAIM_STATUS_REGRESSIONS=" + claimStatusRegressions);
      System.out.println(
          "LATER_ROUTE_FACT_VISIBILITY_FAILURES=" + laterRouteFactVisibilityFailures);
      System.out.println("POST_RESTORE_ARTIFACT_LOSSES=" + postRestoreArtifactLosses);
      System.out.println("POST_RESTORE_FACT_LOSSES=" + postRestoreFactLosses);
      System.out.println("POST_RESTORE_STATUS_CHANGES=" + postRestoreStatusChanges);
      System.out.println(
          "DUPLICATE_REVIEW_CALLS_AFTER_RESTORE=" + duplicateReviewCallsAfterRestore);
      System.out.println("ROOT_HASH_CHANGES=" + rootHashChanges);
      System.out.println("ROOT_GOAL_REPLACEMENTS=" + rootGoalReplacements);
      System.out.println("NEGATIVE_REGISTRY_HASH_CHANGES=" + permanentNegativeHashChanges);
      System.out.println("PERMANENT_NEGATIVE_COUNT_CHANGES=" + permanentNegativeCountChanges);
      System.out.println("NEGATIVE_GATE_BYPASSES=" + negativeGateBypasses);
      System.out.println("ARTIFACT_LEDGER_HASH_BEFORE_RESTORE=" + ledgerHashBeforeRestore);
      System.out.println("ARTIFACT_LEDGER_HASH_AFTER_RESTORE=" + ledgerHashAfterRestore);
      System.out.println("CLAIM_LIFECYCLE_HASH_BEFORE_RESTORE=" + lifecycleHashBeforeRestore);
      System.out.println("CLAIM_LIFECYCLE_HASH_AFTER_RESTORE=" + lifecycleHashAfterRestore);
      System.out.println("NEGATIVE_REGISTRY_HASH_BEFORE_RESTORE=" + negativeHashBeforeRestore);
      System.out.println("NEGATIVE_REGISTRY_HASH_AFTER_RESTORE=" + negativeHashAfterRestore);
      System.out.println("RESULT=PASS");
    } finally {
      harness.close();
    }
  }

  @Test
  void versionSixCheckpointDefaultsNewClaimSnapshotsWithoutChangingNegativeMemory()
      throws Exception {
    Path runDirectory = temporaryDirectory.resolve("v6-migration");
    String runId = "claim-salvage-v6-migration";
    DesktopSolveCheckpoint versionSix;
    String negativeHash;
    try (DesktopClaimSalvageTestHarness source =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      source.freezeAndCreateRoute();
      source.addCounterexampleTargets();
      source.runFailedRound(0);
      DesktopSolveCheckpoint current = source.checkpointRoundTrip();
      negativeHash = source.negativeRegistryHash();
      ObjectNode json = (ObjectNode) ContractObjectMapper.toTree(current);
      json.put("schemaVersion", 6);
      json.remove("attemptArtifacts");
      json.remove("claimLifecycle");
      versionSix = ContractObjectMapper.read(json, DesktopSolveCheckpoint.class);
    }

    assertThat(versionSix.schemaVersion()).isEqualTo(6);
    assertThat(versionSix.attemptArtifacts().records()).isEmpty();
    assertThat(versionSix.claimLifecycle().entries()).isEmpty();
    try (DesktopClaimSalvageTestHarness restored =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      restored.restore(versionSix);
      assertThat(restored.attemptArtifacts().records()).isEmpty();
      assertThat(restored.claimLifecycle().entries()).isEmpty();
      assertThat(restored.negativeRegistryHash()).isEqualTo(negativeHash);
      assertThat(restored.exactStatement()).isEqualTo(DesktopClaimSalvageTestHarness.SOURCE);
    }
  }

  private static long count(
      List<AttemptArtifactRecord> artifacts,
      AttemptArtifactKind kind,
      AttemptArtifactStatus status) {
    return artifacts.stream()
        .filter(artifact -> artifact.kind() == kind)
        .filter(artifact -> status == null || artifact.status() == status)
        .count();
  }

  private static Set<String> artifactIds(DesktopClaimSalvageTestHarness harness) {
    return harness.attemptArtifacts().records().stream()
        .map(AttemptArtifactRecord::artifactId)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static Set<String> factIds(DesktopClaimSalvageTestHarness harness) {
    return harness.typedMemory().facts().stream()
        .map(value -> value.messageId())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static Map<String, AttemptArtifactStatus> artifactStatuses(
      DesktopClaimSalvageTestHarness harness) {
    return harness.attemptArtifacts().records().stream()
        .collect(
            Collectors.toMap(
                AttemptArtifactRecord::artifactId,
                AttemptArtifactRecord::status,
                (left, right) -> left,
                LinkedHashMap::new));
  }

  private static int differenceSize(Set<String> expected, Set<String> actual) {
    Set<String> missing = new LinkedHashSet<>(expected);
    missing.removeAll(actual);
    return missing.size();
  }

  private static int changedStatusCount(
      Map<String, AttemptArtifactStatus> expected,
      Map<String, AttemptArtifactStatus> actual) {
    return (int)
        expected.entrySet().stream()
            .filter(entry -> actual.get(entry.getKey()) != entry.getValue())
            .count();
  }

  private static boolean terminal(AttemptArtifactStatus status) {
    return status == AttemptArtifactStatus.REJECTED
        || status == AttemptArtifactStatus.UNCERTAIN
        || status == AttemptArtifactStatus.PROMOTED_FACT
        || status == AttemptArtifactStatus.APPLIED_COUNTEREXAMPLE;
  }

  private static long failedRouteClaimLosses(DesktopClaimSalvageTestHarness harness) {
    Set<String> verifiedLemmaIds =
        harness.lemmaMemory().verified().stream()
            .map(ClaimCard::claimId)
            .collect(Collectors.toSet());
    List<String> facts =
        harness.typedMemory().facts().stream().map(value -> value.statement()).toList();
    long losses = 0L;
    for (int round = 0; round < ROUNDS; round++) {
      final int currentRound = round;
      String claimId = "correct-local-" + round;
      boolean artifactPromoted =
          harness.attemptArtifacts().records().stream()
              .anyMatch(
                  artifact ->
                      artifact.claimId().equals(claimId)
                          && artifact.status() == AttemptArtifactStatus.PROMOTED_FACT);
      boolean factPresent =
          facts.stream()
              .anyMatch(statement -> statement.contains("CORRECT_LOCAL_R" + currentRound));
      if (!artifactPromoted || !verifiedLemmaIds.contains(claimId) || !factPresent) {
        losses++;
      }
    }
    return losses;
  }

  private static int negativeGateBypasses(
      DesktopClaimSalvageTestHarness harness, List<AttemptArtifactRecord> artifacts) {
    Map<Integer, Long> requiredByRound = new HashMap<>();
    for (int round = 0; round < ROUNDS; round++) {
      final int currentRound = round;
      long promoted =
          artifacts.stream()
              .filter(
                  artifact ->
                      artifact.sourceAttemptId().equals(
                          DesktopClaimSalvageTestHarness.attemptId(currentRound)))
              .filter(
                  artifact ->
                      artifact.status() == AttemptArtifactStatus.PROMOTED_FACT
                          || artifact.status()
                              == AttemptArtifactStatus.APPLIED_COUNTEREXAMPLE)
              .count();
      requiredByRound.put(round, promoted);
    }
    Map<Integer, Long> auditsByRound =
        harness.typedMemory().negativeKnowledgeRegistry().audit().stream()
            .filter(event -> event.surface() == NegativeKnowledgeSurface.FACT_PROMOTION)
            .collect(Collectors.groupingBy(event -> event.round(), Collectors.counting()));
    return requiredByRound.entrySet().stream()
        .mapToInt(
            entry ->
                (int)
                    Math.max(
                        0L,
                        entry.getValue() - auditsByRound.getOrDefault(entry.getKey(), 0L)))
        .sum();
  }
}
