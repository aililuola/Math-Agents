package io.github.aililuola.mathproofmesh.communication;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageReceipt;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.ReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

public final class MessageReceiptService {
  private final MessageRepository repository;

  public MessageReceiptService(MessageRepository repository) {
    this.repository = java.util.Objects.requireNonNull(repository, "repository");
  }

  public MessageReceipt buildReceipt(
      MessageEnvelope message,
      MessageDelivery delivery,
      ReceiptStatus status,
      List<String> parsedAssumptions,
      String parsedConclusion,
      List<QuantifierSpec> parsedQuantifiers,
      List<VariableBinding> parsedVariableBindings,
      List<String> referencedStepIds,
      List<String> claimedClosedObligationIds,
      String reason) {
    List<String> assumptions =
        parsedAssumptions == null ? message.assumptions() : List.copyOf(parsedAssumptions);
    String conclusion = parsedConclusion == null ? message.conclusion() : parsedConclusion;
    List<QuantifierSpec> quantifiers =
        parsedQuantifiers == null ? message.quantifiers() : List.copyOf(parsedQuantifiers);
    List<VariableBinding> bindings =
        parsedVariableBindings == null
            ? message.variableBindings()
            : List.copyOf(parsedVariableBindings);
    return new MessageReceipt(
        null,
        claimedClosedObligationIds,
        delivery.deliveredRound(),
        message.messageId(),
        assumptions,
        conclusion,
        quantifiers,
        bindings,
        reason,
        null,
        delivery.receiptToken(),
        referencedStepIds,
        semanticHash(assumptions, conclusion, quantifiers, bindings),
        status,
        delivery.targetRouteId(),
        false);
  }

  public MessageReceipt acknowledge(MessageReceipt supplied) {
    String deliveryKey = DeliveryKey.of(supplied.messageId(), supplied.targetRouteId());
    MessageDelivery delivery =
        repository
            .findDelivery(deliveryKey)
            .orElseThrow(
                () -> new IllegalArgumentException("receipt does not correspond to a delivery"));
    MessageEnvelope message =
        repository
            .findMessage(supplied.messageId())
            .orElseThrow(() -> new IllegalArgumentException("receipt message is unknown"));
    if (delivery.state() != MessageDeliveryState.PROMPT_CONSUMED
        && delivery.state() != MessageDeliveryState.ACKNOWLEDGED
        && delivery.state() != MessageDeliveryState.REJECTED) {
      throw new IllegalStateException("delivery was not consumed by a prompt");
    }
    String parsedHash =
        semanticHash(
            supplied.parsedAssumptions(),
            supplied.parsedConclusion(),
            supplied.parsedQuantifiers(),
            supplied.parsedVariableBindings());
    boolean tokenValid = sameSecret(supplied.receiptToken(), delivery.receiptToken());
    boolean semanticValid =
        sameSecret(parsedHash, message.expectedSemanticHash())
            && sameSecret(supplied.semanticHash(), message.expectedSemanticHash());
    MessageReceipt validated = supplied;
    if (!tokenValid || !semanticValid) {
      validated =
          new MessageReceipt(
              supplied.acknowledgedAt(),
              supplied.claimedClosedObligationIds(),
              supplied.deliveredRound(),
              supplied.messageId(),
              supplied.parsedAssumptions(),
              supplied.parsedConclusion(),
              supplied.parsedQuantifiers(),
              supplied.parsedVariableBindings(),
              tokenValid
                  ? "semantic hash mismatch"
                  : "invalid or missing broker receipt token",
              supplied.receiptId(),
              supplied.receiptToken(),
              supplied.referencedInStepIds(),
              supplied.semanticHash(),
              ReceiptStatus.REJECTED,
              supplied.targetRouteId(),
              false);
    }
    return repository.saveReceipt(deliveryKey, validated);
  }

  public static String semanticHash(
      List<String> assumptions,
      String conclusion,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> variableBindings) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.set("assumptions", ContractObjectMapper.toTree(assumptions));
    payload.put("conclusion", conclusion);
    payload.set("quantifiers", ContractObjectMapper.toTree(quantifiers));
    payload.set("variable_bindings", ContractObjectMapper.toTree(variableBindings));
    return CanonicalJson.stableHash(payload);
  }

  private static boolean sameSecret(String supplied, String expected) {
    return supplied != null
        && expected != null
        && !supplied.isBlank()
        && MessageDigest.isEqual(
            supplied.getBytes(StandardCharsets.UTF_8),
            expected.getBytes(StandardCharsets.UTF_8));
  }
}
