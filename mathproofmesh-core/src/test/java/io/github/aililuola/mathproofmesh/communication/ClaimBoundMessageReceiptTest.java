package io.github.aililuola.mathproofmesh.communication;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.ReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClaimBoundMessageReceiptTest {
  @Test
  void fullClaimSemanticHashSurvivesBrokerReceiptValidation() {
    Runtime runtime = runtime();
    MessageEnvelope fact = claimBoundFact("claim-bound-fact");

    runtime.broker().publish(fact, "referee-a", 1);
    runtime.broker().consumeForPrompt("route-b", "claim-bound-request", 1, 4);
    MessageDelivery delivery =
        runtime.broker().deliveryRecord(fact.messageId(), "route-b").orElseThrow();
    var receipt =
        runtime
            .broker()
            .receiptService()
            .buildReceipt(
                fact,
                delivery,
                ReceiptStatus.ACCEPTED,
                fact.assumptions(),
                fact.conclusion(),
                fact.quantifiers(),
                fact.variableBindings(),
                List.of("step-1"),
                List.of(),
                "parsed exact claim context");

    assertThat(receipt.semanticHash()).isEqualTo(fact.claimSemanticHash());
    assertThat(runtime.broker().acknowledge(receipt).status()).isEqualTo(ReceiptStatus.ACCEPTED);
  }

  @Test
  void changedParsedClaimCoreCannotReuseFullSemanticHash() {
    Runtime runtime = runtime();
    MessageEnvelope fact = claimBoundFact("claim-bound-tampered");

    runtime.broker().publish(fact, "referee-a", 1);
    runtime.broker().consumeForPrompt("route-b", "claim-bound-tampered-request", 1, 4);
    MessageDelivery delivery =
        runtime.broker().deliveryRecord(fact.messageId(), "route-b").orElseThrow();
    var receipt =
        runtime
            .broker()
            .receiptService()
            .buildReceipt(
                fact,
                delivery,
                ReceiptStatus.ACCEPTED,
                fact.assumptions(),
                "different conclusion",
                fact.quantifiers(),
                fact.variableBindings(),
                List.of(),
                List.of(),
                "tampered parsed claim context");

    assertThat(runtime.broker().acknowledge(receipt).status()).isEqualTo(ReceiptStatus.REJECTED);
  }

  private static MessageEnvelope claimBoundFact(String messageId) {
    return new MessageEnvelope(
        List.of(),
        List.of("H"),
        "P",
        "",
        null,
        List.of(),
        List.of(),
        EvidenceType.NATURAL_PROOF_AUDITED,
        MemoryTier.FACT,
        messageId,
        MessageType.VERIFIED_LEMMA,
        1.0d,
        "p",
        CommunicationFixtures.PROBLEM_HASH,
        List.of(),
        null,
        1,
        "1",
        List.of("scope-D"),
        "author-a",
        RouteRole.PROVER,
        "route-a",
        "P",
        List.of("route-b"),
        2,
        List.of(),
        1.0d,
        ClaimStatus.VERIFIED,
        "1".repeat(64),
        "2".repeat(64),
        "positive");
  }

  private static Runtime runtime() {
    RouteRegistry routes = CommunicationFixtures.routes();
    InMemoryMessageRepository repository = new InMemoryMessageRepository();
    MessageBroker broker =
        CommunicationFixtures.broker(
            MessageBrokerPolicy.strictDefaults(),
            routes,
            CommunicationFixtures.acceptingDependencies(),
            repository);
    return new Runtime(broker);
  }

  private record Runtime(MessageBroker broker) {}
}
