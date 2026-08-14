package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyBlueprintCompiler;

public record StrategyPortfolioCandidate(
    StrategyCard strategy,
    StrategyBlueprintCompiler.Compilation blueprint,
    StrategyMechanismSignature signature,
    StrategyMechanismProfile profile,
    StrategyPreflightReport preflight,
    StrategyFeasibilityScore feasibility) {
  public StrategyPortfolioCandidate {
    strategy = java.util.Objects.requireNonNull(strategy, "strategy");
    blueprint = java.util.Objects.requireNonNull(blueprint, "blueprint");
    signature = java.util.Objects.requireNonNull(signature, "signature");
    profile = java.util.Objects.requireNonNull(profile, "profile");
    preflight = java.util.Objects.requireNonNull(preflight, "preflight");
    feasibility = java.util.Objects.requireNonNull(feasibility, "feasibility");
    if (!strategy.strategyId().equals(blueprint.blueprint().strategyId())
        || !strategy.strategyId().equals(preflight.strategyId())) {
      throw new IllegalArgumentException("portfolio candidate projections disagree");
    }
  }
}
