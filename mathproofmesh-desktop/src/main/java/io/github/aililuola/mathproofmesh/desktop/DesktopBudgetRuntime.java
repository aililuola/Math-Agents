package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.config.AgentConfig;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.ActionKind;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.orchestration.ActionCostEstimator;
import io.github.aililuola.mathproofmesh.orchestration.AdaptiveBudgetManager;
import io.github.aililuola.mathproofmesh.orchestration.BudgetDecisionSnapshot;
import io.github.aililuola.mathproofmesh.orchestration.BudgetBucket;
import io.github.aililuola.mathproofmesh.orchestration.BudgetEnvelope;
import io.github.aililuola.mathproofmesh.orchestration.BudgetEnvelopeId;
import io.github.aililuola.mathproofmesh.orchestration.BudgetEnvelopeLedger;
import io.github.aililuola.mathproofmesh.orchestration.BudgetEnvelopeSnapshot;
import io.github.aililuola.mathproofmesh.orchestration.BudgetEvidencePolicy;
import io.github.aililuola.mathproofmesh.orchestration.BudgetReservationSnapshot;
import io.github.aililuola.mathproofmesh.orchestration.BudgetResourceVector;
import io.github.aililuola.mathproofmesh.orchestration.BudgetStateSnapshot;
import io.github.aililuola.mathproofmesh.orchestration.BudgetUsageSnapshot;
import io.github.aililuola.mathproofmesh.orchestration.BudgetUsageTotals;
import io.github.aililuola.mathproofmesh.orchestration.CertifiedGainReceipt;
import io.github.aililuola.mathproofmesh.orchestration.CertifiedGainSnapshot;
import io.github.aililuola.mathproofmesh.orchestration.PathBudgetStats;
import io.github.aililuola.mathproofmesh.orchestration.PricingSnapshot;
import io.github.aililuola.mathproofmesh.orchestration.TargetMechanismKey;
import io.github.aililuola.mathproofmesh.orchestration.ZeroGainSnapshot;
import io.github.aililuola.mathproofmesh.orchestration.ZeroGainState;
import io.github.aililuola.mathproofmesh.provider.UsageTotals;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Run-scoped budget sidecar. It does not mutate mathematical authority. */
final class DesktopBudgetRuntime {
  private static final long UNLIMITED_TOKENS = 1_000_000_000_000L;
  private static final BigDecimal UNLIMITED_COST = new BigDecimal("1000000000");

  private final String runId;
  private final String configHash;
  private final PricingSnapshot pricing;
  private final BudgetResourceVector limit;
  private final BudgetResourceVector finishReserve;
  private final ActionCostEstimator costEstimator;
  private final AdaptiveBudgetManager manager;
  private BudgetEnvelopeLedger envelopes;
  private ZeroGainState zeroGain = ZeroGainState.empty();
  private final List<CertifiedGainReceipt> gains = new ArrayList<>();
  private BudgetEnvelopeId activeEnvelopeId;
  private TargetMechanismKey activeTarget;
  private GainBaseline activeGainBaseline;

  DesktopBudgetRuntime(String runId, SystemConfig config) {
    this.runId = require(runId, "runId");
    Objects.requireNonNull(config, "config");
    this.configHash =
        CanonicalJson.stableHash(
            Map.of(
                "budget", config.budget(),
                "scheduler", config.scheduler(),
                "continuation", config.continuation(),
                "stage_output_token_limits", config.runtime().stageOutputTokenLimits()));
    this.pricing = pricing(config, configHash);
    this.limit = resourceLimit(config);
    this.costEstimator =
        new ActionCostEstimator(
            costProfile(config), pricing, config.budget().maxCostUsd() != null);
    BudgetResourceVector finalization =
        costEstimator
            .estimate(ActionKind.SYNTHESIZE)
            .plus(costEstimator.estimate(ActionKind.VERIFY))
            .plus(
                new BudgetResourceVector(
                    config.scheduler().finishTransitionBufferCalls(),
                    0L,
                    0L,
                    0L,
                    BigDecimal.ZERO));
    this.finishReserve = clamp(finalization, limit);
    this.manager =
        new AdaptiveBudgetManager(
            config.budget().maxPaths(),
            limit,
            finishReserve,
            costEstimator,
            Math.max(1, config.scheduler().maxNormalAttemptsPerSignature()),
            config.scheduler().globalNoProgressRoundsBeforeStop(),
            evidencePolicy(config));
    this.envelopes = new BudgetEnvelopeLedger(limit, finishReserve);
  }

