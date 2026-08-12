package io.github.aililuola.mathproofmesh.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessagePriority;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase17MessageAdmissionHardeningTest {

  @Test
  void schemaLimitsNullAndPolicyValidationAreAllEnforced() {
    RouteRegistry routes = CommunicationFixtures.routes();
    MessageAdmissionPolicy admission =
        admission(MessageBrokerPolicy.strictDefaults(), routes, CommunicationFixtures.acceptingDependencies());
    assertThat(admission.evaluate(null, "reviewer", 1).rejection())
        .isEqualTo(AdmissionRejection.SCHEMA_OR_LENGTH);

    MessageEnvelope fact = CommunicationFixtures.fact("fact", List.of());
    assertThat(
            admission(policyWithLimits(1, 64, 64), routes, CommunicationFixtures.acceptingDependencies())
                .evaluate(fact, "referee-a", 1)
                .reason())
        .contains("max_message_chars");
    assertThat(
            admission(policyWithLimits(32_000, 0, 64), routes, CommunicationFixtures.acceptingDependencies())
                .evaluate(copy(fact, List.of("assumption"), null, null, null, null, null), "referee-a", 1)
                .reason())
        .contains("max_assumptions");
    assertThat(
            admission(policyWithLimits(32_000, 64, 0), routes, CommunicationFixtures.acceptingDependencies())
                .evaluate(copy(fact, null, null, null, List.of("dependency"), null, null), "referee-a", 1)
                .reason())
        .contains("max_dependencies");

    assertThatThrownBy(
            () ->
                new MessageAdmissionPolicy(
                    null,
                    routes,
                    ArtifactCatalog.allowRunScopedReferences(),
                    CommunicationFixtures.acceptingDependencies()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new MessageAdmissionPolicy(
                    MessageBrokerPolicy.strictDefaults(),
                    null,
                    ArtifactCatalog.allowRunScopedReferences(),
                    CommunicationFixtures.acceptingDependencies()))
        .isInstanceOf(NullPointerException.class);
    for (int invalidIndex = 0; invalidIndex < 7; invalidIndex++) {
      int index = invalidIndex;
      assertThatThrownBy(() -> invalidPolicy(index)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                new MessageBrokerPolicy(
                    "1", 1, 0, 0, 1, 1, 0, 0, -0.1d,
                    true, true, true, true, true, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MessageBrokerPolicy(
                    "1", 1, 0, 0, 1, 1, 0, 0, 1.1d,
                    true, true, true, true, true, true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void targetSelectionCoversDefaultsDedupSelfUnknownCapsAndSharePolicies() {
    RouteRegistry routes = CommunicationFixtures.routes();
    MessageEnvelope defaultTargets = CommunicationFixtures.fact("default-targets", List.of());
    assertThat(evaluate(defaultTargets, MessageBrokerPolicy.strictDefaults(), routes))
        .satisfies(
            result -> {
              assertThat(result.accepted()).isTrue();
              assertThat(result.selectedTargets()).containsExactly("route-b", "route-c");
            });

    MessageEnvelope explicit =
        retarget(defaultTargets, List.of("route-a", "route-b", "route-b", "missing"));
    assertThat(evaluate(explicit, MessageBrokerPolicy.strictDefaults(), routes))
        .satisfies(
            result -> {
              assertThat(result.selectedTargets()).containsExactly("route-b");
              assertThat(result.rejectedTargets()).containsKey("missing");
            });

    MessageBrokerPolicy capOne = policy(MessageBrokerPolicy.strictDefaults(), true, true, true, true, true, true, 1);
    assertThat(evaluate(defaultTargets, capOne, routes).rejectedTargets())
        .containsValue("neighbor cap reached");
    MessageBrokerPolicy disabled = policy(capOne, false, true, true, true, true, true, 3);
    assertThat(evaluate(defaultTargets, disabled, routes).selectedTargets()).isEmpty();

    MessageEnvelope insight =
        mutateEvidence(
            CommunicationFixtures.insight("insight", List.of("route-b")),
            MessageType.CLAIM_PROPOSAL,
            EvidenceType.UNVERIFIED_IDEA,
            MemoryTier.INSIGHT,
            ClaimStatus.PROPOSED,
            0.2d,
            1.0d);
    assertThat(evaluate(insight, MessageBrokerPolicy.strictDefaults(), routes).rejectedTargets())
        .containsValue("cross-route sharing disabled for this message");
    assertThat(
            evaluate(
                    insight,
                    policy(capOne, true, true, true, true, true, true, 3),
                    routes)
                .selectedTargets())
        .containsExactly("route-b");

    for (MessageType type : List.of(MessageType.PROOF_OBLIGATION, MessageType.FAILURE_RECORD)) {
      MessageEnvelope typed =
          mutateEvidence(
              insight,
              type,
              EvidenceType.UNVERIFIED_IDEA,
              MemoryTier.INSIGHT,
              ClaimStatus.PROPOSED,
              0.2d,
              1.0d);
      assertThat(evaluate(typed, policy(capOne, true, true, true, true, true, false, 3), routes).selectedTargets())
          .isNotEmpty();
    }

    RouteRegistry sparse = new RouteRegistry(CommunicationFixtures.PROBLEM_HASH, 1, 8, 0.9d);
    for (String suffix : List.of("a", "b", "c")) {
      sparse.register(CommunicationFixtures.route("route-" + suffix, "s-" + suffix, "m-" + suffix));
      sparse.assignMember("route-" + suffix, "author-" + suffix, RouteRole.PROVER, 0);
    }
    sparse.setNeighbors("route-a", List.of("route-b"));
    MessageEnvelope counterexample =
        mutateEvidence(
            retarget(defaultTargets, List.of("route-c")),
            MessageType.COUNTEREXAMPLE,
            EvidenceType.COUNTEREXAMPLE,
            MemoryTier.NEGATIVE,
            ClaimStatus.REJECTED,
            1.0d,
            1.0d);
    assertThat(evaluate(counterexample, MessageBrokerPolicy.strictDefaults(), sparse).selectedTargets())
        .containsExactly("route-c");
  }

  @Test
  void artifactScopeEvidenceReviewAndPriorityBranchesAreExercised() {
    RouteRegistry routes = CommunicationFixtures.routes();
    MessageEnvelope fact = CommunicationFixtures.fact("branch-fact", List.of("route-b"));
    for (String invalid :
        List.of(
            "artifact://",
            "artifact:///absolute",
            "artifact:\\absolute",
            "artifact://C:\\secret",
            "artifact://a/../b",
            "artifact://a\\..\\b")) {
      assertThat(
              evaluate(copy(fact, null, List.of(invalid), null, null, null, null),
                      MessageBrokerPolicy.strictDefaults(), routes)
                  .rejection())
          .isEqualTo(AdmissionRejection.ARTIFACT_REFERENCE);
    }
    assertThat(
            evaluate(copy(fact, null, null, "file:///outside", null, null, null),
                    MessageBrokerPolicy.strictDefaults(), routes)
                .rejection())
        .isEqualTo(AdmissionRejection.ARTIFACT_REFERENCE);
    assertThat(
            evaluate(copy(fact, null, null, " ", null, null, null),
                    MessageBrokerPolicy.strictDefaults(), routes)
                .accepted())
        .isTrue();

    VariableBinding n = new VariableBinding(List.of(), "n", "integers", "claim", "n");
    VariableBinding nDuplicate = new VariableBinding(List.of("x"), "n", "integers", "claim", "n");
    QuantifierSpec q0 = new QuantifierSpec("n", "integers", "forall", 0, List.of(), "n");
    assertScopeRejected(fact, List.of(), List.of(n, nDuplicate), routes);
    assertScopeRejected(
        fact,
        List.of(q0, new QuantifierSpec("n", "integers", "exists", 0, List.of(), "n")),
        List.of(n),
        routes);
    assertScopeRejected(
        fact,
        List.of(new QuantifierSpec("x", "integers", "forall", 0, List.of(), "x")),
        List.of(n),
        routes);
    assertScopeRejected(
        fact,
        List.of(new QuantifierSpec("n", "reals", "forall", 0, List.of(), "n")),
        List.of(n),
        routes);
    assertThat(evaluate(withScope(fact, List.of(q0), List.of(n)), MessageBrokerPolicy.strictDefaults(), routes).accepted())
        .isTrue();
    for (String marker : List.of("FOR ALL n", "for every n", "there exists n", "forall n", "exists n")) {
      MessageEnvelope global =
          mutateText(fact, marker + " property", marker + " property");
      assertThat(evaluate(global, MessageBrokerPolicy.strictDefaults(), routes).rejection())
          .isEqualTo(AdmissionRejection.QUANTIFIER_SCOPE);
    }

    for (ClaimStatus status : List.of(ClaimStatus.PROPOSED, ClaimStatus.REJECTED)) {
      MessageEnvelope nonVerified =
          mutateEvidence(
              fact,
              MessageType.VERIFIED_LEMMA,
              EvidenceType.NATURAL_PROOF_AUDITED,
              MemoryTier.FACT,
              status,
              1.0d,
              1.0d);
      assertThat(evaluate(nonVerified, MessageBrokerPolicy.strictDefaults(), routes).rejection())
          .isEqualTo(AdmissionRejection.EVIDENCE_TIER);
    }
    assertEvidenceRejected(fact, EvidenceType.NATURAL_PROOF_AUDITED, MemoryTier.FACT, ClaimStatus.VERIFIED, 0.1d, 1.0d, routes);
    assertEvidenceRejected(fact, EvidenceType.NATURAL_PROOF_AUDITED, MemoryTier.FACT, ClaimStatus.VERIFIED, 1.0d, 0.1d, routes);
    assertEvidenceRejected(fact, EvidenceType.COUNTEREXAMPLE, MemoryTier.NEGATIVE, ClaimStatus.VERIFIED, 1.0d, 1.0d, routes);
    assertEvidenceRejected(fact, EvidenceType.UNVERIFIED_IDEA, MemoryTier.NEGATIVE, ClaimStatus.PROPOSED, 0.0d, 1.0d, routes);
    MessageEnvelope negative =
        mutateEvidence(
            fact,
            MessageType.FAILURE_RECORD,
            EvidenceType.UNVERIFIED_IDEA,
            MemoryTier.NEGATIVE,
            ClaimStatus.REJECTED,
            0.0d,
            1.0d);
    assertThat(evaluate(negative, MessageBrokerPolicy.strictDefaults(), routes).accepted()).isTrue();

    for (String reviewer : new String[] {null, "", " ", "author-a"}) {
      assertThat(admission(MessageBrokerPolicy.strictDefaults(), routes, CommunicationFixtures.acceptingDependencies())
              .evaluate(fact, reviewer, 1).rejection())
          .isEqualTo(AdmissionRejection.REVIEW_INDEPENDENCE);
    }

    assertThat(MessageAdmissionPolicy.priority(
            mutateEvidence(fact, MessageType.CONTRADICTION_NOTICE, EvidenceType.UNVERIFIED_IDEA,
                MemoryTier.INSIGHT, ClaimStatus.PROPOSED, 0.0d, 1.0d)))
        .isEqualTo(MessagePriority.CRITICAL);
    assertThat(MessageAdmissionPolicy.priority(fact)).isEqualTo(MessagePriority.HIGH);
    for (MessageType type :
        List.of(
            MessageType.PROOF_OBLIGATION,
            MessageType.REPAIR_REQUEST,
            MessageType.BRIDGE_LEMMA_REQUEST,
            MessageType.STRATEGY_REWRITE_REQUEST)) {
      assertThat(MessageAdmissionPolicy.priority(
              mutateEvidence(fact, type, EvidenceType.UNVERIFIED_IDEA,
                  MemoryTier.INSIGHT, ClaimStatus.PROPOSED, 0.0d, 1.0d)))
          .isEqualTo(MessagePriority.NORMAL);
    }
    assertThat(MessageAdmissionPolicy.priority(CommunicationFixtures.insight("low", List.of())))
        .isEqualTo(MessagePriority.LOW);
  }

  private static void assertScopeRejected(
      MessageEnvelope source,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> bindings,
      RouteRegistry routes) {
    assertThat(evaluate(withScope(source, quantifiers, bindings), MessageBrokerPolicy.strictDefaults(), routes).rejection())
        .isEqualTo(AdmissionRejection.QUANTIFIER_SCOPE);
  }

  private static void assertEvidenceRejected(
      MessageEnvelope source,
      EvidenceType evidence,
      MemoryTier tier,
      ClaimStatus status,
      double confidence,
      double normalization,
      RouteRegistry routes) {
    assertThat(
            evaluate(
                    mutateEvidence(
                        source,
                        source.messageType(),
                        evidence,
                        tier,
                        status,
                        confidence,
                        normalization),
                    MessageBrokerPolicy.strictDefaults(),
                    routes)
                .rejection())
        .isEqualTo(AdmissionRejection.EVIDENCE_TIER);
  }

  private static AdmissionResult evaluate(
      MessageEnvelope message, MessageBrokerPolicy policy, RouteRegistry routes) {
    return admission(policy, routes, CommunicationFixtures.acceptingDependencies())
        .evaluate(message, "referee-a", 1);
  }

  private static MessageAdmissionPolicy admission(
      MessageBrokerPolicy policy, RouteRegistry routes, DependencyCatalog dependencies) {
    return new MessageAdmissionPolicy(
        policy, routes, ArtifactCatalog.allowRunScopedReferences(), dependencies);
  }

  private static MessageEnvelope retarget(MessageEnvelope source, List<String> targets) {
    return new MessageEnvelope(
        source.artifactRefs(), source.assumptions(), source.conclusion(), "", source.createdAt(),
        source.dependencies(), source.dependencyRefs(), source.evidenceType(), source.memoryTier(),
        source.messageId(), source.messageType(), source.normalizationConfidence(),
        source.normalizedStatement(), source.problemHash(), source.quantifiers(), source.rawSourceRef(),
        source.roundCreated(), source.schemaVersion(), source.scopeLimitations(), source.sourceAgentId(),
        source.sourceRole(), source.sourceRouteId(), source.statement(), targets, source.ttlRounds(),
        source.variableBindings(), source.verificationConfidence(), source.verificationStatus());
  }

  private static MessageEnvelope copy(
      MessageEnvelope source,
      List<String> assumptions,
      List<String> artifacts,
      String rawSource,
      List<String> dependencies,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> bindings) {
    return new MessageEnvelope(
        artifacts == null ? source.artifactRefs() : artifacts,
        assumptions == null ? source.assumptions() : assumptions,
        source.conclusion(),
        "",
        source.createdAt(),
        dependencies == null ? source.dependencies() : dependencies,
        source.dependencyRefs(),
        source.evidenceType(),
        source.memoryTier(),
        source.messageId(),
        source.messageType(),
        source.normalizationConfidence(),
        source.normalizedStatement(),
        source.problemHash(),
        quantifiers == null ? source.quantifiers() : quantifiers,
        rawSource,
        source.roundCreated(),
        source.schemaVersion(),
        source.scopeLimitations(),
        source.sourceAgentId(),
        source.sourceRole(),
        source.sourceRouteId(),
        source.statement(),
        source.targetRouteIds(),
        source.ttlRounds(),
        bindings == null ? source.variableBindings() : bindings,
        source.verificationConfidence(),
        source.verificationStatus());
  }

  private static MessageEnvelope withScope(
      MessageEnvelope source, List<QuantifierSpec> quantifiers, List<VariableBinding> bindings) {
    return copy(source, null, null, source.rawSourceRef(), null, quantifiers, bindings);
  }

  private static MessageEnvelope mutateEvidence(
      MessageEnvelope source,
      MessageType type,
      EvidenceType evidence,
      MemoryTier tier,
      ClaimStatus status,
      double confidence,
      double normalization) {
    return CommunicationFixtures.message(
        source.messageId(),
        source.problemHash(),
        source.sourceRouteId(),
        source.sourceAgentId(),
        source.sourceRole(),
        source.targetRouteIds(),
        source.statement(),
        source.conclusion(),
        type,
        evidence,
        tier,
        status,
        confidence,
        normalization,
        source.roundCreated(),
        source.ttlRounds(),
        source.schemaVersion(),
        source.artifactRefs(),
        source.dependencies(),
        source.quantifiers(),
        source.variableBindings());
  }

  private static MessageEnvelope mutateText(
      MessageEnvelope source, String statement, String conclusion) {
    return CommunicationFixtures.message(
        source.messageId(),
        source.problemHash(),
        source.sourceRouteId(),
        source.sourceAgentId(),
        source.sourceRole(),
        source.targetRouteIds(),
        statement,
        conclusion,
        source.messageType(),
        source.evidenceType(),
        source.memoryTier(),
        source.verificationStatus(),
        source.verificationConfidence(),
        source.normalizationConfidence(),
        source.roundCreated(),
        source.ttlRounds(),
        source.schemaVersion(),
        source.artifactRefs(),
        source.dependencies(),
        source.quantifiers(),
        source.variableBindings());
  }

  private static MessageBrokerPolicy policyWithLimits(int chars, int assumptions, int dependencies) {
    MessageBrokerPolicy p = MessageBrokerPolicy.strictDefaults();
    return new MessageBrokerPolicy(
        p.schemaVersion(), chars, assumptions, dependencies,
        p.maxMessagesPerRoutePerRound(), p.maxGlobalMessagesPerRound(),
        p.maxNeighborsPerRoute(), p.initialIsolationRounds(), p.factPassThreshold(),
        p.crossRouteEnabled(), p.shareVerifiedFacts(), p.shareCounterexamples(),
        p.shareOpenObligations(), p.shareFailureRecords(), p.shareUnverifiedInsights());
  }

  private static MessageBrokerPolicy policy(
      MessageBrokerPolicy p,
      boolean cross,
      boolean facts,
      boolean counterexamples,
      boolean obligations,
      boolean failures,
      boolean insights,
      int neighbors) {
    return new MessageBrokerPolicy(
        p.schemaVersion(), p.maxMessageChars(), p.maxAssumptions(), p.maxDependencies(),
        p.maxMessagesPerRoutePerRound(), p.maxGlobalMessagesPerRound(), neighbors,
        p.initialIsolationRounds(), p.factPassThreshold(), cross, facts, counterexamples,
        obligations, failures, insights);
  }

  private static void invalidPolicy(int index) {
    int[] values = {1, 0, 0, 1, 1, 0, 0};
    values[index] = -1;
    new MessageBrokerPolicy(
        "1",
        values[0],
        values[1],
        values[2],
        values[3],
        values[4],
        values[5],
        values[6],
        0.9d,
        true,
        true,
        true,
        true,
        true,
        true);
  }
}
