package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.Set;

public record StrategyMechanismProfile(Set<StrategyMechanismPrimitive> primitives) {
  public StrategyMechanismProfile {
    primitives =
        primitives == null || primitives.isEmpty()
            ? Set.of(StrategyMechanismPrimitive.UNKNOWN)
            : Set.copyOf(primitives);
  }

  @Override
  public Set<StrategyMechanismPrimitive> primitives() {
    return Set.copyOf(primitives);
  }
}
