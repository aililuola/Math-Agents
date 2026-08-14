package io.github.aililuola.mathproofmesh.proofgraph;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Converts authoritative proof-graph state changes into restore-safe scheduling control. */
@SuppressFBWarnings(
    value = {"USO_UNSAFE_METHOD_SYNCHRONIZATION", "IS2_INCONSISTENT_SYNC"},
    justification =
        "Public operations serialize control state; private helpers run under that monitor, and"
            + " restore writes occur only before publication.")
public final class ProofGraphConvergenceMonitor {
  private final ProofGraphConvergenceConfig config;
  private final List<ProofGraphRoundMetrics> roundHistory = new ArrayList<>();
  private final List<ProofGraphRoundClassification> roundClassifications = new ArrayList<>();
  private final Set<String> focusedTaskLeases = new LinkedHashSet<>();
  private ProofGraphControlMode controlMode = ProofGraphControlMode.NORMAL_EXPANSION;
  private int consecutiveStagnation;
  private int consecutiveDivergence;
  private int cooldownRemaining;
  private FocusedRecoveryPlan focusedRecoveryPlan;
  private int stagnationEpisodes;
  private int divergenceEpisodes;
  private int focusedRecoveryEntries;
  private int focusedRecoveryExits;
  private int recoveryCooldownEntries;
  private int genericExpansionAttempts;
  private int genericExpansionBlocks;
  private int genericExpansionLeaks;
  private int observedOccurrenceTotal;
  private int observedVerifiedClaimTotal;
  private int observedExactRefutationTotal;
  private int observedForbiddenProposalTotal;
  private long version;

  public ProofGraphConvergenceMonitor() {
    this(ProofGraphConvergenceConfig.defaults());
  }

  public ProofGraphConvergenceMonitor(ProofGraphConvergenceConfig config) {
    this.config = java.util.Objects.requireNonNull(config, "config");
  }

  private ProofGraphConvergenceMonitor(
      ProofGraphConvergenceConfig config, ProofGraphConvergenceSnapshot snapshot) {
    this(config);
    load(snapshot);
  }

  public static ProofGraphConvergenceMonitor restore(
      ProofGraphConvergenceConfig config, ProofGraphConvergenceSnapshot snapshot) {
    return new ProofGraphConvergenceMonitor(
        config,
        snapshot == null ? ProofGraphConvergenceSnapshot.empty() : snapshot);
  }

  public synchronized ProofGraphRoundMetrics sample(
      int round,
      ProofGraphStore graph,
      int verifiedClaimTotal,
      int exactRefutationTotal,
      int forbiddenProposalTotal,
      String rootGoalHash) {
    java.util.Objects.requireNonNull(graph, "graph");
    ProofGraphRoundMetrics previous = roundHistory.isEmpty() ? null : roundHistory.getLast();
    int rawOpen =
        (int)
            graph.obligations().stream()
                .filter(item -> Set.of("open", "tentative", "blocked").contains(item.status()))
                .count();
    int active =
        (int)
            graph.canonicalOpenTargets().stream()
                .filter(item -> item.signature().kind() != ObligationKind.MAIN_GOAL)
                .filter(
                    item ->
                        item.schedulingState() == CanonicalObligationSchedulingState.ACTIVE)
                .count();
    int deferred =
        (int)
            graph.canonicalOpenTargets().stream()
                .filter(item -> item.signature().kind() != ObligationKind.MAIN_GOAL)
                .filter(
                    item ->
                        item.schedulingState()
                            != CanonicalObligationSchedulingState.ACTIVE)
                .count();
    int closed =
        (int)
            graph.allCanonicalTargets().stream()
                .filter(item -> item.signature().kind() != ObligationKind.MAIN_GOAL)
                .filter(
                    item -> {
                      CanonicalObligationStatus status =
                          graph.canonicalStatus(item.canonicalTargetId());
                      return status == CanonicalObligationStatus.RESOLVED
                          || status == CanonicalObligationStatus.REFUTED;
                    })
                .count();
    int totalCanonical = active + deferred + closed;
    int previousCanonical = previous == null ? totalCanonical : previous.totalCanonicalTargets();
    int currentOccurrences = graph.rawObligationOccurrences().size();
    int occurrenceDelta =
        previous == null ? 0 : Math.max(0, currentOccurrences - observedOccurrenceTotal);
    int newCanonical = Math.max(0, totalCanonical - previousCanonical);
    int duplicates = Math.max(0, occurrenceDelta - newCanonical);
    int verifiedGains = Math.max(0, verifiedClaimTotal - observedVerifiedClaimTotal);
    int refutationGains = Math.max(0, exactRefutationTotal - observedExactRefutationTotal);
    int forbidden = Math.max(0, forbiddenProposalTotal - observedForbiddenProposalTotal);
    int newlyClosed =
        previous == null ? 0 : Math.max(0, closed - previous.closedCanonicalTargets());
    ProofGraphRoundMetrics metrics =
        new ProofGraphRoundMetrics(
            round,
            rawOpen,
            active,
            deferred,
            closed,
            newCanonical,
            duplicates,
            forbidden,
            verifiedGains,
            refutationGains,
            newlyClosed,
            graph.obligations().stream()
                .filter(item -> item.kind() != ObligationKind.MAIN_GOAL)
                .mapToDouble(ProofGraphConvergenceMonitor::rawDebtWeight)
                .sum(),
            graph.activeCanonicalProofDebt(),
            graph.deferredCanonicalProofDebt(),
            graph.globalCanonicalProofDebt(),
            0.0d);
    ProofGraphRoundMetrics sampled = observe(metrics, graph, rootGoalHash);
    observedOccurrenceTotal = currentOccurrences;
    observedVerifiedClaimTotal = verifiedClaimTotal;
    observedExactRefutationTotal = exactRefutationTotal;
    observedForbiddenProposalTotal = forbiddenProposalTotal;
    return sampled;
  }

