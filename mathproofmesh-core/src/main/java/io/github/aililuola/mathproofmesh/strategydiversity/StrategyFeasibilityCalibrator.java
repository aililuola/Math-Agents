package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyBlueprintCompiler;
import java.util.OptionalDouble;

public final class StrategyFeasibilityCalibrator {
  private final StrategyDiversityConfig config;

  public StrategyFeasibilityCalibrator(StrategyDiversityConfig config) {
    this.config = java.util.Objects.requireNonNull(config, "config");
  }

  public StrategyFeasibilityScore calibrate(
      StrategyCard strategy,
      StrategyBlueprintCompiler.Compilation blueprint,
      StrategyPreflightReport preflight,
      double rootGoalAlignment,
      double mechanismNovelty,
      double portfolioComplementarity,
      double commonModePenalty) {
    java.util.Objects.requireNonNull(strategy, "strategy");
    java.util.Objects.requireNonNull(blueprint, "blueprint");
    java.util.Objects.requireNonNull(preflight, "preflight");
    double alignment = unit(rootGoalAlignment);
    double completeness =
        blueprint.blueprint().completePathToMainGoal()
            ? blueprint.blueprint().confidence()
            : 0.0d;
    double evidence = preflight.requiredClaimEvidenceCoverage();
    double novelty = unit(mechanismNovelty);
    double complementarity = unit(portfolioComplementarity);
    double commonPenalty = unit(commonModePenalty);
    double costPenalty = unit(strategy.estimatedCost());
    double base =
        config.rootGoalAlignmentWeight() * alignment
            + config.blueprintCompletenessWeight() * completeness
            + config.requiredClaimEvidenceWeight() * evidence
            + config.mechanismNoveltyWeight() * novelty
            + config.portfolioComplementarityWeight() * complementarity
            - config.commonModePenaltyWeight() * commonPenalty
            - config.costPenaltyWeight() * costPenalty;
    base = Math.max(0.0d, Math.min(1.0d, base));
    double priorCapForRatio = base / 9.0d;
    double modelPrior =
        Math.min(
            config.maximumModelPriorContribution() * unit(strategy.estimatedSuccess()),
            priorCapForRatio);
    double raw = Math.min(1.0d, base + modelPrior);
    boolean allRequiredUnresolved =
        preflight.claims().stream()
            .filter(claim -> "required".equals(claim.necessity()))
            .findAny()
            .isPresent()
            && preflight.claims().stream()
                .filter(claim -> "required".equals(claim.necessity()))
                .allMatch(
                    claim ->
                        claim.status() == CriticalClaimPreflightStatus.UNKNOWN
                            || claim.status() == CriticalClaimPreflightStatus.UNTESTABLE
                            || claim.status()
                                == CriticalClaimPreflightStatus.NOT_REFUTED_IN_BOUNDED_SCOPE);
    OptionalDouble upper =
        preflight.hardRejected()
            ? OptionalDouble.of(0.0d)
            : allRequiredUnresolved
                ? OptionalDouble.of(config.unknownRequiredClaimUpperBound())
                : OptionalDouble.empty();
    double total = upper.isPresent() ? Math.min(raw, upper.getAsDouble()) : raw;
    if (total == 0.0d) {
      modelPrior = 0.0d;
    } else if (modelPrior / total > 0.10d) {
      modelPrior = total * 0.10d;
    }
    return new StrategyFeasibilityScore(
        alignment,
        completeness,
        evidence,
        novelty,
        complementarity,
        commonPenalty,
        costPenalty,
        modelPrior,
        total,
        upper);
  }

  public StrategyDiversityConfig config() {
    return config;
  }

  private static double unit(double value) {
    if (!Double.isFinite(value)) {
      return 0.0d;
    }
    return Math.max(0.0d, Math.min(1.0d, value));
  }
}
