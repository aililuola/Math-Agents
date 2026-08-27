package io.github.aililuola.mathproofmesh.memory;

public final class NegativeKnowledgeBlockedException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  private final transient NegativeKnowledgeDecision decision;

  public NegativeKnowledgeBlockedException(NegativeKnowledgeDecision decision) {
    super(decision.code() + ": " + decision.detail());
    this.decision = java.util.Objects.requireNonNull(decision, "decision");
  }

  public NegativeKnowledgeDecision decision() {
    return decision;
  }
}
