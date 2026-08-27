package io.github.aililuola.mathproofmesh.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageAdmissionPolicyParityTest {
  private final RouteRegistry routes = CommunicationFixtures.routes();
  private final MessageBrokerPolicy policy = MessageBrokerPolicy.strictDefaults();

  @Test
  void schemaGateRunsBeforeProblemAndOwnership() {
    MessageEnvelope message =
        copy(
            CommunicationFixtures.fact("bad-schema", List.of("route-b")),
            "not-the-problem",
            "missing-route",
            "missing-agent",
            "0",
            1,
            2,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            EvidenceType.NATURAL_PROOF_AUDITED,
            MemoryTier.FACT,
            ClaimStatus.VERIFIED,
            0.99,
            1.0);
    AdmissionResult result = evaluate(message, acceptingDependencies(), "referee-a", 1);
    assertEquals(AdmissionRejection.SCHEMA_OR_LENGTH, result.rejection());
    assertEquals("unsupported message schema version", result.reason());
  }

  @Test
  void problemHashGatePrecedesOwnership() {
    MessageEnvelope message =
        copy(
            CommunicationFixtures.fact("bad-problem", List.of("route-b")),
            "not-the-problem",
            "missing-route",
            "missing-agent",
            "1",
            1,
            2,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            EvidenceType.NATURAL_PROOF_AUDITED,
            MemoryTier.FACT,
            ClaimStatus.VERIFIED,
            0.99,
            1.0);
    assertEquals(
        AdmissionRejection.PROBLEM_HASH,
        evaluate(message, acceptingDependencies(), "referee-a", 1).rejection());
  }

  @Test
  void sourceAgentAndRoleMustBelongToRoute() {
    MessageEnvelope message =
        copy(
            CommunicationFixtures.fact("bad-owner", List.of("route-b")),
            CommunicationFixtures.PROBLEM_HASH,
            "route-a",
            "referee-a",
            "1",
            1,
            2,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            EvidenceType.NATURAL_PROOF_AUDITED,
            MemoryTier.FACT,
            ClaimStatus.VERIFIED,
            0.99,
            1.0);
    assertEquals(
        AdmissionRejection.SOURCE_OWNERSHIP,
        evaluate(message, acceptingDependencies(), "referee-b", 1).rejection());
  }

  @Test
  void unknownAndNonNeighborTargetsAreIndividuallyRejected() {
    RouteRegistry sparse =
        new RouteRegistry(CommunicationFixtures.PROBLEM_HASH, 1, 8, 0.9);
    for (String suffix : List.of("a", "b", "c")) {
      sparse.register(
          CommunicationFixtures.route(
              "route-" + suffix, "strategy-" + suffix, "mechanism-" + suffix));
      sparse.assignMember(
          "route-" + suffix, "author-" + suffix, RouteRole.PROVER, 0);
    }
    sparse.setNeighbors("route-a", List.of("route-b"));
    MessageAdmissionPolicy admission =
        new MessageAdmissionPolicy(
            policy,
            sparse,
            ArtifactCatalog.allowRunScopedReferences(),
            acceptingDependencies());
    AdmissionResult result =
        admission.evaluate(
            CommunicationFixtures.fact(
                "targets", List.of("route-b", "route-c", "missing")),
            "referee-a",
            1);
    assertTrue(result.accepted());
    assertEquals(List.of("route-b"), result.selectedTargets());
    assertEquals("target is not a sparse neighbor", result.rejectedTargets().get("route-c"));
    assertEquals("unknown target route", result.rejectedTargets().get("missing"));
  }

  @Test
  void expiredTtlIsRejectedBeforeArtifactInspection() {
    MessageEnvelope message =
        copy(
            CommunicationFixtures.fact("expired", List.of("route-b")),
            CommunicationFixtures.PROBLEM_HASH,
            "route-a",
            "author-a",
            "1",
            0,
            1,
            List.of("file:///outside"),
            List.of(),
            List.of(),
            List.of(),
            EvidenceType.NATURAL_PROOF_AUDITED,
            MemoryTier.FACT,
            ClaimStatus.VERIFIED,
            0.99,
            1.0);
    AdmissionResult result = evaluate(message, acceptingDependencies(), "referee-a", 2);
    assertEquals(AdmissionRejection.TTL, result.rejection());
  }

  @Test
  void artifactReferencesMustBeRunScopedAndTraversalFree() {
    MessageEnvelope external =
        copyWithArtifacts(
            CommunicationFixtures.fact("external-artifact", List.of("route-b")),
            List.of("file:///tmp/proof.json"));
    assertEquals(
        "artifact references must be run-scoped",
        evaluate(external, acceptingDependencies(), "referee-a", 1).reason());
    MessageEnvelope traversal =
        copyWithArtifacts(
            CommunicationFixtures.fact("traversal-artifact", List.of("route-b")),
            List.of("artifact://proof/../../secret"));
    assertEquals(
        "artifact reference escapes the run root",
        evaluate(traversal, acceptingDependencies(), "referee-a", 1).reason());
  }

  @Test
  void missingRunArtifactIsRejected() {
    MessageEnvelope message =
        copyWithArtifacts(
            CommunicationFixtures.fact("missing-artifact", List.of("route-b")),
            List.of("artifact://proof/a.json"));
    MessageAdmissionPolicy admission =
        new MessageAdmissionPolicy(policy, routes, ignored -> false, acceptingDependencies());
    assertEquals(
        "artifact reference does not exist in this run",
        admission.evaluate(message, "referee-a", 1).reason());
  }

  @Test
  void quantifierOrderAndBindingAreValidated() {
    List<QuantifierSpec> quantifiers =
        List.of(new QuantifierSpec("n", "integers", "forall", 1, List.of(), "n"));
    MessageEnvelope message =
        copyWithScope(
            CommunicationFixtures.fact("scope", List.of("route-b")),
            quantifiers,
            List.of(new VariableBinding(List.of(), "n", "integers", "claim", "n")));
    AdmissionResult result = evaluate(message, acceptingDependencies(), "referee-a", 1);
    assertEquals(AdmissionRejection.QUANTIFIER_SCOPE, result.rejection());
    assertEquals("quantifier orders must be contiguous and start at zero", result.reason());
  }

  @Test
  void unresolvedInvalidatedAndCyclicDependenciesAreDistinct() {
    MessageEnvelope message =
        copyWithDependencies(
            CommunicationFixtures.fact("dependent", List.of("route-b")),
            List.of("dependency"));
    assertEquals(
        "fact dependencies are unresolved",
        evaluate(message, DependencyCatalog.empty(), "referee-a", 1).reason());
    DependencyCatalog invalidated = catalog(true, false);
    assertEquals(
        "dependency is invalidated",
        evaluate(message, invalidated, "referee-a", 1).reason());
    DependencyCatalog cyclic = catalog(false, true);
    assertEquals(
        "fact dependency cycle detected",
        evaluate(message, cyclic, "referee-a", 1).reason());
  }

  @Test
  void boundedExperimentCannotBecomeReusableFact() {
    MessageEnvelope message =
        copyEvidence(
            CommunicationFixtures.fact("bounded", List.of("route-b")),
            EvidenceType.BOUNDED_EXPERIMENT,
            MemoryTier.FACT,
            ClaimStatus.VERIFIED,
            0.99,
            1.0);
    assertEquals(
        "evidence type cannot establish a reusable fact",
        evaluate(message, acceptingDependencies(), "referee-a", 1).reason());
  }

  @Test
  void counterexampleRequiresNegativeMemoryAndRejectedStatus() {
    MessageEnvelope message =
        copyEvidence(
            CommunicationFixtures.fact("counterexample", List.of("route-b")),
            EvidenceType.COUNTEREXAMPLE,
            MemoryTier.FACT,
            ClaimStatus.VERIFIED,
            1.0,
            1.0);
    assertEquals(
        "counterexamples must enter NegativeMemory",
        evaluate(message, acceptingDependencies(), "referee-a", 1).reason());
  }

  @Test
  void authorCannotRefereeOwnFact() {
    AdmissionResult result =
        evaluate(
            CommunicationFixtures.fact("self-review", List.of("route-b")),
            acceptingDependencies(),
            "author-a",
            1);
    assertEquals(AdmissionRejection.REVIEW_INDEPENDENCE, result.rejection());
    assertEquals("the author cannot referee its own fact", result.reason());
  }

  @Test
  void validFactReachesContentHashGateAndPasses() {
    AdmissionResult result =
        evaluate(
            CommunicationFixtures.fact("valid", List.of("route-b")),
            acceptingDependencies(),
            "referee-a",
            1);
    assertTrue(result.accepted());
    assertNull(result.rejection());
    assertEquals(List.of("route-b"), result.selectedTargets());
  }

  @Test
  void semanticDuplicateDoesNotUpgradeOrRepersistMessage() {
    InMemoryMessageRepository repository = new InMemoryMessageRepository();
    MessageBroker broker =
        CommunicationFixtures.broker(
            policy, routes, acceptingDependencies(), repository);
    assertTrue(
        broker
            .publish(
                CommunicationFixtures.fact("original", List.of("route-b")),
                "referee-a",
                1)
            .accepted());
    var duplicate =
        broker.publish(
            CommunicationFixtures.fact("copy", List.of("route-c")),
            "referee-a",
            1);
    assertTrue(duplicate.accepted());
    assertEquals("original", duplicate.duplicateOf());
    assertEquals(1, repository.snapshot().messages().size());
  }

  @Test
  void persistenceFailureLeavesNoPartialMessageDeliveryOrEvent() {
    InMemoryMessageRepository repository = new InMemoryMessageRepository();
    repository.failNextCommit();
    MessageBroker broker =
        CommunicationFixtures.broker(
            policy, routes, acceptingDependencies(), repository);
    var decision =
        broker.publish(
            CommunicationFixtures.fact("atomic", List.of("route-b")),
            "referee-a",
            1);
    assertFalse(decision.accepted());
    assertEquals(AdmissionRejection.PERSISTENCE, broker.admissionAudit().getFirst().rejection());
    assertTrue(repository.snapshot().messages().isEmpty());
    assertTrue(repository.snapshot().deliveries().isEmpty());
    assertTrue(repository.snapshot().domainEvents().isEmpty());
  }

  private AdmissionResult evaluate(
      MessageEnvelope message,
      DependencyCatalog dependencies,
      String reviewer,
      int currentRound) {
    return new MessageAdmissionPolicy(
            policy,
            routes,
            ArtifactCatalog.allowRunScopedReferences(),
            dependencies)
        .evaluate(message, reviewer, currentRound);
  }

  private static DependencyCatalog acceptingDependencies() {
    return CommunicationFixtures.acceptingDependencies();
  }

  private static DependencyCatalog catalog(boolean invalidated, boolean cyclic) {
    return new DependencyCatalog() {
      @Override
      public boolean exists(String dependencyId) {
        return true;
      }

      @Override
      public boolean invalidated(String dependencyId) {
        return invalidated;
      }

      @Override
      public boolean wouldCreateCycle(String messageId, List<String> dependencyIds) {
        return cyclic;
      }
    };
  }

  private static MessageEnvelope copyWithArtifacts(
      MessageEnvelope source, List<String> artifacts) {
    return copy(
        source,
        source.problemHash(),
        source.sourceRouteId(),
        source.sourceAgentId(),
        source.schemaVersion(),
        source.roundCreated(),
        source.ttlRounds(),
        artifacts,
        source.dependencies(),
        source.quantifiers(),
        source.variableBindings(),
        source.evidenceType(),
        source.memoryTier(),
        source.verificationStatus(),
        source.verificationConfidence(),
        source.normalizationConfidence());
  }

  private static MessageEnvelope copyWithDependencies(
      MessageEnvelope source, List<String> dependencies) {
    return copy(
        source,
        source.problemHash(),
        source.sourceRouteId(),
        source.sourceAgentId(),
        source.schemaVersion(),
        source.roundCreated(),
        source.ttlRounds(),
        source.artifactRefs(),
        dependencies,
        source.quantifiers(),
        source.variableBindings(),
        source.evidenceType(),
        source.memoryTier(),
        source.verificationStatus(),
        source.verificationConfidence(),
        source.normalizationConfidence());
  }

  private static MessageEnvelope copyWithScope(
      MessageEnvelope source,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> bindings) {
    return copy(
        source,
        source.problemHash(),
        source.sourceRouteId(),
        source.sourceAgentId(),
        source.schemaVersion(),
        source.roundCreated(),
        source.ttlRounds(),
        source.artifactRefs(),
        source.dependencies(),
        quantifiers,
        bindings,
        source.evidenceType(),
        source.memoryTier(),
        source.verificationStatus(),
        source.verificationConfidence(),
        source.normalizationConfidence());
  }

  private static MessageEnvelope copyEvidence(
      MessageEnvelope source,
      EvidenceType evidence,
      MemoryTier tier,
      ClaimStatus status,
      double confidence,
      double normalizationConfidence) {
    return copy(
        source,
        source.problemHash(),
        source.sourceRouteId(),
        source.sourceAgentId(),
        source.schemaVersion(),
        source.roundCreated(),
        source.ttlRounds(),
        source.artifactRefs(),
        source.dependencies(),
        source.quantifiers(),
        source.variableBindings(),
        evidence,
        tier,
        status,
        confidence,
        normalizationConfidence);
  }

  private static MessageEnvelope copy(
      MessageEnvelope source,
      String problemHash,
      String routeId,
      String agentId,
      String schemaVersion,
      int roundCreated,
      int ttl,
      List<String> artifacts,
      List<String> dependencies,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> bindings,
      EvidenceType evidence,
      MemoryTier tier,
      ClaimStatus status,
      double confidence,
      double normalizationConfidence) {
    return CommunicationFixtures.message(
        source.messageId(),
        problemHash,
        routeId,
        agentId,
        source.sourceRole(),
        source.targetRouteIds(),
        source.statement(),
        source.conclusion(),
        source.messageType(),
        evidence,
        tier,
        status,
        confidence,
        normalizationConfidence,
        roundCreated,
        ttl,
        schemaVersion,
        artifacts,
        dependencies,
        quantifiers,
        bindings);
  }
}
