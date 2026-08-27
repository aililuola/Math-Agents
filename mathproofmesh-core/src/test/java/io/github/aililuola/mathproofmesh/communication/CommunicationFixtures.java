package io.github.aililuola.mathproofmesh.communication;

import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.RouteDescriptor;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.RouteStatus;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.List;

final class CommunicationFixtures {
  static final String PROBLEM_HASH = "7".repeat(64);

  private CommunicationFixtures() {}

  static RouteRegistry routes() {
    RouteRegistry routes = new RouteRegistry(PROBLEM_HASH, 2, 8, 0.9);
    for (String suffix : List.of("a", "b", "c")) {
      String routeId = "route-" + suffix;
      routes.register(route(routeId, "strategy-" + suffix, "mechanism-" + suffix));
      routes.assignMember(routeId, "author-" + suffix, RouteRole.PROVER, 0);
      routes.assignMember(routeId, "referee-" + suffix, RouteRole.REFEREE, 0);
    }
    routes.setNeighbors("route-a", List.of("route-b", "route-c"));
    routes.setNeighbors("route-b", List.of("route-a", "route-c"));
    routes.setNeighbors("route-c", List.of("route-a", "route-b"));
    return routes;
  }

  static RouteDescriptor route(String routeId, String strategyId, String mechanism) {
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
        List.of(mechanism),
        List.of(),
        null,
        List.of(),
        0,
        false,
        null,
        routeId,
        List.of(),
        0,
        RouteStatus.ACTIVE,
        strategyId,
        mechanism);
  }

  static MessageEnvelope fact(String messageId, List<String> targets) {
    return message(
        messageId,
        PROBLEM_HASH,
        "route-a",
        "author-a",
        RouteRole.PROVER,
        targets,
        "verified identity",
        "verified identity",
        MessageType.VERIFIED_LEMMA,
        EvidenceType.NATURAL_PROOF_AUDITED,
        MemoryTier.FACT,
        ClaimStatus.VERIFIED,
        0.99,
        1.0,
        1,
        2,
        "1",
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  static MessageEnvelope insight(String messageId, List<String> targets) {
    return message(
        messageId,
        PROBLEM_HASH,
        "route-a",
        "author-a",
        RouteRole.PROVER,
        targets,
        "unverified route idea " + messageId,
        "unverified route idea " + messageId,
        MessageType.FAILURE_RECORD,
        EvidenceType.UNVERIFIED_IDEA,
        MemoryTier.INSIGHT,
        ClaimStatus.PROPOSED,
        0.2,
        1.0,
        1,
        2,
        "1",
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  static MessageEnvelope message(
      String messageId,
      String problemHash,
      String sourceRoute,
      String sourceAgent,
      RouteRole sourceRole,
      List<String> targets,
      String statement,
      String conclusion,
      MessageType messageType,
      EvidenceType evidenceType,
      MemoryTier memoryTier,
      ClaimStatus status,
      double verificationConfidence,
      double normalizationConfidence,
      int roundCreated,
      int ttlRounds,
      String schemaVersion,
      List<String> artifactRefs,
      List<String> dependencies,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> bindings) {
    return new MessageEnvelope(
        artifactRefs,
        List.of(),
        conclusion,
        "",
        null,
        dependencies,
        List.of(),
        evidenceType,
        memoryTier,
        messageId,
        messageType,
        normalizationConfidence,
        statement.toLowerCase(java.util.Locale.ROOT),
        problemHash,
        quantifiers,
        null,
        roundCreated,
        schemaVersion,
        List.of(),
        sourceAgent,
        sourceRole,
        sourceRoute,
        statement,
        targets,
        ttlRounds,
        bindings,
        verificationConfidence,
        status);
  }

  static MessageBroker broker(
      MessageBrokerPolicy policy,
      RouteRegistry routes,
      DependencyCatalog dependencies,
      InMemoryMessageRepository repository) {
    return new MessageBroker(
        policy,
        routes,
        ArtifactCatalog.allowRunScopedReferences(),
        dependencies,
        repository);
  }

  static DependencyCatalog acceptingDependencies() {
    return new DependencyCatalog() {
      @Override
      public boolean exists(String dependencyId) {
        return true;
      }

      @Override
      public boolean invalidated(String dependencyId) {
        return false;
      }

      @Override
      public boolean wouldCreateCycle(String messageId, List<String> dependencyIds) {
        return false;
      }
    };
  }
}
