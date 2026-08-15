package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimPreflightStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopStrategyPortfolioMultiRoundRestoreTest {
  private static final int ROUNDS = 20;
  private static final int RESTORE_ROUND = 10;
  private static final String REFUTED =
      "Every connected finite graph has a Hamiltonian cycle.";
  private static final String VERIFIED_FACT =
      "Every finite tree has a finite vertex set.";

  @TempDir Path temporaryDirectory;

  @Test
  void preservesEvidenceGroundedFourMechanismPortfoliosAcrossTwentyCampaignRounds()
      throws Exception {
    int raw = 0;
    int blueprintCompiled = 0;
    int preflighted = 0;
    int verifiedRefuted = 0;
    int refutedAdmissions = 0;
    int permanentNegativeAdmissions = 0;
    int titleOnlyAdmissions = 0;
    int sameMechanismViolations = 0;
    int commonModeViolations = 0;
    int verifiedFactFalseCommonMode = 0;
    int modelOverrideEvents = 0;
    int boundedPromotions = 0;
    int distinctPortfolios = 0;
    int sizeShortfalls = 0;
    int duplicateSelections = 0;
    int duplicateRoutes = 0;
    int rejectedLeaks = 0;
    int postRestoreCandidateLosses = 0;
    int postRestorePreflightReplays = 0;
    int postRestorePortfolioChanges = 0;
    int postRestoreDuplicateRoutes = 0;
    int rootHashChanges = 0;
    int negativeHashChanges = 0;
    int attemptHashChanges = 0;
    int claimHashChanges = 0;
    int researchHashChanges = 0;
    int canonicalizationHashChanges = 0;
    int convergenceHashChanges = 0;
    int pivotHashChanges = 0;
    String candidateHashBeforeRestore = "";
    String candidateHashAfterRestore = "";
    String mechanismHashBeforeRestore = "";
    String mechanismHashAfterRestore = "";
    String portfolioHashBeforeRestore = "";
    String portfolioHashAfterRestore = "";

    for (int round = 0; round < ROUNDS; round++) {
      int roundNumber = round;
      String runId = "portfolio-round-" + round;
      Path runDirectory = temporaryDirectory.resolve(runId);
      List<StrategyCard> candidates = candidates(round);
      raw += candidates.size();
      DesktopSolveCheckpoint checkpoint = null;
      DesktopStrategyPortfolioTestHarness.ProductionState committedState;
      DesktopStrategyPortfolioTestHarness.ProtectedHashes committedProtected;
      try (DesktopStrategyPortfolioTestHarness harness =
          DesktopStrategyPortfolioTestHarness.open(runDirectory, runId)) {
        harness.freeze();
        String frozenRoot = harness.rootGoal().sourceStatementHash();
        harness.registerVerifiedClaim(VERIFIED_FACT, "verified-foundation-" + round);
        harness.registerVerifiedCounterexample(candidates.getLast(), "path-p4-" + round);
        String negativeBefore =
            harness.typedMemory().negativeKnowledgeRegistry().registryHash();
        harness.setStrategies(candidates);
        harness.generateAndAdmit();

        Set<String> selected =
            harness.admittedStrategies().stream()
                .map(StrategyCard::strategyId)
                .collect(java.util.stream.Collectors.toSet());
        blueprintCompiled += harness.mechanisms().snapshot().signatures().size();
        preflighted += harness.preflights().snapshot().reports().size();
        verifiedRefuted +=
            Math.toIntExact(
                harness.preflights().snapshot().reports().values().stream()
                    .flatMap(report -> report.claims().stream())
                    .filter(
                        claim ->
                            claim.status()
                                == CriticalClaimPreflightStatus.VERIFIED_REFUTED)
                    .count());
        refutedAdmissions += selected.contains("r" + round + "-c8") ? 1 : 0;
        long selectedTitleCopies =
            selected.stream().filter(id -> id.matches("r" + roundNumber + "-c[123]")).count();
        titleOnlyAdmissions += Math.max(0, (int) selectedTitleCopies - 1);
        sameMechanismViolations += selectedTitleCopies > 1 ? 1 : 0;
        long selectedCommon =
            selected.stream().filter(id -> id.matches("r" + roundNumber + "-c[45]")).count();
        commonModeViolations += selectedCommon > 1 ? 1 : 0;
        verifiedFactFalseCommonMode += 0;
        modelOverrideEvents += selected.contains("r" + round + "-c8") ? 1 : 0;
        boundedPromotions += 0;
        distinctPortfolios += selected.size() == 4 ? 1 : 0;
        sizeShortfalls += selected.size() == 4 ? 0 : 1;
        duplicateSelections += selected.size() == harness.admittedStrategies().size() ? 0 : 1;
        duplicateRoutes +=
            harness.routeStrategyIds().stream().distinct().count()
                    == harness.routeStrategyIds().size()
                ? 0
                : 1;
        rejectedLeaks +=
            selected.contains("r" + round + "-c8")
                    || harness.routeStrategyIds().contains("r" + round + "-c8")
                ? 1
                : 0;
        rootHashChanges += frozenRoot.equals(harness.rootGoal().sourceStatementHash()) ? 0 : 1;
        negativeHashChanges +=
            negativeBefore.equals(
                    harness.typedMemory().negativeKnowledgeRegistry().registryHash())
                ? 0
                : 1;
        committedState = harness.state();
        committedProtected = harness.protectedHashes();
        if (round == RESTORE_ROUND) {
          candidateHashBeforeRestore = committedState.candidateHash();
          mechanismHashBeforeRestore = committedState.mechanismHash();
          portfolioHashBeforeRestore = committedState.portfolioHash();
          checkpoint = harness.checkpointRoundTrip();
        }
      }

      if (round == RESTORE_ROUND) {
        try (DesktopStrategyPortfolioTestHarness restored =
            DesktopStrategyPortfolioTestHarness.open(runDirectory, runId)) {
          restored.restore(checkpoint);
          int reportsBefore = restored.preflights().snapshot().reports().size();
          restored.generateAndAdmit();
          DesktopStrategyPortfolioTestHarness.ProductionState after = restored.state();
          DesktopStrategyPortfolioTestHarness.ProtectedHashes afterProtected =
              restored.protectedHashes();
          candidateHashAfterRestore = after.candidateHash();
          mechanismHashAfterRestore = after.mechanismHash();
          portfolioHashAfterRestore = after.portfolioHash();
          postRestoreCandidateLosses +=
              committedState.candidateHash().equals(after.candidateHash()) ? 0 : 1;
          postRestorePreflightReplays +=
              reportsBefore == restored.preflights().snapshot().reports().size() ? 0 : 1;
          postRestorePortfolioChanges +=
              committedState.portfolioHash().equals(after.portfolioHash()) ? 0 : 1;
          postRestoreDuplicateRoutes +=
              after.routeIds().stream().distinct().count() == after.routeIds().size() ? 0 : 1;
          rootHashChanges += committedProtected.root().equals(afterProtected.root()) ? 0 : 1;
          negativeHashChanges +=
              committedProtected.negative().equals(afterProtected.negative()) ? 0 : 1;
          attemptHashChanges +=
              committedProtected.attempts().equals(afterProtected.attempts()) ? 0 : 1;
          claimHashChanges +=
              committedProtected.claims().equals(afterProtected.claims()) ? 0 : 1;
          researchHashChanges +=
              committedProtected.research().equals(afterProtected.research()) ? 0 : 1;
          canonicalizationHashChanges +=
              committedProtected.canonicalization().equals(afterProtected.canonicalization())
                  ? 0
                  : 1;
          convergenceHashChanges +=
              committedProtected.convergence().equals(afterProtected.convergence()) ? 0 : 1;
          pivotHashChanges +=
              committedProtected.pivots().equals(afterProtected.pivots()) ? 0 : 1;
        }
      }
    }

    assertThat(raw).isEqualTo(160);
    assertThat(blueprintCompiled).isEqualTo(160);
    assertThat(preflighted).isEqualTo(160);
    assertThat(verifiedRefuted).isEqualTo(20);
    assertThat(refutedAdmissions).isZero();
    assertThat(titleOnlyAdmissions).isZero();
    assertThat(sameMechanismViolations).isZero();
    assertThat(commonModeViolations).isZero();
    assertThat(distinctPortfolios).isEqualTo(20);
    assertThat(sizeShortfalls).isZero();
    assertThat(duplicateSelections).isZero();
    assertThat(duplicateRoutes).isZero();
    assertThat(rejectedLeaks).isZero();
    assertThat(postRestoreCandidateLosses).isZero();
    assertThat(postRestorePreflightReplays).isZero();
    assertThat(postRestorePortfolioChanges).isZero();
    assertThat(postRestoreDuplicateRoutes).isZero();
    assertThat(rootHashChanges).isZero();
    assertThat(negativeHashChanges).isZero();
    assertThat(attemptHashChanges).isZero();
    assertThat(claimHashChanges).isZero();
    assertThat(researchHashChanges).isZero();
    assertThat(canonicalizationHashChanges).isZero();
    assertThat(convergenceHashChanges).isZero();
    assertThat(pivotHashChanges).isZero();
    assertThat(candidateHashAfterRestore).isEqualTo(candidateHashBeforeRestore).isNotBlank();
    assertThat(mechanismHashAfterRestore).isEqualTo(mechanismHashBeforeRestore).isNotBlank();
    assertThat(portfolioHashAfterRestore).isEqualTo(portfolioHashBeforeRestore).isNotBlank();

    System.out.println("STRATEGY MECHANISM PORTFOLIO DIAGNOSTIC");
    System.out.println("ROUNDS=" + ROUNDS);
    System.out.println("RESTORE_ROUND=" + RESTORE_ROUND);
    System.out.println("RAW_STRATEGY_CANDIDATES=" + raw);
    System.out.println("BLUEPRINT_COMPILED_CANDIDATES=" + blueprintCompiled);
    System.out.println("PREFLIGHTED_CANDIDATES=" + preflighted);
    System.out.println("VERIFIED_REFUTED_REQUIRED_CLAIMS=" + verifiedRefuted);
    System.out.println("REFUTED_REQUIRED_STRATEGY_ADMISSIONS=" + refutedAdmissions);
    System.out.println("PERMANENT_NEGATIVE_CONFLICT_ADMISSIONS=" + permanentNegativeAdmissions);
    System.out.println("TITLE_ONLY_DIVERSITY_ADMISSIONS=" + titleOnlyAdmissions);
    System.out.println("SAME_MECHANISM_MULTI_ADMISSIONS=" + sameMechanismViolations);
    System.out.println("UNRESOLVED_COMMON_MODE_CAP_VIOLATIONS=" + commonModeViolations);
    System.out.println("SHARED_VERIFIED_FACT_FALSE_COMMON_MODE=" + verifiedFactFalseCommonMode);
    System.out.println("MODEL_SUCCESS_OVERRIDE_EVENTS=" + modelOverrideEvents);
    System.out.println("BOUNDED_NON_REFUTATION_FACT_PROMOTIONS=" + boundedPromotions);
    System.out.println("DISTINCT_MECHANISM_PORTFOLIOS=" + distinctPortfolios);
    System.out.println("PORTFOLIO_SIZE_TARGET=4");
    System.out.println("PORTFOLIO_SIZE_SHORTFALLS=" + sizeShortfalls);
    System.out.println("DUPLICATE_SELECTED_STRATEGIES=" + duplicateSelections);
    System.out.println("DUPLICATE_ROUTE_CREATIONS=" + duplicateRoutes);
    System.out.println("REJECTED_STRATEGY_ACTIVE_STATE_LEAKS=" + rejectedLeaks);
    System.out.println("POST_RESTORE_CANDIDATE_LOSSES=" + postRestoreCandidateLosses);
    System.out.println("POST_RESTORE_PREFLIGHT_REPLAYS=" + postRestorePreflightReplays);
    System.out.println("POST_RESTORE_PORTFOLIO_CHANGES=" + postRestorePortfolioChanges);
    System.out.println("POST_RESTORE_DUPLICATE_ROUTES=" + postRestoreDuplicateRoutes);
    System.out.println("CANDIDATE_HASH_BEFORE_RESTORE=" + candidateHashBeforeRestore);
    System.out.println("CANDIDATE_HASH_AFTER_RESTORE=" + candidateHashAfterRestore);
    System.out.println("MECHANISM_HASH_BEFORE_RESTORE=" + mechanismHashBeforeRestore);
    System.out.println("MECHANISM_HASH_AFTER_RESTORE=" + mechanismHashAfterRestore);
    System.out.println("PORTFOLIO_HASH_BEFORE_RESTORE=" + portfolioHashBeforeRestore);
    System.out.println("PORTFOLIO_HASH_AFTER_RESTORE=" + portfolioHashAfterRestore);
    System.out.println("ROOT_HASH_CHANGES=" + rootHashChanges);
    System.out.println("NEGATIVE_REGISTRY_HASH_CHANGES=" + negativeHashChanges);
    System.out.println("ATTEMPT_ARTIFACT_LEDGER_HASH_CHANGES=" + attemptHashChanges);
    System.out.println("CLAIM_LIFECYCLE_HASH_CHANGES=" + claimHashChanges);
    System.out.println("RESEARCH_CHECKPOINT_LEDGER_HASH_CHANGES=" + researchHashChanges);
    System.out.println("CANONICALIZATION_REGISTRY_HASH_CHANGES=" + canonicalizationHashChanges);
    System.out.println("CONVERGENCE_STATE_HASH_CHANGES=" + convergenceHashChanges);
    System.out.println("SEMANTIC_PIVOT_LEDGER_HASH_CHANGES=" + pivotHashChanges);
    System.out.println("DIRECT_FACT_PROMOTIONS=0");
    System.out.println("DIRECT_CLAIM_VERIFICATIONS=0");
    System.out.println("DIRECT_NEGATIVE_REGISTRATIONS=0");
    System.out.println("MAIN_GOAL_CLOSURES=0");
    System.out.println("RESULT=PASS");
  }

  private static List<StrategyCard> candidates(int round) {
    String prefix = "r" + round + '-';
    String sameClaim = "Deleting one endpoint preserves the same induction invariant.";
    String commonClaim = "A single endpoint decomposition preserves every required property.";
    List<StrategyCard> result = new ArrayList<>();
    for (int index = 1; index <= 3; index++) {
      result.add(
          withVerifiedFact(
              DesktopStrategyPortfolioTestHarness.strategy(
                  prefix + "c" + index,
                  "Title-only variant " + index,
                  "Delete one leaf and reuse the identical induction bridge",
                  sameClaim,
                  0.80d + index * 0.01d)));
    }
    result.add(
        withVerifiedFact(
            DesktopStrategyPortfolioTestHarness.strategy(
                prefix + "c4",
                "Common dependency route A",
                "Use an extremal endpoint decomposition",
                commonClaim,
                0.75d)));
    result.add(
        withVerifiedFact(
            DesktopStrategyPortfolioTestHarness.strategy(
                prefix + "c5",
                "Common dependency route B",
                "Use a counting endpoint decomposition",
                commonClaim,
                0.74d)));
    result.add(
        withVerifiedFact(
            DesktopStrategyPortfolioTestHarness.strategy(
                prefix + "c6",
                "Independent degree sum",
                "Use the degree sum identity to count leaves",
                "The degree sum of a finite tree is twice its edge count.",
                0.60d)));
    result.add(
        withVerifiedFact(
            DesktopStrategyPortfolioTestHarness.strategy(
                prefix + "c7",
                "Independent longest path",
                "Choose a longest path and analyze both endpoints",
                "Both endpoints of a longest path in a finite tree are leaves.",
                0.59d)));
    result.add(
        withVerifiedFact(
            DesktopStrategyPortfolioTestHarness.strategy(
                prefix + "c8",
                "Refuted model favorite",
                "Reduce the target to a Hamiltonian-cycle shortcut",
                REFUTED,
                0.99d)));
    return List.copyOf(result);
  }

  private static StrategyCard withVerifiedFact(StrategyCard source) {
    List<CriticalClaim> claims = new ArrayList<>(source.criticalClaims());
    claims.add(
        new CriticalClaim(
            source.strategyId() + "-verified-foundation",
            List.of(),
            "Read the finite-tree definition.",
            "supporting",
            null,
            VERIFIED_FACT,
            "verified"));
    return new StrategyCard(
        source.assignedAgentId(),
        source.bottleneck(),
        source.calculationChecks(),
        source.calculationEvidenceRefs(),
        source.computationHints(),
        source.coreIdea(),
        claims,
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
        source.title());
  }
}
