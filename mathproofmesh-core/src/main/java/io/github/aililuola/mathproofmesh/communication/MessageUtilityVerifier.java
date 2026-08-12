package io.github.aililuola.mathproofmesh.communication;

import io.github.aililuola.mathproofmesh.contract.MessageReceipt;
import io.github.aililuola.mathproofmesh.contract.ReceiptStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class MessageUtilityVerifier {
  private final MessageRepository repository;

  public MessageUtilityVerifier(MessageRepository repository) {
    this.repository = java.util.Objects.requireNonNull(repository, "repository");
  }

  public Optional<MessageUtilityRecord> verify(
      String messageId, String targetRouteId, VerifiedDownstreamEffect effect) {
    String deliveryKey = DeliveryKey.of(messageId, targetRouteId);
    MessageReceipt receipt = repository.findReceipt(deliveryKey).orElse(null);
    if (receipt == null || receipt.status() != ReceiptStatus.ACCEPTED) {
      return Optional.empty();
    }
    List<String> steps =
        intersection(receipt.referencedInStepIds(), effect.committedStepIds());
    List<String> obligations =
        intersection(
            receipt.claimedClosedObligationIds(), effect.closedObligationIds());
    List<String> refutations = sorted(effect.refutedClaimIds());
    List<String> produced = sorted(effect.producedMessageIds());
    List<String> rewrites = sorted(effect.blueprintRewriteRequestIds());
    if (steps.isEmpty()
        && obligations.isEmpty()
        && refutations.isEmpty()
        && produced.isEmpty()
        && rewrites.isEmpty()
        && !effect.citedByFinalProof()) {
      return Optional.empty();
    }
    double debtReduction =
        Math.max(0.0, effect.proofDebtBefore() - effect.proofDebtAfter());
    double score =
        Math.min(
            1.0,
            (steps.isEmpty() ? 0.0 : 0.3)
                + (obligations.isEmpty() ? 0.0 : 0.3)
                + (refutations.isEmpty() ? 0.0 : 0.3)
                + (produced.isEmpty() ? 0.0 : 0.2)
                + (rewrites.isEmpty() ? 0.0 : 0.2)
                + (effect.citedByFinalProof() ? 0.4 : 0.0)
                + Math.min(0.2, debtReduction));
    MessageUtilityRecord utility =
        new MessageUtilityRecord(
            deliveryKey,
            steps,
            obligations,
            refutations,
            produced,
            rewrites,
            effect.citedByFinalProof(),
            debtReduction,
            score);
    repository.saveUtility(utility);
    return Optional.of(utility);
  }

  private static List<String> intersection(List<String> claims, Set<String> verified) {
    return claims.stream().filter(verified::contains).distinct().sorted().toList();
  }

  private static List<String> sorted(Set<String> values) {
    return values.stream().sorted().toList();
  }
}
