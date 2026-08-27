package io.github.aililuola.mathproofmesh.communication;

import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageReceipt;
import java.util.List;
import java.util.Map;

public record MessageStoreSnapshot(
    Map<String, MessageEnvelope> messages,
    Map<String, String> dedupeIndex,
    Map<String, MessageDelivery> deliveries,
    Map<String, MessageReceipt> receipts,
    Map<String, MessageUtilityRecord> utilities,
    Map<String, List<String>> providerRequests,
    Map<String, String> domainEvents,
    Map<String, InvalidatedDelivery> invalidatedDeliveries) {

  public MessageStoreSnapshot {
    messages = messages == null ? Map.of() : Map.copyOf(messages);
    dedupeIndex = dedupeIndex == null ? Map.of() : Map.copyOf(dedupeIndex);
    deliveries = deliveries == null ? Map.of() : Map.copyOf(deliveries);
    receipts = receipts == null ? Map.of() : Map.copyOf(receipts);
    utilities = utilities == null ? Map.of() : Map.copyOf(utilities);
    providerRequests =
        providerRequests == null
            ? Map.of()
            : providerRequests.entrySet().stream()
                .collect(
                    java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    domainEvents = domainEvents == null ? Map.of() : Map.copyOf(domainEvents);
    invalidatedDeliveries =
        invalidatedDeliveries == null ? Map.of() : Map.copyOf(invalidatedDeliveries);
  }

  @Override
  public Map<String, List<String>> providerRequests() {
    return providerRequests.entrySet().stream()
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
  }
}
