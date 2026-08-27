package io.github.aililuola.mathproofmesh.memory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class NegativeKnowledgeAdmissionGate {
  private final NegativeKnowledgeRegistry registry;

  public NegativeKnowledgeAdmissionGate(NegativeKnowledgeRegistry registry) {
    this.registry = java.util.Objects.requireNonNull(registry, "registry");
  }

  public NegativeKnowledgeDecision evaluate(
      NegativeKnowledgeCandidate candidate, int currentRound) {
    return registry.decide(candidate, currentRound);
  }

  public List<NegativeKnowledgeDecision> evaluateAll(
      Collection<NegativeKnowledgeCandidate> candidates, int currentRound) {
    java.util.Objects.requireNonNull(candidates, "candidates");
    List<NegativeKnowledgeDecision> decisions = new ArrayList<>(candidates.size());
    candidates.forEach(candidate -> decisions.add(evaluate(candidate, currentRound)));
    return List.copyOf(decisions);
  }

  public void requireAllowed(NegativeKnowledgeCandidate candidate, int currentRound) {
    NegativeKnowledgeDecision decision = evaluate(candidate, currentRound);
    if (!decision.allowed()) {
      throw new NegativeKnowledgeBlockedException(decision);
    }
  }

  public void requireAllAllowed(
      Collection<NegativeKnowledgeCandidate> candidates, int currentRound) {
    for (NegativeKnowledgeDecision decision : evaluateAll(candidates, currentRound)) {
      if (!decision.allowed()) {
        throw new NegativeKnowledgeBlockedException(decision);
      }
    }
  }

  public NegativeKnowledgeRegistry registry() {
    return registry;
  }
}
