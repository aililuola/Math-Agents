package io.github.aililuola.mathproofmesh.orchestration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.util.Objects;

/** Conservative multidimensional resource amount used for admission, never actual billing. */
public record BudgetResourceVector(
    long calls,
    long estimatedInputTokens,
    long maxOutputTokens,
    long maxTotalTokens,
    BigDecimal maxCostUsd) {

  public BudgetResourceVector {
    if (calls < 0
        || estimatedInputTokens < 0
        || maxOutputTokens < 0
        || maxTotalTokens < 0) {
      throw new IllegalArgumentException("budget resources must not be negative");
    }
    maxCostUsd = normalize(maxCostUsd, "maxCostUsd");
  }

  public static BudgetResourceVector zero() {
    return new BudgetResourceVector(0L, 0L, 0L, 0L, BigDecimal.ZERO);
  }

  public BudgetResourceVector plus(BudgetResourceVector other) {
    Objects.requireNonNull(other, "other");
    return new BudgetResourceVector(
        Math.addExact(calls, other.calls),
        Math.addExact(estimatedInputTokens, other.estimatedInputTokens),
        Math.addExact(maxOutputTokens, other.maxOutputTokens),
        Math.addExact(maxTotalTokens, other.maxTotalTokens),
        maxCostUsd.add(other.maxCostUsd));
  }

  public BudgetResourceVector minus(BudgetResourceVector other) {
    Objects.requireNonNull(other, "other");
    if (!other.fitsWithin(this)) {
      throw new IllegalArgumentException("budget subtraction would become negative");
    }
    return new BudgetResourceVector(
        calls - other.calls,
        estimatedInputTokens - other.estimatedInputTokens,
        maxOutputTokens - other.maxOutputTokens,
        maxTotalTokens - other.maxTotalTokens,
        maxCostUsd.subtract(other.maxCostUsd));
  }

  public BudgetResourceVector times(long multiplier) {
    if (multiplier < 0L) {
      throw new IllegalArgumentException("budget multiplier must not be negative");
    }
    return new BudgetResourceVector(
        Math.multiplyExact(calls, multiplier),
        Math.multiplyExact(estimatedInputTokens, multiplier),
        Math.multiplyExact(maxOutputTokens, multiplier),
        Math.multiplyExact(maxTotalTokens, multiplier),
        maxCostUsd.multiply(BigDecimal.valueOf(multiplier)));
  }

  public boolean fitsWithin(BudgetResourceVector limit) {
    Objects.requireNonNull(limit, "limit");
    return calls <= limit.calls
        && estimatedInputTokens <= limit.estimatedInputTokens
        && maxOutputTokens <= limit.maxOutputTokens
        && maxTotalTokens <= limit.maxTotalTokens
        && maxCostUsd.compareTo(limit.maxCostUsd) <= 0;
  }

  @JsonIgnore
  public boolean isZero() {
    return calls == 0L
        && estimatedInputTokens == 0L
        && maxOutputTokens == 0L
        && maxTotalTokens == 0L
        && maxCostUsd.signum() == 0;
  }

  private static BigDecimal normalize(BigDecimal value, String field) {
    BigDecimal result = Objects.requireNonNull(value, field);
    if (result.signum() < 0) {
      throw new IllegalArgumentException(field + " must not be negative");
    }
    return result.signum() == 0 ? BigDecimal.ZERO : result.stripTrailingZeros();
  }
}
