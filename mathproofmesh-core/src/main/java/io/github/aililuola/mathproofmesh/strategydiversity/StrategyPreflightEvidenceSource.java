package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.Optional;

@FunctionalInterface
public interface StrategyPreflightEvidenceSource {
  Optional<CriticalClaimPreflightEvidence> evaluate(
      CriticalClaimSemanticKey key, CriticalClaimPreflightSpec spec);
}
