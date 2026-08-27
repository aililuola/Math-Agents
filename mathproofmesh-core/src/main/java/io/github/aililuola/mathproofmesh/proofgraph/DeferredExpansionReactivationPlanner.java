package io.github.aililuola.mathproofmesh.proofgraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure ordering and eligibility policy for deferred proof-graph work. */
public final class DeferredExpansionReactivationPlanner {
  public List<DeferredExpansionReactivationDecision> plan(
      Collection<DeferredExpansionReactivationCandidate> candidates,
      ProofGraphControlMode controlMode,
      ProofGraphConvergenceConfig config) {
    java.util.Objects.requireNonNull(candidates, "candidates");
    java.util.Objects.requireNonNull(controlMode, "controlMode");
    java.util.Objects.requireNonNull(config, "config");
    List<DeferredExpansionReactivationCandidate> ordered =
        candidates.stream()
            .sorted(
                Comparator.comparingDouble(DeferredExpansionReactivationCandidate::priority)
                    .reversed()
                    .thenComparing(
                        Comparator.comparingDouble(
                                DeferredExpansionReactivationCandidate::centrality)
                            .reversed())
                    .thenComparingInt(candidate -> candidate.record().round())
                    .thenComparing(candidate -> candidate.record().deferredId()))
            .toList();
    Map<String, Integer> routeCounts = new LinkedHashMap<>();
    int campaignCount =
        ordered.stream()
            .mapToInt(DeferredExpansionReactivationCandidate::activeTargetsCampaign)
            .max()
            .orElse(0);
    int reactivations = 0;
    List<DeferredExpansionReactivationDecision> decisions = new ArrayList<>(ordered.size());
    for (DeferredExpansionReactivationCandidate candidate : ordered) {
      DeferredExpansionRecord record = candidate.record();
      int routeCount =
          routeCounts.computeIfAbsent(record.routeId(), ignored -> candidate.activeTargetsForRoute());
      DeferredExpansionReactivationDecision decision =
          decide(candidate, controlMode, config, routeCount, campaignCount, reactivations);
      decisions.add(decision);
      if (decision.reactivates()) {
        routeCounts.put(record.routeId(), routeCount + 1);
        campaignCount++;
        reactivations++;
      }
    }
    return List.copyOf(decisions);
  }

  private static DeferredExpansionReactivationDecision decide(
      DeferredExpansionReactivationCandidate candidate,
      ProofGraphControlMode controlMode,
      ProofGraphConvergenceConfig config,
      int routeCount,
      int campaignCount,
      int reactivations) {
    DeferredExpansionRecord record = candidate.record();
    if (record.status() != DeferredExpansionStatus.DEFERRED) {
      return decision(record, DeferredExpansionReactivationOutcome.KEEP_DEFERRED, "NOT_DEFERRED");
    }
    if (!candidate.sameProblem()) {
      return decision(record, DeferredExpansionReactivationOutcome.RETIRE, "PROBLEM_HASH_MISMATCH");
    }
    if (!candidate.rawOccurrenceExists() || !candidate.canonicalTargetExists()) {
      return decision(record, DeferredExpansionReactivationOutcome.RETIRE, "TARGET_MISSING");
    }
    if (candidate.canonicalStatus() == CanonicalObligationStatus.RESOLVED
        || candidate.canonicalStatus() == CanonicalObligationStatus.REFUTED) {
      return decision(record, DeferredExpansionReactivationOutcome.RETIRE, "TARGET_TERMINAL");
    }
    if (!candidate.negativeKnowledgeAllowed()) {
      return decision(record, DeferredExpansionReactivationOutcome.RETIRE, "NEGATIVE_KNOWLEDGE_BLOCK");
    }
    if (candidate.routePermanentlyUnavailable()) {
      return decision(record, DeferredExpansionReactivationOutcome.RETIRE, "ROUTE_UNRECOVERABLE");
    }
    if (candidate.canonicalSchedulingState() == CanonicalObligationSchedulingState.ACTIVE) {
      return decision(
          record,
          DeferredExpansionReactivationOutcome.SATISFY_BY_ACTIVE_TARGET,
          "CANONICAL_TARGET_ALREADY_ACTIVE");
    }
    if (!candidate.routeSchedulable()) {
      return decision(record, DeferredExpansionReactivationOutcome.KEEP_DEFERRED, "ROUTE_NOT_READY");
    }
    if (reactivations >= config.maxDeferredReactivationsPerRound()) {
      return decision(record, DeferredExpansionReactivationOutcome.KEEP_DEFERRED, "ROUND_LIMIT");
    }
    if (routeCount >= config.maxActiveCanonicalTargetsPerRoute()
        || campaignCount >= config.maxActiveCanonicalTargetsCampaign()) {
      return decision(record, DeferredExpansionReactivationOutcome.KEEP_DEFERRED, "CAPACITY_UNAVAILABLE");
    }
    if (controlMode == ProofGraphControlMode.FOCUSED_RECOVERY && !candidate.selectedBinding()) {
      return decision(record, DeferredExpansionReactivationOutcome.KEEP_DEFERRED, "FOCUSED_BINDING_NOT_SELECTED");
    }
    return decision(record, DeferredExpansionReactivationOutcome.REACTIVATE, "ELIGIBLE_FOR_REACTIVATION");
  }

  private static DeferredExpansionReactivationDecision decision(
      DeferredExpansionRecord record,
      DeferredExpansionReactivationOutcome outcome,
      String reason) {
    return new DeferredExpansionReactivationDecision(record.deferredId(), outcome, reason);
  }
}
