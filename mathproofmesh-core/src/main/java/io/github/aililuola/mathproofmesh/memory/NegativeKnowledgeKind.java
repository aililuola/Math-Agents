package io.github.aililuola.mathproofmesh.memory;

public enum NegativeKnowledgeKind {
  TEMPORARY_HYPOTHESIS_REJECTION(false),
  VERIFIED_COUNTEREXAMPLE(true),
  DETERMINISTIC_GUARDRAIL(true);

  private final boolean permanent;

  NegativeKnowledgeKind(boolean permanent) {
    this.permanent = permanent;
  }

  public boolean permanent() {
    return permanent;
  }
}