  public synchronized ProofGraphRoundMetrics observe(
      ProofGraphRoundMetrics unscored, ProofGraphStore graph, String rootGoalHash) {
    java.util.Objects.requireNonNull(unscored, "unscored");
    java.util.Objects.requireNonNull(graph, "graph");
    ProofGraphRoundMetrics previous = roundHistory.isEmpty() ? null : roundHistory.getLast();
    ProofGraphRoundMetrics metrics =
        unscored.withConvergenceScore(config.score(unscored, previous));
    ProofGraphRoundClassification classification = classify(metrics, previous);
    roundHistory.add(metrics);
    roundClassifications.add(classification);
    updateConsecutive(classification);
    transition(metrics, classification, graph, rootGoalHash);
    version++;
    return metrics;
  }

  public synchronized ProofGraphRoundClassification classify(
      ProofGraphRoundMetrics metrics, ProofGraphRoundMetrics previous) {
    java.util.Objects.requireNonNull(metrics, "metrics");
    boolean debtDecrease =
        previous != null
            && previous.globalCanonicalProofDebt() - metrics.globalCanonicalProofDebt()
                > config.debtEpsilon();
    if (metrics.authoritativeProgress() || debtDecrease) {
      return ProofGraphRoundClassification.PROGRESSING;
    }
    boolean targetsRose =
        previous != null
            && metrics.activeCanonicalTargets() + metrics.deferredCanonicalTargets()
                > previous.activeCanonicalTargets() + previous.deferredCanonicalTargets();
    boolean debtRose =
        previous != null
            && metrics.globalCanonicalProofDebt() - previous.globalCanonicalProofDebt()
                > config.debtEpsilon();
    if (targetsRose || debtRose) {
      return ProofGraphRoundClassification.DIVERGING;
    }
    return ProofGraphRoundClassification.STAGNATING;
  }

  public synchronized FocusedExpansionDecision decideExpansion(
      FocusedRecoveryActionType actionType,
      boolean existingCanonicalTarget,
      int activeTargetsForRoute,
      int activeTargetsCampaign,
      String familyId,
      String canonicalTargetId) {
    java.util.Objects.requireNonNull(actionType, "actionType");
    if (existingCanonicalTarget) {
      if (controlMode != ProofGraphControlMode.FOCUSED_RECOVERY
          || actionType.recoveryAction()
          || focusedRecoveryPlan != null
              && focusedRecoveryPlan.selects(familyId, canonicalTargetId)) {
        return FocusedExpansionDecision.allow();
      }
      return blockGeneric(FocusedExpansionDecision.deferFocusedRecovery());
    }
    if (activeTargetsForRoute >= config.maxActiveCanonicalTargetsPerRoute()
        || activeTargetsCampaign >= config.maxActiveCanonicalTargetsCampaign()) {
      return blockGeneric(FocusedExpansionDecision.deferCapacity());
    }
    if (controlMode != ProofGraphControlMode.FOCUSED_RECOVERY) {
      return FocusedExpansionDecision.allow();
    }
    boolean selected =
        focusedRecoveryPlan != null
            && focusedRecoveryPlan.selects(familyId, canonicalTargetId);
    if (!actionType.recoveryAction() && !selected) {
      return blockGeneric(FocusedExpansionDecision.deferFocusedRecovery());
    }
    if (focusedRecoveryPlan == null || focusedRecoveryPlan.quotaRemaining() <= 0) {
      return blockGeneric(FocusedExpansionDecision.deferFocusedRecovery());
    }
    return FocusedExpansionDecision.allow();
  }

