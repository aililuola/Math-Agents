package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.MechanismOperationDeclaration;
import io.github.aililuola.mathproofmesh.contract.MechanismOperationKind;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyBlueprintCompiler;
import java.util.List;
import java.util.Set;

final class StrategyDiversityTestFixtures {
  static final String PROBLEM_HASH = "finite-graph-problem";
  static final String ROOT_HASH = "finite-graph-root";
  static final String GOAL = "Every finite tree with at least two vertices has at least two leaves.";

  private StrategyDiversityTestFixtures() {}

  static StrategyCard strategy(
      String id, String title, String mechanism, String requiredClaim, double prior) {
    return new StrategyCard(
        null,
        "Establish the route-specific bridge for " + mechanism + ".",
        List.of(),
        List.of(),
        List.of(),
        mechanism,
        List.of(claim(id + "-required", requiredClaim, "required")),
        0.2d,
        prior,
        List.of("Use " + mechanism + " to derive the target from " + requiredClaim + "."),
        "Search finite structures for a counterexample to " + requiredClaim + ".",
        "Independent structural route",
        null,
        null,
        List.of(),
        List.of("The structure is finite."),
        id,
        List.of("presentation-" + id),
        title,
        List.of(
            new MechanismOperationDeclaration(
                "declared-mechanism",
                operationKind(mechanism),
                List.of("@roots"),
                List.of("@direct_targets"))),
        List.of());
  }

  private static MechanismOperationKind operationKind(String mechanism) {
    String normalized = mechanism.toLowerCase(java.util.Locale.ROOT);
    if (normalized.contains("longest")
        || normalized.contains("geodesic")
        || normalized.contains("extremal")) {
      return MechanismOperationKind.EXTREMAL_SELECTION;
    }
    if (normalized.contains("count") || normalized.contains("degree sum")) {
      return MechanismOperationKind.COUNTING;
    }
    if (normalized.contains("smallest counterexample")
        || normalized.contains("minimal counterexample")) {
      return MechanismOperationKind.MINIMAL_COUNTEREXAMPLE;
    }
    if (normalized.contains("induct")
        || normalized.contains("recursive")
        || normalized.contains("leaf")
        || normalized.contains("pendant")
        || normalized.contains("endpoint")) {
      return MechanismOperationKind.REDUCTION;
    }
    return MechanismOperationKind.DIRECT;
  }

  static CriticalClaim claim(String id, String statement, String necessity) {
    return new CriticalClaim(
        id,
        List.of(),
        "Enumerate the smallest finite counterexamples.",
        necessity,
        null,
        statement,
        "needs_check");
  }

  static ProofControlModels.Strategy control(StrategyCard strategy) {
    return new ProofControlModels.Strategy(
        strategy.strategyId(),
        strategy.title(),
        strategy.coreIdea(),
        strategy.prerequisites(),
        strategy.criticalClaims().stream().map(CriticalClaim::statement).toList(),
        strategy.expectedLemmas(),
        List.of(strategy.falsificationTest()),
        List.of("finite structure", "target relation"),
        "route-test");
  }

  static StrategyBlueprintCompiler.Compilation blueprint(StrategyCard strategy) {
    return new StrategyBlueprintCompiler()
        .compile(PROBLEM_HASH, control(strategy), goal());
  }

  static ProofControlModels.Obligation goal() {
    return new ProofControlModels.Obligation(
        "main-goal",
        GOAL,
        ProofControlModels.ObligationKind.MAIN_GOAL,
        ProofControlModels.ObligationStatus.OPEN,
        List.of(),
        List.of("route-test"),
        1.0d,
        1.0d);
  }

  static StrategyPortfolioCandidate candidate(
      StrategyCard strategy,
      StrategyPreflightReport preflight,
      double total) {
    StrategyBlueprintCompiler.Compilation blueprint = blueprint(strategy);
    StrategyMechanismAnalyzer analyzer = new StrategyMechanismAnalyzer();
    StrategyMechanismSignature signature =
        analyzer.signature(PROBLEM_HASH, ROOT_HASH, strategy, control(strategy), blueprint);
    StrategyFeasibilityScore score =
        new StrategyFeasibilityScore(
            1.0d,
            1.0d,
            preflight.requiredClaimEvidenceCoverage(),
            1.0d,
            1.0d,
            preflight.unresolvedRequiredClaimKeys().isEmpty() ? 0.0d : 0.25d,
            strategy.estimatedCost(),
            0.0d,
            total,
            java.util.OptionalDouble.empty());
    return new StrategyPortfolioCandidate(
        strategy, blueprint, signature, analyzer.profile(strategy, blueprint), preflight, score);
  }

  static StrategyPreflightReport report(
      StrategyCard strategy, CriticalClaimPreflightStatus status) {
    CriticalClaim claim = strategy.criticalClaims().getFirst();
    CriticalClaimSemanticKey key =
        new CriticalClaimKeyCompiler().compile(PROBLEM_HASH, claim);
    boolean rejected =
        status == CriticalClaimPreflightStatus.VERIFIED_REFUTED
            || status == CriticalClaimPreflightStatus.PERMANENTLY_BLOCKED
            || status == CriticalClaimPreflightStatus.ERROR;
    Set<String> unresolved =
        status == CriticalClaimPreflightStatus.UNKNOWN
                || status == CriticalClaimPreflightStatus.UNTESTABLE
                || status == CriticalClaimPreflightStatus.NOT_REFUTED_IN_BOUNDED_SCOPE
            ? Set.of(key.semanticKey())
            : Set.of();
    CriticalClaimPreflightResult result =
        new CriticalClaimPreflightResult(
            claim.claimId(), key, claim.necessity(), status, List.of(), status.name());
    return new StrategyPreflightReport(
        strategy.strategyId(),
        PROBLEM_HASH,
        List.of(result),
        rejected,
        false,
        status == CriticalClaimPreflightStatus.VERIFIED_SUPPORTED ? 1.0d : 0.0d,
        unresolved,
        StrategySemanticNormalizer.hash(List.of(strategy.strategyId(), status.name())));
  }
}
