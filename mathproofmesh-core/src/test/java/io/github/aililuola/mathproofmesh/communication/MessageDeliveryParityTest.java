package io.github.aililuola.mathproofmesh.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageReceipt;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.ReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MessageDeliveryParityTest {
  @Test
  void deliveryPromptAcknowledgementAndUseRemainDistinct() {
    Runtime runtime = runtime(MessageBrokerPolicy.strictDefaults());
    MessageEnvelope fact = CommunicationFixtures.fact("states", List.of("route-b"));
    runtime.broker.publish(fact, "referee-a", 1);
    assertEquals(
        MessageDeliveryState.QUEUED,
        runtime.broker.deliveryRecord("states", "route-b").orElseThrow().state());

    runtime.broker.stageDeliveries("route-b", 1, 4);
    assertEquals(
        MessageDeliveryState.DELIVERED,
        runtime.broker.deliveryRecord("states", "route-b").orElseThrow().state());

    PromptDeliveryBatch prompt =
        runtime.broker.consumeForPrompt("route-b", "provider-request-1", 1, 4);
    assertEquals(List.of(fact), prompt.messages());
    assertEquals(
        MessageDeliveryState.PROMPT_CONSUMED,
        runtime.broker.deliveryRecord("states", "route-b").orElseThrow().state());

    MessageReceipt receipt = acceptedReceipt(runtime.broker, fact, "route-b");
    runtime.broker.acknowledge(receipt);
    MessageDelivery acknowledged =
        runtime.broker.deliveryRecord("states", "route-b").orElseThrow();
    assertEquals(MessageDeliveryState.ACKNOWLEDGED, acknowledged.state());
    assertFalse(acknowledged.actuallyUsed());

    assertTrue(
        runtime
            .broker
            .verifyUtility(
                "states",
                "route-b",
                new VerifiedDownstreamEffect(
                    Set.of("step-1"),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    false,
                    2.0,
                    1.0))
            .isPresent());
    assertTrue(
        runtime.broker.deliveryRecord("states", "route-b").orElseThrow().actuallyUsed());
  }

  @Test
  void consumedDeliveryIsNeverReemittedAfterRestore() {
    Runtime runtime = runtime(MessageBrokerPolicy.strictDefaults());
    MessageEnvelope fact = CommunicationFixtures.fact("once", List.of("route-b"));
    runtime.broker.publish(fact, "referee-a", 1);
    assertEquals(
        List.of(fact),
        runtime
            .broker
            .consumeForPrompt("route-b", "provider-request-once", 1, 4)
            .messages());

    InMemoryMessageRepository restoredRepository =
        new InMemoryMessageRepository(runtime.repository.snapshot());
    MessageBroker restored =
        CommunicationFixtures.broker(
            MessageBrokerPolicy.strictDefaults(),
            runtime.routes,
            CommunicationFixtures.acceptingDependencies(),
            restoredRepository);
    assertTrue(
        restored
            .consumeForPrompt("route-b", "new-provider-request", 1, 4)
            .messages()
            .isEmpty());
  }

  @Test
  void sameProviderRequestIsRecognizedWithoutPromptReplay() {
    Runtime runtime = runtime(MessageBrokerPolicy.strictDefaults());
    MessageEnvelope fact = CommunicationFixtures.fact("request-id", List.of("route-b"));
    runtime.broker.publish(fact, "referee-a", 1);
    runtime.broker.consumeForPrompt("route-b", "same-request", 1, 4);
    PromptDeliveryBatch replay =
        runtime.broker.consumeForPrompt("route-b", "same-request", 1, 4);
    assertTrue(replay.replayedRequest());
    assertTrue(replay.messages().isEmpty());
  }

  @Test
  void promptConsumptionAndProviderRequestCommitAtomically() {
    Runtime runtime = runtime(MessageBrokerPolicy.strictDefaults());
    MessageEnvelope fact = CommunicationFixtures.fact("prompt-atomic", List.of("route-b"));
    runtime.broker.publish(fact, "referee-a", 1);
    runtime.repository.failNextCommit();
    assertThrows(
        IllegalStateException.class,
        () ->
            runtime
                .broker
                .consumeForPrompt("route-b", "failed-provider-request", 1, 4));
    assertEquals(
        MessageDeliveryState.QUEUED,
        runtime
            .broker
            .deliveryRecord("prompt-atomic", "route-b")
            .orElseThrow()
            .state());
    assertFalse(
        runtime.repository.snapshot().providerRequests().containsKey("failed-provider-request"));
  }

  @Test
  void acceptedReceiptRequiresBrokerTokenAndSemanticParity() {
    Runtime runtime = runtime(MessageBrokerPolicy.strictDefaults());
    MessageEnvelope fact = CommunicationFixtures.fact("receipt", List.of("route-b"));
    runtime.broker.publish(fact, "referee-a", 1);
    runtime.broker.consumeForPrompt("route-b", "receipt-request", 1, 4);
    MessageReceipt receipt = acceptedReceipt(runtime.broker, fact, "route-b");
    MessageReceipt acknowledged = runtime.broker.acknowledge(receipt);
    assertEquals(ReceiptStatus.ACCEPTED, acknowledged.status());
    assertEquals(fact.expectedSemanticHash(), acknowledged.semanticHash());
  }

  @Test
  void forgedReceiptTokenIsRejected() {
    Runtime runtime = runtime(MessageBrokerPolicy.strictDefaults());
    MessageEnvelope fact = CommunicationFixtures.fact("forged", List.of("route-b"));
    runtime.broker.publish(fact, "referee-a", 1);
    runtime.broker.consumeForPrompt("route-b", "forged-request", 1, 4);
    MessageReceipt valid = acceptedReceipt(runtime.broker, fact, "route-b");
    MessageReceipt forged =
        new MessageReceipt(
            valid.acknowledgedAt(),
            valid.claimedClosedObligationIds(),
            valid.deliveredRound(),
            valid.messageId(),
            valid.parsedAssumptions(),
            valid.parsedConclusion(),
            valid.parsedQuantifiers(),
            valid.parsedVariableBindings(),
            valid.reason(),
            valid.receiptId(),
            "forged-token",
            valid.referencedInStepIds(),
            valid.semanticHash(),
            valid.status(),
            valid.targetRouteId(),
            false);
    MessageReceipt rejected = runtime.broker.acknowledge(forged);
    assertEquals(ReceiptStatus.REJECTED, rejected.status());
    assertEquals("invalid or missing broker receipt token", rejected.reason());
  }

  @Test
  void quantifierReversalReceiptIsRejected() {
    Runtime runtime = runtime(MessageBrokerPolicy.strictDefaults());
    List<VariableBinding> bindings =
        List.of(
            new VariableBinding(List.of(), "n", "integers", "claim", "n"),
            new VariableBinding(List.of(), "m", "integers", "depends on n", "m"));
    List<QuantifierSpec> quantifiers =
        List.of(
            new QuantifierSpec("n", "integers", "forall", 0, List.of(), "n"),
            new QuantifierSpec("m", "integers", "exists", 1, List.of(), "m"));
    MessageEnvelope quantified =
        CommunicationFixtures.message(
            "quantified",
            CommunicationFixtures.PROBLEM_HASH,
            "route-a",
            "author-a",
            RouteRole.PROVER,
            List.of("route-b"),
            "for all n there exists m",
            "m depends on n",
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
            quantifiers,
            bindings);
    runtime.broker.publish(quantified, "referee-a", 1);
    runtime.broker.consumeForPrompt("route-b", "quantified-request", 1, 4);
    MessageDelivery delivery =
        runtime.broker.deliveryRecord("quantified", "route-b").orElseThrow();
    List<QuantifierSpec> reversed =
        List.of(
            new QuantifierSpec("m", "integers", "exists", 0, List.of(), "m"),
            new QuantifierSpec("n", "integers", "forall", 1, List.of(), "n"));
    MessageReceipt receipt =
        runtime
            .broker
            .receiptService()
            .buildReceipt(
                quantified,
                delivery,
                ReceiptStatus.ACCEPTED,
                null,
                null,
                reversed,
                bindings,
                List.of(),
                List.of(),
                "");
    assertEquals(ReceiptStatus.REJECTED, runtime.broker.acknowledge(receipt).status());
  }

  @Test
  void receiptAcceptanceAloneNeverCreatesUtility() {
    Runtime runtime = runtime(MessageBrokerPolicy.strictDefaults());
    MessageEnvelope fact = CommunicationFixtures.fact("no-utility", List.of("route-b"));
    runtime.broker.publish(fact, "referee-a", 1);
    runtime.broker.consumeForPrompt("route-b", "no-utility-request", 1, 4);
    runtime.broker.acknowledge(acceptedReceipt(runtime.broker, fact, "route-b"));
    assertEquals(0.0, runtime.broker.utilityForRoute("route-b"));
    assertTrue(runtime.broker.utilityRecord("no-utility", "route-b").isEmpty());
  }

  @Test
  void routeWideDebtDropAloneIsNotAttributedToMessage() {
    Runtime runtime = runtime(MessageBrokerPolicy.strictDefaults());
    MessageEnvelope fact = CommunicationFixtures.fact("debt-only", List.of("route-b"));
    runtime.broker.publish(fact, "referee-a", 1);
    runtime.broker.consumeForPrompt("route-b", "debt-request", 1, 4);
    runtime.broker.acknowledge(acceptedReceipt(runtime.broker, fact, "route-b"));
    VerifiedDownstreamEffect effect =
        new VerifiedDownstreamEffect(
            Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), false, 5.0, 1.0);
    assertTrue(runtime.broker.verifyUtility("debt-only", "route-b", effect).isEmpty());
  }

  @Test
  void onlyVerifiedReceiptClaimsReceiveUtilityCredit() {
    Runtime runtime = runtime(MessageBrokerPolicy.strictDefaults());
    MessageEnvelope fact = CommunicationFixtures.fact("useful", List.of("route-b"));
    runtime.broker.publish(fact, "referee-a", 1);
    runtime.broker.consumeForPrompt("route-b", "useful-request", 1, 4);
    MessageDelivery delivery =
        runtime.broker.deliveryRecord("useful", "route-b").orElseThrow();
    MessageReceipt receipt =
        runtime
            .broker
            .receiptService()
            .buildReceipt(
                fact,
                delivery,
                ReceiptStatus.ACCEPTED,
                null,
                null,
                null,
                null,
                List.of("actual-step", "invented-step"),
                List.of("actual-obligation", "invented-obligation"),
                "");
    runtime.broker.acknowledge(receipt);
    MessageUtilityRecord utility =
        runtime
            .broker
            .verifyUtility(
                "useful",
                "route-b",
                new VerifiedDownstreamEffect(
                    Set.of("actual-step"),
                    Set.of("actual-obligation"),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    false,
                    2.0,
                    1.0))
            .orElseThrow();
    assertEquals(List.of("actual-step"), utility.referencedStepIds());
    assertEquals(List.of("actual-obligation"), utility.closedObligationIds());
  }

  @Test
  void unconsumedDeliveryExpiresWithoutBeingMarkedUsed() {
    Runtime runtime = runtime(MessageBrokerPolicy.strictDefaults());
    MessageEnvelope fact = CommunicationFixtures.fact("expires", List.of("route-b"));
    runtime.broker.publish(fact, "referee-a", 1);
    runtime.broker.stageDeliveries("route-b", 4, 4);
    MessageDelivery delivery =
        runtime.broker.deliveryRecord("expires", "route-b").orElseThrow();
    assertEquals(MessageDeliveryState.EXPIRED, delivery.state());
    assertEquals(0, delivery.processingOpportunities());
    assertFalse(delivery.actuallyUsed());
  }

  @Test
  void initialIsolationDefersCrossRoutePrompt() {
    Runtime runtime = runtime(policy(8, 2, true));
    MessageEnvelope fact = CommunicationFixtures.fact("isolated", List.of("route-b"));
    runtime.broker.publish(fact, "referee-a", 0);
    assertEquals(
        MessageDeliveryState.DEFERRED,
        runtime.broker.deliveryRecord("isolated", "route-b").orElseThrow().state());
    assertTrue(
        runtime
            .broker
            .consumeForPrompt("route-b", "isolation-early", 0, 4)
            .messages()
            .isEmpty());
    assertEquals(
        List.of(fact),
        runtime
            .broker
            .consumeForPrompt("route-b", "isolation-released", 2, 4)
            .messages());
  }

  @Test
  void initialIsolationReleasesAtMostTheConfiguredPerRoundLimit() {
    Runtime runtime = runtime(policy(1, 1, true));
    MessageEnvelope first = CommunicationFixtures.insight("isolated-a", List.of("route-b"));
    MessageEnvelope second = CommunicationFixtures.insight("isolated-b", List.of("route-b"));
    runtime.broker.publish(first, null, 0);
    runtime.broker.publish(second, null, 0);

    assertTrue(
        runtime.broker.consumeForPrompt("route-b", "before-release", 0, 4).messages().isEmpty());
    List<MessageEnvelope> roundOne =
        runtime.broker.consumeForPrompt("route-b", "round-one", 1, 4).messages();
    List<MessageEnvelope> roundTwo =
        runtime.broker.consumeForPrompt("route-b", "round-two", 2, 4).messages();
    assertEquals(1, roundOne.size());
    assertEquals(1, roundTwo.size());
    assertEquals(
        Set.of(first.messageId(), second.messageId()),
        Set.of(roundOne.getFirst().messageId(), roundTwo.getFirst().messageId()));
  }

  @Test
  void highPriorityFactRetainsReservedSlot() {
    Runtime runtime = runtime(policy(2, 0, true));
    runtime.broker.publish(CommunicationFixtures.insight("low-a", List.of("route-b")), null, 1);
    var lowSecond =
        runtime.broker.publish(
            CommunicationFixtures.insight("low-b", List.of("route-b")), null, 1);
    var high =
        runtime.broker.publish(
            CommunicationFixtures.fact("high", List.of("route-b")), "referee-a", 1);
    assertTrue(lowSecond.selectedTargets().isEmpty());
    assertEquals(List.of("route-b"), high.selectedTargets());
    assertEquals(
        "high",
        runtime
            .broker
            .consumeForPrompt("route-b", "priority-request", 1, 4)
            .messages()
            .getFirst()
            .messageId());
  }

  @Test
  void invalidatedDeliveryIsArchivedWithoutBlockingRepublication() {
    Runtime runtime = runtime(MessageBrokerPolicy.strictDefaults());
    MessageEnvelope fact = CommunicationFixtures.fact("reusable", List.of("route-b"));
    assertTrue(runtime.broker.publish(fact, "referee-a", 1).accepted());

    String reason = "checkpoint_rolled_back:checkpoint-abandoned";
    List<InvalidatedDelivery> invalidated =
        runtime.broker.invalidateMessages(List.of(fact.messageId()), reason);

    assertEquals(1, invalidated.size());
    assertEquals("invalidated", invalidated.getFirst().deliveryState());
    assertEquals(reason, invalidated.getFirst().invalidationReason());
    assertTrue(runtime.repository.snapshot().deliveries().isEmpty());
    assertEquals(
        invalidated.getFirst(),
        runtime
            .repository
            .snapshot()
            .invalidatedDeliveries()
            .get(DeliveryKey.of("reusable", "route-b")));
    assertTrue(runtime.broker.stageDeliveries("route-b", 1, 4).isEmpty());

    var republished = runtime.broker.publish(fact, "referee-a", 2);
    assertTrue(republished.accepted());
    assertEquals(List.of("route-b"), republished.selectedTargets());
    assertTrue(runtime.broker.deliveryRecord("reusable", "route-b").isPresent());
    assertEquals(
        List.of(fact),
        runtime
            .broker
            .consumeForPrompt("route-b", "republication", 2, 4)
            .messages());
  }

  @Test
  void deliveryKeysSeparateRoutesAndRunsDoNotShareState() {
    assertNotEquals(DeliveryKey.of("m", "route-a"), DeliveryKey.of("m", "route-b"));
    Runtime first = runtime(MessageBrokerPolicy.strictDefaults());
    Runtime second = runtime(MessageBrokerPolicy.strictDefaults());
    first.broker.publish(
        CommunicationFixtures.fact("same-id", List.of("route-b")), "referee-a", 1);
    assertTrue(second.repository.snapshot().messages().isEmpty());
  }

  @Test
  void duplicatePublishDoesNotDuplicateDomainEffects() {
    Runtime runtime = runtime(MessageBrokerPolicy.strictDefaults());
    runtime.broker.publish(
        CommunicationFixtures.fact("domain-original", List.of("route-b")),
        "referee-a",
        1);
    runtime.broker.publish(
        CommunicationFixtures.fact("domain-copy", List.of("route-b")),
        "referee-a",
        1);
    long admissions =
        runtime.repository.snapshot().domainEvents().keySet().stream()
            .filter(key -> key.startsWith("message-admitted:"))
            .count();
    assertEquals(1, admissions);
  }

  private static MessageReceipt acceptedReceipt(
      MessageBroker broker, MessageEnvelope message, String targetRoute) {
    MessageDelivery delivery =
        broker.deliveryRecord(message.messageId(), targetRoute).orElseThrow();
    return broker
        .receiptService()
        .buildReceipt(
            message,
            delivery,
            ReceiptStatus.ACCEPTED,
            null,
            null,
            null,
            null,
            List.of("step-1"),
            List.of(),
            "");
  }

  private static Runtime runtime(MessageBrokerPolicy policy) {
    RouteRegistry routes = CommunicationFixtures.routes();
    InMemoryMessageRepository repository = new InMemoryMessageRepository();
    MessageBroker broker =
        CommunicationFixtures.broker(
            policy,
            routes,
            CommunicationFixtures.acceptingDependencies(),
            repository);
    return new Runtime(routes, repository, broker);
  }

  private static MessageBrokerPolicy policy(
      int routeMessageLimit, int isolationRounds, boolean shareInsights) {
    return new MessageBrokerPolicy(
        "1",
        32_000,
        64,
        64,
        routeMessageLimit,
        128,
        3,
        isolationRounds,
        0.9,
        true,
        true,
        true,
        true,
        true,
        shareInsights);
  }

  private record Runtime(
      RouteRegistry routes,
      InMemoryMessageRepository repository,
      MessageBroker broker) {}
}
