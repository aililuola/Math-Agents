package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.InspirationMechanism;
import io.github.aililuola.mathproofmesh.contract.InspirationTask;
import io.github.aililuola.mathproofmesh.contract.InspirationTrigger;
import io.github.aililuola.mathproofmesh.contract.InspirationTriggerType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Observable trigger detection and bounded deterministic task scheduling. */
public final class TriggerPolicy {
  private final InspirationPolicy policy;
  private final InspirationMechanismRegistry registry;
  private final TriggerRules rules;

  public TriggerPolicy(
      InspirationPolicy policy,
      InspirationMechanismRegistry registry,
      TriggerRules rules) {
    this.policy = java.util.Objects.requireNonNull(policy, "policy");
    this.registry = java.util.Objects.requireNonNull(registry, "registry");
    this.rules = java.util.Objects.requireNonNull(rules, "rules");
  }

  public List<InspirationTrigger> detect(InspirationSnapshot snapshot) {
    if (!policy.runs()) {
      return List.of();
    }
    List<InspirationTrigger> triggers = new ArrayList<>();
    List<String> stalled =
        snapshot.stagnationRoundsByRoute().entrySet().stream()
            .filter(entry -> entry.getValue() >= rules.stagnationRounds())
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    if (!stalled.isEmpty()
        && snapshot.verifiedFactGainRecent() < rules.minimumVerifiedGain()) {
      triggers.add(
          trigger(
              InspirationTriggerType.STAGNATION,
              snapshot,
              stalled,
              List.of(),
              "verified Fact gain stayed below the configured threshold"));
    }
    if (!snapshot.proofDebtByRoute().isEmpty()
        && snapshot.proofDebtHistory().size() >= 2
        && snapshot.proofDebtReductionRecent() < rules.proofDebtMinReduction()) {
      triggers.add(
          trigger(
              InspirationTriggerType.PROOF_DEBT_PLATEAU,
              snapshot,
              new ArrayList<>(snapshot.proofDebtByRoute().keySet()),
              List.of(),
              "proof debt reduction plateaued"));
    }
    Map<String, Integer> counts = new HashMap<>();
    snapshot.firstErrorFingerprints().stream()
        .filter(item -> !item.isBlank())
        .forEach(item -> counts.merge(item, 1, Integer::sum));
    List<String> repeated =
        counts.entrySet().stream()
            .filter(entry -> entry.getValue() >= rules.repeatedErrorThreshold())
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    if (!repeated.isEmpty()) {
      triggers.add(
          trigger(
              InspirationTriggerType.REPEATED_FIRST_ERROR,
              snapshot,
              snapshot.activeRouteIds(),
              repeated,
              "the same first-error fingerprint recurred"));
    }
    if (!snapshot.sharedBottleneckIds().isEmpty()) {
      triggers.add(
          trigger(
              InspirationTriggerType.SHARED_BOTTLENECK,
              snapshot,
              snapshot.activeRouteIds(),
              snapshot.sharedBottleneckIds(),
              "multiple routes share an open proof obligation"));
    }
    if (!snapshot.activeRouteIds().isEmpty()
        && snapshot.failedRouteIds().containsAll(snapshot.activeRouteIds())) {
      triggers.add(
          trigger(
              InspirationTriggerType.ALL_ROUTES_FAILED,
              snapshot,
              snapshot.activeRouteIds(),
              List.of(),
              "all active routes failed independent verification"));
    }
    if (snapshot.routeRedundancy() >= rules.routeRedundancyThreshold()) {
      triggers.add(
          trigger(
              InspirationTriggerType.HIGH_ROUTE_REDUNDANCY,
              snapshot,
              snapshot.activeRouteIds(),
              List.of(),
              "route mechanisms are structurally redundant"));
    }
    if (snapshot.finalRepairFailed()) {
      triggers.add(
          trigger(
              InspirationTriggerType.FINAL_REPAIR_FAILED,
              snapshot,
              snapshot.activeRouteIds(),
              List.of(),
              "the final targeted repair failed"));
    }
    if (snapshot.manualTrigger()) {
      triggers.add(
          trigger(
              InspirationTriggerType.MANUAL,
              snapshot,
              snapshot.activeRouteIds(),
              snapshot.sharedBottleneckIds(),
              "an explicit route-local mechanism change was requested"));
    }
    return List.copyOf(triggers);
  }

