package io.github.aililuola.mathproofmesh.orchestration;

import io.github.aililuola.mathproofmesh.contract.ActionKind;
import io.github.aililuola.mathproofmesh.contract.BudgetAction;
import io.github.aililuola.mathproofmesh.contract.BudgetDecision;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic soft-budget scheduler driven by verified progress and failure class. */
public final class AdaptiveBudgetManager {
  private final int maxPaths;
  private final int finishReserve;
  private final Map<String, BudgetDecision> decisions = new LinkedHashMap<>();

  public AdaptiveBudgetManager(int maxPaths, int finishReserve) {
    if (maxPaths <= 0 || finishReserve < 0) {
      throw new IllegalArgumentException("invalid scheduler limits");
    }
    this.maxPaths = maxPaths;
    this.finishReserve = finishReserve;
  }

  public synchronized BudgetDecision decide(
      String actionKey,
      List<AttemptEvidence> attempts,
      int currentPaths,
      int remainingCalls,
      double coverage,
      double uncertainty) {
    String key = required(actionKey, "actionKey");
    BudgetDecision prior = decisions.get(key);
    if (prior != null) {
      return prior;
    }
    List<AttemptEvidence> values = attempts == null ? List.of() : List.copyOf(attempts);
    long failed =
        values.stream()
            .filter(item -> item.failureClass() != AttemptEvidence.FailureClass.NONE)
            .count();
    boolean allFailed = !values.isEmpty() && failed == values.size();
    boolean hasSuccess = values.stream().anyMatch(AttemptEvidence::verifiedProgress);
    List<BudgetAction> candidates = new ArrayList<>();
    boolean widenEligible =
        allFailed
            && !hasSuccess
            && currentPaths < maxPaths
            && remainingCalls > finishReserve;
    candidates.add(
        action(
            ActionKind.WIDEN,
            widenEligible,
            allFailed,
            widenEligible ? "" : "verified progress, path cap, or finish reserve blocks widen",
            1.0d,
            1));
    AttemptEvidence preferredPartial =
        values.stream()
            .filter(item -> !item.complete())
            .filter(item -> item.failureClass() == AttemptEvidence.FailureClass.NONE)
            .min(
                java.util.Comparator.comparingDouble(AttemptEvidence::risk)
                    .thenComparingDouble(AttemptEvidence::proofDebt)
                    .thenComparing(AttemptEvidence::routeId))
            .orElse(null);
    if (preferredPartial == null) {
      preferredPartial =
          values.stream()
              .filter(AttemptEvidence::verifiedProgress)
              .min(
                  java.util.Comparator.comparingDouble(AttemptEvidence::proofDebt)
                      .thenComparingDouble(AttemptEvidence::risk)
                      .thenComparing(AttemptEvidence::routeId))
              .orElse(null);
    }
    boolean deepen = preferredPartial != null && remainingCalls > finishReserve;
    candidates.add(
        new BudgetAction(
            ActionKind.DEEPEN,
            deepen ? null : "no preferred partial route or protected budget exhausted",
            deepen,
            deepen ? 1 : 0,
            false,
            currentPaths,
            null,
            "prefer verified or incomplete partial work over rejected deltas",
            deepen ? 1.2d : 0.0d,
            false,
            null,
            preferredPartial == null ? null : preferredPartial.routeId()));
    AttemptEvidence preferredRevision =
        values.stream()
            .filter(item -> !item.complete())
            .filter(item -> item.failureClass() != AttemptEvidence.FailureClass.NONE)
            .min(
                java.util.Comparator.comparingDouble(AttemptEvidence::risk)
                    .thenComparingDouble(AttemptEvidence::proofDebt)
                    .thenComparing(AttemptEvidence::routeId))
            .orElse(null);
    boolean revise = preferredRevision != null && remainingCalls > finishReserve;
    candidates.add(
        new BudgetAction(
            ActionKind.REVISE,
            revise ? null : "no revisable structural failure or protected budget exhausted",
            revise,
            revise ? 1 : 0,
            false,
            currentPaths,
            null,
            "repair a structural failure from its preserved committed checkpoint",
            revise ? 1.1d : 0.0d,
            false,
            null,
            preferredRevision == null ? null : preferredRevision.routeId()));
    List<BudgetAction> ranked =
        candidates.stream()
            .sorted(
                java.util.Comparator.comparing(BudgetAction::eligible)
                    .reversed()
                    .thenComparing(BudgetAction::score, java.util.Comparator.reverseOrder()))
            .toList();
    List<BudgetAction> selected = new ArrayList<>();
    for (int index = 0; index < ranked.size(); index++) {
      BudgetAction value = ranked.get(index);
      selected.add(
          new BudgetAction(
              value.action(),
              value.blockedReason(),
              value.eligible(),
              value.estimatedCalls(),
              value.forced(),
              value.plannedPaths(),
              index + 1,
              value.reason(),
              value.score(),
              index == 0 && value.eligible(),
              value.strategyId(),
              value.targetId()));
    }
    BudgetDecision result =
        new BudgetDecision(
            selected.stream().filter(BudgetAction::selected).toList(),
            allFailed,
            selected,
            unit(coverage),
            values.isEmpty() ? 0.0d : failed / (double) values.size(),
            finishReserve,
            widenEligible,
            unit(uncertainty),
            "ranked from certified progress, failure class, path capacity, and soft budget");
    decisions.put(key, result);
    return result;
  }

  private BudgetAction action(
      ActionKind kind,
      boolean eligible,
      boolean forced,
      String blocked,
      double score,
      int calls) {
    return new BudgetAction(
        kind,
        blocked,
        eligible,
        calls,
        forced,
        maxPaths,
        null,
        "all failed paths may force one bounded widen",
        score,
        false,
        null,
        null);
  }

  private static double unit(double value) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("scheduler ratio must be finite");
    }
    return Math.max(0.0d, Math.min(1.0d, value));
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
