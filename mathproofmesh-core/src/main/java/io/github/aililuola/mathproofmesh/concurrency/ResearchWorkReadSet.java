package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Set;

public record ResearchWorkReadSet(Set<String> authorityRefs, Set<String> inputRefs) {
  public ResearchWorkReadSet {
    authorityRefs = authorityRefs == null ? Set.of() : Set.copyOf(authorityRefs);
    inputRefs = inputRefs == null ? Set.of() : Set.copyOf(inputRefs);
  }

  public static ResearchWorkReadSet empty() {
    return new ResearchWorkReadSet(Set.of(), Set.of());
  }
}