  AdaptiveBudgetManager manager() {
    return manager;
  }

  BudgetEnvelopeLedger envelopes() {
    return envelopes;
  }

  PricingSnapshot pricing() {
    return pricing;
  }

  BudgetResourceVector limit() {
    return limit;
  }

  BudgetResourceVector finishReserve() {
    return finishReserve;
  }

  BudgetResourceVector availableExplorationCapacity() {
    BudgetResourceVector available = envelopes.available();
    return finishReserve.fitsWithin(available)
        ? available.minus(finishReserve)
        : BudgetResourceVector.zero();
  }

  ZeroGainState zeroGain() {
    return zeroGain;
  }

  BudgetResourceVector estimate(ActionKind action) {
    return costEstimator.estimate(action);
  }

  BudgetResourceVector estimateInitialExploration(int routeCount) {
    if (routeCount < 0) {
      throw new IllegalArgumentException("routeCount must not be negative");
    }
    return costEstimator.estimate(ActionKind.DEEPEN).times(routeCount);
  }

  BudgetResourceVector authorityReviewReserve(int routeCount) {
    if (routeCount < 0) {
      throw new IllegalArgumentException("routeCount must not be negative");
    }
    return costEstimator.estimate(ActionKind.VERIFY).times(routeCount);
  }

  Optional<BudgetEnvelope> activeEnvelope() {
    return envelopes.envelopeSnapshot().envelopes().stream()
        .filter(
            envelope ->
                envelope.status()
                        == io.github.aililuola.mathproofmesh.orchestration.BudgetEnvelopeStatus.ACTIVE
                    || envelope.status()
                        == io.github.aililuola.mathproofmesh.orchestration.BudgetEnvelopeStatus.RESERVED)
        .findFirst();
  }

  boolean hasActiveEnvelope() {
    return activeEnvelopeId != null;
  }

  BudgetEnvelope reserveAndActivate(
      String epochId,
      String workItemId,
      String actionDecisionId,
      BudgetBucket bucket,
      BudgetResourceVector resources,
      TargetMechanismKey target,
      GainBaseline gainBaseline) {
    BudgetEnvelope envelope =
        envelopes.reserve(
            runId, epochId, workItemId, actionDecisionId, bucket, resources);
    activeEnvelopeId = envelope.envelopeId();
    activeTarget = Objects.requireNonNull(target, "target");
    activeGainBaseline = Objects.requireNonNull(gainBaseline, "gainBaseline");
    return envelopes.activate(envelope.envelopeId());
  }

  Optional<BudgetEnvelopeId> finishActiveEnvelope(GainBaseline after, int exhaustAt) {
    Objects.requireNonNull(after, "after");
    if (activeEnvelopeId == null) {
      return Optional.empty();
    }
    BudgetEnvelopeId finishedId = activeEnvelopeId;
    if (activeGainBaseline != null && activeTarget != null) {
      CertifiedGainReceipt receipt = gainReceipt(activeGainBaseline, after, finishedId);
      recordGain(receipt, activeTarget, exhaustAt);
    }
    envelopes.finish(finishedId);
    activeEnvelopeId = null;
    activeTarget = null;
    activeGainBaseline = null;
    return Optional.of(finishedId);
  }

