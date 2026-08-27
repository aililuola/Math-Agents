package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

final class StrategyImmutableCollections {
  private StrategyImmutableCollections() {}

  static <T> Set<T> orderedSet(Set<? extends T> values) {
    if (values == null || values.isEmpty()) {
      return Set.of();
    }
    return Collections.unmodifiableSet(new LinkedHashSet<>(values));
  }
}
