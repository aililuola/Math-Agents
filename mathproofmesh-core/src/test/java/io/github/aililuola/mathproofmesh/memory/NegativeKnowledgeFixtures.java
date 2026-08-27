package io.github.aililuola.mathproofmesh.memory;

import io.github.aililuola.mathproofmesh.computation.ComputationEvidenceGate;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.List;

final class NegativeKnowledgeFixtures {
  static final String PROBLEM_A = "a".repeat(64);
  static final String PROBLEM_B = "b".repeat(64);
  static final String FINITE_PRIMES =
      "The sequence has finite prime support.";
  static final List<String> GREEDY_SCOPE =
      List.of("submitted greedy-GCD sequence problem");

  private NegativeKnowledgeFixtures() {}

  static NegativeKnowledgeCandidate candidate(
      String problemHash,
      String statement,
      NegativeKnowledgeTargetType targetType,
      NegativeKnowledgeSurface surface,
      NegativeCandidateIntent intent) {
    return candidate(
        problemHash,
        statement,
        targetType,
        List.of(),
        List.of(),
        List.of(),
        GREEDY_SCOPE,
        surface,
        intent);
  }

  static NegativeKnowledgeCandidate candidate(
      String problemHash,
      String statement,
      NegativeKnowledgeTargetType targetType,
      List<String> assumptions,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> bindings,
      List<String> scope,
      NegativeKnowledgeSurface surface,
      NegativeCandidateIntent intent) {
    return new NegativeKnowledgeCandidate(
        problemHash,
        targetType,
        statement,
        "",
        assumptions,
        quantifiers,
        bindings,
        scope,
        surface,
        intent);
  }

  static DeterministicNegativeSeed deterministicSeed() {
    return DeterministicNegativeSeed.trustedCodeSeed(
        "finite-prime-support",
        NegativeKnowledgeTargetType.CLAIM,
        "The entire sequence contains only finitely many prime divisors.",
        List.of(
            FINITE_PRIMES,
            "Only finitely many primes divide terms of the sequence.",
            "\u5e8f\u5217\u7684\u7d20\u6570\u56e0\u5b50\u96c6\u5408\u6709\u9650\u3002"),
        List.of(),
        List.of(),
        List.of(),
        GREEDY_SCOPE,
        "Finite prime support is not established by the greedy-GCD recurrence.");
  }

  static MessageEnvelope negative(
      String id,
      String problemHash,
      String statement,
      int round,
      int ttl,
      String sourceAgent,
      EvidenceType evidenceType,
      List<String> artifacts,
      String rawSourceRef,
      List<String> assumptions,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> bindings,
      List<String> scope) {
    return new MessageEnvelope(
        artifacts,
        assumptions,
        statement,
        "",
        null,
        List.of(),
        List.of(),
        evidenceType,
        MemoryTier.NEGATIVE,
        id,
        evidenceType == EvidenceType.COUNTEREXAMPLE
            ? MessageType.COUNTEREXAMPLE
            : MessageType.FAILURE_RECORD,
        1.0d,
        statement,
        problemHash,
        quantifiers,
        rawSourceRef,
        round,
        "1",
        scope,
        sourceAgent,
        RouteRole.SKEPTIC,
        "route-negative",
        statement,
        List.of(),
        ttl,
        bindings,
        1.0d,
        ClaimStatus.REJECTED);
  }

  static MessageEnvelope temporary(String id, String statement, int round, int ttl) {
    return negative(
        id,
        PROBLEM_A,
        statement,
        round,
        ttl,
        "model-skeptic",
        EvidenceType.UNVERIFIED_IDEA,
        List.of(),
        null,
        List.of(),
        List.of(),
        List.of(),
        GREEDY_SCOPE);
  }

  static MessageEnvelope counterexample(String id, String statement) {
    return negative(
        id,
        PROBLEM_A,
        statement,
        0,
        2,
        "independent-computation-replay",
        EvidenceType.COUNTEREXAMPLE,
        List.of("experiment://" + id),
        "result-hash-" + id,
        List.of(),
        List.of(),
        List.of(),
        GREEDY_SCOPE);
  }

  static VerifiedCounterexampleAuthority verifiedAuthority(String id, String statement) {
    return VerifiedCounterexampleAuthority.independentReplay(
        true,
        true,
        ComputationEvidenceGate.EvidenceAuthority.REFUTED,
        "experiment://" + id,
        statement,
        "result-hash-" + id,
        List.of());
  }

  static QuantifierSpec forallN() {
    return new QuantifierSpec("n", "positive integers", "forall", 0, List.of(), "n");
  }

  static VariableBinding bindingN() {
    return new VariableBinding(List.of(), "n", "positive integers", "goal", "n");
  }
}
