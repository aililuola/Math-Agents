package io.github.aililuola.mathproofmesh.desktop.benchmark;

import io.github.aililuola.mathproofmesh.orchestration.PricingSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/** Computes the immutable worst-case cost before any real benchmark inference is allowed. */
public final class OlympiadBenchmarkCostEstimator {
  private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);

  private OlympiadBenchmarkCostEstimator() {}

  public static Estimate estimate(
      List<OlympiadBenchmarkPlan.RunSpec> schedule, PricingSnapshot pricing) {
    Objects.requireNonNull(schedule, "schedule");
    Objects.requireNonNull(pricing, "pricing");
    if (schedule.size() != 34 || !pricing.usableWithCostCap()) {
      throw new IllegalArgumentException("complete schedule and usable pricing are required");
    }
    long calls = 0L;
    long tokens = 0L;
    for (OlympiadBenchmarkPlan.RunSpec run : schedule) {
      calls = Math.addExact(calls, run.tier().maximumCalls());
      tokens = Math.addExact(tokens, run.tier().maximumTokens());
    }
    BigDecimal maximumRate = pricing.inputPerMillion().max(pricing.outputPerMillion());
    BigDecimal cost =
        BigDecimal.valueOf(tokens)
            .multiply(maximumRate)
            .divide(ONE_MILLION, 12, RoundingMode.CEILING)
            .stripTrailingZeros();
    if (pricing.billingMode() == PricingSnapshot.BillingMode.BILLING_EXEMPT) {
      cost = BigDecimal.ZERO;
    }
    return new Estimate(schedule.size(), calls, tokens, cost, pricing.pricingHash());
  }

  public record Estimate(
      int runs, long maximumCalls, long maximumTokens, BigDecimal maximumCostUsd, String pricingHash) {
    public Estimate {
      if (runs != 34 || maximumCalls < 1L || maximumTokens < 1L) {
        throw new IllegalArgumentException("invalid benchmark cost estimate");
      }
      maximumCostUsd = Objects.requireNonNull(maximumCostUsd, "maximumCostUsd");
      pricingHash = Objects.requireNonNull(pricingHash, "pricingHash");
    }

    public boolean coveredBy(BigDecimal hardCapUsd) {
      return hardCapUsd != null
          && hardCapUsd.signum() > 0
          && hardCapUsd.compareTo(maximumCostUsd) >= 0;
    }
  }
}
