package io.github.aililuola.mathproofmesh.orchestration;

import java.math.BigDecimal;
import java.util.Objects;

/** Committed or reserved usage represented in the same dimensions as an action estimate. */
public record BudgetUsageTotals(
    long calls,
    long inputTokens,
    long outputTokens,
    long totalTokens,
    BigDecimal costUsd) {

  public BudgetUsageTotals {
    if (calls < 0 || inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
      throw new IllegalArgumentException("usage counters must not be negative");
    }
    if (Math.addExact(inputTokens, outputTokens) != totalTokens) {
      throw new IllegalArgumentException("totalTokens must equal inputTokens plus outputTokens");
    }
    costUsd = Objects.requireNonNull(costUsd, "costUsd");
    if (costUsd.signum() < 0) {
      throw new IllegalArgumentException("costUsd must not be negative");
    }
    costUsd = costUsd.signum() == 0 ? BigDecimal.ZERO : costUsd.stripTrailingZeros();
  }

  public static BudgetUsageTotals zero() {
    return new BudgetUsageTotals(0L, 0L, 0L, 0L, BigDecimal.ZERO);
  }

  public static BudgetUsageTotals reserved(BudgetResourceVector resources) {
    Objects.requireNonNull(resources, "resources");
    return new BudgetUsageTotals(
        resources.calls(),
        resources.estimatedInputTokens(),
        resources.maxOutputTokens(),
        resources.maxTotalTokens(),
        resources.maxCostUsd());
  }

  public BudgetUsageTotals plus(BudgetUsageTotals other) {
    Objects.requireNonNull(other, "other");
    return new BudgetUsageTotals(
        Math.addExact(calls, other.calls),
        Math.addExact(inputTokens, other.inputTokens),
        Math.addExact(outputTokens, other.outputTokens),
        Math.addExact(totalTokens, other.totalTokens),
        costUsd.add(other.costUsd));
  }

  public BudgetResourceVector asResourceVector() {
    return new BudgetResourceVector(calls, inputTokens, outputTokens, totalTokens, costUsd);
  }
}
