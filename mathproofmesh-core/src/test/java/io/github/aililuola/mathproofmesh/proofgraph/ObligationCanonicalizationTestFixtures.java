package io.github.aililuola.mathproofmesh.proofgraph;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import java.util.List;
import java.util.Map;

final class ObligationCanonicalizationTestFixtures {
  static final String PROBLEM_HASH = "5".repeat(64);

  private ObligationCanonicalizationTestFixtures() {}

  static ProofObligation obligation(
      String id, String route, String statement, String normalized, String familyKey) {
    return obligation(
        id,
        route,
        statement,
        normalized,
        familyKey,
        ObligationKind.LEMMA,
        List.of(),
        List.of(),
        "plan-" + id);
  }

  static ProofObligation obligation(
      String id,
      String route,
      String statement,
      String normalized,
      String familyKey,
      ObligationKind kind,
      List<String> assumptions,
      List<QuantifierSpec> quantifiers,
      String plan) {
    return new ProofObligation(
        assumptions,
        0.7d,
        "",
        List.of(),
        List.of(ContractObjectMapper.toTree(Map.of("plan", plan))),
        List.of(),
        familyKey == null || familyKey.isBlank() ? null : familyKey,
        kind,
        normalized,
        id,
        0.8d,
        PROBLEM_HASH,
        quantifiers,
        List.of(route),
        statement,
        "open");
  }

  static ObligationCreationContext context(
      ProofObligation obligation,
      String route,
      String familyKey,
      List<String> scope,
      String polarity,
      Map<String, String> roles,
      int round) {
    return new ObligationCreationContext(
        obligation.problemHash(),
        route,
        "strategy-" + route,
        ObligationSourceType.STRATEGY_BLUEPRINT,
        "blueprint://" + obligation.obligationId(),
        scope,
        polarity,
        roles,
        familyKey,
        familyKey,
        BottleneckRelationType.SHARES_UPSTREAM_BOTTLENECK,
        ObligationOccurrenceSchedulingState.ACTIVE,
        round);
  }

  static MessageEnvelope verifiedFact(String id, String statement) {
    return message(
        id,
        statement,
        MessageType.VERIFIED_LEMMA,
        EvidenceType.NATURAL_PROOF_AUDITED,
        MemoryTier.FACT,
        ClaimStatus.VERIFIED);
  }

  static MessageEnvelope counterexample(String id, String statement) {
    return message(
        id,
        statement,
        MessageType.COUNTEREXAMPLE,
        EvidenceType.COUNTEREXAMPLE,
        MemoryTier.NEGATIVE,
        ClaimStatus.REJECTED);
  }

  private static MessageEnvelope message(
      String id,
      String statement,
      MessageType type,
      EvidenceType evidence,
      MemoryTier tier,
      ClaimStatus status) {
    return new MessageEnvelope(
        List.of(),
        List.of(),
        statement,
        "",
        null,
        List.of(),
        List.of(),
        evidence,
        tier,
        id,
        type,
        1.0d,
        statement,
        PROBLEM_HASH,
        List.of(),
        null,
        0,
        "1",
        List.of(),
        "test-author",
        RouteRole.PROVER,
        "test-route",
        statement,
        List.of(),
        2,
        List.of(),
        1.0d,
        status);
  }
}