  void restoreActiveEnvelope(TargetMechanismKey target, GainBaseline gainBaseline) {
    BudgetEnvelope envelope = activeEnvelope().orElse(null);
    if (envelope == null) {
      activeEnvelopeId = null;
      activeTarget = null;
      activeGainBaseline = null;
      return;
    }
    activeEnvelopeId = envelopes.activate(envelope.envelopeId()).envelopeId();
    activeTarget = Objects.requireNonNull(target, "target");
    activeGainBaseline = Objects.requireNonNull(gainBaseline, "gainBaseline");
  }

  BudgetStateSnapshot snapshot(
      String authorityHash,
      String epochId,
      long round,
      int currentPathCount,
      UsageTotals usage,
      List<PathBudgetStats> paths) {
    Objects.requireNonNull(usage, "usage");
    BudgetUsageTotals committed =
        new BudgetUsageTotals(
            usage.calls(),
            usage.inputTokens(),
            usage.outputTokens(),
            usage.totalTokens(),
            usage.costUsd());
    BudgetResourceVector liveReserved = envelopes.reservedResources();
    BudgetUsageTotals reserved = BudgetUsageTotals.reserved(liveReserved);
    Map<io.github.aililuola.mathproofmesh.orchestration.BudgetBucket, BudgetUsageTotals> buckets =
        new EnumMap<>(io.github.aililuola.mathproofmesh.orchestration.BudgetBucket.class);
    buckets.putAll(envelopes.usageSnapshot().committedByBucket());
    return new BudgetStateSnapshot(
        runId,
        authorityHash,
        epochId,
        round,
        configHash,
        pricing.pricingHash(),
        currentPathCount,
        committed,
        reserved,
        buckets,
        BudgetUsageTotals.reserved(finishReserve),
        paths,
        zeroGain,
        null);
  }

  void recordGain(CertifiedGainReceipt receipt, TargetMechanismKey key, int exhaustAt) {
    Objects.requireNonNull(receipt, "receipt");
    if (gains.stream().noneMatch(value -> value.receiptHash().equals(receipt.receiptHash()))) {
      gains.add(receipt);
      gains.sort(Comparator.comparing(CertifiedGainReceipt::epochId));
      zeroGain = zeroGain.record(key, receipt.meaningfulGain(), exhaustAt);
    }
  }

  void restore(
      int checkpointSchema,
      UsageTotals legacyUsage,
      BudgetDecisionSnapshot decisions,
      BudgetEnvelopeSnapshot envelopeSnapshot,
      BudgetReservationSnapshot reservations,
      BudgetUsageSnapshot usageSnapshot,
      PricingSnapshot persistedPricing,
      ZeroGainSnapshot zeroGainSnapshot,
      CertifiedGainSnapshot gainSnapshot) {
    if (persistedPricing != null
        && !persistedPricing.pricingHash().equals(pricing.pricingHash())) {
      throw new IllegalStateException("PRICING_CONFIG_DRIFT");
    }
    manager.restoreDecisionSnapshot(decisions);
    BudgetUsageSnapshot migratedUsage = usageSnapshot;
    if (checkpointSchema < 22) {
      BudgetUsageTotals legacy =
          new BudgetUsageTotals(
              legacyUsage.calls(),
              legacyUsage.inputTokens(),
              legacyUsage.outputTokens(),
              legacyUsage.totalTokens(),
              legacyUsage.costUsd());
      migratedUsage =
          new BudgetUsageSnapshot(
              BudgetUsageSnapshot.CURRENT_SCHEMA_VERSION,
              legacy,
              Map.of(
                  io.github.aililuola.mathproofmesh.orchestration.BudgetBucket.LEGACY_UNCLASSIFIED,
                  legacy));
    }
    envelopes =
        BudgetEnvelopeLedger.restore(
            limit, finishReserve, envelopeSnapshot, reservations, migratedUsage);
    zeroGain = zeroGainSnapshot == null ? ZeroGainState.empty() : zeroGainSnapshot.state();
    gains.clear();
    if (gainSnapshot != null) {
      gains.addAll(gainSnapshot.receipts());
    }
  }