  public synchronized void recordFocusedNewTarget() {
    if (controlMode != ProofGraphControlMode.FOCUSED_RECOVERY || focusedRecoveryPlan == null) {
      return;
    }
    focusedRecoveryPlan = focusedRecoveryPlan.useNewTarget();
    version++;
  }

  public synchronized boolean acquireFocusedTaskLease(
      FocusedRecoveryActionType actionType, int round) {
    java.util.Objects.requireNonNull(actionType, "actionType");
    if (focusedRecoveryPlan == null || round < 0) {
      return false;
    }
    String key =
        CanonicalJson.stableHash(
            Map.of(
                "episode_id", focusedRecoveryPlan.episodeId(),
                "family_id", focusedRecoveryPlan.selectedFamilyId(),
                "canonical_ids", focusedRecoveryPlan.selectedCanonicalTargetIds(),
                "action", actionType.name(),
                "round", round));
    boolean acquired = focusedTaskLeases.add(key);
    if (acquired) {
      version++;
    }
    return acquired;
  }

  public synchronized void recordGenericExpansionAttempt(boolean admitted) {
    genericExpansionAttempts++;
    if (controlMode == ProofGraphControlMode.FOCUSED_RECOVERY) {
      if (admitted) {
        genericExpansionLeaks++;
      } else {
        genericExpansionBlocks++;
      }
    }
    version++;
  }

  public synchronized ProofGraphControlMode controlMode() {
    return controlMode;
  }

  public synchronized Optional<FocusedRecoveryPlan> focusedRecoveryPlan() {
    return Optional.ofNullable(focusedRecoveryPlan);
  }

  public synchronized int focusedRecoveryEntries() {
    return focusedRecoveryEntries;
  }

  public synchronized int focusedRecoveryExits() {
    return focusedRecoveryExits;
  }

  public synchronized int recoveryCooldownEntries() {
    return recoveryCooldownEntries;
  }

  public synchronized int stagnationEpisodes() {
    return stagnationEpisodes;
  }

  public synchronized int divergenceEpisodes() {
    return divergenceEpisodes;
  }

  public synchronized int genericExpansionAttempts() {
    return genericExpansionAttempts;
  }

  public synchronized int genericExpansionBlocks() {
    return genericExpansionBlocks;
  }

  public synchronized int genericExpansionLeaks() {
    return genericExpansionLeaks;
  }

  public synchronized List<ProofGraphRoundMetrics> roundHistory() {
    return List.copyOf(roundHistory);
  }

  public synchronized List<ProofGraphRoundClassification> roundClassifications() {
    return List.copyOf(roundClassifications);
  }

  public ProofGraphConvergenceConfig config() {
    return config;
  }

  public synchronized ProofGraphConvergenceSnapshot snapshot() {
    return new ProofGraphConvergenceSnapshot(
        controlMode,
        roundHistory,
        roundClassifications,
        consecutiveStagnation,
        consecutiveDivergence,
        cooldownRemaining,
        focusedRecoveryPlan,
        focusedTaskLeases,
        stagnationEpisodes,
        divergenceEpisodes,
        focusedRecoveryEntries,
        focusedRecoveryExits,
        recoveryCooldownEntries,
        genericExpansionAttempts,
        genericExpansionBlocks,
        genericExpansionLeaks,
        observedOccurrenceTotal,
        observedVerifiedClaimTotal,
        observedExactRefutationTotal,
        observedForbiddenProposalTotal,
        version);
  }

  public synchronized String stableHash() {
    return CanonicalJson.stableHash(snapshot());
  }

