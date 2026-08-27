package io.github.aililuola.mathproofmesh.orchestration;

import io.github.aililuola.mathproofmesh.contract.ActionKind;
import io.github.aililuola.mathproofmesh.contract.BudgetAction;
import io.github.aililuola.mathproofmesh.contract.BudgetDecision;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic soft-budget scheduler driven by verified progress and failure class. */
public final class AdaptiveBudgetManager {
  private static final String POLICY_VERSION = "evidence-budget-v3";
  private final int maxPaths;
  private final int finishReserve;
  private final Map<String, BudgetDecision> decisions = new LinkedHashMap<>();
  private final Map<String, EvidenceAwareBudgetDecision> stateDecisions = new LinkedHashMap<>();
  private final BudgetResourceVector configuredLimit;
  private final BudgetResourceVector configuredFinishReserve;
  private final ActionCostEstimator costEstimator;
  private final int perTargetZeroGainLimit;
  private final int globalZeroGainLimit;
  private final BudgetEvidencePolicy evidencePolicy;

  public AdaptiveBudgetManager(int maxPaths, int finishReserve) {
    if (maxPaths <= 0 || finishReserve < 0) {
      throw new IllegalArgumentException("invalid scheduler limits");
    }
    this.maxPaths = maxPaths;
    this.finishReserve = finishReserve;
    this.configuredLimit =
        new BudgetResourceVector(
            1_000_000_000L,
            1_000_000_000_000L,
            1_000_000_000_000L,
            2_000_000_000_000L,
            new BigDecimal("1000000000"));
    this.configuredFinishReserve =
        new BudgetResourceVector(finishReserve, 0L, 0L, 0L, BigDecimal.ZERO);
    this.costEstimator =
        new ActionCostEstimator(
            ActionCostEstimator.Profile.defaults(),
            new PricingSnapshot(
                "legacy",
                "legacy",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                PricingSnapshot.BillingMode.BILLING_EXEMPT,
                "legacy-config",
                null));
    this.perTargetZeroGainLimit = 2;
    this.globalZeroGainLimit = 3;
    this.evidencePolicy = BudgetEvidencePolicy.defaults();
  }

  public AdaptiveBudgetManager(
      int maxPaths,
      BudgetResourceVector configuredLimit,
      BudgetResourceVector finishReserve,
      ActionCostEstimator costEstimator,
      int perTargetZeroGainLimit,
      int globalZeroGainLimit) {
    this(
        maxPaths,
        configuredLimit,
        finishReserve,
        costEstimator,
        perTargetZeroGainLimit,
        globalZeroGainLimit,
        BudgetEvidencePolicy.defaults());
  }

  public AdaptiveBudgetManager(
      int maxPaths,
      BudgetResourceVector configuredLimit,
      BudgetResourceVector finishReserve,
      ActionCostEstimator costEstimator,
      int perTargetZeroGainLimit,
      int globalZeroGainLimit,
      BudgetEvidencePolicy evidencePolicy) {
    if (maxPaths <= 0
        || perTargetZeroGainLimit < 1
        || globalZeroGainLimit < 1) {
      throw new IllegalArgumentException("invalid evidence-aware scheduler limits");
    }
    this.maxPaths = maxPaths;
    this.configuredLimit = Objects.requireNonNull(configuredLimit, "configuredLimit");
    this.configuredFinishReserve = Objects.requireNonNull(finishReserve, "finishReserve");
    if (!finishReserve.fitsWithin(configuredLimit)) {
      throw new IllegalArgumentException("finish reserve exceeds configured limit");
    }
    this.finishReserve = Math.toIntExact(Math.min(Integer.MAX_VALUE, finishReserve.calls()));
    this.costEstimator = Objects.requireNonNull(costEstimator, "costEstimator");
    this.perTargetZeroGainLimit = perTargetZeroGainLimit;
    this.globalZeroGainLimit = globalZeroGainLimit;
    this.evidencePolicy = Objects.requireNonNull(evidencePolicy, "evidencePolicy");
  }