  BudgetDecisionSnapshot decisionSnapshot() {
    return manager.decisionSnapshot();
  }

  BudgetEnvelopeSnapshot envelopeSnapshot() {
    return envelopes.envelopeSnapshot();
  }

  BudgetReservationSnapshot reservationSnapshot() {
    return envelopes.reservationSnapshot();
  }

  BudgetUsageSnapshot usageSnapshot() {
    return envelopes.usageSnapshot();
  }

  ZeroGainSnapshot zeroGainSnapshot() {
    return new ZeroGainSnapshot(ZeroGainSnapshot.CURRENT_SCHEMA_VERSION, zeroGain);
  }

  CertifiedGainSnapshot certifiedGainSnapshot() {
    return new CertifiedGainSnapshot(CertifiedGainSnapshot.CURRENT_SCHEMA_VERSION, gains);
  }

  private static CertifiedGainReceipt gainReceipt(
      GainBaseline before, GainBaseline after, BudgetEnvelopeId envelopeId) {
    return new CertifiedGainReceipt(
        before.epochId() + "-" + envelopeId.value(),
        before.authorityHash(),
        after.authorityHash(),
        Math.max(0, after.verifiedClaims() - before.verifiedClaims()),
        Math.max(0, after.facts() - before.facts()),
        Math.max(0, after.refutedObligations() - before.refutedObligations()),
        Math.max(0, after.closedObligations() - before.closedObligations()),
        before.proofDebt(),
        after.proofDebt(),
        Math.max(0, after.verifiedCheckpoints() - before.verifiedCheckpoints()),
        Math.max(0, after.admittedStrategies() - before.admittedStrategies()),
        false,
        null);
  }

  record GainBaseline(
      String epochId,
      String authorityHash,
      int verifiedClaims,
      int facts,
      int refutedObligations,
      int closedObligations,
      double proofDebt,
      int verifiedCheckpoints,
      int admittedStrategies) {
    GainBaseline {
      epochId = require(epochId, "epochId");
      authorityHash = require(authorityHash, "authorityHash");
    }
  }

  private static PricingSnapshot pricing(SystemConfig config, String configHash) {
    List<AgentConfig> enabled = config.agents().stream().filter(AgentConfig::enabled).toList();
    boolean exempt = enabled.stream().allMatch(agent -> "mock".equals(agent.provider()));
    BigDecimal input =
        enabled.stream()
            .map(agent -> BigDecimal.valueOf(agent.pricing().inputPerMillion()))
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    BigDecimal output =
        enabled.stream()
            .map(agent -> BigDecimal.valueOf(agent.pricing().outputPerMillion()))
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    PricingSnapshot.BillingMode mode =
        exempt
            ? PricingSnapshot.BillingMode.BILLING_EXEMPT
            : input.signum() > 0 && output.signum() > 0
                ? PricingSnapshot.BillingMode.BILLED
                : PricingSnapshot.BillingMode.UNKNOWN;
    return new PricingSnapshot(
        enabled.stream().map(AgentConfig::provider).distinct().sorted().reduce((a, b) -> a + "+" + b).orElse("none"),
        enabled.stream().map(AgentConfig::model).distinct().sorted().reduce((a, b) -> a + "+" + b).orElse("none"),
        exempt ? BigDecimal.ZERO : input,
        exempt ? BigDecimal.ZERO : output,
        mode,
        configHash,
        null);
  }

  private static BudgetResourceVector resourceLimit(SystemConfig config) {
    long tokens =
        config.budget().maxTotalTokens() == null
            ? UNLIMITED_TOKENS
            : config.budget().maxTotalTokens().longValue();
    BigDecimal cost =
        config.budget().maxCostUsd() == null
            ? UNLIMITED_COST
            : BigDecimal.valueOf(config.budget().maxCostUsd());
    return new BudgetResourceVector(
        config.budget().maxTotalCalls(), tokens, tokens, tokens, cost);
  }

