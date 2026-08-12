package io.github.aililuola.mathproofmesh.communication;

import io.github.aililuola.mathproofmesh.contract.BrokerDecision;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessagePriority;
import io.github.aililuola.mathproofmesh.contract.MessageReceipt;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class MessageBroker {
  private static final Set<MessagePriority> ALL_PRIORITIES =
      EnumSet.allOf(MessagePriority.class);
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final MessageBrokerPolicy policy;
  private final MessageAdmissionPolicy admission;
  private final MessageRepository repository;
  private final MessageReceiptService receipts;
  private final MessageUtilityVerifier utilityVerifier;
  private final List<BrokerDecision> decisions = new ArrayList<>();
  private final List<BrokerAdmissionAudit> audit = new ArrayList<>();
  private final Set<String> admittedFactIds = new LinkedHashSet<>();

  public MessageBroker(
      MessageBrokerPolicy policy,
      RouteRegistry routes,
      ArtifactCatalog artifacts,
      DependencyCatalog dependencies,
      MessageRepository repository) {
    this.policy = java.util.Objects.requireNonNull(policy, "policy");
    this.repository = java.util.Objects.requireNonNull(repository, "repository");
    admission = new MessageAdmissionPolicy(policy, routes, artifacts, dependencies);
    receipts = new MessageReceiptService(repository);
    utilityVerifier = new MessageUtilityVerifier(repository);
  }

  public synchronized BrokerDecision publish(
      MessageEnvelope message, String reviewerAgentId, int currentRound) {
    AdmissionResult admitted = admission.evaluate(message, reviewerAgentId, currentRound);
    if (!admitted.accepted()) {
      BrokerDecision rejected =
          decision(
              message == null ? "unknown" : message.messageId(),
              false,
              null,
              admitted.reason(),
              List.of(),
              Map.of());
      decisions.add(rejected);
      audit.add(
          new BrokerAdmissionAudit(
              rejected.messageId(),
              false,
              admitted.rejection().gate(),
              admitted.rejection(),
              admitted.reason()));
      return rejected;
    }

    String dedupeKey = dedupeKey(message);
    Optional<MessageEnvelope> duplicate = repository.findByDedupeKey(dedupeKey);
    if (duplicate.isPresent()) {
      MessageEnvelope original = duplicate.orElseThrow();
      List<String> existingTargets =
          repository.deliveriesForMessage(original.messageId()).stream()
              .map(MessageDelivery::targetRouteId)
              .toList();
      CapacitySelection selection =
          enforceCapacity(
              admitted.selectedTargets().stream()
                  .filter(target -> !existingTargets.contains(target))
                  .toList(),
              admitted.rejectedTargets(),
              admitted.priority(),
              currentRound);
      List<MessageDelivery> deliveries =
          createDeliveries(
              original.messageId(),
              selection.selectedTargets(),
              admitted.priority(),
              currentRound);
      repository.addDeliveries(
          original.messageId(),
          deliveries,
          "{\"event_type\":\"message_deduplicated\"}");
      BrokerDecision result =
          decision(
              message.messageId(),
              true,
              original.messageId(),
              null,
              selection.selectedTargets(),
              selection.rejectedTargets());
      decisions.add(result);
      audit.add(
          new BrokerAdmissionAudit(
              message.messageId(),
              true,
              AdmissionRejection.CONTENT_DUPLICATE.gate(),
              AdmissionRejection.CONTENT_DUPLICATE,
              "semantic duplicate of " + original.messageId()));
      return result;
    }

    CapacitySelection selection =
        enforceCapacity(
            admitted.selectedTargets(),
            admitted.rejectedTargets(),
            admitted.priority(),
            currentRound);
    List<MessageDelivery> deliveries =
        createDeliveries(
            message.messageId(),
            selection.selectedTargets(),
            admitted.priority(),
            currentRound);
    try {
      repository.saveAccepted(
          message,
          dedupeKey,
          deliveries,
          "{\"event_type\":\"message_admitted\",\"message_id\":\""
              + message.messageId()
              + "\"}");
    } catch (RuntimeException exception) {
      BrokerDecision rejected =
          decision(
              message.messageId(),
              false,
              null,
              "message persistence failed atomically",
              List.of(),
              Map.of());
      decisions.add(rejected);
      audit.add(
          new BrokerAdmissionAudit(
              message.messageId(),
              false,
              AdmissionRejection.PERSISTENCE.gate(),
              AdmissionRejection.PERSISTENCE,
              exception.getMessage()));
      return rejected;
    }
    if (message.memoryTier() == MemoryTier.FACT) {
      admittedFactIds.add(message.messageId());
    }
    BrokerDecision result =
        decision(
            message.messageId(),
            true,
            null,
            null,
            selection.selectedTargets(),
            selection.rejectedTargets());
    decisions.add(result);
    audit.add(new BrokerAdmissionAudit(message.messageId(), true, 14, null, ""));
    return result;
  }

  public List<MessageDelivery> stageDeliveries(
      String targetRouteId, int currentRound, int limit) {
    repository.expire(currentRound);
    return repository.stageDeliveries(
        targetRouteId,
        currentRound,
        Math.min(limit, policy.maxMessagesPerRoutePerRound()));
  }

  public PromptDeliveryBatch consumeForPrompt(
      String targetRouteId, String providerRequestId, int currentRound, int limit) {
    repository.expire(currentRound);
    return repository.consumeForPrompt(
        targetRouteId,
        providerRequestId,
        currentRound,
        Math.min(limit, policy.maxMessagesPerRoutePerRound()));
  }

  public MessageReceipt acknowledge(MessageReceipt receipt) {
    return receipts.acknowledge(receipt);
  }

  public Optional<MessageUtilityRecord> verifyUtility(
      String messageId, String targetRouteId, VerifiedDownstreamEffect effect) {
    return utilityVerifier.verify(messageId, targetRouteId, effect);
  }

  public Optional<MessageDelivery> deliveryRecord(
      String messageId, String targetRouteId) {
    return repository.findDelivery(DeliveryKey.of(messageId, targetRouteId));
  }

  public Optional<MessageReceipt> receiptRecord(
      String messageId, String targetRouteId) {
    return repository.findReceipt(DeliveryKey.of(messageId, targetRouteId));
  }

  public Optional<MessageUtilityRecord> utilityRecord(
      String messageId, String targetRouteId) {
    return repository.findUtility(DeliveryKey.of(messageId, targetRouteId));
  }

  public List<InvalidatedDelivery> invalidateMessages(
      java.util.Collection<String> messageIds, String reason) {
    return repository.invalidateMessages(messageIds, reason);
  }

  public double utilityForRoute(String routeId) {
    List<MessageDelivery> acknowledged =
        repository.snapshot().deliveries().values().stream()
            .filter(delivery -> delivery.targetRouteId().equals(routeId))
            .filter(
                delivery ->
                    repository
                        .findReceipt(delivery.deliveryKey())
                        .map(receipt -> receipt.status().value().equals("accepted"))
                        .orElse(false))
            .toList();
    if (acknowledged.isEmpty()) {
      return 0.0;
    }
    double total =
        acknowledged.stream()
            .mapToDouble(
                delivery ->
                    repository
                        .findUtility(delivery.deliveryKey())
                        .map(MessageUtilityRecord::score)
                        .orElse(0.0))
            .sum();
    return total / acknowledged.size();
  }

  public List<MessageEnvelope> admittedFacts() {
    return admittedFactIds.stream()
        .map(repository::findMessage)
        .flatMap(Optional::stream)
        .toList();
  }

  public boolean contains(MessageEnvelope message) {
    return repository.findByDedupeKey(dedupeKey(message)).isPresent();
  }

  public List<BrokerDecision> decisions() {
    return List.copyOf(decisions);
  }

  public List<BrokerAdmissionAudit> admissionAudit() {
    return List.copyOf(audit);
  }

  public MessageReceiptService receiptService() {
    return receipts;
  }

  private CapacitySelection enforceCapacity(
      List<String> candidates,
      Map<String, String> previousRejections,
      MessagePriority priority,
      int currentRound) {
    List<String> selected = new ArrayList<>();
    Map<String, String> rejected = new LinkedHashMap<>(previousRejections);
    long provisionalGlobal = 0;
    for (String target : candidates) {
      long routeCount =
          repository.countDeliveries(target, currentRound, ALL_PRIORITIES)
              + selected.stream().filter(target::equals).count();
      int routeLimit = availableLimit(policy.maxMessagesPerRoutePerRound(), priority);
      if (routeCount >= routeLimit) {
        rejected.put(target, priority.value() + " priority slot unavailable");
        continue;
      }
      long globalCount =
          repository.countDeliveries(currentRound, ALL_PRIORITIES) + provisionalGlobal;
      int globalLimit = availableLimit(policy.maxGlobalMessagesPerRound(), priority);
      if (globalCount >= globalLimit) {
        rejected.put(
            target, "global " + priority.value() + " priority slot unavailable");
        continue;
      }
      selected.add(target);
      provisionalGlobal++;
    }
    return new CapacitySelection(List.copyOf(selected), Map.copyOf(rejected));
  }

  private static int availableLimit(int limit, MessagePriority priority) {
    int reserved =
        switch (priority) {
          case CRITICAL, HIGH -> 0;
          case NORMAL -> Math.min(1, Math.max(0, limit - 1));
          case LOW -> Math.min(2, Math.max(0, limit - 1));
        };
    return Math.max(0, limit - reserved);
  }

  private List<MessageDelivery> createDeliveries(
      String messageId,
      List<String> targets,
      MessagePriority priority,
      int currentRound) {
    int releaseRound =
        currentRound < policy.initialIsolationRounds()
            ? policy.initialIsolationRounds()
            : currentRound;
    return targets.stream()
        .map(
            target -> {
              MessageDelivery queued =
                  MessageDelivery.queued(
                      messageId, target, priority, releaseRound, receiptToken());
              return releaseRound == currentRound
                  ? queued
                  : queued.transition(MessageDeliveryState.DEFERRED, 0);
            })
        .toList();
  }

  private static String dedupeKey(MessageEnvelope message) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("problem_hash", message.problemHash());
    payload.put("message_type", message.messageType().value());
    payload.put("normalized_statement", message.normalizedStatement());
    payload.put("assumptions", message.assumptions().stream().sorted().toList());
    payload.put("dependencies", message.dependencies().stream().sorted().toList());
    payload.put("evidence_type", message.evidenceType().value());
    payload.put("memory_tier", message.memoryTier().value());
    return CanonicalJson.stableHash(payload);
  }

  private static String receiptToken() {
    byte[] bytes = new byte[24];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static BrokerDecision decision(
      String messageId,
      boolean accepted,
      String duplicateOf,
      String rejectionReason,
      List<String> selectedTargets,
      Map<String, String> rejectedTargets) {
    return new BrokerDecision(
        accepted,
        List.of(),
        null,
        null,
        List.of(),
        duplicateOf,
        messageId,
        List.of(),
        rejectedTargets,
        rejectionReason,
        Map.of("selected_target_count", (double) selectedTargets.size()),
        selectedTargets);
  }

  private record CapacitySelection(
      List<String> selectedTargets, Map<String, String> rejectedTargets) {}
}