  /** Decides only from the canonical state hash. Caller action keys are not consulted. */
  public synchronized EvidenceAwareBudgetDecision decide(BudgetStateSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    EvidenceAwareBudgetDecision prior = stateDecisions.get(snapshot.snapshotHash());
    if (prior != null && POLICY_VERSION.equals(prior.identity().policyVersion())) {
      return prior;
    }
    BudgetResourceVector used =
        snapshot.committedUsage().asResourceVector().plus(snapshot.reservedUsage().asResourceVector());
    BudgetResourceVector available =
        used.fitsWithin(configuredLimit)
            ? configuredLimit.minus(used)
            : BudgetResourceVector.zero();
    BudgetResourceVector finish =
        snapshot.finishReserve().equals(BudgetUsageTotals.zero())
            ? configuredFinishReserve
            : snapshot.finishReserve().asResourceVector();
    List<PathBudgetStats> paths = snapshot.pathStats();
    PathBudgetStats preferred =
        paths.stream()
            .filter(path -> !path.failed())
            .filter(path -> !path.complete())
            .filter(PathBudgetStats::structurallyValid)
            .max(
                Comparator.comparingDouble(this::pathValue)
                    .thenComparing(PathBudgetStats::routeId, Comparator.reverseOrder()))
            .orElse(null);
    PathBudgetStats failed =
        paths.stream()
            .filter(PathBudgetStats::failed)
            .filter(evidencePolicy::repairAllowed)
            .min(
                Comparator.comparingInt(PathBudgetStats::failedRepairAttempts)
                    .thenComparing(PathBudgetStats::routeId))
            .orElse(null);
    PathBudgetStats verifiable =
        paths.stream()
            .filter(path -> !path.failed())
            .filter(path -> path.verifiedProgress() || path.verificationScore() >= 0.5d)
            .filter(path -> !"pass".equals(path.latestVerdict()))
            .max(
                Comparator.comparingDouble(PathBudgetStats::verificationScore)
                    .thenComparing(PathBudgetStats::routeId, Comparator.reverseOrder()))
            .orElse(null);
    PathBudgetStats synthesis =
        paths.stream()
            .filter(path -> !path.failed())
            .filter(path -> path.verifiedProgress() || "pass".equals(path.latestVerdict()))
            .max(
                Comparator.comparingDouble(PathBudgetStats::verificationScore)
                    .thenComparing(PathBudgetStats::routeId, Comparator.reverseOrder()))
            .orElse(null);
    boolean verifiedComplete =
        paths.stream()
            .anyMatch(
                path ->
                    path.complete()
                        && "pass".equals(path.latestVerdict())
                        && path.unresolvedGapCount() == 0);
    boolean allFailed = !paths.isEmpty() && paths.stream().allMatch(PathBudgetStats::failed);
    boolean allMechanismsExhausted =
        !paths.isEmpty()
            && paths.stream()
                .allMatch(
                    path ->
                        snapshot
                            .zeroGainState()
                            .exhaustedMechanismSignatures()
                            .contains(path.mechanismSignature()));
    boolean pathCapacityAvailable = snapshot.currentPathCount() < maxPaths;
    boolean forcedWiden =
        allFailed
            && allMechanismsExhausted
            && evidencePolicy.forceWidenWhenAllFailed()
            && pathCapacityAvailable;
    boolean ordinaryWiden = !allFailed && pathCapacityAvailable;

    List<BudgetActionCandidate> raw = new ArrayList<>();
    raw.add(
        candidate(
            ActionKind.WIDEN,
            "",
            "",
            "try one unused independent mechanism",
            ordinaryWiden || forcedWiden,
            "path cap or previously exhausted failure frontier blocks widening",
            allFailed ? 2.6d : 1.4d,
            BudgetBucket.BREADTH,
            forcedWiden,
            available,
            finish));
    boolean deepenEvidence =
        preferred != null
            && !preferred.failed()
            && !preferred.complete()
            && preferred.structurallyValid()
            && evidencePolicy.adjustedProgress(preferred)
                >= evidencePolicy.meaningfulProgressThreshold();
    boolean deepenStagnated =
        preferred != null
            && snapshot.zeroGainState().count(preferred.key(ActionKind.DEEPEN))
                >= perTargetZeroGainLimit;
    raw.add(
        candidate(
            ActionKind.DEEPEN,
            preferred == null ? "" : preferred.routeId(),
            preferred == null ? "" : preferred.strategyId(),
            "continue the highest-value committed partial route",
            deepenEvidence && !deepenStagnated,
            deepenStagnated
                ? "STOP_ZERO_GAIN_TARGET"
                : "no nonfailed incomplete route has committed evidence",
            preferred == null ? 0.0d : 1.0d + pathValue(preferred),
            BudgetBucket.DEPTH,
            false,
            available,
            finish));
    raw.add(
        candidate(
            ActionKind.VERIFY,
            verifiable == null ? "" : verifiable.routeId(),
            verifiable == null ? "" : verifiable.strategyId(),
            "independently verify committed progress before spending more depth",
            verifiable != null,
            "no committed candidate currently needs verification",
            verifiable == null ? 0.0d : 2.7d + verifiable.verificationScore(),
            BudgetBucket.VERIFICATION,
            false,
            available,
            BudgetResourceVector.zero()));
    raw.add(
        candidate(
            ActionKind.REVISE,
            failed == null ? "" : failed.routeId(),
            failed == null ? "" : failed.strategyId(),
            "repair one bounded failed route from committed evidence",
            failed != null,
            "no repairable failed route remains",
            failed == null ? 0.0d : 2.2d - 0.1d * failed.failedRepairAttempts(),
            BudgetBucket.REVISION,
            false,
            available,
            finish));
    raw.add(
        candidate(
            ActionKind.SYNTHESIZE,
            synthesis == null ? "" : synthesis.routeId(),
            synthesis == null ? "" : synthesis.strategyId(),
            "protect final synthesis and blind verification resources",
            synthesis != null,
            "no verified synthesis candidate exists",
            synthesis == null ? 0.0d : 3.2d + synthesis.verificationScore(),
            BudgetBucket.SYNTHESIS,
            false,
            available,
            BudgetResourceVector.zero()));

    String stopReason = "";
    if (verifiedComplete) {
      stopReason = "STOP_VERIFIED_COMPLETE";
    } else if (snapshot.zeroGainState().globalZeroGainRounds() >= globalZeroGainLimit
        && allMechanismsExhausted
        && (!pathCapacityAvailable || !evidencePolicy.forceWidenWhenAllFailed())) {
      stopReason = "STOP_ZERO_GAIN";
    }
    boolean anyAffordable = raw.stream().anyMatch(BudgetActionCandidate::eligible);
    if (stopReason.isEmpty() && !anyAffordable) {
      boolean resourceBlocked =
          raw.stream()
              .map(BudgetActionCandidate::blockedReason)
              .anyMatch(
                  reason ->
                      "MULTIDIMENSIONAL_BUDGET_EXHAUSTED".equals(reason)
                          || "UNPRICED_PROVIDER".equals(reason));
      stopReason = resourceBlocked ? "STOP_BUDGET_EXHAUSTED" : "STOP_NO_ADMISSIBLE_WORK";
    }
    raw.add(
        new BudgetActionCandidate(
            ActionKind.STOP,
            "",
            "",
            stopReason.isEmpty() ? "continue with an eligible evidence-backed action" : stopReason,
            !stopReason.isEmpty(),
            stopReason.isEmpty() ? "admissible work remains" : "",
            stopReason.isEmpty() ? -1.0d : 100.0d,
            BudgetResourceVector.zero(),
            BudgetBucket.FINISH,
            0,
            false,
            false));

    List<BudgetActionCandidate> ranked =
        raw.stream()
            .sorted(
                Comparator.comparing(BudgetActionCandidate::eligible)
                    .reversed()
                    .thenComparing(
                        BudgetActionCandidate::evidenceScore, Comparator.reverseOrder())
                    .thenComparing(value -> value.action().ordinal())
                    .thenComparing(BudgetActionCandidate::strategyId)
                    .thenComparing(BudgetActionCandidate::targetId))
            .toList();
    List<BudgetActionCandidate> finalActions = new ArrayList<>();
    for (int index = 0; index < ranked.size(); index++) {
      BudgetActionCandidate value = ranked.get(index);
      finalActions.add(
          new BudgetActionCandidate(
              value.action(),
              value.targetId(),
              value.strategyId(),
              value.reason(),
              value.eligible(),
              value.blockedReason(),
              value.evidenceScore(),
              value.resourceEstimate(),
              value.bucket(),
              index + 1,
              value.forced(),
              index == 0 && value.eligible()));
    }
    String decisionHash =
        CanonicalJson.stableHash(
            Map.of(
                "state_hash", snapshot.snapshotHash(),
                "policy_version", POLICY_VERSION,
                "actions", finalActions,
                "stop_reason", stopReason));
    EvidenceAwareBudgetDecision result =
        new EvidenceAwareBudgetDecision(
            new BudgetDecisionIdentity(snapshot.snapshotHash(), POLICY_VERSION, decisionHash),
            finalActions,
            "ranked from committed evidence, zero-gain state, multidimensional cost, and finish reserve",
            stopReason);
    stateDecisions.put(snapshot.snapshotHash(), result);
    return result;
  }