  public List<InspirationTask> schedule(
      List<InspirationTrigger> triggers,
      InspirationSnapshot snapshot,
      Map<InspirationMechanism, Integer> selectionCounts,
      Map<String, InspirationOutcomeLedger.SelectionProfile> profiles) {
    if (!policy.runs() || registry.enabledSchedulable().isEmpty()) {
      return List.of();
    }
    List<Candidate> candidates = new ArrayList<>();
    int sourceRank = 0;
    for (InspirationTrigger trigger : triggers == null ? List.<InspirationTrigger>of() : triggers) {
      for (InspirationMechanism mechanism : mechanismsFor(trigger.triggerType())) {
        if (registry.isSchedulable(mechanism)) {
          candidates.add(new Candidate(sourceRank++, trigger, mechanism));
        }
      }
    }
    Map<InspirationMechanism, Integer> counts =
        selectionCounts == null ? Map.of() : Map.copyOf(selectionCounts);
    Map<String, InspirationOutcomeLedger.SelectionProfile> safeProfiles =
        profiles == null ? Map.of() : Map.copyOf(profiles);
    candidates.sort(
        Comparator.comparing(
                (Candidate item) ->
                    !safeProfiles
                        .getOrDefault(
                            InspirationOutcomeLedger.profileKey(
                                item.trigger().triggerType(), item.mechanism()),
                            InspirationOutcomeLedger.SelectionProfile.empty())
                        .forceExploration())
            .thenComparing(
                Comparator.comparingDouble(
                        (Candidate item) ->
                            safeProfiles
                                .getOrDefault(
                                    InspirationOutcomeLedger.profileKey(
                                        item.trigger().triggerType(), item.mechanism()),
                                    InspirationOutcomeLedger.SelectionProfile.empty())
                                .ucbScore())
                    .reversed())
            .thenComparingInt(item -> counts.getOrDefault(item.mechanism(), 0))
            .thenComparingInt(Candidate::sourceRank));
    List<InspirationTask> tasks = new ArrayList<>();
    for (Candidate candidate : candidates) {
      InspirationTrigger trigger = candidate.trigger();
      String id =
          "inspiration_task_"
              + CanonicalJson.stableHash(
                      List.of(trigger.triggerId(), candidate.mechanism().value()))
                  .substring(0, 12);
      List<String> obligations =
          trigger.triggerType() == InspirationTriggerType.SHARED_BOTTLENECK
              ? trigger.evidenceRefs()
              : snapshot.openObligationIds();
      tasks.add(
          new InspirationTask(
              policy.limits().maxProposalsPerTask(),
              candidate.mechanism(),
              trigger.reason(),
              obligations,
              trigger.affectedRouteIds(),
              id,
              trigger.triggerId()));
      if (tasks.size() >= policy.limits().maxTasksPerRound()) {
        break;
      }
    }
    return List.copyOf(tasks);
  }

  private static InspirationTrigger trigger(
      InspirationTriggerType type,
      InspirationSnapshot snapshot,
      List<String> routes,
      List<String> evidence,
      String reason) {
    List<String> sortedRoutes = routes.stream().distinct().sorted().toList();
    List<String> sortedEvidence = evidence.stream().distinct().sorted().toList();
    double debt =
        snapshot.proofDebtByRoute().values().stream().mapToDouble(Double::doubleValue).sum();
    String id =
        "trigger_"
            + CanonicalJson.stableHash(
                    List.of(
                        type.value(),
                        snapshot.roundIndex(),
                        sortedRoutes,
                        sortedEvidence,
                        snapshot.problemHash()))
                .substring(0, 12);
    return new InspirationTrigger(
        sortedRoutes,
        sortedEvidence,
        Math.max(0.0d, debt),
        reason,
        type == InspirationTriggerType.REPEATED_FIRST_ERROR ? sortedEvidence : List.of(),
        snapshot.roundIndex(),
        id,
        type,
        snapshot.verifiedFactGainRecent());
  }

  private static List<InspirationMechanism> mechanismsFor(InspirationTriggerType type) {
    return switch (type) {
      case STAGNATION, PROOF_DEBT_PLATEAU ->
          List.of(
              InspirationMechanism.REPRESENTATION_SWITCH,
              InspirationMechanism.STRUCTURAL_ANALOGY,
              InspirationMechanism.INVARIANT_HYPOTHESIS);
      case REPEATED_FIRST_ERROR ->
          List.of(
              InspirationMechanism.AUXILIARY_CONSTRUCTION,
              InspirationMechanism.INVARIANT_HYPOTHESIS);
      case HIGH_ROUTE_REDUNDANCY ->
          List.of(
              InspirationMechanism.REPRESENTATION_SWITCH,
              InspirationMechanism.SURPRISE_EXPLORATION);
      case ALL_ROUTES_FAILED, FINAL_REPAIR_FAILED ->
          List.of(
              InspirationMechanism.SURPRISE_EXPLORATION,
              InspirationMechanism.META_REPLAN);
      case SHARED_BOTTLENECK, MANUAL ->
          List.of(
              InspirationMechanism.REVERSE_GOAL_ANALYSIS,
              InspirationMechanism.BRIDGE_LEMMA,
              InspirationMechanism.AUXILIARY_CONSTRUCTION);
    };
  }

  private record Candidate(
      int sourceRank, InspirationTrigger trigger, InspirationMechanism mechanism) {}

  public record TriggerRules(
      int stagnationRounds,
      int minimumVerifiedGain,
      double proofDebtMinReduction,
      int repeatedErrorThreshold,
      double routeRedundancyThreshold) {
    public TriggerRules {
      if (stagnationRounds <= 0
          || minimumVerifiedGain < 0
          || repeatedErrorThreshold <= 0
          || !Double.isFinite(proofDebtMinReduction)
          || proofDebtMinReduction < 0.0d
          || !Double.isFinite(routeRedundancyThreshold)
          || routeRedundancyThreshold < 0.0d
          || routeRedundancyThreshold > 1.0d) {
        throw new IllegalArgumentException("invalid inspiration trigger rules");
      }
    }

    public static TriggerRules defaults() {
      return new TriggerRules(2, 1, 0.05d, 2, 0.80d);
    }
  }
}
