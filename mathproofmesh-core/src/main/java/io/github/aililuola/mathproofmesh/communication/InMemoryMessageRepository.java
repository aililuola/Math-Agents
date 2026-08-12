package io.github.aililuola.mathproofmesh.communication;

import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessagePriority;
import io.github.aililuola.mathproofmesh.contract.MessageReceipt;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class InMemoryMessageRepository implements MessageRepository {
  private final Map<String, MessageEnvelope> messages;
  private final Map<String, String> dedupeIndex;
  private final Map<String, MessageDelivery> deliveries;
  private final Map<String, MessageReceipt> receipts;
  private final Map<String, MessageUtilityRecord> utilities;
  private final Map<String, List<String>> providerRequests;
  private final Map<String, String> domainEvents;
  private final Map<String, InvalidatedDelivery> invalidatedDeliveries;
  private boolean failNextCommit;

  public InMemoryMessageRepository() {
    this(
        new MessageStoreSnapshot(
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
  }

  public InMemoryMessageRepository(MessageStoreSnapshot snapshot) {
    messages = new LinkedHashMap<>(snapshot.messages());
    dedupeIndex = new LinkedHashMap<>(snapshot.dedupeIndex());
    deliveries = new LinkedHashMap<>(snapshot.deliveries());
    receipts = new LinkedHashMap<>(snapshot.receipts());
    utilities = new LinkedHashMap<>(snapshot.utilities());
    providerRequests = new LinkedHashMap<>(snapshot.providerRequests());
    domainEvents = new LinkedHashMap<>(snapshot.domainEvents());
    invalidatedDeliveries = new LinkedHashMap<>(snapshot.invalidatedDeliveries());
  }

  public synchronized void failNextCommit() {
    failNextCommit = true;
  }

  @Override
  public synchronized Optional<MessageEnvelope> findMessage(String messageId) {
    return Optional.ofNullable(messages.get(messageId));
  }

  @Override
  public synchronized Optional<MessageEnvelope> findByDedupeKey(String dedupeKey) {
    return Optional.ofNullable(dedupeIndex.get(dedupeKey)).map(messages::get);
  }

  @Override
  public synchronized Optional<MessageDelivery> findDelivery(String deliveryKey) {
    return Optional.ofNullable(deliveries.get(deliveryKey));
  }

  @Override
  public synchronized List<MessageDelivery> deliveriesForMessage(String messageId) {
    return deliveries.values().stream()
        .filter(delivery -> delivery.messageId().equals(messageId))
        .toList();
  }

  @Override
  public synchronized long countDeliveries(
      String targetRouteId, int deliveredRound, Set<MessagePriority> priorities) {
    return deliveries.values().stream()
        .filter(delivery -> delivery.targetRouteId().equals(targetRouteId))
        .filter(delivery -> delivery.deliveredRound() == deliveredRound)
        .filter(delivery -> priorities.contains(delivery.priority()))
        .count();
  }

  @Override
  public synchronized long countDeliveries(
      int deliveredRound, Set<MessagePriority> priorities) {
    return deliveries.values().stream()
        .filter(delivery -> delivery.deliveredRound() == deliveredRound)
        .filter(delivery -> priorities.contains(delivery.priority()))
        .count();
  }

  @Override
  public synchronized void saveAccepted(
      MessageEnvelope message,
      String dedupeKey,
      Collection<MessageDelivery> newDeliveries,
      String admittedEventPayload) {
    MessageEnvelope sameId = messages.get(message.messageId());
    if (sameId != null && !sameId.contentHash().equals(message.contentHash())) {
      throw new IllegalStateException("message ID already has different content");
    }
    String duplicateId = dedupeIndex.get(dedupeKey);
    if (duplicateId != null && !duplicateId.equals(message.messageId())) {
      throw new IllegalStateException("semantic duplicate must use addDeliveries");
    }
    failBeforeCommitIfRequested();
    messages.putIfAbsent(message.messageId(), message);
    dedupeIndex.putIfAbsent(dedupeKey, message.messageId());
    newDeliveries.forEach(
        delivery -> deliveries.putIfAbsent(delivery.deliveryKey(), delivery));
    domainEvents.putIfAbsent(
        "message-admitted:" + message.messageId(), admittedEventPayload);
  }

  @Override
  public synchronized void addDeliveries(
      String messageId,
      Collection<MessageDelivery> newDeliveries,
      String admittedEventPayload) {
    if (!messages.containsKey(messageId)) {
      throw new IllegalArgumentException("unknown message: " + messageId);
    }
    failBeforeCommitIfRequested();
    for (MessageDelivery delivery : newDeliveries) {
      deliveries.putIfAbsent(delivery.deliveryKey(), delivery);
      domainEvents.putIfAbsent(
          "message-delivery:" + delivery.deliveryKey(), admittedEventPayload);
    }
  }

  @Override
  public synchronized List<MessageDelivery> stageDeliveries(
      String targetRouteId, int currentRound, int limit) {
    List<MessageDelivery> selected =
        eligible(targetRouteId, currentRound).stream().limit(limit).toList();
    for (MessageDelivery delivery : selected) {
      deliveries.put(
          delivery.deliveryKey(),
          delivery.transition(MessageDeliveryState.DELIVERED, delivery.processingOpportunities()));
    }
    return selected.stream().map(item -> deliveries.get(item.deliveryKey())).toList();
  }

  @Override
  public synchronized PromptDeliveryBatch consumeForPrompt(
      String targetRouteId, String providerRequestId, int currentRound, int limit) {
    if (providerRequests.containsKey(providerRequestId)) {
      return new PromptDeliveryBatch(
          providerRequestId, targetRouteId, List.of(), List.of(), true);
    }
    Map<String, MessageDelivery> deliveryCopy = new LinkedHashMap<>(deliveries);
    Map<String, List<String>> requestCopy = new LinkedHashMap<>(providerRequests);
    List<MessageDelivery> selected =
        deliveryCopy.values().stream()
            .filter(delivery -> delivery.targetRouteId().equals(targetRouteId))
            .filter(
                delivery ->
                    delivery.state() == MessageDeliveryState.QUEUED
                        || delivery.state() == MessageDeliveryState.DELIVERED
                        || (delivery.state() == MessageDeliveryState.DEFERRED
                            && delivery.deliveredRound() <= currentRound))
            .sorted(deliveryOrder())
            .limit(limit)
            .toList();
    List<MessageDelivery> consumed = new ArrayList<>();
    for (MessageDelivery delivery : selected) {
      MessageDelivery updated = delivery.consume(providerRequestId);
      deliveryCopy.put(delivery.deliveryKey(), updated);
      consumed.add(updated);
    }
    requestCopy.put(
        providerRequestId, consumed.stream().map(MessageDelivery::deliveryKey).toList());
    if (failNextCommit) {
      failNextCommit = false;
      throw new IllegalStateException("injected failure before atomic commit");
    }
    deliveries.clear();
    deliveries.putAll(deliveryCopy);
    providerRequests.clear();
    providerRequests.putAll(requestCopy);
    List<MessageEnvelope> promptMessages =
        consumed.stream().map(item -> messages.get(item.messageId())).toList();
    return new PromptDeliveryBatch(
        providerRequestId, targetRouteId, promptMessages, consumed, false);
  }

  @Override
  public synchronized MessageReceipt saveReceipt(
      String deliveryKey, MessageReceipt receipt) {
    MessageDelivery delivery = deliveries.get(deliveryKey);
    if (delivery == null) {
      throw new IllegalArgumentException("receipt does not correspond to a delivery");
    }
    MessageReceipt existing = receipts.get(deliveryKey);
    if (existing != null) {
      return existing;
    }
    MessageDeliveryState state =
        receipt.status() == io.github.aililuola.mathproofmesh.contract.ReceiptStatus.ACCEPTED
            ? MessageDeliveryState.ACKNOWLEDGED
            : MessageDeliveryState.REJECTED;
    deliveries.put(
        deliveryKey, delivery.transition(state, delivery.processingOpportunities()));
    receipts.put(deliveryKey, receipt);
    domainEvents.putIfAbsent(
        "message-receipt:" + deliveryKey, receipt.status().value());
    return receipt;
  }

  @Override
  public synchronized Optional<MessageReceipt> findReceipt(String deliveryKey) {
    return Optional.ofNullable(receipts.get(deliveryKey));
  }

  @Override
  public synchronized void saveUtility(MessageUtilityRecord utility) {
    MessageDelivery delivery = deliveries.get(utility.deliveryKey());
    if (delivery == null) {
      throw new IllegalArgumentException("utility does not correspond to a delivery");
    }
    utilities.putIfAbsent(utility.deliveryKey(), utility);
    deliveries.put(utility.deliveryKey(), delivery.markUsed());
    domainEvents.putIfAbsent(
        "message-utility:" + utility.deliveryKey(), Double.toString(utility.score()));
  }

  @Override
  public synchronized Optional<MessageUtilityRecord> findUtility(String deliveryKey) {
    return Optional.ofNullable(utilities.get(deliveryKey));
  }

  @Override
  public synchronized List<InvalidatedDelivery> invalidateMessages(
      Collection<String> messageIds, String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("invalidation reason is required");
    }
    Set<String> selectedIds = Set.copyOf(messageIds);
    List<InvalidatedDelivery> archived =
        deliveries.values().stream()
            .filter(delivery -> selectedIds.contains(delivery.messageId()))
            .map(delivery -> InvalidatedDelivery.of(delivery, reason))
            .toList();
    for (InvalidatedDelivery invalidated : archived) {
      String key = invalidated.delivery().deliveryKey();
      invalidatedDeliveries.putIfAbsent(key, invalidated);
      deliveries.remove(key);
      receipts.remove(key);
      utilities.remove(key);
      domainEvents.putIfAbsent("message-invalidated:" + key, reason);
    }
    return List.copyOf(archived);
  }

  @Override
  public synchronized List<String> expire(int currentRound) {
    List<String> expired = new ArrayList<>();
    for (MessageDelivery delivery : List.copyOf(deliveries.values())) {
      if (delivery.state() != MessageDeliveryState.QUEUED
          && delivery.state() != MessageDeliveryState.DELIVERED
          && delivery.state() != MessageDeliveryState.DEFERRED) {
        continue;
      }
      MessageEnvelope message = messages.get(delivery.messageId());
      if (currentRound - delivery.deliveredRound() > message.ttlRounds()) {
        deliveries.put(
            delivery.deliveryKey(),
            delivery.transition(
                MessageDeliveryState.EXPIRED, delivery.processingOpportunities()));
        expired.add(delivery.deliveryKey());
      }
    }
    return List.copyOf(expired);
  }

  @Override
  public synchronized MessageStoreSnapshot snapshot() {
    return new MessageStoreSnapshot(
        messages,
        dedupeIndex,
        deliveries,
        receipts,
        utilities,
        providerRequests,
        domainEvents,
        invalidatedDeliveries);
  }

  private List<MessageDelivery> eligible(String targetRouteId, int currentRound) {
    return deliveries.values().stream()
        .filter(delivery -> delivery.targetRouteId().equals(targetRouteId))
        .filter(
            delivery ->
                delivery.state() == MessageDeliveryState.QUEUED
                    || (delivery.state() == MessageDeliveryState.DEFERRED
                        && delivery.deliveredRound() <= currentRound))
        .sorted(deliveryOrder())
        .toList();
  }

  private static Comparator<MessageDelivery> deliveryOrder() {
    return Comparator.comparingInt(
            (MessageDelivery delivery) ->
                switch (delivery.priority()) {
                  case CRITICAL -> 0;
                  case HIGH -> 1;
                  case NORMAL -> 2;
                  case LOW -> 3;
                })
        .thenComparingInt(MessageDelivery::deliveredRound)
        .thenComparing(MessageDelivery::deliveryKey);
  }

  private void failBeforeCommitIfRequested() {
    if (failNextCommit) {
      failNextCommit = false;
      throw new IllegalStateException("injected failure before atomic commit");
    }
  }
}
