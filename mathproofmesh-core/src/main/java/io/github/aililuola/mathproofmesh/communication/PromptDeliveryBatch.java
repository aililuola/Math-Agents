package io.github.aililuola.mathproofmesh.communication;

import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.util.List;

public record PromptDeliveryBatch(
    String providerRequestId,
    String targetRouteId,
    List<MessageEnvelope> messages,
    List<MessageDelivery> deliveries,
    boolean replayedRequest) {

  public PromptDeliveryBatch {
    messages = messages == null ? List.of() : List.copyOf(messages);
    deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
  }
}
