package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic bounded global optimizer over server-owned structural candidates. */
public final class StrategyPortfolioOptimizer {
  public StrategyPortfolioDecision optimize(
      String episodeId,
      List<StrategyPortfolioCandidate> candidates,
      StrategyPortfolioConstraint constraint) {
    episodeId = StrategySemanticNormalizer.require(episodeId, "episodeId");
    java.util.Objects.requireNonNull(constraint, "constraint");
    List<StrategyPortfolioCandidate> eligible =
        boundedCandidates(candidates == null ? List.of() : candidates, constraint);
    SearchState state = new SearchState();
    search(eligible, constraint, 0, new ArrayList<>(), state);
    List<StrategyPortfolioCandidate> selected =
        state.best == null ? List.of() : List.copyOf(state.best);
    Set<String> selectedIds =
        selected.stream()
            .map(candidate -> candidate.strategy().strategyId())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Map<String, String> reasons = new LinkedHashMap<>();
    for (StrategyPortfolioCandidate candidate : candidates == null ? List.<StrategyPortfolioCandidate>of() : candidates) {
      String strategyId = candidate.strategy().strategyId();
      if (selectedIds.contains(strategyId)) {
        continue;
      }
      reasons.put(strategyId, rejectionReason(candidate, selected, constraint));
    }
    List<String> ids = selectedIds.stream().sorted().toList();
    double objective = objective(selected);
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("episode_id", episodeId);
    identity.put("selected", ids);
    identity.put("reasons", reasons);
    identity.put("constraint", constraint);
    String hash = StrategySemanticNormalizer.hash(identity);
    StrategyPortfolioAuditEvent audit =
        new StrategyPortfolioAuditEvent(
            "portfolio-audit-" + hash.substring(0, 20),
            episodeId,
            "global_optimize",
            ids,
            ids.size() < constraint.minimumSize()
                ? "QUALIFIED_PORTFOLIO_SMALLER_THAN_MINIMUM"
                : "GLOBAL_CONSTRAINTS_SATISFIED");
    return new StrategyPortfolioDecision(
        episodeId,
        ids,
        reasons,
        objective,
        ids.size() >= constraint.requestedSize(),
        hash,
        List.of(audit));
  }

  private static List<StrategyPortfolioCandidate> boundedCandidates(
      List<StrategyPortfolioCandidate> candidates, StrategyPortfolioConstraint constraint) {
    List<StrategyPortfolioCandidate> sorted =
        candidates.stream()
            .sorted(
                Comparator.<StrategyPortfolioCandidate>comparingDouble(
                        candidate -> -candidate.feasibility().total())
                    .thenComparing(candidate -> candidate.strategy().strategyId()))
            .toList();
    if (sorted.size() <= constraint.maxExactCandidates()) {
      return sorted;
    }
    LinkedHashMap<String, StrategyPortfolioCandidate> retained = new LinkedHashMap<>();
    for (StrategyPortfolioCandidate candidate : sorted) {
      retained.putIfAbsent(candidate.signature().structuralSignatureHash(), candidate);
    }
    for (StrategyPortfolioCandidate candidate : sorted) {
      for (String key : candidate.preflight().unresolvedRequiredClaimKeys()) {
        retained.putIfAbsent("claim:" + key, candidate);
      }
    }
    for (StrategyPortfolioCandidate candidate : sorted) {
      retained.putIfAbsent("strategy:" + candidate.strategy().strategyId(), candidate);
      if (retained.size() >= constraint.maxExactCandidates()) {
        break;
      }
    }
    return retained.values().stream()
        .distinct()
        .limit(constraint.maxExactCandidates())
        .sorted(Comparator.comparing(candidate -> candidate.strategy().strategyId()))
        .toList();
  }

  private static void search(
      List<StrategyPortfolioCandidate> candidates,
      StrategyPortfolioConstraint constraint,
      int index,
      List<StrategyPortfolioCandidate> selected,
      SearchState state) {
    if (selected.size() > constraint.requestedSize()) {
      return;
    }
    if (index == candidates.size()) {
      if (better(selected, state.best)) {
        state.best = List.copyOf(selected);
      }
      return;
    }
    search(candidates, constraint, index + 1, selected, state);
    StrategyPortfolioCandidate candidate = candidates.get(index);
    if (eligible(candidate, selected, constraint)) {
      selected.add(candidate);
      search(candidates, constraint, index + 1, selected, state);
      selected.removeLast();
    }
  }