  private static ActionCostEstimator.Profile costProfile(SystemConfig config) {
    return new ActionCostEstimator.Profile(
        config.scheduler().widenPathsPerAction(),
        config.continuation().segmentsPerExploreCall(),
        config.continuation().verifyEachDelta(),
        config.continuation().deltaVerifierReplicas(),
        1,
        config.scheduler().includePostActionVerificationInCost() ? 1 : 0,
        config.scheduler().includeMetaReviewInCost() ? 1 : 0,
        1,
        config.budget().baseVerifierReplicas(),
        Math.max(0, config.budget().highRiskVerifierReplicas() - config.budget().baseVerifierReplicas()),
        config.scheduler().verificationCallSafetyMargin(),
        config.budget().baseVerifierReplicas(),
        config.scheduler().reserveRevisionCycles(),
        1,
        config.budget().effectiveEstimatedInputTokensPerCall(),
        stageLimit(config, "strategy_generation", 16_000),
        Math.max(
            stageLimit(config, "proof_continuation", 16_000),
            config.continuation().maxOutputTokensPerSegment()),
        stageLimit(config, "claim_extraction", 12_000),
        maxStageLimit(
            config,
            16_000,
            "structural_verification",
            "checkpoint_verification",
            "detailed_verification",
            "final_verification",
            "blind_structural_verification",
            "blind_detailed_verification"),
        stageLimit(config, "final_revision", 16_000),
        stageLimit(config, "synthesis", 16_000),
        stageLimit(config, "meta_review", 16_000));
  }

  private static BudgetEvidencePolicy evidencePolicy(SystemConfig config) {
    return new BudgetEvidencePolicy(
        config.scheduler().forceWidenWhenAllFailed(),
        config.scheduler().maxExecutionRepairsPerPath(),
        config.scheduler().maxPlanRepairsPerPath(),
        config.scheduler().maxUnknownFailureRepairsPerPath(),
        config.scheduler().allowStrategyFailureRepair(),
        config.scheduler().meaningfulProgressThreshold(),
        config.scheduler().unverifiedProgressDiscount(),
        config.scheduler().uncertainProgressDiscount(),
        config.scheduler().failedProgressDiscount(),
        config.scheduler().structuralFailureProgressCap(),
        config.scheduler().executionFailureProgressCap(),
        config.scheduler().strategyFailureProgressCap(),
        config.scheduler().structuralFailurePenalty(),
        config.scheduler().executionFailurePenalty(),
        config.scheduler().strategyFailurePenalty(),
        config.scheduler().repeatedFailurePenalty());
  }

  private static long stageLimit(SystemConfig config, String stage, int fallback) {
    return config.runtime().stageOutputTokenLimits().getOrDefault(stage, fallback);
  }

  private static long maxStageLimit(SystemConfig config, int fallback, String... stages) {
    long result = fallback;
    for (String stage : stages) {
      result = Math.max(result, stageLimit(config, stage, fallback));
    }
    return result;
  }

  private static BudgetResourceVector clamp(
      BudgetResourceVector requested, BudgetResourceVector limit) {
    long totalCap = Math.min(requested.maxTotalTokens(), limit.maxTotalTokens());
    long input =
        Math.min(
            Math.min(requested.estimatedInputTokens(), limit.estimatedInputTokens()), totalCap);
    long output =
        Math.min(
            Math.min(requested.maxOutputTokens(), limit.maxOutputTokens()), totalCap - input);
    return new BudgetResourceVector(
        Math.min(requested.calls(), limit.calls()),
        input,
        output,
        Math.addExact(input, output),
        requested.maxCostUsd().min(limit.maxCostUsd()));
  }

  private static String require(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