  private void transition(
      ProofGraphRoundMetrics metrics,
      ProofGraphRoundClassification classification,
      ProofGraphStore graph,
      String rootGoalHash) {
    if (controlMode == ProofGraphControlMode.NORMAL_EXPANSION) {
      ProofGraphConvergenceTrigger trigger = null;
      if (consecutiveDivergence >= config.divergenceWindow()) {
        trigger = ProofGraphConvergenceTrigger.CONSECUTIVE_DIVERGENCE;
        divergenceEpisodes++;
      } else if (consecutiveStagnation >= config.stagnationWindow()) {
        trigger = ProofGraphConvergenceTrigger.CONSECUTIVE_STAGNATION;
        stagnationEpisodes++;
      } else if (metrics.activeCanonicalTargets()
              >= config.maxActiveCanonicalTargetsCampaign()
          || routeCapacityReached(graph)) {
        trigger = ProofGraphConvergenceTrigger.ACTIVE_TARGET_CAPACITY;
      }
      if (trigger != null) {
        enterFocused(metrics.round(), trigger, graph, rootGoalHash);
      }
      return;
    }
    if (controlMode == ProofGraphControlMode.FOCUSED_RECOVERY) {
      if (classification == ProofGraphRoundClassification.PROGRESSING) {
        controlMode = ProofGraphControlMode.RECOVERY_COOLDOWN;
        cooldownRemaining = config.cooldownRounds();
        focusedRecoveryExits++;
        recoveryCooldownEntries++;
      }
      return;
    }
    if (classification == ProofGraphRoundClassification.DIVERGING) {
      ProofGraphConvergenceTrigger trigger =
          ProofGraphConvergenceTrigger.CONSECUTIVE_DIVERGENCE;
      enterFocused(metrics.round(), trigger, graph, rootGoalHash);
      return;
    }
    if (cooldownRemaining > 0) {
      cooldownRemaining--;
      consecutiveStagnation = 0;
      consecutiveDivergence = 0;
      return;
    }
    controlMode = ProofGraphControlMode.NORMAL_EXPANSION;
    focusedRecoveryPlan = null;
    consecutiveStagnation = 0;
    consecutiveDivergence = 0;
  }

  private void enterFocused(
      int round,
      ProofGraphConvergenceTrigger trigger,
      ProofGraphStore graph,
      String rootGoalHash) {
    FocusedRecoveryPlan selected = selectFocusedRecoveryPlan(round, trigger, graph, rootGoalHash);
    if (selected == null) {
      return;
    }
    controlMode = ProofGraphControlMode.FOCUSED_RECOVERY;
    focusedRecoveryPlan = selected;
    cooldownRemaining = 0;
    consecutiveStagnation = 0;
    consecutiveDivergence = 0;
    focusedRecoveryEntries++;
  }

  private FocusedRecoveryPlan selectFocusedRecoveryPlan(
      int round,
      ProofGraphConvergenceTrigger trigger,
      ProofGraphStore graph,
      String rootGoalHash) {
    Comparator<BottleneckFamilyRecord> familyOrder =
        Comparator.<BottleneckFamilyRecord>comparingInt(
                family -> openMemberCount(graph, family))
            .reversed()
            .thenComparing(
                Comparator.<BottleneckFamilyRecord>comparingInt(
                        family -> routeCoverage(graph, family))
                    .reversed())
            .thenComparing(
                Comparator.<BottleneckFamilyRecord>comparingDouble(
                        family -> representativeCentrality(graph, family))
                    .reversed())
            .thenComparing(
                Comparator.<BottleneckFamilyRecord>comparingDouble(
                        family -> representativePriority(graph, family))
                    .reversed())
            .thenComparing(BottleneckFamilyRecord::familyId);
    Optional<BottleneckFamilyRecord> family =
        graph.activeBottleneckFamilies().stream().sorted(familyOrder).findFirst();
    String familyId = family.map(BottleneckFamilyRecord::familyId).orElse("");
    Set<String> canonicalIds =
        family
            .map(BottleneckFamilyRecord::canonicalTargetIds)
            .orElseGet(
                () ->
                    graph.canonicalOpenTargets().stream()
                        .sorted(
                            Comparator.<CanonicalObligationRecord>comparingDouble(
                                    item -> graph.representativeCentrality(item.canonicalTargetId()))
                                .reversed()
                                .thenComparing(
                                    Comparator.<CanonicalObligationRecord>comparingDouble(
                                            item ->
                                                graph.representativePriority(
                                                    item.canonicalTargetId()))
                                        .reversed())
                                .thenComparing(CanonicalObligationRecord::canonicalTargetId))
                        .findFirst()
                        .map(item -> Set.of(item.canonicalTargetId()))
                        .orElse(Set.of()));
    if (canonicalIds.isEmpty()) {
      return null;
    }
    String episodeId =
        "recovery_"
            + CanonicalJson.stableHash(
                Map.of(
                    "problem_hash", graph.problemHash(),
                    "round", round,
                    "trigger", trigger.name(),
                    "family_id", familyId,
                    "canonical_ids", canonicalIds))
                .substring(0, 24);
    return new FocusedRecoveryPlan(
        episodeId,
        graph.problemHash(),
        rootGoalHash,
        trigger,
        round,
        familyId,
        canonicalIds,
        config.maxNewCanonicalTargetsPerFocusedEpisode(),
        0);
  }

