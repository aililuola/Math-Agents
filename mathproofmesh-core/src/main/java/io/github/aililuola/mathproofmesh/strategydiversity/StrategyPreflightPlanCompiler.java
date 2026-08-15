package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.CriticalClaimPreflightPlan;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategyPreflightPlan;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Binds claim preflights only to typed requests embedded in the captured strategy contract. */
public final class StrategyPreflightPlanCompiler {
  private static final Set<String> SAFE_KINDS =
      Set.of(
          "sympy_simplify",
          "sympy_equivalent",
          "numeric_counterexample",
          "polynomial_factor",
          "modular_exhaustive",
          "bounded_integer_search",
          "graph_certificate",
          "recurrence_check",
          "bounded_greedy_sequence",
          "candidate_period_check",
          "exact_geometry",
          "lean_check");

  public StrategyPreflightPlan compile(String problemHash, StrategyCard strategy) {
    java.util.Objects.requireNonNull(strategy, "strategy");
    Map<String, ToolRequest> requests = new LinkedHashMap<>();
    for (ToolRequest request : strategy.calculationChecks()) {
      if (!SAFE_KINDS.contains(request.kind())) {
        continue;
      }
      ToolRequest previous = requests.putIfAbsent(request.requestId(), request);
      if (previous != null) {
        throw new IllegalArgumentException("duplicate calculation request id");
      }
    }
    List<CriticalClaimPreflightPlan> claims =
        strategy.criticalClaims().stream()
            .map(
                claim -> {
                  ToolRequest selected =
                      claim.evidenceRefs().stream()
                          .map(requests::get)
                          .filter(java.util.Objects::nonNull)
                          .filter(
                              request ->
                                  claim.preferredTool() == null
                                      || claim.preferredTool().isBlank()
                                      || request.kind().equals(claim.preferredTool()))
                          .findFirst()
                          .orElse(null);
                  return new CriticalClaimPreflightPlan(
                      claim.claimId(),
                      selected == null ? "" : selected.requestId(),
                      claim.evidenceRefs(),
                      selected == null ? List.of() : List.of(selected.requestId()));
                })
            .toList();
    return new StrategyPreflightPlan(problemHash, strategy.strategyId(), claims);
  }

  public Set<String> registeredContractIds(StrategyCard strategy) {
    return strategy.calculationChecks().stream()
        .filter(request -> SAFE_KINDS.contains(request.kind()))
        .map(ToolRequest::requestId)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  public ToolRequest request(
      StrategyCard strategy, CriticalClaimPreflightPlan claimPlan) {
    if (claimPlan.computationContractId() == null
        || claimPlan.computationContractId().isBlank()) {
      return null;
    }
    return strategy.calculationChecks().stream()
        .filter(request -> SAFE_KINDS.contains(request.kind()))
        .filter(request -> request.requestId().equals(claimPlan.computationContractId()))
        .findFirst()
        .orElse(null);
  }
}
