package io.github.aililuola.mathproofmesh.communication;

import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessagePriority;
import io.github.aililuola.mathproofmesh.contract.MessageReceipt;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface MessageRepository {
  Optional<MessageEnvelope> findMessage(String messageId);

  Optional<MessageEnvelope> findByDedupeKey(String dedupeKey);

  Optional<MessageDelivery> findDelivery(String deliveryKey);

  List<MessageDelivery> deliveriesForMessage(String messageId);

  long countDeliveries(
      String targetRouteId, int deliveredRound, Set<MessagePriority> priorities);

  long countDeliveries(int deliveredRound, Set<MessagePriority> priorities);

  void saveAccepted(
      MessageEnvelope message,
      String dedupeKey,
      Collection<MessageDelivery> deliveries,
      String admittedEventPayload);

  void addDeliveries(
      String messageId,
      Collection<MessageDelivery> deliveries,
      String admittedEventPayload);

  List<MessageDelivery> stageDeliveries(String targetRouteId, int currentRound, int limit);

  PromptDeliveryBatch consumeForPrompt(
      String targetRouteId, String providerRequestId, int currentRound, int limit);

  MessageReceipt saveReceipt(String deliveryKey, MessageReceipt receipt);

  Optional<MessageReceipt> findReceipt(String deliveryKey);

  void saveUtility(MessageUtilityRecord utility);

  Optional<MessageUtilityRecord> findUtility(String deliveryKey);

  List<InvalidatedDelivery> invalidateMessages(
      Collection<String> messageIds, String reason);

  List<String> expire(int currentRound);

  MessageStoreSnapshot snapshot();
}