  private void updateConsecutive(ProofGraphRoundClassification classification) {
    switch (classification) {
      case PROGRESSING -> {
        consecutiveStagnation = 0;
        consecutiveDivergence = 0;
      }
      case STAGNATING -> {
        consecutiveStagnation++;
        consecutiveDivergence = 0;
      }
      case DIVERGING -> {
        consecutiveDivergence++;
        consecutiveStagnation = 0;
      }
    }
  }

  private FocusedExpansionDecision blockGeneric(FocusedExpansionDecision decision) {
    return decision;
  }

  private void load(ProofGraphConvergenceSnapshot snapshot) {
    java.util.Objects.requireNonNull(snapshot, "snapshot");
    controlMode = snapshot.controlMode();
    roundHistory.addAll(snapshot.roundHistory());
    roundClassifications.addAll(snapshot.roundClassifications());
    consecutiveStagnation = snapshot.consecutiveStagnation();
    consecutiveDivergence = snapshot.consecutiveDivergence();
    cooldownRemaining = snapshot.cooldownRemaining();
    focusedRecoveryPlan = snapshot.focusedRecoveryPlan();
    focusedTaskLeases.addAll(snapshot.focusedTaskLeases());
    stagnationEpisodes = snapshot.stagnationEpisodes();
    divergenceEpisodes = snapshot.divergenceEpisodes();
    focusedRecoveryEntries = snapshot.focusedRecoveryEntries();
    focusedRecoveryExits = snapshot.focusedRecoveryExits();
    recoveryCooldownEntries = snapshot.recoveryCooldownEntries();
    genericExpansionAttempts = snapshot.genericExpansionAttempts();
    genericExpansionBlocks = snapshot.genericExpansionBlocks();
    genericExpansionLeaks = snapshot.genericExpansionLeaks();
    observedOccurrenceTotal = snapshot.observedOccurrenceTotal();
    observedVerifiedClaimTotal = snapshot.observedVerifiedClaimTotal();
    observedExactRefutationTotal = snapshot.observedExactRefutationTotal();
    observedForbiddenProposalTotal = snapshot.observedForbiddenProposalTotal();
    version = snapshot.version();
  }

  private static int openMemberCount(ProofGraphStore graph, BottleneckFamilyRecord family) {
    return (int)
        family.canonicalTargetIds().stream()
            .map(graph::canonicalStatus)
            .filter(
                status ->
                    status == CanonicalObligationStatus.OPEN
                        || status == CanonicalObligationStatus.MIXED)
            .count();
  }

  private static int routeCoverage(ProofGraphStore graph, BottleneckFamilyRecord family) {
    return (int)
        family.canonicalTargetIds().stream()
            .flatMap(
                id ->
                    graph.allCanonicalTargets().stream()
                        .filter(item -> item.canonicalTargetId().equals(id))
                        .flatMap(item -> item.routeIds().stream()))
            .distinct()
            .count();
  }

  private static double representativeCentrality(
      ProofGraphStore graph, BottleneckFamilyRecord family) {
    return graph.representativeCentrality(family.representativeCanonicalTargetId());
  }

  private static double representativePriority(
      ProofGraphStore graph, BottleneckFamilyRecord family) {
    return graph.representativePriority(family.representativeCanonicalTargetId());
  }

  private boolean routeCapacityReached(ProofGraphStore graph) {
    return graph.canonicalOpenTargets().stream()
        .filter(
            target ->
                target.schedulingState() == CanonicalObligationSchedulingState.ACTIVE)
        .flatMap(target -> target.routeIds().stream())
        .distinct()
        .anyMatch(
            routeId ->
                graph.activeCanonicalTargetCount(routeId)
                    >= config.maxActiveCanonicalTargetsPerRoute());
  }

  private static double rawDebtWeight(ProofObligation obligation) {
    if ("closed".equals(obligation.status()) || "refuted".equals(obligation.status())) {
      return 0.0d;
    }
    return 1.0d
        + Math.max(0.0d, obligation.priority())
        + Math.max(0.0d, obligation.centrality())
        + 0.25d * obligation.dependencyIds().size();
  }
}
