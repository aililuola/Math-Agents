package io.github.aililuola.mathproofmesh.proofgraph;

import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.contract.RouteDescriptor;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.RouteStatus;
import java.util.List;

final class ProofGraphFixtures {
  static final String PROBLEM_HASH = "9".repeat(64);

  private ProofGraphFixtures() {}

  static ProofObligation obligation(
      String id, String statement, String route, List<String> dependencies) {
    return obligation(
        id,
        statement,
        List.of(route),
        dependencies,
        ObligationKind.SUBGOAL,
        0.8,
        0.7);
  }

  static ProofObligation obligation(
      String id,
      String statement,
      List<String> routes,
      List<String> dependencies,
      ObligationKind kind,
      double priority,
      double centrality) {
    return new ProofObligation(
        List.of(),
        centrality,
        "",
        dependencies,
        List.of(),
        List.of(),
        null,
        kind,
        statement,
        id,
        priority,
        PROBLEM_HASH,
        List.of(),
        routes,
        statement,
        "open");
  }

  static MessageEnvelope fact(String id, String statement) {
    return message(
        id,
        statement,
        statement,
        "route-a",
        "author-a",
        MessageType.VERIFIED_LEMMA,
        EvidenceType.NATURAL_PROOF_AUDITED,
        MemoryTier.FACT,
        ClaimStatus.VERIFIED);
  }

  static MessageEnvelope message(
      String id,
      String statement,
      String conclusion,
      String route,
      String author,
      MessageType type,
      EvidenceType evidence,
      MemoryTier tier,
      ClaimStatus status) {
    return new MessageEnvelope(
        List.of(),
        List.of(),
        conclusion,
        "",
        null,
        List.of(),
        List.of(),
        evidence,
        tier,
        id,
        type,
        1.0,
        statement,
        PROBLEM_HASH,
        List.of(),
        null,
        0,
        "1",
        List.of(),
        author,
        RouteRole.PROVER,
        route,
        statement,
        List.of(),
        2,
        List.of(),
        0.95,
        status);
  }

  static RouteDescriptor route(
      String id, RouteStatus status, List<String> mechanisms) {
    return new RouteDescriptor(
        null,
        0,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        mechanisms,
        List.of(),
        null,
        List.of(),
        0,
        false,
        null,
        id,
        List.of(),
        0,
        status,
        "strategy-" + id,
        String.join("-", mechanisms));
  }
}
