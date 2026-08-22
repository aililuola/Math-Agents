package io.github.aililuola.mathproofmesh.orchestration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical decision input shared by Desktop, Temporal replay, and checkpoint recovery. */
public record BudgetStateSnapshot(
    String runId,
    String authorityStateHash,
    String researchEpochId,
    long adaptiveRound,
    String configHash,
    String pricingHash,
    int currentPathCount,
    BudgetUsageTotals committedUsage,
    BudgetUsageTotals reservedUsage,
    Map<BudgetBucket, BudgetUsageTotals> bucketUsage,
    BudgetUsageTotals finishReserve,
    List<PathBudgetStats> pathStats,
    ZeroGainState zeroGainState,
    String snapshotHash) {

  public BudgetStateSnapshot {
    runId = required(runId, "runId");
    authorityStateHash = required(authorityStateHash, "authorityStateHash");
    researchEpochId = required(researchEpochId, "researchEpochId");
    configHash = required(configHash, "configHash");
    pricingHash = required(pricingHash, "pricingHash");
    if (adaptiveRound < 0 || currentPathCount < 0) {
      throw new IllegalArgumentException("budget round and path count must not be negative");
    }
    committedUsage = committedUsage == null ? BudgetUsageTotals.zero() : committedUsage;
    reservedUsage = reservedUsage == null ? BudgetUsageTotals.zero() : reservedUsage;
    finishReserve = finishReserve == null ? BudgetUsageTotals.zero() : finishReserve;
    Map<BudgetBucket, BudgetUsageTotals> orderedBuckets = new LinkedHashMap<>();
    if (bucketUsage != null) {
      bucketUsage.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              entry ->
                  orderedBuckets.put(
                      java.util.Objects.requireNonNull(entry.getKey(), "bucket"),
                      entry.getValue() == null ? BudgetUsageTotals.zero() : entry.getValue()));
    }
    bucketUsage = Collections.unmodifiableMap(orderedBuckets);
    List<PathBudgetStats> sortedPaths =
        new ArrayList<>(pathStats == null ? List.of() : pathStats);
    sortedPaths.sort(
        Comparator.comparing(PathBudgetStats::strategyId)
            .thenComparing(PathBudgetStats::routeId)
            .thenComparing(PathBudgetStats::latestAttemptId));
    pathStats = List.copyOf(sortedPaths);
    zeroGainState = zeroGainState == null ? ZeroGainState.empty() : zeroGainState;
    String expected = hash(
        runId,
        authorityStateHash,
        researchEpochId,
        adaptiveRound,
        configHash,
        pricingHash,
        currentPathCount,
        committedUsage,
        reservedUsage,
        bucketUsage,
        finishReserve,
        pathStats,
        zeroGainState);
    snapshotHash = snapshotHash == null || snapshotHash.isBlank() ? expected : snapshotHash.strip();
    if (!sameHash(expected, snapshotHash)) {
      throw new IllegalArgumentException("budget state snapshot hash mismatch");
    }
  }

  private static String hash(
      String runId,
      String authorityStateHash,
      String researchEpochId,
      long adaptiveRound,
      String configHash,
      String pricingHash,
      int currentPathCount,
      BudgetUsageTotals committedUsage,
      BudgetUsageTotals reservedUsage,
      Map<BudgetBucket, BudgetUsageTotals> bucketUsage,
      BudgetUsageTotals finishReserve,
      List<PathBudgetStats> pathStats,
      ZeroGainState zeroGainState) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("run_id", runId);
    payload.put("authority_state_hash", authorityStateHash);
    payload.put("research_epoch_id", researchEpochId);
    payload.put("adaptive_round", adaptiveRound);
    payload.put("config_hash", configHash);
    payload.put("pricing_hash", pricingHash);
    payload.put("current_path_count", currentPathCount);
    payload.put("committed_usage", committedUsage);
    payload.put("reserved_usage", reservedUsage);
    payload.put("bucket_usage", bucketUsage);
    payload.put("finish_reserve", finishReserve);
    payload.put("path_stats", pathStats);
    payload.put("zero_gain_state_hash", zeroGainState.stateHash());
    return CanonicalJson.stableHash(payload);
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }

  private static boolean sameHash(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }
}
