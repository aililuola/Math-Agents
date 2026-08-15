package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.Set;

public record StrategyPortfolioConstraint(
    int requestedSize,
    int minimumSize,
    int maxExactCandidates,
    Set<String> activeStructuralSignatures,
    Set<String> activeUnresolvedRequiredClaimKeys,
    double minimumAdmissibleFeasibility,
    double minimumBlueprintCompleteness,
    double minimumRequiredClaimEvidence) {
  public StrategyPortfolioConstraint {
    if (requestedSize < 0 || minimumSize < 0 || minimumSize > requestedSize) {
      throw new IllegalArgumentException("portfolio size constraint is invalid");
    }
    if (maxExactCandidates < 1) {
      throw new IllegalArgumentException("maxExactCandidates must be positive");
    }
    activeStructuralSignatures =
        activeStructuralSignatures == null ? Set.of() : Set.copyOf(activeStructuralSignatures);
    activeUnresolvedRequiredClaimKeys =
        activeUnresolvedRequiredClaimKeys == null
            ? Set.of()
            : Set.copyOf(activeUnresolvedRequiredClaimKeys);
    unit(minimumAdmissibleFeasibility, "minimumAdmissibleFeasibility");
    unit(minimumBlueprintCompleteness, "minimumBlueprintCompleteness");
    unit(minimumRequiredClaimEvidence, "minimumRequiredClaimEvidence");
  }

  public StrategyPortfolioConstraint(
      int requestedSize,
      int minimumSize,
      int maxExactCandidates,
      Set<String> activeStructuralSignatures,
      Set<String> activeUnresolvedRequiredClaimKeys) {
    this(
        requestedSize,
        minimumSize,
        maxExactCandidates,
        activeStructuralSignatures,
        activeUnresolvedRequiredClaimKeys,
        StrategyDiversityConfig.defaults().minimumAdmissibleFeasibility(),
        StrategyDiversityConfig.defaults().minimumBlueprintCompleteness(),
        StrategyDiversityConfig.defaults().minimumRequiredClaimEvidenceForPrimaryRoute());
  }

  @Override
  public Set<String> activeStructuralSignatures() {
    return Set.copyOf(activeStructuralSignatures);
  }

  @Override
  public Set<String> activeUnresolvedRequiredClaimKeys() {
    return Set.copyOf(activeUnresolvedRequiredClaimKeys);
  }

  private static void unit(double value, String field) {
    if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
      throw new IllegalArgumentException(field + " must be in [0,1]");
    }
  }
}
