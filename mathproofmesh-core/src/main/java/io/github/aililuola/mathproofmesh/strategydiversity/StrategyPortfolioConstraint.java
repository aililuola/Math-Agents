package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.Set;

public record StrategyPortfolioConstraint(
    int requestedSize,
    int minimumSize,
    int maxExactCandidates,
    Set<String> activeStructuralSignatures,
    Set<String> activeUnresolvedRequiredClaimKeys) {
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
  }

  @Override
  public Set<String> activeStructuralSignatures() {
    return Set.copyOf(activeStructuralSignatures);
  }

  @Override
  public Set<String> activeUnresolvedRequiredClaimKeys() {
    return Set.copyOf(activeUnresolvedRequiredClaimKeys);
  }
}
