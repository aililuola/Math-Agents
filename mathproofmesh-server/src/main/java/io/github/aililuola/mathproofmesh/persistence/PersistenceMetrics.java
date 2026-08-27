package io.github.aililuola.mathproofmesh.persistence;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class PersistenceMetrics {
  private PersistenceMetrics() {}

  public static MessageMetrics messageMetrics(
      List<PublicationDecision> decisions,
      Collection<DeliveryState> deliveries,
      Collection<ReceiptState> receipts,
      Collection<UtilityState> utilities,
      Collection<String> inspirationActions) {
    Objects.requireNonNull(decisions, "decisions");
    long uniquePublished =
        decisions.stream()
            .filter(PublicationDecision::accepted)
            .filter(decision -> decision.duplicateOf() == null)
            .map(PublicationDecision::messageId)
            .distinct()
            .count();
    Map<String, Long> actionCounts =
        inspirationActions.stream()
            .collect(
                Collectors.groupingBy(
                    value -> value,
                    LinkedHashMap::new,
                    Collectors.counting()));
    return new MessageMetrics(
        decisions.size(),
        uniquePublished,
        deliveries.size(),
        deliveries.stream().filter(DeliveryState::promptConsumed).count(),
        receipts.stream().filter(receipt -> "accepted".equals(receipt.status())).count(),
        utilities.size(),
        Map.copyOf(actionCounts));
  }

  public static FactInventory factInventory(
      Collection<FactCandidate> candidates,
      Set<String> admittedFactIds,
      Map<String, ReviewProvenance> reviews,
      int legacyClaimHistoryCount,
      int legacyVerifiedClaimHistoryCount) {
    List<FactCandidate> admitted =
        candidates.stream()
            .filter(candidate -> admittedFactIds.contains(candidate.messageId()))
            .filter(candidate -> "fact".equals(candidate.memoryTier()))
            .filter(candidate -> "verified".equals(candidate.verificationStatus()))
            .filter(
                candidate -> {
                  ReviewProvenance review = reviews.get(candidate.messageId());
                  return review != null
                      && review.independent()
                      && review.reviewerAgentId() != null;
                })
            .toList();
    long typedCandidates =
        candidates.stream()
            .filter(candidate -> "fact".equals(candidate.memoryTier()))
            .filter(candidate -> "verified".equals(candidate.verificationStatus()))
            .count();
    return new FactInventory(
        admitted,
        typedCandidates,
        legacyClaimHistoryCount,
        legacyVerifiedClaimHistoryCount);
  }

  public record PublicationDecision(
      String messageId, boolean accepted, String duplicateOf
  ) {}

  public record DeliveryState(String deliveryKey, boolean promptConsumed) {}

  public record ReceiptState(String receiptId, String status) {}

  public record UtilityState(String utilityId, String messageId) {}

  public record MessageMetrics(
      long messagePublicationAttempts,
      long messagesPublishedUnique,
      long deliveryRecords,
      long messagesConsumed,
      long messagesSemanticallyAccepted,
      long messagesMathematicallyUsed,
      Map<String, Long> inspirationMaterializationActions
  ) {
    public MessageMetrics {
      inspirationMaterializationActions =
          Map.copyOf(inspirationMaterializationActions);
    }
  }

  public record FactCandidate(
      String messageId,
      String memoryTier,
      String verificationStatus,
      String contentHash
  ) {}

  public record ReviewProvenance(boolean independent, String reviewerAgentId) {}

  public record FactInventory(
      List<FactCandidate> brokerAdmittedGlobalFacts,
      long typedFactCandidateCount,
      int legacyClaimHistoryCount,
      int legacyVerifiedClaimHistoryCount
  ) {
    public FactInventory {
      brokerAdmittedGlobalFacts = List.copyOf(brokerAdmittedGlobalFacts);
    }

    public int factCount() {
      return brokerAdmittedGlobalFacts.size();
    }
  }
}
