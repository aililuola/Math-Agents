package io.github.aililuola.mathproofmesh.inspiration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.InspirationMechanism;
import io.github.aililuola.mathproofmesh.contract.MetaStrategyDecision;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistent policy over observable metrics; it is never a Fact author. */
public final class PersistentMetaStrategist {
  private final TriggerPolicy.TriggerRules rules;
  private final List<MetaStrategyDecision> history = new ArrayList<>();
  private final Map<InspirationMechanism, Integer> cooldowns =
      new EnumMap<>(InspirationMechanism.class);

  public PersistentMetaStrategist(TriggerPolicy.TriggerRules rules) {
    this.rules = java.util.Objects.requireNonNull(rules, "rules");
  }

  public synchronized MetaStrategyDecision decide(InspirationSnapshot snapshot) {
    InspirationMechanism mechanism = null;
    String action = "continue_current_mechanism";
    String reason = "verified progress remains above the intervention threshold";
    if (snapshot.finalRepairFailed()) {
      action = "rewrite_plan";
      mechanism = InspirationMechanism.META_REPLAN;
      reason = "final repair failed; an audited plan rewrite is required";
    } else if (!snapshot.sharedBottleneckIds().isEmpty()) {
      action = "rewrite_plan";
      mechanism = InspirationMechanism.REVERSE_GOAL_ANALYSIS;
      reason = "a shared open obligation should be isolated as a bridge";
    } else if (snapshot.routeRedundancy() >= rules.routeRedundancyThreshold()) {
      action = "switch_representation";
      mechanism = InspirationMechanism.REPRESENTATION_SWITCH;
      reason = "route mechanisms are structurally redundant";
    } else if (repeated(snapshot.firstErrorFingerprints(), rules.repeatedErrorThreshold())) {
      action = "invent_auxiliary_construction";
      mechanism = InspirationMechanism.AUXILIARY_CONSTRUCTION;
      reason = "a repeated first-error fingerprint indicates a missing object";
    } else if (!snapshot.proofDebtByRoute().isEmpty()
        && snapshot.proofDebtReductionRecent() < rules.proofDebtMinReduction()) {
      action = "rewrite_plan";
      mechanism = InspirationMechanism.META_REPLAN;
      reason = "proof debt is not decreasing";
    } else if (!snapshot.activeRouteIds().isEmpty()
        && snapshot.failedRouteIds().containsAll(snapshot.activeRouteIds())) {
      action = "surprise_exploration";
      mechanism = InspirationMechanism.SURPRISE_EXPLORATION;
      reason = "all current mechanisms failed independent review";
    }
    if (mechanism != null
        && cooldowns.getOrDefault(mechanism, -1) > snapshot.roundIndex()) {
      InspirationMechanism cooledMechanism = mechanism;
      InspirationMechanism replacement =
          List.of(
                  InspirationMechanism.REPRESENTATION_SWITCH,
                  InspirationMechanism.STRUCTURAL_ANALOGY,
                  InspirationMechanism.AUXILIARY_CONSTRUCTION,
                  InspirationMechanism.META_REPLAN)
              .stream()
              .filter(item -> item != cooledMechanism)
              .filter(item -> cooldowns.getOrDefault(item, -1) <= snapshot.roundIndex())
              .findFirst()
              .orElse(null);
      if (replacement == null) {
        mechanism = null;
        action = "continue_current_mechanism";
        reason = "all mechanism-changing actions are cooling down";
      } else {
        mechanism = replacement;
        action =
            switch (replacement) {
              case REPRESENTATION_SWITCH -> "switch_representation";
              case STRUCTURAL_ANALOGY -> "search_analogy";
              case AUXILIARY_CONSTRUCTION -> "invent_auxiliary_construction";
              default -> "rewrite_plan";
            };
        reason += "; selected a non-cooled alternative";
      }
    }
    Map<String, JsonNode> metrics = observableMetrics(snapshot);
    String id =
        "meta_"
            + CanonicalJson.stableHash(
                    List.of(snapshot.problemHash(), snapshot.roundIndex(), action, metrics))
                .substring(0, 16);
    MetaStrategyDecision decision =
        new MetaStrategyDecision(
            action,
            snapshot.activeRouteIds(),
            id,
            mechanism == null ? 0 : 1,
            metrics,
            reason,
            snapshot.roundIndex(),
            mechanism);
    if (history.stream().noneMatch(item -> item.decisionId().equals(id))) {
      history.add(decision);
    }
    return decision;
  }

  public synchronized void cool(
      InspirationMechanism mechanism, int currentRound, int rounds) {
    if (currentRound < 0 || rounds < 0) {
      throw new IllegalArgumentException("cooldown rounds must be nonnegative");
    }
    cooldowns.put(mechanism, currentRound + rounds);
  }

  public synchronized List<MetaStrategyDecision> history() {
    return List.copyOf(history);
  }

  private static Map<String, JsonNode> observableMetrics(InspirationSnapshot snapshot) {
    JsonNodeFactory json = JsonNodeFactory.instance;
    Map<String, JsonNode> metrics = new LinkedHashMap<>();
    metrics.put("verified_fact_gain_recent", json.numberNode(snapshot.verifiedFactGainRecent()));
    metrics.put(
        "proof_debt_reduction_recent", json.numberNode(snapshot.proofDebtReductionRecent()));
    metrics.put("route_redundancy", json.numberNode(snapshot.routeRedundancy()));
    metrics.put("failed_route_count", json.numberNode(snapshot.failedRouteIds().size()));
    metrics.put("active_route_count", json.numberNode(snapshot.activeRouteIds().size()));
    metrics.put(
        "shared_bottleneck_count", json.numberNode(snapshot.sharedBottleneckIds().size()));
    metrics.put("remaining_calls", json.numberNode(snapshot.remainingCalls()));
    metrics.put(
        "finalization_reserve_calls", json.numberNode(snapshot.finalizationReserveCalls()));
    return Map.copyOf(metrics);
  }

  private static boolean repeated(List<String> values, int threshold) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    values.forEach(item -> counts.merge(item, 1, Integer::sum));
    return counts.values().stream().anyMatch(count -> count >= threshold);
  }
}
