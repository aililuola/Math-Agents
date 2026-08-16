package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.communication.MessageStoreSnapshot;
import io.github.aililuola.mathproofmesh.contract.BoundedObservationPayload;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseClaim;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseManifest;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.VerifiedCounterexamplePayload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MathematicalArtifactBrokerExactAttributionCoverageTest {
  @Test
  void exactEffectTargetsAreBoundOnceAndRemainRouteScoped() {
    MathematicalArtifactBroker repairBroker = new MathematicalArtifactBroker();
    BrokerArtifactEnvelope obstruction = BrokerArtifactTestFixtures.obstruction();
    use(
        repairBroker,
        obstruction,
        "route-repair",
        "request-repair",
        new BrokerArtifactUseClaim(
            obstruction.artifactId(),
            BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR,
            List.of(),
            List.of(),
            List.of("target-tree"),
            "repair the projected exact obstruction"));
    assertThat(
            repairBroker.bindEffectTarget(
                "route-other",
                BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR,
                "repair-1",
                Set.of(),
                Set.of("target-tree")))
        .isEmpty();
    assertThat(
            repairBroker.bindEffectTarget(
                "route-repair",
                BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR,
                "repair-1",
                Set.of(),
                Set.of("target-other")))
        .isEmpty();
    assertThat(
            repairBroker.bindEffectTarget(
                "route-repair",
                BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR,
                "repair-1",
                Set.of(),
                Set.of("target-tree")))
        .get()
        .extracting(BrokerArtifactLineageRecord::repairId)
        .isEqualTo("repair-1");
    assertThat(
            repairBroker.bindEffectTarget(
                "route-repair",
                BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR,
                "repair-2",
                Set.of(),
                Set.of("target-tree")))
        .isEmpty();
    assertThat(
            repairBroker.boundEffectIdsForRoute(
                "route-repair", BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR))
        .containsExactly("repair-1");

    MathematicalArtifactBroker pivotBroker = new MathematicalArtifactBroker();
    BrokerArtifactEnvelope counterexample = BrokerArtifactTestFixtures.counterexample();
    String targetHash =
        ((VerifiedCounterexamplePayload) counterexample.payload()).targetSemanticHash();
    use(
        pivotBroker,
        counterexample,
        "route-pivot",
        "request-pivot",
        new BrokerArtifactUseClaim(
            counterexample.artifactId(),
            BrokerArtifactUseKind.TRIGGERS_SEMANTIC_PIVOT,
            List.of(),
            List.of("claim-tree"),
            List.of("target-tree"),
            targetHash,
            "pivot away from the exactly refuted claim"));
    assertThat(
            pivotBroker.bindEffectTarget(
                "route-pivot",
                BrokerArtifactUseKind.TRIGGERS_SEMANTIC_PIVOT,
                "pivot-1",
                Set.of("claim-tree"),
                Set.of("target-tree")))
        .get()
        .extracting(BrokerArtifactLineageRecord::pivotId)
        .isEqualTo("pivot-1");
    assertThat(
            pivotBroker.boundEffectIdsForRoute(
                "route-pivot", BrokerArtifactUseKind.TRIGGERS_SEMANTIC_PIVOT))
        .containsExactly("pivot-1");

    MathematicalArtifactBroker computationBroker = new MathematicalArtifactBroker();
    BrokerArtifactEnvelope observation = boundedObservation();
    use(
        computationBroker,
        observation,
        "route-computation",
        "request-computation",
        new BrokerArtifactUseClaim(
            observation.artifactId(),
            BrokerArtifactUseKind.SUPPORTS_COMPUTATION_PLAN,
            List.of(),
            List.of("claim-tree"),
            List.of("target-tree"),
            "test the exact bounded pattern"));
    assertThat(
            computationBroker.bindEffectTarget(
                "route-computation",
                BrokerArtifactUseKind.SUPPORTS_COMPUTATION_PLAN,
                "computation-1",
                Set.of("claim-tree"),
                Set.of("target-tree")))
        .get()
        .extracting(BrokerArtifactLineageRecord::computationPlanId)
        .isEqualTo("computation-1");
    assertThat(
            computationBroker.boundEffectIdsForRoute(
                "route-computation", BrokerArtifactUseKind.SUPPORTS_COMPUTATION_PLAN))
        .containsExactly("computation-1");
    assertThat(
            computationBroker.boundEffectIdsForRoute(
                "route-other", BrokerArtifactUseKind.SUPPORTS_COMPUTATION_PLAN))
        .isEmpty();
  }

  @Test
  void utilityRequiresANewEffectAndIsRemovedFromModernSchedulingAfterInvalidation() {
    var scenario = BrokerArtifactTestFixtures.delivered(4.0d);
    MathematicalArtifactBroker broker = scenario.broker();
    String deliveryId = scenario.prompt().deliveries().getFirst().deliveryId();
    assertThat(broker.pendingProviderRequestsForRoute("route-b"))
        .containsExactly("provider-request-1");
    assertThat(broker.pendingProviderRequestsForRoute("route-other")).isEmpty();
    assertThat(broker.verifyEffect(deliveryId, unchanged(4.0d))).isEmpty();

    broker.stageUseManifest(
        BrokerArtifactTestFixtures.useManifest(
            scenario.artifact(), BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP));
    assertThat(broker.acknowledge("provider-request-1", Set.of("downstream-step")))
        .singleElement()
        .extracting(BrokerArtifactReceipt::status)
        .isEqualTo(BrokerArtifactReceiptStatus.USED_PENDING_EFFECT);
    assertThat(broker.pendingProviderRequestsForRoute("route-b")).isEmpty();
    assertThat(broker.verifyEffect(deliveryId, unchanged(4.0d))).isEmpty();
    assertThat(
            broker.verifyEffect(
                deliveryId,
                new BrokerArtifactEffectObservation(
                    Set.of("downstream-step"),
                    Set.of("downstream-claim"),
                    Set.of(),
                    Set.of("target-tree"),
                    Set.of(),
                    "target-tree",
                    null,
                    null,
                    null,
                    false,
                    2.0d)))
        .isPresent();
    assertThat(broker.utilityForRoute("route-b")).isPositive();
    assertThat(broker.utilityForRoute("route-other")).isZero();
    assertThat(
            broker.consumeForPrompt(
                    "route-b",
                    "provider-request-1",
                    1,
                    8,
                    2.0d,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    "strategy-1",
                    "target-tree")
                .replayedRequest())
        .isTrue();
    assertThat(
            broker.consumeForPrompt(
                    "route-other",
                    "provider-request-1",
                    1,
                    8,
                    2.0d,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    "strategy-1",
                    "target-tree")
                .artifacts())
        .isEmpty();

    broker.invalidate(scenario.artifact().artifactId(), "claim-court", "authority revoked", 2);
    assertThat(broker.utilityForRoute("route-b")).isZero();

    MathematicalArtifactBroker expiring = new MathematicalArtifactBroker();
    expiring.publish(
        BrokerArtifactTestFixtures.verifiedClaim(),
        List.of(BrokerArtifactTestFixtures.related("route-expiring")),
        0,
        8);
    assertThat(expiring.expire(20)).isZero();
    assertThat(expiring.expire(21)).isEqualTo(1);
    assertThat(expiring.expire(22)).isZero();

    MathematicalArtifactBroker restored = new MathematicalArtifactBroker();
    restored.restore(null, null, null, null, null, null, null);
    assertThat(restored.artifacts()).isEmpty();
  }

  @Test
  void legacyMigrationAcceptsOnlyCompleteTrustedMathematicalRecords() {
    MessageEnvelope fact =
        legacy(
            "legacy-fact",
            BrokerArtifactTestFixtures.PROBLEM_HASH,
            MessageType.VERIFIED_LEMMA,
            EvidenceType.NATURAL_PROOF_AUDITED,
            MemoryTier.FACT,
            ClaimStatus.VERIFIED,
            "claim-court://legacy",
            true);
    MessageEnvelope counterexample =
        legacy(
            "legacy-counterexample",
            BrokerArtifactTestFixtures.PROBLEM_HASH,
            MessageType.COUNTEREXAMPLE,
            EvidenceType.COUNTEREXAMPLE,
            MemoryTier.NEGATIVE,
            ClaimStatus.REJECTED,
            "experiment://legacy-counterexample",
            true);
    MessageEnvelope wrongProblem =
        legacy(
            "wrong-problem",
            "different-problem",
            MessageType.VERIFIED_LEMMA,
            EvidenceType.NATURAL_PROOF_AUDITED,
            MemoryTier.FACT,
            ClaimStatus.VERIFIED,
            "claim-court://legacy",
            true);
    MessageEnvelope incomplete =
        legacy(
            "incomplete",
            BrokerArtifactTestFixtures.PROBLEM_HASH,
            MessageType.VERIFIED_LEMMA,
            EvidenceType.NATURAL_PROOF_AUDITED,
            MemoryTier.FACT,
            ClaimStatus.VERIFIED,
            "claim-court://legacy",
            false);
    MessageEnvelope untrustedCounterexample =
        legacy(
            "untrusted-counterexample",
            BrokerArtifactTestFixtures.PROBLEM_HASH,
            MessageType.COUNTEREXAMPLE,
            EvidenceType.COUNTEREXAMPLE,
            MemoryTier.NEGATIVE,
            ClaimStatus.REJECTED,
            "model://untrusted",
            true);
    MessageEnvelope control =
        legacy(
            "control",
            BrokerArtifactTestFixtures.PROBLEM_HASH,
            MessageType.FAILURE_RECORD,
            EvidenceType.UNVERIFIED_IDEA,
            MemoryTier.NEGATIVE,
            ClaimStatus.UNCERTAIN,
            "audit://failure",
            true);
    Map<String, MessageEnvelope> messages = new LinkedHashMap<>();
    for (MessageEnvelope message :
        List.of(fact, counterexample, wrongProblem, incomplete, untrustedCounterexample, control)) {
      messages.put(message.messageId(), message);
    }
    MessageStoreSnapshot snapshot =
        new MessageStoreSnapshot(
            messages, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    MathematicalArtifactBroker broker = new MathematicalArtifactBroker();
    assertThat(
            broker.migrateLegacy(
                null,
                BrokerArtifactTestFixtures.PROBLEM_HASH,
                BrokerArtifactTestFixtures.ROOT_HASH,
                List.of(),
                0))
        .isZero();
    assertThat(
            broker.migrateLegacy(
                snapshot,
                BrokerArtifactTestFixtures.PROBLEM_HASH,
                BrokerArtifactTestFixtures.ROOT_HASH,
                List.of(),
                0))
        .isEqualTo(2);
    assertThat(broker.artifacts()).hasSize(2);
  }

  @Test
  void brokerFailureInjectionExercisesRollbackAndHardCrashFrontiers() {
    MathematicalArtifactBroker broker = new MathematicalArtifactBroker();
    broker.setFailurePointForTest(BrokerArtifactFailurePoint.AFTER_DELIVERY);
    assertThatThrownBy(
            () ->
                broker.publish(
                    BrokerArtifactTestFixtures.verifiedClaim(),
                    List.of(BrokerArtifactTestFixtures.related("route-b")),
                    0,
                    8))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AFTER_DELIVERY");
    assertThat(broker.artifacts()).isEmpty();
    assertThat(broker.deliveries()).isEmpty();

    MathematicalArtifactBroker hardCrash = new MathematicalArtifactBroker();
    hardCrash.setHardCrashPointForTest(BrokerArtifactFailurePoint.AFTER_ARTIFACT_REGISTRY);
    assertThatThrownBy(
            () ->
                hardCrash.publish(
                    BrokerArtifactTestFixtures.verifiedClaim(),
                    List.of(BrokerArtifactTestFixtures.related("route-b")),
                    0,
                    8))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("AFTER_ARTIFACT_REGISTRY");
  }

  private static void use(
      MathematicalArtifactBroker broker,
      BrokerArtifactEnvelope artifact,
      String routeId,
      String requestId,
      BrokerArtifactUseClaim use) {
    broker.publish(artifact, List.of(BrokerArtifactTestFixtures.related(routeId)), 0, 8);
    broker.consumeForPrompt(
        routeId,
        requestId,
        0,
        8,
        1.0d,
        Set.of("target-tree"),
        Set.of(),
        Set.of(),
        "strategy-1",
        "target-tree");
    assertThat(
            broker.acknowledge(
                requestId, new BrokerArtifactUseManifest(requestId, List.of(use)), Set.of()))
        .singleElement()
        .extracting(BrokerArtifactReceipt::status)
        .isEqualTo(BrokerArtifactReceiptStatus.USED_PENDING_EFFECT);
  }

  private static BrokerArtifactEnvelope boundedObservation() {
    var context = BrokerArtifactTestFixtures.context("forall", "global", "positive");
    return new BrokerArtifactCompiler()
        .compile(
            BrokerArtifactTestFixtures.request(
                BrokerArtifactType.BOUNDED_OBSERVATION,
                new BoundedObservationPayload("Checked all trees through eight vertices.", context),
                BrokerArtifactSourceKind.BOUNDED_EVIDENCE,
                "route-a",
                "claim-tree",
                "revision-observation",
                true))
        .artifact();
  }

  private static BrokerArtifactEffectObservation unchanged(double debt) {
    return new BrokerArtifactEffectObservation(
        Set.of(),
        Set.of(),
        Set.of(),
        Set.of(),
        Set.of(),
        "target-tree",
        null,
        null,
        null,
        false,
        debt);
  }

  private static MessageEnvelope legacy(
      String id,
      String problemHash,
      MessageType messageType,
      EvidenceType evidenceType,
      MemoryTier tier,
      ClaimStatus status,
      String rawSourceRef,
      boolean claimBound) {
    return new MessageEnvelope(
        List.of("artifact://legacy/" + id),
        List.of("legacy assumption"),
        "legacy conclusion " + id,
        "",
        null,
        List.of(),
        List.of(),
        evidenceType,
        tier,
        id,
        messageType,
        1.0d,
        "legacy normalized " + id,
        problemHash,
        List.of(),
        rawSourceRef,
        0,
        "1",
        List.of("global"),
        "legacy-author",
        RouteRole.PROVER,
        "legacy-source",
        "legacy statement " + id,
        List.of(),
        3,
        List.of(),
        1.0d,
        status,
        claimBound ? "statement-" + id : null,
        claimBound ? "semantic-" + id : null,
        claimBound ? "positive" : null);
  }
}