  public synchronized BudgetDecisionSnapshot decisionSnapshot() {
    return new BudgetDecisionSnapshot(
        BudgetDecisionSnapshot.CURRENT_SCHEMA_VERSION,
        stateDecisions.values().stream()
            .sorted(Comparator.comparing(value -> value.identity().stateHash()))
            .toList());
  }

  public synchronized void restoreDecisionSnapshot(BudgetDecisionSnapshot snapshot) {
    BudgetDecisionSnapshot values =
        snapshot == null ? BudgetDecisionSnapshot.empty() : snapshot;
    stateDecisions.clear();
    for (EvidenceAwareBudgetDecision decision : values.decisions()) {
      String stateHash = decision.identity().stateHash();
      EvidenceAwareBudgetDecision prior = stateDecisions.putIfAbsent(stateHash, decision);
      if (prior != null && !prior.equals(decision)) {
        throw new IllegalArgumentException("conflicting budget decisions for one state hash");
      }
    }
  }

  private BudgetActionCandidate candidate(
      ActionKind action,
      String targetId,
      String strategyId,
      String reason,
      boolean evidenceEligible,
      String evidenceBlockedReason,
      double score,
      BudgetBucket bucket,
      boolean forced,
      BudgetResourceVector available,
      BudgetResourceVector protectedReserve) {
    BudgetResourceVector estimate;
    try {
      estimate = costEstimator.estimate(action);
    } catch (IllegalStateException unpriced) {
      return new BudgetActionCandidate(
          action,
          targetId,
          strategyId,
          reason,
          false,
          "UNPRICED_PROVIDER",
          score,
          BudgetResourceVector.zero(),
          bucket,
          0,
          forced,
          false);
    }
    BudgetResourceVector affordable = available;
    if (!protectedReserve.isZero()) {
      affordable =
          protectedReserve.fitsWithin(available)
              ? available.minus(protectedReserve)
              : BudgetResourceVector.zero();
    }
    boolean resourcesFit = estimate.fitsWithin(affordable);
    return new BudgetActionCandidate(
        action,
        targetId,
        strategyId,
        reason,
        evidenceEligible && resourcesFit,
        evidenceEligible
            ? resourcesFit ? "" : "MULTIDIMENSIONAL_BUDGET_EXHAUSTED"
            : evidenceBlockedReason,
        score,
        estimate,
        bucket,
        0,
        forced,
        false);
  }

  private double pathValue(PathBudgetStats path) {
    return evidencePolicy.adjustedProgress(path)
        + path.gapReduction()
        + path.novelty() * 0.5d
        + path.verificationScore()
        - path.uncertainty() * 0.5d
        - path.stagnationRounds() * 0.1d
        - evidencePolicy.failurePenalty(path);
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