  private static boolean eligible(
      StrategyPortfolioCandidate candidate,
      List<StrategyPortfolioCandidate> selected,
      StrategyPortfolioConstraint constraint) {
    if (candidate.preflight().hardRejected()
        || candidate.preflight().requiresRegeneration()
        || candidate.feasibility().total() <= 0.0d
        || constraint.activeStructuralSignatures().contains(
            candidate.signature().structuralSignatureHash())) {
      return false;
    }
    Set<String> unresolved = candidate.preflight().unresolvedRequiredClaimKeys();
    if (!java.util.Collections.disjoint(
        unresolved, constraint.activeUnresolvedRequiredClaimKeys())) {
      return false;
    }
    for (StrategyPortfolioCandidate existing : selected) {
      if (candidate.strategy().strategyId().equals(existing.strategy().strategyId())
          || candidate.signature().structuralSignatureHash()
              .equals(existing.signature().structuralSignatureHash())
          || !java.util.Collections.disjoint(
              unresolved, existing.preflight().unresolvedRequiredClaimKeys())) {
        return false;
      }
    }
    return true;
  }

  private static boolean better(
      List<StrategyPortfolioCandidate> candidate,
      List<StrategyPortfolioCandidate> incumbent) {
    if (incumbent == null) {
      return true;
    }
    double candidateObjective = objective(candidate);
    double incumbentObjective = objective(incumbent);
    if (Math.abs(candidateObjective - incumbentObjective) > 1.0e-12d) {
      return candidateObjective > incumbentObjective;
    }
    return strategyIdKey(candidate).compareTo(strategyIdKey(incumbent)) < 0;
  }

  private static double objective(List<StrategyPortfolioCandidate> candidates) {
    Set<StrategyMechanismPrimitive> profiles = new LinkedHashSet<>();
    candidates.forEach(candidate -> profiles.addAll(candidate.profile().primitives()));
    double evidence =
        candidates.stream()
            .mapToDouble(candidate -> candidate.preflight().requiredClaimEvidenceCoverage())
            .sum();
    double scores = candidates.stream().mapToDouble(candidate -> candidate.feasibility().total()).sum();
    return candidates.size() * 10.0d + scores + profiles.size() * 0.01d + evidence * 0.01d;
  }

  private static String strategyIdKey(List<StrategyPortfolioCandidate> candidates) {
    return candidates.stream()
        .map(candidate -> candidate.strategy().strategyId())
        .sorted()
        .collect(java.util.stream.Collectors.joining("\u0000"));
  }

  private static String rejectionReason(
      StrategyPortfolioCandidate candidate,
      List<StrategyPortfolioCandidate> selected,
      StrategyPortfolioConstraint constraint) {
    if (candidate.preflight().hardRejected()) {
      return candidate.preflight().claims().stream()
          .filter(
              claim ->
                  claim.status() == CriticalClaimPreflightStatus.VERIFIED_REFUTED
                      || claim.status() == CriticalClaimPreflightStatus.PERMANENTLY_BLOCKED
                      || claim.status() == CriticalClaimPreflightStatus.ERROR)
          .map(claim -> claim.status().name())
          .findFirst()
          .orElse("PREFLIGHT_HARD_REJECTION");
    }
    if (candidate.preflight().requiresRegeneration()) {
      return "SUPPORTING_CLAIM_REQUIRES_REGENERATION";
    }
    if (constraint.activeStructuralSignatures().contains(
        candidate.signature().structuralSignatureHash())
        || selected.stream()
            .anyMatch(
                existing ->
                    existing.signature().structuralSignatureHash()
                        .equals(candidate.signature().structuralSignatureHash()))) {
      return "SAME_STRUCTURAL_MECHANISM";
    }
    if (!java.util.Collections.disjoint(
            candidate.preflight().unresolvedRequiredClaimKeys(),
            constraint.activeUnresolvedRequiredClaimKeys())
        || selected.stream()
            .anyMatch(
                existing ->
                    !java.util.Collections.disjoint(
                        existing.preflight().unresolvedRequiredClaimKeys(),
                        candidate.preflight().unresolvedRequiredClaimKeys()))) {
      return "SHARED_UNRESOLVED_REQUIRED_CLAIM";
    }
    return "LOWER_GLOBAL_PORTFOLIO_OBJECTIVE";
  }

  private static final class SearchState {
    private List<StrategyPortfolioCandidate> best;
  }
}
