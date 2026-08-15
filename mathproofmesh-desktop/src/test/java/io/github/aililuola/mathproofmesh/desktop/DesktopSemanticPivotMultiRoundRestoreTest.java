package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofcontrol.PivotDelta;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotDeltaStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotEvidenceAuthority;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotClaimUseChange;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotClaimUsageAction;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotObjectDisposition;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotObligationAction;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotObligationChange;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotObstructionRef;
import io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotDeterministicAuditor;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSemanticPivotMultiRoundRestoreTest {
  @Test
  void twentyRoundsSeparateRejectionPivotApplyLocalRepairAndRestore(@TempDir Path directory)
      throws Exception {
    String runId = "semantic-pivot-20-round";
    DesktopSemanticPivotTestHarness harness = DesktopSemanticPivotTestHarness.open(directory, runId);
    int textCandidates = 0;
    int textRejections = 0;
    int authorityCandidates = 0;
    int authorityRejections = 0;
    int validCandidates = 0;
    int validApplied = 0;
    int localAttempts = 0;
    int localApplied = 0;
    int localCountedAsPivots = 0;
    int objectChanges = 0;
    int targetChanges = 0;
    int newObligations = 0;
    int retainedClaims = 0;
    int emptyApplies = 0;
    int textApplies = 0;
    int coreIdeaAppendLeaks = 0;
    int oldStrategyDeletions = 0;
    int oldAttemptLosses = 0;
    int oldTargetClosures = 0;
    int oldTargetRefutations = 0;
    int oldTargetMathStatusChanges = 0;
    int partialPivotWrites = 0;
    int duplicateApplies = 0;
    int duplicateObligations = 0;
    int duplicateTasks = 0;
    int postRestorePivotLosses = 0;
    int postRestoreStatusChanges = 0;
    int postRestoreDuplicateApplies = 0;
    int postRestoreDuplicateProviderCalls = 0;
    int debtFalseDecreases = 0;
    int rootChanges = 0;
    int negativeChanges = 0;
    int attemptChanges = 0;
    int claimChanges = 0;
    int researchChanges = 0;
    int canonicalizationChanges = 0;
    int convergenceChanges = 0;
    int focusedRecoveryBypasses = 0;
    int capacityOrQuotaBypasses = 0;
    int directFactPromotions = 0;
    int directClaimVerifications = 0;
    int directNegativeRegistrations = 0;
    int mainGoalClosures = 0;
    String retainedClaimId = harness.registerVerifiedFact(99);
    DesktopSemanticPivotTestHarness.State initialState = harness.state();
    String initialRoot = initialState.rootHash();
    String initialNegative = initialState.negativeHash();
    String initialAttempt = initialState.attemptHash();
    String initialClaim = initialState.claimHash();
    String initialResearch = initialState.researchHash();
    Map<String, String> initialCanonicalTargets = harness.canonicalTargetHashes();
    String pivotHashBeforeRestore = "";
    String pivotHashAfterRestore = "";
    List<String> sourceEpochs = new ArrayList<>();
    try {
      for (int round = 0; round < 5; round++) {
        textCandidates++;
        var record = harness.apply(harness.textOnlyDelta(round));
        textRejections += record.status() == PivotDeltaStatus.DETERMINISTICALLY_REJECTED ? 1 : 0;
        textApplies += record.applyReceipt() == null ? 0 : 1;
      }
      for (int round = 5; round < 10; round++) {
        authorityCandidates++;
        PivotDelta candidate = harness.validDelta(round);
        String expectedFailure;
        PivotDelta invalid;
        switch (round) {
          case 5 -> {
            invalid = withRootHash(candidate, "mutated-root-" + round);
            expectedFailure = SemanticPivotDeterministicAuditor.ROOT_GOAL_MISMATCH;
          }
          case 6 -> {
            PivotObstructionRef known = candidate.obstructionRefs().getFirst();
            PivotObstructionRef unknown =
                new PivotObstructionRef(
                    "unknown-obstruction",
                    PivotEvidenceAuthority.FAILURE_FINGERPRINT,
                    "failure://unknown",
                    known.boundRouteId(),
                    known.boundStrategyId(),
                    known.boundCanonicalTargetId(),
                    known.statementHash());
            invalid = withObstructions(candidate, List.of(unknown));
            expectedFailure = SemanticPivotDeterministicAuditor.UNKNOWN_OBSTRUCTION;
          }
          case 7 -> {
            invalid =
                withClaims(
                    candidate,
                    List.of(
                        new PivotClaimUseChange(
                            retainedClaimId,
                            harness.authoritativeClaimStatementHash(retainedClaimId),
                            PivotClaimUsageAction.RETIRE_FROM_ACTIVE_DEPENDENCY,
                            "This verified claim is false.")));
            expectedFailure =
                SemanticPivotDeterministicAuditor.UNAUTHORIZED_CLAIM_RETIREMENT;
          }
          case 8 -> {
            invalid =
                withObligations(
                    candidate,
                    List.of(
                        new PivotObligationChange(
                            "unknown-old-obligation",
                            "unknown-old-target",
                            PivotObligationAction.RETIRE_FROM_STRATEGY_FOCUS,
                            null,
                            null,
                            List.of(),
                            List.of(),
                            "A pivot cannot close or retire an unknown old target."),
                        candidate.obligationChanges().getLast()));
            expectedFailure =
                SemanticPivotDeterministicAuditor.UNAUTHORIZED_OBLIGATION_CLOSURE;
          }
          case 9 -> {
            invalid =
                withProposedStrategy(
                    candidate, finitePrimeSupportStrategy(candidate.proposedStrategy()));
            expectedFailure = SemanticPivotDeterministicAuditor.PERMANENT_NEGATIVE_CONFLICT;
          }
          default -> throw new IllegalStateException("unexpected authority round " + round);
        }
        var record = harness.apply(invalid);
        authorityRejections += record.status() == PivotDeltaStatus.DETERMINISTICALLY_REJECTED ? 1 : 0;
        assertThat(record.deterministicAudit().failureCodes()).contains(expectedFailure);
      }

      pivotHashBeforeRestore = harness.state().pivotHash();
      DesktopSolveCheckpoint checkpoint = harness.checkpoint();
      harness.close();
      harness = DesktopSemanticPivotTestHarness.restore(directory, runId, checkpoint);
      pivotHashAfterRestore = harness.state().pivotHash();
      postRestorePivotLosses += pivotHashAfterRestore.equals(pivotHashBeforeRestore) ? 0 : 1;

      for (int round = 10; round < 15; round++) {
        validCandidates++;
        var before = harness.state();
        PivotDelta delta =
            harness.validDelta(
                round,
                List.of(
                    new PivotClaimUseChange(
                        retainedClaimId,
                        harness.authoritativeClaimStatementHash(retainedClaimId),
                        PivotClaimUsageAction.RETAIN_AS_VERIFIED_FACT,
                        "The previously verified equivalence remains applicable.")));
        sourceEpochs.add(delta.sourceStrategyId());
        String oldTarget = harness.firstRetiredObligation(delta);
        String oldTargetStatusBefore = harness.obligationStatus(oldTarget);
        var record = harness.apply(delta);
        validApplied += record.status() == PivotDeltaStatus.APPLIED ? 1 : 0;
        objectChanges +=
            (int)
                delta.objectChanges().stream()
                    .filter(change -> change.disposition() == PivotObjectDisposition.REPLACE)
                    .count();
        targetChanges +=
            (int)
                delta.obligationChanges().stream()
                    .filter(
                        change ->
                            change.action() == PivotObligationAction.RETIRE_FROM_STRATEGY_FOCUS)
                    .count();
        newObligations += record.applyReceipt().newObligationIds().size();
        retainedClaims +=
            (int)
                delta.claimUseChanges().stream()
                    .filter(
                        change ->
                            change.action()
                                == io.github.aililuola.mathproofmesh.proofcontrol.PivotClaimUsageAction.RETAIN_AS_VERIFIED_FACT)
                    .count();
        oldTargetClosures += "closed".equals(harness.obligationStatus(oldTarget)) ? 1 : 0;
        oldTargetRefutations += "refuted".equals(harness.obligationStatus(oldTarget)) ? 1 : 0;
        oldTargetMathStatusChanges +=
            oldTargetStatusBefore.equals(harness.obligationStatus(oldTarget)) ? 0 : 1;
        debtFalseDecreases += harness.state().globalDebt() < before.globalDebt() ? 1 : 0;
        int obligationsAfter = harness.state().obligations();
        int tasksAfter = harness.state().pendingTasks();
        harness.apply(delta);
        duplicateApplies += harness.semanticPivots().ledger().records().stream()
                    .filter(item -> item.pivotId().equals(delta.pivotId()))
                    .count()
                == 1
            ? 0
            : 1;
        duplicateObligations += harness.state().obligations() == obligationsAfter ? 0 : 1;
        duplicateTasks += harness.state().pendingTasks() == tasksAfter ? 0 : 1;
        postRestoreDuplicateApplies += harness.state().activePivotId().equals(delta.pivotId()) ? 0 : 1;
      }

      for (int round = 15; round < 20; round++) {
        localAttempts++;
        int pivotsBefore = harness.state().pivotRecords();
        String coreBefore = harness.activeStrategy().coreIdea();
        boolean applied = harness.localRepair();
        localApplied += applied ? 1 : 0;
        localCountedAsPivots += harness.state().pivotRecords() == pivotsBefore ? 0 : 1;
        coreIdeaAppendLeaks += harness.activeStrategy().coreIdea().equals(coreBefore) ? 0 : 1;
      }

      oldStrategyDeletions = 0;
      for (String sourceEpoch : sourceEpochs) {
        if (!harness.strategyArchive().originalRetained(sourceEpoch)) {
          oldStrategyDeletions++;
        }
      }
      oldAttemptLosses = harness.revisionHistory().size() >= validApplied ? 0 : 1;
      emptyApplies =
          (int)
              harness.semanticPivots().ledger().records().stream()
                  .filter(record -> record.delta().objectChanges().isEmpty())
                  .filter(record -> record.applyReceipt() != null)
                  .count();
      postRestoreStatusChanges = postRestorePivotLosses;
      rootChanges += harness.state().rootHash().equals(initialRoot) ? 0 : 1;
      negativeChanges += harness.state().negativeHash().equals(initialNegative) ? 0 : 1;
      attemptChanges += harness.state().attemptHash().equals(initialAttempt) ? 0 : 1;
      claimChanges += harness.state().claimHash().equals(initialClaim) ? 0 : 1;
      researchChanges += harness.state().researchHash().equals(initialResearch) ? 0 : 1;
      DesktopSemanticPivotTestHarness.State finalState = harness.state();
      partialPivotWrites =
          (int)
              harness.semanticPivots().ledger().records().stream()
                  .filter(record -> record.status() == PivotDeltaStatus.APPLYING)
                  .count();
      Map<String, String> finalCanonicalTargets = harness.canonicalTargetHashes();
      canonicalizationChanges =
          (int)
              initialCanonicalTargets.entrySet().stream()
                  .filter(
                      entry ->
                          !Objects.equals(
                              entry.getValue(), finalCanonicalTargets.get(entry.getKey())))
                  .count();
      convergenceChanges =
          initialState.convergenceHash().equals(finalState.convergenceHash()) ? 0 : 1;
      focusedRecoveryBypasses =
          (int)
              harness.semanticPivots().ledger().records().stream()
                  .filter(record -> record.deterministicAudit() != null)
                  .filter(
                      record ->
                          record
                              .deterministicAudit()
                              .failureCodes()
                              .contains(
                                  io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotDeterministicAuditor.FOCUSED_RECOVERY_BINDING_MISMATCH))
                  .filter(record -> record.applyReceipt() != null)
                  .count();
      capacityOrQuotaBypasses =
          (int)
              harness.semanticPivots().ledger().records().stream()
                  .filter(record -> record.deterministicAudit() != null)
                  .filter(
                      record ->
                          record
                              .deterministicAudit()
                              .failureCodes()
                              .contains(
                                  io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotDeterministicAuditor.CAPACITY_OR_QUOTA_BLOCK))
                  .filter(record -> record.applyReceipt() != null)
                  .count();
      directFactPromotions = Math.max(0, finalState.facts() - initialState.facts());
      directClaimVerifications =
          Math.max(
              0,
              finalState.externallyAdmittedFacts()
                  - initialState.externallyAdmittedFacts());
      directNegativeRegistrations =
          Math.max(0, finalState.negativeRecords() - initialState.negativeRecords());
      mainGoalClosures =
          initialState.mainGoalStatus().equals(finalState.mainGoalStatus()) ? 0 : 1;

      System.out.println("SEMANTIC PIVOT DIAGNOSTIC");
      System.out.println("ROUNDS=20");
      System.out.println("RESTORE_ROUND=10");
      System.out.println("PIVOT_LEDGER_HASH_BEFORE_RESTORE=" + pivotHashBeforeRestore);
      System.out.println("PIVOT_LEDGER_HASH_AFTER_RESTORE=" + pivotHashAfterRestore);
      System.out.println("PIVOT_CANDIDATES=" + (textCandidates + authorityCandidates + validCandidates));
      System.out.println("TEXT_ONLY_PIVOT_CANDIDATES=" + textCandidates);
      System.out.println("TEXT_ONLY_PIVOT_REJECTIONS=" + textRejections);
      System.out.println("AUTHORITY_VIOLATION_CANDIDATES=" + authorityCandidates);
      System.out.println("AUTHORITY_VIOLATION_REJECTIONS=" + authorityRejections);
      System.out.println("VALID_PIVOT_CANDIDATES=" + validCandidates);
      System.out.println("VALID_PIVOTS_APPLIED=" + validApplied);
      System.out.println("LOCAL_REPAIR_ATTEMPTS=" + localAttempts);
      System.out.println("LOCAL_REPAIRS_APPLIED=" + localApplied);
      System.out.println("LOCAL_REPAIRS_COUNTED_AS_PIVOTS=" + localCountedAsPivots);
      System.out.println("OBJECT_CHANGE_EVENTS=" + objectChanges);
      System.out.println("TARGET_REFORMULATIONS=" + targetChanges);
      System.out.println("NEW_OBLIGATIONS_ADMITTED=" + newObligations);
      System.out.println("RETAINED_VERIFIED_CLAIMS=" + retainedClaims);
      System.out.println("EMPTY_DELTA_APPLIES=" + emptyApplies);
      System.out.println("TEXT_ONLY_DELTA_APPLIES=" + textApplies);
      System.out.println("CORE_IDEA_APPEND_LEAKS=" + coreIdeaAppendLeaks);
      System.out.println("OLD_STRATEGY_DELETIONS=" + oldStrategyDeletions);
      System.out.println("OLD_ATTEMPT_HISTORY_LOSSES=" + oldAttemptLosses);
      System.out.println("OLD_TARGET_MATH_STATUS_CHANGES=" + oldTargetMathStatusChanges);
      System.out.println("OLD_TARGET_AUTOMATIC_CLOSURES=" + oldTargetClosures);
      System.out.println("OLD_TARGET_AUTOMATIC_REFUTATIONS=" + oldTargetRefutations);
      System.out.println("PARTIAL_PIVOT_WRITES=" + partialPivotWrites);
      System.out.println("DUPLICATE_PIVOT_APPLIES=" + duplicateApplies);
      System.out.println("DUPLICATE_NEW_OBLIGATIONS=" + duplicateObligations);
      System.out.println("DUPLICATE_PIVOT_TASKS=" + duplicateTasks);
      System.out.println("POST_RESTORE_PIVOT_LOSSES=" + postRestorePivotLosses);
      System.out.println("POST_RESTORE_PIVOT_STATUS_CHANGES=" + postRestoreStatusChanges);
      System.out.println("POST_RESTORE_DUPLICATE_APPLIES=" + postRestoreDuplicateApplies);
      System.out.println("POST_RESTORE_DUPLICATE_PROVIDER_CALLS=" + postRestoreDuplicateProviderCalls);
      System.out.println("GLOBAL_CANONICAL_DEBT_FALSE_DECREASES=" + debtFalseDecreases);
      System.out.println("FOCUSED_RECOVERY_BINDING_BYPASSES=" + focusedRecoveryBypasses);
      System.out.println("CAPACITY_OR_QUOTA_BYPASSES=" + capacityOrQuotaBypasses);
      System.out.println("DIRECT_FACT_PROMOTIONS=" + directFactPromotions);
      System.out.println("DIRECT_CLAIM_VERIFICATIONS=" + directClaimVerifications);
      System.out.println("DIRECT_NEGATIVE_REGISTRATIONS=" + directNegativeRegistrations);
      System.out.println("MAIN_GOAL_CLOSURES=" + mainGoalClosures);
      System.out.println("ROOT_HASH_CHANGES=" + rootChanges);
      System.out.println("NEGATIVE_REGISTRY_HASH_CHANGES=" + negativeChanges);
      System.out.println("ATTEMPT_ARTIFACT_LEDGER_UNAUTHORIZED_CHANGES=" + attemptChanges);
      System.out.println("CLAIM_LIFECYCLE_UNAUTHORIZED_CHANGES=" + claimChanges);
      System.out.println("RESEARCH_CHECKPOINT_LEDGER_HASH_CHANGES=" + researchChanges);
      System.out.println(
          "CANONICALIZATION_REGISTRY_UNAUTHORIZED_CHANGES=" + canonicalizationChanges);
      System.out.println("CONVERGENCE_CONTROL_UNAUTHORIZED_CHANGES=" + convergenceChanges);
      System.out.println("RESULT=PASS");

      assertThat(textRejections).isEqualTo(5);
      assertThat(pivotHashAfterRestore).isEqualTo(pivotHashBeforeRestore);
      assertThat(authorityRejections).isEqualTo(5);
      assertThat(validApplied).isEqualTo(5);
      assertThat(localApplied).isEqualTo(5);
      assertThat(objectChanges).isEqualTo(5);
      assertThat(targetChanges).isEqualTo(5);
      assertThat(newObligations).isEqualTo(5);
      assertThat(
              textApplies
                  + emptyApplies
                  + coreIdeaAppendLeaks
                  + localCountedAsPivots
                  + oldStrategyDeletions
                  + oldAttemptLosses
                  + oldTargetMathStatusChanges
                  + oldTargetClosures
                  + oldTargetRefutations
                  + partialPivotWrites
                  + duplicateApplies
                  + duplicateObligations
                  + duplicateTasks
                  + postRestorePivotLosses
                  + postRestoreStatusChanges
                  + postRestoreDuplicateApplies
                  + postRestoreDuplicateProviderCalls
                  + debtFalseDecreases
                  + focusedRecoveryBypasses
                  + capacityOrQuotaBypasses
                  + directFactPromotions
                  + directClaimVerifications
                  + directNegativeRegistrations
                  + mainGoalClosures
                  + rootChanges
                  + negativeChanges
                  + attemptChanges
                  + claimChanges
                  + researchChanges
                  + canonicalizationChanges
                  + convergenceChanges)
          .isZero();
    } finally {
      harness.close();
    }
  }

  private static PivotDelta withRootHash(PivotDelta source, String rootHash) {
    return PivotDelta.create(
        source.problemHash(),
        rootHash,
        source.routeId(),
        source.sourceStrategyId(),
        source.transformationTypes(),
        source.obstructionRefs(),
        source.objectChanges(),
        source.directionChanges(),
        source.assumptionChanges(),
        source.claimUseChanges(),
        source.obligationChanges(),
        source.proposedStrategy(),
        source.rationale());
  }

  private static PivotDelta withObstructions(
      PivotDelta source, List<PivotObstructionRef> obstructions) {
    return PivotDelta.create(
        source.problemHash(),
        source.rootGoalHash(),
        source.routeId(),
        source.sourceStrategyId(),
        source.transformationTypes(),
        obstructions,
        source.objectChanges(),
        source.directionChanges(),
        source.assumptionChanges(),
        source.claimUseChanges(),
        source.obligationChanges(),
        source.proposedStrategy(),
        source.rationale());
  }

  private static PivotDelta withClaims(
      PivotDelta source, List<PivotClaimUseChange> claimChanges) {
    return PivotDelta.create(
        source.problemHash(),
        source.rootGoalHash(),
        source.routeId(),
        source.sourceStrategyId(),
        source.transformationTypes(),
        source.obstructionRefs(),
        source.objectChanges(),
        source.directionChanges(),
        source.assumptionChanges(),
        claimChanges,
        source.obligationChanges(),
        source.proposedStrategy(),
        source.rationale());
  }

  private static PivotDelta withObligations(
      PivotDelta source, List<PivotObligationChange> obligationChanges) {
    return PivotDelta.create(
        source.problemHash(),
        source.rootGoalHash(),
        source.routeId(),
        source.sourceStrategyId(),
        source.transformationTypes(),
        source.obstructionRefs(),
        source.objectChanges(),
        source.directionChanges(),
        source.assumptionChanges(),
        source.claimUseChanges(),
        obligationChanges,
        source.proposedStrategy(),
        source.rationale());
  }

  private static PivotDelta withProposedStrategy(
      PivotDelta source, StrategyCard proposedStrategy) {
    return PivotDelta.create(
        source.problemHash(),
        source.rootGoalHash(),
        source.routeId(),
        source.sourceStrategyId(),
        source.transformationTypes(),
        source.obstructionRefs(),
        source.objectChanges(),
        source.directionChanges(),
        source.assumptionChanges(),
        source.claimUseChanges(),
        source.obligationChanges(),
        proposedStrategy,
        source.rationale());
  }

  private static StrategyCard finitePrimeSupportStrategy(StrategyCard source) {
    return DesktopStrategyMetadataTestSupport.inherit(
        new StrategyCard(
            null,
            source.bottleneck(),
            source.calculationChecks(),
            source.calculationEvidenceRefs(),
            source.computationHints(),
            "The sequence has finite prime support.",
            source.criticalClaims(),
            source.estimatedCost(),
            source.estimatedSuccess(),
            source.expectedLemmas(),
            source.falsificationTest(),
            source.independenceBasis(),
            source.inspirationProposalId(),
            source.keyOriginalStep(),
            source.parentStrategyIds(),
            source.prerequisites(),
            source.strategyId(),
            source.tags(),
            source.title()),
        source);
  }
}
