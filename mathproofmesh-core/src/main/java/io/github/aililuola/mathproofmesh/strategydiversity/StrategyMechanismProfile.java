package io.github.aililuola.mathproofmesh.strategydiversity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.LinkedHashSet;
import java.util.Set;

public record StrategyMechanismProfile(
    @JsonDeserialize(as = LinkedHashSet.class) Set<StrategyMechanismPrimitive> primitives) {
  public StrategyMechanismProfile {
    primitives =
        primitives == null || primitives.isEmpty()
            ? Set.of(StrategyMechanismPrimitive.UNKNOWN)
            : StrategyImmutableCollections.orderedSet(primitives);
  }

  @Override
  public Set<StrategyMechanismPrimitive> primitives() {
    return StrategyImmutableCollections.orderedSet(primitives);
  }
}
