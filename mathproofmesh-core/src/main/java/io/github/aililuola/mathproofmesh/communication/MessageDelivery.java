package io.github.aililuola.mathproofmesh.communication;

import io.github.aililuola.mathproofmesh.contract.MessagePriority;
import java.util.Objects;

public record MessageDelivery(
    String deliveryKey,
    String messageId,
    String targetRouteId,
    MessageDeliveryState state,
    MessagePriority priority,
    int deliveredRound,
    int processingOpportunities,
    String providerRequestId,
    String receiptToken,
    boolean actuallyUsed,
    long version) {

  public MessageDelivery {
    deliveryKey = requireText(deliveryKey, "deliveryKey");
    messageId = requireText(messageId, "messageId");
    targetRouteId = requireText(targetRouteId, "targetRouteId");
    state = Objects.requireNonNull(state, "state");
    priority = Objects.requireNonNull(priority, "priority");
    if (deliveredRound < 0 || processingOpportunities < 0 || version < 0) {
      throw new IllegalArgumentException("delivery counters cannot be negative");
    }
    providerRequestId = providerRequestId == null ? "" : providerRequestId.strip();
    receiptToken = requireText(receiptToken, "receiptToken");
  }

  public static MessageDelivery queued(
      String messageId,
      String targetRouteId,
      MessagePriority priority,
      int deliveredRound,
      String receiptToken) {
    return new MessageDelivery(
        DeliveryKey.of(messageId, targetRouteId),
        messageId,
        targetRouteId,
        MessageDeliveryState.QUEUED,
        priority,
        deliveredRound,
        0,
        "",
        receiptToken,
        false,
        0);
  }

  public MessageDelivery transition(MessageDeliveryState next, int opportunities) {
    return new MessageDelivery(
        deliveryKey,
        messageId,
        targetRouteId,
        next,
        priority,
        deliveredRound,
        opportunities,
        providerRequestId,
        receiptToken,
        actuallyUsed,
        version + 1);
  }

  public MessageDelivery consume(String requestId) {
    return new MessageDelivery(
        deliveryKey,
        messageId,
        targetRouteId,
        MessageDeliveryState.PROMPT_CONSUMED,
        priority,
        deliveredRound,
        processingOpportunities + 1,
        requireText(requestId, "providerRequestId"),
        receiptToken,
        actuallyUsed,
        version + 1);
  }

  public MessageDelivery markUsed() {
    return new MessageDelivery(
        deliveryKey,
        messageId,
        targetRouteId,
        state,
        priority,
        deliveredRound,
        processingOpportunities,
        providerRequestId,
        receiptToken,
        true,
        version + 1);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.strip();
  }
}
