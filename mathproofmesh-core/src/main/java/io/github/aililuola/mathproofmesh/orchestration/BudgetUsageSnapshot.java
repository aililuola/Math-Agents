package io.github.aililuola.mathproofmesh.orchestration;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public record BudgetUsageSnapshot(
    int schemaVersion,
    BudgetUsageTotals committed,
    Map<BudgetBucket, BudgetUsageTotals> committedByBucket) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public BudgetUsageSnapshot {
    if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported budget usage snapshot schema");
    }
    committed = committed == null ? BudgetUsageTotals.zero() : committed;
    Map<BudgetBucket, BudgetUsageTotals> ordered = new EnumMap<>(BudgetBucket.class);
    if (committedByBucket != null) {
      committedByBucket.forEach(
          (bucket, usage) ->
              ordered.put(
                  java.util.Objects.requireNonNull(bucket, "bucket"),
                  java.util.Objects.requireNonNull(usage, "usage")));
    }
    committedByBucket = Collections.unmodifiableMap(ordered);
  }

  public static BudgetUsageSnapshot empty() {
    return new BudgetUsageSnapshot(CURRENT_SCHEMA_VERSION, BudgetUsageTotals.zero(), Map.of());
  }
}
