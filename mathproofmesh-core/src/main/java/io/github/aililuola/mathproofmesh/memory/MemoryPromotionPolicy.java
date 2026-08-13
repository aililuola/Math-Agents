package io.github.aililuola.mathproofmesh.memory;

import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MemoryPromotionPolicy {
  private static final Set<EvidenceType> REUSABLE_FACT_EVIDENCE =
      EnumSet.of(
          EvidenceType.NATURAL_PROOF_AUDITED,
          EvidenceType.EXACT_SYMBOLIC_IDENTITY,
          EvidenceType.COMPLETE_FINITE_ENUMERATION,
          EvidenceType.SAT_SMT_CERTIFICATE,
          EvidenceType.FORMAL_KERNEL_CERTIFICATE);

  private final MemoryPolicy policy;

  public MemoryPromotionPolicy(MemoryPolicy policy) {
    this.policy = java.util.Objects.requireNonNull(policy, "policy");
  }

  void validate(
      MessageEnvelope candidate,
      String refereeAgentId,
      double confidence,
      Collection<MessageEnvelope> facts,
      NegativeKnowledgeAdmissionGate negativeKnowledgeGate,
      int currentRound) {
    if (refereeAgentId == null || refereeAgentId.isBlank()) {
      throw new IllegalArgumentException("facts require an independent referee");
    }
    if (refereeAgentId.equals(candidate.sourceAgentId())) {
      throw new IllegalArgumentException("the author cannot promote its own insight");
    }
    if (!REUSABLE_FACT_EVIDENCE.contains(candidate.evidenceType())) {
      throw new IllegalArgumentException("this evidence cannot be promoted to FactMemory");
    }
    if (confidence < policy.factPassThreshold()) {
      throw new IllegalArgumentException("verification confidence is below the fact gate");
    }
    if (candidate.normalizationConfidence() < policy.factPassThreshold()) {
      throw new IllegalArgumentException("quantifier/scope normalization is incomplete");
    }
    if (!dependenciesResolved(candidate.dependencies(), facts)) {
      throw new IllegalArgumentException("fact dependencies are unresolved");
    }
    if (wouldCreateCycle(candidate.messageId(), candidate.dependencies(), facts)) {
      throw new IllegalArgumentException("fact dependency cycle detected");
    }
    negativeKnowledgeGate.requireAllowed(
        NegativeKnowledgeRegistry.candidateFromMessage(
            candidate,
            NegativeKnowledgeSurface.FACT_PROMOTION,
            NegativeCandidateIntent.FACT_PROMOTION),
        currentRound);
  }

  private static boolean dependenciesResolved(
      List<String> dependencies, Collection<MessageEnvelope> facts) {
    Set<String> accepted = new HashSet<>();
    for (MessageEnvelope fact : facts) {
      accepted.add(fact.messageId());
      accepted.add(fact.contentHash());
    }
    return dependencies.stream()
        .allMatch(dependency -> dependency.startsWith("external:") || accepted.contains(dependency));
  }

  private static boolean wouldCreateCycle(
      String nodeId,
      List<String> dependencies,
      Collection<MessageEnvelope> facts) {
    Map<String, List<String>> graph = new HashMap<>();
    for (MessageEnvelope fact : facts) {
      graph.put(fact.messageId(), fact.dependencies());
    }
    graph.put(nodeId, dependencies);
    for (String dependency : dependencies) {
      if (reaches(dependency, nodeId, graph, new HashSet<>())) {
        return true;
      }
    }
    return false;
  }

  private static boolean reaches(
      String current,
      String target,
      Map<String, List<String>> graph,
      Set<String> seen) {
    if (current.equals(target)) {
      return true;
    }
    if (!seen.add(current)) {
      return false;
    }
    return graph.getOrDefault(current, List.of()).stream()
        .anyMatch(next -> reaches(next, target, graph, seen));
  }

}
