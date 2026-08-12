package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Separates delivery from verified mathematical use and utility credit. */
public final class MessageUtilityController {
  public record Contract(
      String id,
      String messageId,
      String sourceRouteId,
      List<String> targetObligationIds,
      ProofControlModels.MessageExpectedEffect expectedEffect,
      List<String> requiredAssumptions,
      double expectedCoreDebtReduction,
      int expiresRound) {
    public Contract {
      targetObligationIds = List.copyOf(targetObligationIds);
      requiredAssumptions = List.copyOf(requiredAssumptions);
      if (expectedCoreDebtReduction < 0.0d || expiresRound < 0) {
        throw new IllegalArgumentException("utility values must be nonnegative");
      }
    }
  }

  public record UsageReceipt(
      String id,
      String messageId,
      String consumerRouteId,
      List<String> referencedVerifiedStepIds,
      List<String> closedObligationIds,
      List<String> refutedClaimIds,
      boolean citedByFinalProof,
      boolean verifiedUse,
      double utilityScore) {
    public UsageReceipt {
      referencedVerifiedStepIds = List.copyOf(referencedVerifiedStepIds);
      closedObligationIds = List.copyOf(closedObligationIds);
      refutedClaimIds = List.copyOf(refutedClaimIds);
      if (utilityScore < 0.0d) {
        throw new IllegalArgumentException("utilityScore must be nonnegative");
      }
    }
  }

  public record Decision(
      String id,
      String messageId,
      ProofControlModels.BroadcastDecision decision,
      String reason,
      double expectedCoreDebtReduction,
      List<String> targetObligationIds,
      boolean consumesNeighborQuota) {
    public Decision {
      targetObligationIds = List.copyOf(targetObligationIds);
    }
  }

  private final Map<String, Contract> contracts = new LinkedHashMap<>();
  private final Map<String, Decision> decisions = new LinkedHashMap<>();
  private final Map<String, UsageReceipt> receipts = new LinkedHashMap<>();

  public Contract registerContract(
      String messageId,
      String sourceRouteId,
      List<String> targetObligationIds,
      ProofControlModels.MessageExpectedEffect effect,
      List<String> requiredAssumptions,
      double expectedDebtReduction,
      int expiresRound,
      Set<String> knownObligationIds) {
    if (targetObligationIds == null
        || targetObligationIds.isEmpty()
        || !knownObligationIds.containsAll(targetObligationIds)) {
      throw new IllegalArgumentException(
          "utility contract requires known target obligations");
    }
    String id =
        "utility_contract_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "message", messageId,
                        "route", sourceRouteId,
                        "targets", targetObligationIds.stream().sorted().toList(),
                        "effect", effect.name()))
                .substring(0, 20);
    Contract contract =
        new Contract(
            id,
            messageId,
            sourceRouteId,
            targetObligationIds.stream().distinct().sorted().toList(),
            effect,
            requiredAssumptions == null ? List.of() : requiredAssumptions,
            expectedDebtReduction,
            expiresRound);
    contracts.putIfAbsent(messageId, contract);
    return contracts.get(messageId);
  }

  public Decision decideBroadcast(
      String messageId,
      boolean counterexample,
      boolean admittedFact,
      boolean highPriority,
      int currentRound) {
    Decision existing = decisions.get(messageId);
    if (existing != null) {
      return existing;
    }
    Contract contract = contracts.get(messageId);
    ProofControlModels.BroadcastDecision decision;
    String reason;
    double reduction = contract == null ? 0.0d : contract.expectedCoreDebtReduction();
    List<String> targets = contract == null ? List.of() : contract.targetObligationIds();
    if (counterexample) {
      decision = ProofControlModels.BroadcastDecision.BROADCAST;
      reason = "counterexample is utility-contract exempt";
    } else if (admittedFact && highPriority) {
      decision = ProofControlModels.BroadcastDecision.BROADCAST;
      reason = "high-priority admitted Fact";
    } else if (contract != null
        && currentRound <= contract.expiresRound()
        && contract.expectedCoreDebtReduction() > 0.0d) {
      decision = ProofControlModels.BroadcastDecision.BROADCAST;
      reason = "positive expected core-debt reduction";
    } else {
      decision = ProofControlModels.BroadcastDecision.KEEP_LOCAL;
      reason = "zero verified expected utility";
    }
    String id =
        "broadcast_decision_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "message", messageId,
                        "decision", decision.name(),
                        "targets", targets,
                        "round", currentRound))
                .substring(0, 20);
    Decision record =
        new Decision(
            id,
            messageId,
            decision,
            reason,
            reduction,
            targets,
            decision == ProofControlModels.BroadcastDecision.BROADCAST);
    decisions.put(messageId, record);
    return record;
  }

  public UsageReceipt recordUsage(
      String messageId,
      String consumerRouteId,
      List<String> claimedStepIds,
      Set<String> verifiedStepIds,
      List<String> closedObligationIds,
      List<String> refutedClaimIds,
      boolean citedByFinalProof) {
    List<String> verified =
        claimedStepIds == null
            ? List.of()
            : claimedStepIds.stream().filter(verifiedStepIds::contains).distinct().sorted().toList();
    boolean used =
        !verified.isEmpty()
            || closedObligationIds != null && !closedObligationIds.isEmpty()
            || refutedClaimIds != null && !refutedClaimIds.isEmpty()
            || citedByFinalProof;
    double score =
        used
            ? verified.size()
                + (closedObligationIds == null ? 0 : closedObligationIds.size()) * 2.0d
                + (refutedClaimIds == null ? 0 : refutedClaimIds.size()) * 2.0d
                + (citedByFinalProof ? 3.0d : 0.0d)
            : 0.0d;
    String id =
        "usage_receipt_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "message", messageId,
                        "consumer", consumerRouteId,
                        "verified", verified,
                        "closed",
                            closedObligationIds == null ? List.of() : closedObligationIds,
                        "refuted", refutedClaimIds == null ? List.of() : refutedClaimIds,
                        "final", citedByFinalProof))
                .substring(0, 20);
    UsageReceipt receipt =
        new UsageReceipt(
            id,
            messageId,
            consumerRouteId,
            verified,
            closedObligationIds == null ? List.of() : closedObligationIds,
            refutedClaimIds == null ? List.of() : refutedClaimIds,
            citedByFinalProof,
            used,
            score);
    receipts.putIfAbsent(id, receipt);
    return receipts.get(id);
  }

  public long expiredUnusedCount(int currentRound) {
    return contracts.values().stream()
        .filter(value -> value.expiresRound() < currentRound)
        .filter(
            value ->
                receipts.values().stream()
                    .noneMatch(
                        receipt ->
                            receipt.messageId().equals(value.messageId())
                                && receipt.verifiedUse()))
        .count();
  }
}
