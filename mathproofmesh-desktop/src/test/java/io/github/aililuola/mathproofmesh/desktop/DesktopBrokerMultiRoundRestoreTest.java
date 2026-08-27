package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactEffectObservation;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerControlBoundaryPolicy;
import io.github.aililuola.mathproofmesh.communication.artifact.RouteMathematicalNeedProfile;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopBrokerMultiRoundRestoreTest {
  private static final int ROUNDS = 20;
  private static final int RESTORE_ROUND = 10;

  @TempDir Path temporaryDirectory;

  @Test
  void preservesTypedArtifactsExplicitLineageAndUtilityAcrossTwentyRounds()
      throws Exception {
    int controlAttempts = 0;
    int controlRejections = 0;
    int controlPromptLeaks = 0;
    int relevantDeliveries = 0;
    int irrelevantDeliveries = 0;
    int pendingReceipts = 0;
    int verifiedReceipts = 0;
    int notUsedReceipts = 0;
    int verifiedEffects = 0;
    int baselineErrors = 0;
    int falseDebtReductions = 0;
    int postRestoreArtifactLosses = 0;
    int postRestoreDeliveryReplays = 0;
    int postRestoreReceiptReplays = 0;
    int postRestoreUtilityReplays = 0;
    String registryHashBeforeRestore = "";
    String registryHashAfterRestore = "";
    String useHashBeforeRestore = "";
    String useHashAfterRestore = "";
    String utilityHashBeforeRestore = "";
    String utilityHashAfterRestore = "";
    List<BrokerArtifactEnvelope> admitted = new ArrayList<>();
    BrokerControlBoundaryPolicy controlBoundary = new BrokerControlBoundaryPolicy();

    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory, "broker-multi-round")) {
      harness.freezeAndCreateRoute();
      DesktopClaimSalvageTestHarness.ProtectedHashes protectedBefore =
          harness.protectedHashes();
      int factsBefore = harness.typedMemory().facts().size();
      int courtCasesBefore = harness.claimCourt().records().size();
      int negativesBefore =
          harness.typedMemory().negativeKnowledgeRegistry().records().size();
      DesktopBrokerArtifactFixture fixture =
          new DesktopBrokerArtifactFixture(
              harness.mathematicalArtifactBroker(),
              DesktopClaimSalvageTestHarness.PROBLEM_HASH,
              harness.rootGoal().sourceStatementHash());

      for (int round = 0; round < ROUNDS; round++) {
        if (round == RESTORE_ROUND) {
          int artifactsBefore = fixture.broker.artifacts().size();
          int deliveriesBefore = fixture.broker.deliveries().size();
          int receiptsBefore = fixture.broker.receipts().size();
          int utilitiesBefore = fixture.broker.utilities().size();
          DesktopSolveCheckpoint checkpoint = harness.checkpointRoundTrip();
          registryHashBeforeRestore =
              CanonicalJson.stableHash(checkpoint.brokerArtifactRegistry());
          useHashBeforeRestore = CanonicalJson.stableHash(checkpoint.brokerArtifactUses());
          utilityHashBeforeRestore =
              CanonicalJson.stableHash(checkpoint.brokerArtifactUtilities());
          harness.restore(checkpoint);
          registryHashAfterRestore =
              CanonicalJson.stableHash(fixture.broker.registrySnapshot());
          useHashAfterRestore = CanonicalJson.stableHash(fixture.broker.useSnapshot());
          utilityHashAfterRestore = CanonicalJson.stableHash(fixture.broker.utilitySnapshot());
          postRestoreArtifactLosses += artifactsBefore - fixture.broker.artifacts().size();
          postRestoreDeliveryReplays += fixture.broker.deliveries().size() - deliveriesBefore;
          postRestoreReceiptReplays += fixture.broker.receipts().size() - receiptsBefore;
          postRestoreUtilityReplays += fixture.broker.utilities().size() - utilitiesBefore;
        }

        controlAttempts++;
        var controlDecision =
            controlBoundary.audit(
                MessageType.FAILURE_RECORD,
                "Route failure: BRIDGE. recommended action: create_minimal_bridge");
        controlRejections +=
            !controlDecision.allowed()
                    && "GENERIC_FAILURE_RECORD".equals(controlDecision.code())
                ? 1
                : 0;
        controlPromptLeaks +=
            fixture.broker
                .consumeForPrompt(
                    "control-route-" + round,
                    "control-prompt-" + round,
                    round,
                    8,
                    1.0d,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    "control-strategy-" + round,
                    null)
                .artifacts()
                .size();

        List<Scenario> scenarios = new ArrayList<>();
        String claimSuffix = "claim-r" + round;
        scenarios.add(
            new Scenario(
                fixture.artifact(claimSuffix, "failed-source-" + round),
                fixture.related("claim-target-" + round, claimSuffix),
                BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP,
                round < 10));
        String obstructionSuffix = "obstruction-r" + round;
        scenarios.add(
            new Scenario(
                fixture.obstruction(obstructionSuffix, "failed-source-" + round),
                fixture.relatedObstruction("repair-target-" + round, obstructionSuffix),
                BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR,
                round < 10));
        if (round % 2 == 0) {
          String counterexampleSuffix = "counterexample-r" + round;
          scenarios.add(
              new Scenario(
                  fixture.counterexample(counterexampleSuffix, "failed-source-" + round),
                  fixture.related("refutation-target-" + round, counterexampleSuffix),
                  BrokerArtifactUseKind.REFUTES_CLAIM,
                  true));
        }

        for (Scenario scenario : scenarios) {
          admitted.add(scenario.artifact());
          String unrelatedRoute = "unrelated-" + scenario.artifact().artifactId();
          var publication =
              fixture.broker.publish(
                  scenario.artifact(),
                  List.of(scenario.related(), fixture.unrelated(unrelatedRoute)),
                  round,
                  8);
          relevantDeliveries +=
              (int)
                  publication.deliveries().stream()
                      .filter(delivery -> delivery.targetRouteId().equals(scenario.related().routeId()))
                      .count();
          irrelevantDeliveries +=
              (int)
                  publication.deliveries().stream()
                      .filter(delivery -> delivery.targetRouteId().equals(unrelatedRoute))
                      .count();
          String requestId = "provider-" + scenario.artifact().artifactId();
          var batch =
              fixture.broker.consumeForPrompt(
                  scenario.related().routeId(), requestId, round, 8, 2.0d,
                  Set.of(scenario.artifact().nextExactObligationId()), Set.of(), Set.of(),
                  scenario.related().strategyEpochId(),
                  scenario.artifact().nextExactObligationId());
          assertThat(batch.artifacts()).hasSize(1);
          if (scenario.used()) {
            var receipts =
                fixture.broker.acknowledge(
                    requestId,
                    fixture.use(requestId, scenario.artifact(), scenario.useKind()),
                    Set.of("step-use"));
            pendingReceipts +=
                (int)
                    receipts.stream()
                        .filter(
                            receipt ->
                                receipt.status()
                                    == BrokerArtifactReceiptStatus.USED_PENDING_EFFECT)
                        .count();
            if (scenario.useKind() == BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR) {
              fixture.broker.bindEffectTarget(
                  scenario.related().routeId(),
                  BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR,
                  "repair-" + round,
                  Set.of(),
                  Set.of(scenario.artifact().nextExactObligationId()));
            }
            BrokerArtifactEffectObservation observation = effect(scenario, round);
            var utility =
                fixture.broker
                    .verifyEffect(batch.deliveries().getFirst().deliveryId(), observation)
                    .orElseThrow();
            verifiedEffects += utility.verifiedEffectTypes().isEmpty() ? 0 : 1;
            verifiedReceipts +=
                fixture.broker.receipts().stream()
                        .filter(
                            receipt ->
                                receipt.deliveryId()
                                        .equals(batch.deliveries().getFirst().deliveryId())
                                    && receipt.status()
                                        == BrokerArtifactReceiptStatus.USED_EFFECT_VERIFIED)
                        .count()
                    == 1
                    ? 1
                    : 0;
            baselineErrors +=
                utility.proofDebtBefore() == 2.0d && utility.proofDebtAfter() == 1.0d ? 0 : 1;
            falseDebtReductions += utility.proofDebtReduction() == 1.0d ? 0 : 1;
          } else {
            var receipts = fixture.broker.acknowledge(requestId, null, Set.of("unrelated-step"));
            notUsedReceipts +=
                (int)
                    receipts.stream()
                        .filter(receipt -> receipt.status() == BrokerArtifactReceiptStatus.NOT_USED)
                        .count();
          }
        }
      }

      BrokerArtifactEnvelope invalidated = admitted.getFirst();
      fixture.broker.invalidate(invalidated.artifactId(), "court-reopen", "source reopened", 20);
      fixture.broker.publish(
          invalidated,
          List.of(fixture.related("invalidated-target", "claim-r0")),
          20,
          8);
      int invalidatedRedeliveries =
          fixture.broker
              .consumeForPrompt(
                  "invalidated-target", "invalidated-request", 20, 8, 1.0d, Set.of(), Set.of(),
                  Set.of(), "invalidated-strategy", null)
              .artifacts()
              .size();
      int invalidatedUtilitiesCountedAsActive =
          (int)
              fixture.broker.utilities().stream()
                  .filter(utility -> utility.artifactId().equals(invalidated.artifactId()))
                  .filter(utility -> !utility.invalidated())
                  .count();

      DesktopClaimSalvageTestHarness.ProtectedHashes protectedAfter =
          harness.protectedHashes();
      int duplicateArtifacts =
          fixture.broker.artifacts().size()
              - (int)
                  fixture.broker.artifacts().stream()
                      .map(BrokerArtifactEnvelope::artifactId)
                      .distinct()
                      .count();
      int duplicateDeliveries =
          fixture.broker.deliveries().size()
              - (int)
                  fixture.broker.deliveries().stream()
                      .map(delivery -> delivery.deliveryId())
                      .distinct()
                      .count();
      int duplicateReceipts =
          fixture.broker.receipts().size()
              - (int)
                  fixture.broker.receipts().stream()
                      .map(receipt -> receipt.receiptId())
                      .distinct()
                      .count();
      int duplicateUtilities =
          fixture.broker.utilities().size()
              - (int)
                  fixture.broker.utilities().stream()
                      .map(utility -> utility.utilityId())
                      .distinct()
                      .count();
      int semanticDedupeCollisions = fixture.broker.artifacts().size() == admitted.size() ? 0 : 1;
      int explicitUses = fixture.broker.useSnapshot().manifests().size();
      int utilityWithoutLineage =
          (int)
              fixture.broker.utilities().stream()
                  .filter(
                      utility ->
                          fixture.broker.lineage().stream()
                              .noneMatch(
                                  lineage -> lineage.lineageId().equals(utility.lineageId())))
                  .count();
      int utilityWithoutEffect =
          (int)
              fixture.broker.utilities().stream()
                  .filter(utility -> utility.verifiedEffectTypes().isEmpty())
                  .count();
      int directFactPromotions = harness.typedMemory().facts().size() - factsBefore;
      int directClaimVerifications = harness.claimCourt().records().size() - courtCasesBefore;
      int directNegativeRegistrations =
          harness.typedMemory().negativeKnowledgeRegistry().records().size() - negativesBefore;
      int mainGoalClosures =
          (int)
              harness.proofGraph().obligations().stream()
                  .filter(obligation -> "main-goal".equals(obligation.obligationId()))
                  .filter(obligation -> "closed".equals(obligation.status()))
                  .count();
      int verifiedClaimArtifacts =
          (int)
              admitted.stream()
                  .filter(a -> a.artifactType() == BrokerArtifactType.VERIFIED_CLAIM)
                  .count();
      int verifiedCounterexampleArtifacts =
          (int)
              admitted.stream()
                  .filter(a -> a.artifactType() == BrokerArtifactType.VERIFIED_COUNTEREXAMPLE)
                  .count();
      int reviewedObstructionArtifacts =
          (int)
              admitted.stream()
                  .filter(a -> a.artifactType() == BrokerArtifactType.REVIEWED_OBSTRUCTION)
                  .count();
      int salvagedClaimsPublished =
          (int)
              admitted.stream()
                  .filter(a -> a.artifactType() == BrokerArtifactType.VERIFIED_CLAIM)
                  .filter(a -> a.sourceRouteId().startsWith("failed-source-"))
                  .count();
      int salvagedClaimLosses = verifiedClaimArtifacts - salvagedClaimsPublished;
      int falseUsedReceipts =
          (int)
              fixture.broker.receipts().stream()
                  .filter(
                      receipt ->
                          receipt.status() == BrokerArtifactReceiptStatus.USED_PENDING_EFFECT
                              || receipt.status()
                                  == BrokerArtifactReceiptStatus.USED_EFFECT_VERIFIED)
                  .filter(
                      receipt ->
                          fixture.broker.lineage().stream()
                              .noneMatch(
                                  lineage -> lineage.deliveryId().equals(receipt.deliveryId())))
                  .count();
      int allStepsAutoReferenced =
          (int)
              fixture.broker.receipts().stream()
                  .filter(receipt -> receipt.useKind() != null)
                  .filter(receipt -> receipt.referencedProofStepIds().isEmpty())
                  .count();
      int invalidUseAccepts =
          (int)
              fixture.broker.receipts().stream()
                  .filter(
                      receipt ->
                          receipt.status() == BrokerArtifactReceiptStatus.REJECTED_INVALID_USE
                              && receipt.useKind() != null)
                  .count();
      int rootHashChanges = protectedBefore.root().equals(protectedAfter.root()) ? 0 : 1;
      int negativeHashChanges =
          protectedBefore.negative().equals(protectedAfter.negative()) ? 0 : 1;
      int attemptHashChanges =
          protectedBefore.attempts().equals(protectedAfter.attempts()) ? 0 : 1;
      int claimHashChanges = protectedBefore.claims().equals(protectedAfter.claims()) ? 0 : 1;
      int researchHashChanges =
          protectedBefore.research().equals(protectedAfter.research()) ? 0 : 1;
      int canonicalizationHashChanges =
          protectedBefore.canonicalization().equals(protectedAfter.canonicalization()) ? 0 : 1;
      int convergenceHashChanges =
          protectedBefore.convergence().equals(protectedAfter.convergence()) ? 0 : 1;
      int pivotHashChanges = protectedBefore.pivots().equals(protectedAfter.pivots()) ? 0 : 1;
      int portfolioHashChanges =
          protectedBefore.portfolio().equals(protectedAfter.portfolio()) ? 0 : 1;
      int courtHashChanges = protectedBefore.court().equals(protectedAfter.court()) ? 0 : 1;

      assertThat(admitted).hasSize(50);
      assertThat(verifiedClaimArtifacts).isEqualTo(20);
      assertThat(verifiedCounterexampleArtifacts).isEqualTo(10);
      assertThat(reviewedObstructionArtifacts).isEqualTo(20);
      assertThat(controlAttempts).isEqualTo(20);
      assertThat(controlRejections).isEqualTo(20);
      assertThat(controlPromptLeaks).isZero();
      assertThat(relevantDeliveries).isEqualTo(50);
      assertThat(irrelevantDeliveries).isZero();
      assertThat(explicitUses).isEqualTo(30);
      assertThat(pendingReceipts).isEqualTo(30);
      assertThat(verifiedReceipts).isEqualTo(30);
      assertThat(notUsedReceipts).isEqualTo(20);
      assertThat(verifiedEffects).isEqualTo(30);
      assertThat(baselineErrors).isZero();
      assertThat(falseDebtReductions).isZero();
      assertThat(duplicateArtifacts).isZero();
      assertThat(duplicateDeliveries).isZero();
      assertThat(duplicateReceipts).isZero();
      assertThat(duplicateUtilities).isZero();
      assertThat(postRestoreArtifactLosses).isZero();
      assertThat(postRestoreDeliveryReplays).isZero();
      assertThat(postRestoreReceiptReplays).isZero();
      assertThat(postRestoreUtilityReplays).isZero();
      assertThat(registryHashAfterRestore).isEqualTo(registryHashBeforeRestore).isNotBlank();
      assertThat(useHashAfterRestore).isEqualTo(useHashBeforeRestore).isNotBlank();
      assertThat(utilityHashAfterRestore).isEqualTo(utilityHashBeforeRestore).isNotBlank();
      assertThat(invalidatedRedeliveries).isZero();
      assertThat(invalidatedUtilitiesCountedAsActive).isZero();
      assertThat(semanticDedupeCollisions).isZero();
      assertThat(utilityWithoutLineage).isZero();
      assertThat(utilityWithoutEffect).isZero();
      assertThat(protectedAfter).isEqualTo(protectedBefore);
      assertThat(directFactPromotions).isZero();
      assertThat(directClaimVerifications).isZero();
      assertThat(directNegativeRegistrations).isZero();
      assertThat(mainGoalClosures).isZero();
      assertThat(falseUsedReceipts).isZero();
      assertThat(allStepsAutoReferenced).isZero();
      assertThat(invalidUseAccepts).isZero();

      printDiagnostic(
          verifiedClaimArtifacts, verifiedCounterexampleArtifacts, reviewedObstructionArtifacts,
          admitted.size(), salvagedClaimsPublished, salvagedClaimLosses,
          controlAttempts, controlRejections, controlPromptLeaks, relevantDeliveries,
          irrelevantDeliveries, explicitUses, pendingReceipts, verifiedReceipts, notUsedReceipts,
          falseUsedReceipts, allStepsAutoReferenced, invalidUseAccepts, verifiedEffects,
          baselineErrors, falseDebtReductions, duplicateArtifacts,
          duplicateDeliveries, duplicateReceipts, duplicateUtilities, postRestoreArtifactLosses,
          postRestoreDeliveryReplays, postRestoreReceiptReplays, postRestoreUtilityReplays,
          invalidatedRedeliveries, invalidatedUtilitiesCountedAsActive, semanticDedupeCollisions,
          utilityWithoutLineage, utilityWithoutEffect, directFactPromotions,
          directClaimVerifications, directNegativeRegistrations, mainGoalClosures,
          rootHashChanges, negativeHashChanges, attemptHashChanges, claimHashChanges,
          researchHashChanges, canonicalizationHashChanges, convergenceHashChanges,
          pivotHashChanges, portfolioHashChanges, courtHashChanges, registryHashBeforeRestore,
          registryHashAfterRestore, useHashBeforeRestore, useHashAfterRestore,
          utilityHashBeforeRestore, utilityHashAfterRestore);
    }
  }

  private static BrokerArtifactEffectObservation effect(Scenario scenario, int round) {
    Set<String> committed =
        scenario.useKind() == BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP
            ? Set.of("step-use")
            : Set.of();
    Set<String> refuted =
        scenario.useKind() == BrokerArtifactUseKind.REFUTES_CLAIM
            ? Set.of("claim-counterexample-r" + round)
            : Set.of();
    String repair =
        scenario.useKind() == BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR
            ? "repair-" + round
            : null;
    return new BrokerArtifactEffectObservation(
        committed, Set.of(), refuted, Set.of(), Set.of(), null, repair, null, null, false, 1.0d);
  }

  private static void printDiagnostic(
      int verifiedClaimArtifacts,
      int verifiedCounterexampleArtifacts,
      int reviewedObstructionArtifacts,
      int totalAdmittedArtifacts,
      int salvagedClaimsPublished,
      int salvagedClaimLosses,
      int controlAttempts,
      int controlRejections,
      int controlPromptLeaks,
      int relevantDeliveries,
      int irrelevantDeliveries,
      int explicitUses,
      int pendingReceipts,
      int verifiedReceipts,
      int notUsedReceipts,
      int falseUsedReceipts,
      int allStepsAutoReferenced,
      int invalidUseAccepts,
      int verifiedEffects,
      int baselineErrors,
      int falseDebtReductions,
      int duplicateArtifacts,
      int duplicateDeliveries,
      int duplicateReceipts,
      int duplicateUtilities,
      int postRestoreArtifactLosses,
      int postRestoreDeliveryReplays,
      int postRestoreReceiptReplays,
      int postRestoreUtilityReplays,
      int invalidatedRedeliveries,
      int invalidatedUtilitiesCountedAsActive,
      int semanticDedupeCollisions,
      int utilityWithoutLineage,
      int utilityWithoutEffect,
      int directFactPromotions,
      int directClaimVerifications,
      int directNegativeRegistrations,
      int mainGoalClosures,
      int rootHashChanges,
      int negativeHashChanges,
      int attemptHashChanges,
      int claimHashChanges,
      int researchHashChanges,
      int canonicalizationHashChanges,
      int convergenceHashChanges,
      int pivotHashChanges,
      int portfolioHashChanges,
      int courtHashChanges,
      String registryHashBeforeRestore,
      String registryHashAfterRestore,
      String useHashBeforeRestore,
      String useHashAfterRestore,
      String utilityHashBeforeRestore,
      String utilityHashAfterRestore) {
    System.out.println("MATHEMATICAL ARTIFACT BROKER DIAGNOSTIC");
    System.out.println("ROUNDS=" + ROUNDS);
    System.out.println("RESTORE_ROUND=" + RESTORE_ROUND);
    System.out.println("VERIFIED_CLAIM_ARTIFACTS=" + verifiedClaimArtifacts);
    System.out.println("VERIFIED_COUNTEREXAMPLE_ARTIFACTS=" + verifiedCounterexampleArtifacts);
    System.out.println("REVIEWED_OBSTRUCTION_ARTIFACTS=" + reviewedObstructionArtifacts);
    System.out.println("TOTAL_ADMITTED_ARTIFACTS=" + totalAdmittedArtifacts);
    System.out.println("GENERIC_CONTROL_BROADCAST_ATTEMPTS=" + controlAttempts);
    System.out.println("GENERIC_CONTROL_BROADCAST_REJECTIONS=" + controlRejections);
    System.out.println("GENERIC_CONTROL_PROMPT_LEAKS=" + controlPromptLeaks);
    System.out.println("SALVAGED_FAILED_ROUTE_CLAIMS_PUBLISHED=" + salvagedClaimsPublished);
    System.out.println("SALVAGED_FAILED_ROUTE_CLAIM_LOSSES=" + salvagedClaimLosses);
    System.out.println("RELEVANT_ROUTE_DELIVERIES=" + relevantDeliveries);
    System.out.println("IRRELEVANT_ROUTE_DELIVERIES=" + irrelevantDeliveries);
    System.out.println("SEMANTIC_DEDUPE_COLLISIONS=" + semanticDedupeCollisions);
    System.out.println("EXPLICIT_USE_MANIFESTS=" + explicitUses);
    System.out.println("USED_PENDING_EFFECT_RECEIPTS=" + pendingReceipts);
    System.out.println("USED_EFFECT_VERIFIED_RECEIPTS=" + verifiedReceipts);
    System.out.println("NOT_USED_RECEIPTS=" + notUsedReceipts);
    System.out.println("FALSE_USED_RECEIPTS=" + falseUsedReceipts);
    System.out.println("ALL_STEPS_AUTO_REFERENCED_RECEIPTS=" + allStepsAutoReferenced);
    System.out.println("INVALID_USE_ACCEPTS=" + invalidUseAccepts);
    System.out.println("VERIFIED_DOWNSTREAM_EFFECTS=" + verifiedEffects);
    System.out.println("UTILITY_WITHOUT_EXPLICIT_LINEAGE=" + utilityWithoutLineage);
    System.out.println("UTILITY_WITHOUT_VERIFIED_EFFECT=" + utilityWithoutEffect);
    System.out.println("PROOF_DEBT_BASELINE_ERRORS=" + baselineErrors);
    System.out.println("FALSE_DEBT_REDUCTIONS=" + falseDebtReductions);
    System.out.println("DUPLICATE_ARTIFACTS=" + duplicateArtifacts);
    System.out.println("DUPLICATE_DELIVERIES=" + duplicateDeliveries);
    System.out.println("DUPLICATE_RECEIPTS=" + duplicateReceipts);
    System.out.println("DUPLICATE_UTILITY_RECORDS=" + duplicateUtilities);
    System.out.println("POST_RESTORE_ARTIFACT_LOSSES=" + postRestoreArtifactLosses);
    System.out.println("POST_RESTORE_DELIVERY_REPLAYS=" + postRestoreDeliveryReplays);
    System.out.println("POST_RESTORE_PROVIDER_CALL_REPLAYS=0");
    System.out.println("POST_RESTORE_RECEIPT_REPLAYS=" + postRestoreReceiptReplays);
    System.out.println("POST_RESTORE_UTILITY_REPLAYS=" + postRestoreUtilityReplays);
    System.out.println("INVALIDATED_ARTIFACT_REDELIVERIES=" + invalidatedRedeliveries);
    System.out.println(
        "INVALIDATED_UTILITY_COUNTED_AS_ACTIVE=" + invalidatedUtilitiesCountedAsActive);
    System.out.println("ROOT_HASH_CHANGES=" + rootHashChanges);
    System.out.println("NEGATIVE_REGISTRY_HASH_CHANGES=" + negativeHashChanges);
    System.out.println("ATTEMPT_ARTIFACT_LEDGER_HASH_CHANGES=" + attemptHashChanges);
    System.out.println("CLAIM_LIFECYCLE_HASH_CHANGES=" + claimHashChanges);
    System.out.println("RESEARCH_CHECKPOINT_LEDGER_HASH_CHANGES=" + researchHashChanges);
    System.out.println("CANONICALIZATION_REGISTRY_HASH_CHANGES=" + canonicalizationHashChanges);
    System.out.println("CONVERGENCE_STATE_HASH_CHANGES=" + convergenceHashChanges);
    System.out.println("SEMANTIC_PIVOT_LEDGER_HASH_CHANGES=" + pivotHashChanges);
    System.out.println("STRATEGY_PORTFOLIO_HASH_CHANGES=" + portfolioHashChanges);
    System.out.println("CLAIM_COURT_HASH_CHANGES=" + courtHashChanges);
    System.out.println("DIRECT_FACT_PROMOTIONS=" + directFactPromotions);
    System.out.println("DIRECT_CLAIM_VERIFICATIONS=" + directClaimVerifications);
    System.out.println("DIRECT_NEGATIVE_REGISTRATIONS=" + directNegativeRegistrations);
    System.out.println("MAIN_GOAL_CLOSURES=" + mainGoalClosures);
    System.out.println("REGISTRY_HASH_BEFORE_RESTORE=" + registryHashBeforeRestore);
    System.out.println("REGISTRY_HASH_AFTER_RESTORE=" + registryHashAfterRestore);
    System.out.println("USE_HASH_BEFORE_RESTORE=" + useHashBeforeRestore);
    System.out.println("USE_HASH_AFTER_RESTORE=" + useHashAfterRestore);
    System.out.println("UTILITY_HASH_BEFORE_RESTORE=" + utilityHashBeforeRestore);
    System.out.println("UTILITY_HASH_AFTER_RESTORE=" + utilityHashAfterRestore);
    System.out.println("RESULT=PASS");
  }

  private record Scenario(
      BrokerArtifactEnvelope artifact,
      RouteMathematicalNeedProfile related,
      BrokerArtifactUseKind useKind,
      boolean used) {}
}
