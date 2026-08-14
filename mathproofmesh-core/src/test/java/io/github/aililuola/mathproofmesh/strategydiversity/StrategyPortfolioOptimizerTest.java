package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrategyPortfolioOptimizerTest {
  @Test
  void globalSelectionCapsDuplicateMechanismsAndCommonModeGroups() {
    List<StrategyPortfolioCandidate> candidates = new ArrayList<>();
    StrategyCard duplicateA = strategy("dup-a", "Induction by deleting a leaf", "Invariant U.", 0.9d);
    StrategyCard duplicateB = strategy("dup-b", "Induction by deleting a leaf", "Invariant U.", 0.8d);
    StrategyCard common = strategy("common", "Longest-path extremal proof", "Invariant U.", 0.85d);
    StrategyCard independentA = strategy("ind-a", "Degree-sum counting", "Degree sum bridge.", 0.7d);
    StrategyCard independentB = strategy("ind-b", "Contradiction through edge removal", "Edge removal bridge.", 0.65d);
    StrategyCard refuted = strategy("refuted", "Model favorite", "False bridge.", 0.99d);
    for (StrategyCard candidate : List.of(duplicateA, duplicateB, common, independentA, independentB)) {
      candidates.add(
          StrategyDiversityTestFixtures.candidate(
              candidate,
              StrategyDiversityTestFixtures.report(
                  candidate, CriticalClaimPreflightStatus.UNKNOWN),
              0.4d));
    }
    candidates.add(
        StrategyDiversityTestFixtures.candidate(
            refuted,
            StrategyDiversityTestFixtures.report(
                refuted, CriticalClaimPreflightStatus.VERIFIED_REFUTED),
            0.8d));

    StrategyPortfolioDecision decision =
        new StrategyPortfolioOptimizer()
            .optimize(
                "episode",
                candidates,
                new StrategyPortfolioConstraint(4, 2, 20, Set.of(), Set.of()));

    assertThat(decision.selectedStrategyIds()).doesNotContain("refuted");
    assertThat(decision.selectedStrategyIds().stream()
            .filter(id -> Set.of("dup-a", "dup-b", "common").contains(id)))
        .hasSize(1);
    assertThat(decision.selectedStrategyIds()).contains("ind-a", "ind-b");
    assertThat(decision.selectedStrategyIds()).isSorted();
  }

  @Test
  void emptyAndIneligibleInputsProduceDeterministicReasons() {
    StrategyPortfolioOptimizer optimizer = new StrategyPortfolioOptimizer();
    StrategyPortfolioConstraint emptyConstraint =
        new StrategyPortfolioConstraint(3, 2, 4, null, null);

    StrategyPortfolioDecision empty = optimizer.optimize("empty", null, emptyConstraint);

    assertThat(empty.selectedStrategyIds()).isEmpty();
    assertThat(empty.requestedSizeMet()).isFalse();
    assertThat(empty.audit()).singleElement()
        .extracting(StrategyPortfolioAuditEvent::detail)
        .isEqualTo("QUALIFIED_PORTFOLIO_SMALLER_THAN_MINIMUM");

    StrategyPortfolioCandidate activeSignature = candidate("active", "Mechanism A", 0.7d);
    StrategyPortfolioCandidate activeClaim = candidate("active-claim", "Mechanism B", 0.7d);
    StrategyPortfolioCandidate regeneration = candidate("regenerate", "Mechanism C", 0.7d);
    regeneration = withPreflight(regeneration, report(regeneration, false, true, Set.of()));
    StrategyPortfolioCandidate zeroScore = withScore(candidate("zero", "Mechanism D", 0.7d), 0.0d);
    StrategyPortfolioCandidate refuted =
        StrategyDiversityTestFixtures.candidate(
            strategy("refuted", "Mechanism E", "Refuted bridge.", 0.9d),
            StrategyDiversityTestFixtures.report(
                strategy("refuted", "Mechanism E", "Refuted bridge.", 0.9d),
                CriticalClaimPreflightStatus.VERIFIED_REFUTED),
            0.8d);
    Set<String> activeClaims = activeClaim.preflight().unresolvedRequiredClaimKeys();
    StrategyPortfolioConstraint blockedConstraint =
        new StrategyPortfolioConstraint(
            4,
            0,
            20,
            Set.of(activeSignature.signature().structuralSignatureHash()),
            activeClaims);

    StrategyPortfolioDecision blocked =
        optimizer.optimize(
            "blocked",
            List.of(activeSignature, activeClaim, regeneration, zeroScore, refuted),
            blockedConstraint);

    assertThat(blocked.selectedStrategyIds()).isEmpty();
    assertThat(blocked.nonSelectionReasons())
        .containsEntry("active", "SAME_STRUCTURAL_MECHANISM")
        .containsEntry("active-claim", "SHARED_UNRESOLVED_REQUIRED_CLAIM")
        .containsEntry("regenerate", "SUPPORTING_CLAIM_REQUIRES_REGENERATION")
        .containsEntry("zero", "LOWER_GLOBAL_PORTFOLIO_OBJECTIVE")
        .containsEntry("refuted", "VERIFIED_REFUTED");
  }

  @Test
  void boundedSearchRetainsStructuralAndClaimRepresentatives() {
    List<StrategyPortfolioCandidate> candidates = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      candidates.add(candidate("bounded-" + index, "Mechanism " + index, 0.8d - index * 0.05d));
    }
    StrategyPortfolioConstraint constraint =
        new StrategyPortfolioConstraint(3, 2, 3, Set.of(), Set.of());

    StrategyPortfolioDecision first =
        new StrategyPortfolioOptimizer().optimize("bounded", candidates, constraint);
    StrategyPortfolioDecision replay =
        new StrategyPortfolioOptimizer().optimize("bounded", candidates, constraint);

    assertThat(first.selectedStrategyIds()).hasSize(3).isSorted();
    assertThat(first.requestedSizeMet()).isTrue();
    assertThat(first.decisionHash()).isEqualTo(replay.decisionHash());
    assertThat(first.nonSelectionReasons()).hasSize(5);
  }

  @Test
  void selectedCandidatesCannotShareStrategySignatureOrUnresolvedClaim() {
    StrategyPortfolioCandidate selected = candidate("selected", "Mechanism primary", 0.8d);
    StrategyPortfolioCandidate duplicateId = candidate("selected", "Mechanism alternate", 0.7d);
    StrategyPortfolioCandidate duplicateSignature =
        withSignature(
            candidate("duplicate-signature", "Mechanism distinct", 0.75d),
            selected.signature().structuralSignatureHash());
    StrategyPortfolioCandidate sharedClaim = candidate("shared-claim", "Mechanism claim", 0.72d);
    sharedClaim =
        withPreflight(
            sharedClaim,
            report(
                sharedClaim,
                false,
                false,
                selected.preflight().unresolvedRequiredClaimKeys()));

    StrategyPortfolioDecision decision =
        new StrategyPortfolioOptimizer()
            .optimize(
                "pairwise",
                List.of(selected, duplicateId, duplicateSignature, sharedClaim),
                new StrategyPortfolioConstraint(1, 1, 20, Set.of(), Set.of()));

    assertThat(decision.selectedStrategyIds()).containsExactly("selected");
    assertThat(decision.nonSelectionReasons())
        .containsEntry("duplicate-signature", "SAME_STRUCTURAL_MECHANISM")
        .containsEntry("shared-claim", "SHARED_UNRESOLVED_REQUIRED_CLAIM");
  }

  private static StrategyCard strategy(
      String id, String mechanism, String required, double prior) {
    return StrategyDiversityTestFixtures.strategy(id, id, mechanism, required, prior);
  }

  private static StrategyPortfolioCandidate candidate(
      String id, String mechanism, double total) {
    StrategyCard strategy = strategy(id, mechanism, "Required bridge for " + id + ".", 0.8d);
    return StrategyDiversityTestFixtures.candidate(
        strategy,
        StrategyDiversityTestFixtures.report(strategy, CriticalClaimPreflightStatus.UNKNOWN),
        total);
  }

  private static StrategyPortfolioCandidate withPreflight(
      StrategyPortfolioCandidate candidate, StrategyPreflightReport report) {
    return new StrategyPortfolioCandidate(
        candidate.strategy(),
        candidate.blueprint(),
        candidate.signature(),
        candidate.profile(),
        report,
        candidate.feasibility());
  }

  private static StrategyPortfolioCandidate withSignature(
      StrategyPortfolioCandidate candidate, String structuralHash) {
    StrategyMechanismSignature source = candidate.signature();
    StrategyMechanismSignature signature =
        new StrategyMechanismSignature(
            source.problemHash(),
            source.rootGoalHash(),
            source.targetCanonicalIds(),
            source.requiredClaimSemanticKeys(),
            source.domainObjectRoleSignature(),
            source.representationSignature(),
            source.dependencyDagShapeHash(),
            source.proofTransformationHash(),
            source.falsificationContractSignature(),
            structuralHash);
    return new StrategyPortfolioCandidate(
        candidate.strategy(),
        candidate.blueprint(),
        signature,
        candidate.profile(),
        candidate.preflight(),
        candidate.feasibility());
  }

  private static StrategyPortfolioCandidate withScore(
      StrategyPortfolioCandidate candidate, double total) {
    StrategyFeasibilityScore source = candidate.feasibility();
    StrategyFeasibilityScore score =
        new StrategyFeasibilityScore(
            source.rootGoalAlignment(),
            source.blueprintCompleteness(),
            source.requiredClaimEvidenceCoverage(),
            source.mechanismNovelty(),
            source.portfolioComplementarity(),
            source.commonModePenalty(),
            source.costPenalty(),
            0.0d,
            total,
            OptionalDouble.empty());
    return new StrategyPortfolioCandidate(
        candidate.strategy(),
        candidate.blueprint(),
        candidate.signature(),
        candidate.profile(),
        candidate.preflight(),
        score);
  }

  private static StrategyPreflightReport report(
      StrategyPortfolioCandidate candidate,
      boolean hardRejected,
      boolean requiresRegeneration,
      Set<String> unresolved) {
    StrategyPreflightReport source = candidate.preflight();
    return new StrategyPreflightReport(
        source.strategyId(),
        source.problemHash(),
        source.claims(),
        hardRejected,
        requiresRegeneration,
        source.requiredClaimEvidenceCoverage(),
        unresolved,
        StrategySemanticNormalizer.hash(
            List.of(
                source.strategyId(),
                hardRejected,
                requiresRegeneration,
                unresolved.stream().sorted().toList())));
  }
}
