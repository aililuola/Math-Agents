package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.communication.ArtifactCatalog;
import io.github.aililuola.mathproofmesh.communication.DependencyCatalog;
import io.github.aililuola.mathproofmesh.communication.InMemoryMessageRepository;
import io.github.aililuola.mathproofmesh.communication.MessageBroker;
import io.github.aililuola.mathproofmesh.communication.MessageBrokerPolicy;
import io.github.aililuola.mathproofmesh.communication.RouteRegistry;
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

final class DesktopBrokerLegacyBlackBoxFixture {
  static final String PROBLEM_HASH = "9".repeat(64);

  final RouteRegistry routes = new RouteRegistry(PROBLEM_HASH, 2, 8, 0.99d);
  final InMemoryMessageRepository repository = new InMemoryMessageRepository();
  final MessageBroker broker;

  DesktopBrokerLegacyBlackBoxFixture() {
    routes.register(route("route-a", "strategy-a"));
    routes.register(route("route-b", "strategy-b"));
    routes.assignMember("route-a", "author-a", RouteRole.PROVER, 0);
    routes.assignMember("route-a", "referee-a", RouteRole.REFEREE, 0);
    routes.assignMember("route-b", "author-b", RouteRole.PROVER, 0);
    routes.assignMember("route-b", "referee-b", RouteRole.REFEREE, 0);
    routes.setNeighbors("route-a", List.of("route-b"));
    routes.setNeighbors("route-b", List.of("route-a"));
    broker =
        new MessageBroker(
            MessageBrokerPolicy.strictDefaults(),
            routes,
            ArtifactCatalog.allowRunScopedReferences(),
            new DependencyCatalog() {
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
            },
            repository);
  }

  MessageEnvelope fact(
      String id,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> bindings,
      List<String> scope,
      String polarity) {
    return new MessageEnvelope(
        List.of("artifact://" + id),
        List.of(),
        "P(x)",
        "",
        null,
        List.of(),
        List.of(),
        EvidenceType.NATURAL_PROOF_AUDITED,
        MemoryTier.FACT,
        id,
        MessageType.VERIFIED_LEMMA,
        1.0d,
        "p(x)",
        PROBLEM_HASH,
        quantifiers,
        null,
        0,
        "1",
        scope,
        "author-a",
        RouteRole.PROVER,
        "route-a",
        "P(x)",
        List.of("route-b"),
        3,
        bindings,
        1.0d,
        ClaimStatus.VERIFIED);
  }

  MessageEnvelope genericFailure(String id) {
    return new MessageEnvelope(
        List.of(),
        List.of(),
        "create_minimal_bridge",
        "",
        null,
        List.of(),
        List.of(),
        EvidenceType.UNVERIFIED_IDEA,
        MemoryTier.NEGATIVE,
        id,
        MessageType.FAILURE_RECORD,
        1.0d,
        "route failure bridge create_minimal_bridge",
        PROBLEM_HASH,
        List.of(),
        null,
        0,
        "1",
        List.of(),
        "author-a",
        RouteRole.PROVER,
        "route-a",
        "Route failure: BRIDGE. recommended action: create_minimal_bridge",
        List.of("route-b"),
        3,
        List.of(),
        1.0d,
        ClaimStatus.REJECTED);
  }

  private static RouteDescriptor route(String routeId, String strategyId) {
    return new RouteDescriptor(
        null, 0, 0, null, null, null, null, null, null, List.of(strategyId), List.of(), null,
        List.of(), 0, false, null, routeId, List.of(), 0, RouteStatus.ACTIVE, strategyId,
        strategyId);
  }
}
