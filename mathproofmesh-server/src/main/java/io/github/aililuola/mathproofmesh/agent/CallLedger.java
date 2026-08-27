package io.github.aililuola.mathproofmesh.agent;

import io.github.aililuola.mathproofmesh.config.PricingConfig;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.UsageTotals;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Atomic budget reservations plus a provider-call-derived usage total. */
public final class CallLedger {
  private final long maxCalls;
  private final Long maxTokens;
  private final BigDecimal maxCostUsd;
  private final Map<String, Reservation> reservations = new LinkedHashMap<>();

  private long committedCalls;
  private long committedInputTokens;
  private long committedOutputTokens;
  private BigDecimal committedCost = BigDecimal.ZERO;
  private double committedLatencyMs;
  private long reservedCalls;
  private long reservedTokens;
  private BigDecimal reservedCost = BigDecimal.ZERO;

  public CallLedger(long maxCalls, Long maxTokens, BigDecimal maxCostUsd) {
    if (maxCalls < 1) {
      throw new IllegalArgumentException("maxCalls must be positive");
    }
    if (maxTokens != null && maxTokens < 1) {
      throw new IllegalArgumentException("maxTokens must be positive");
    }
    if (maxCostUsd != null && maxCostUsd.signum() <= 0) {
      throw new IllegalArgumentException("maxCostUsd must be positive");
    }
    this.maxCalls = maxCalls;
    this.maxTokens = maxTokens;
    this.maxCostUsd = maxCostUsd;
  }

  public synchronized Reservation reserve(
      String stage,
      String bucket,
      long expectedTokens,
      BigDecimal expectedCostUsd) {
    return reserveWithId(
        UUID.randomUUID().toString(), stage, bucket, expectedTokens, expectedCostUsd);
  }

  public synchronized Reservation reserveWithId(
      String reservationId,
      String stage,
      String bucket,
      long expectedTokens,
      BigDecimal expectedCostUsd) {
    String safeReservationId = requireText(reservationId, "reservationId");
    String safeStage = requireText(stage, "stage");
    String safeBucket = requireText(bucket, "bucket");
    if (expectedTokens < 0) {
      throw new IllegalArgumentException("expectedTokens must not be negative");
    }
    BigDecimal expectedCost =
        Objects.requireNonNull(expectedCostUsd, "expectedCostUsd");
    if (expectedCost.signum() < 0) {
      throw new IllegalArgumentException("expectedCostUsd must not be negative");
    }
    Reservation prior = reservations.get(safeReservationId);
    if (prior != null) {
      if (!prior.stage().equals(safeStage)
          || !prior.bucket().equals(safeBucket)
          || prior.expectedTokens() != expectedTokens
          || prior.expectedCostUsd().compareTo(expectedCost) != 0) {
        throw new IllegalStateException("stable budget reservation changed resources");
      }
      return prior;
    }
    if (committedCalls + reservedCalls + 1 > maxCalls) {
      throw new BudgetExhaustedError("provider call budget exhausted");
    }
    if (maxTokens != null
        && committedInputTokens
                + committedOutputTokens
                + reservedTokens
                + expectedTokens
            > maxTokens) {
      throw new BudgetExhaustedError("provider token budget exhausted");
    }
    if (maxCostUsd != null
        && committedCost.add(reservedCost).add(expectedCost).compareTo(maxCostUsd)
            > 0) {
      throw new BudgetExhaustedError("provider cost budget exhausted");
    }
    Reservation reservation =
        new Reservation(
            safeReservationId, safeStage, safeBucket, expectedTokens, expectedCost);
    reservations.put(reservation.id(), reservation);
    reservedCalls++;
    reservedTokens = Math.addExact(reservedTokens, expectedTokens);
    reservedCost = reservedCost.add(expectedCost);
    return reservation;
  }

  public synchronized UsageTotals commit(
      String reservationId, LLMResponse response, PricingConfig pricing) {
    Objects.requireNonNull(response, "response");
    Objects.requireNonNull(pricing, "pricing");
    Reservation reservation = remove(reservationId);
    releaseCounters(reservation);
    BigDecimal cost =
        tokenCost(
            response.inputTokens(),
            response.outputTokens(),
            pricing.inputPerMillion(),
            pricing.outputPerMillion());
    committedCalls++;
    committedInputTokens =
        Math.addExact(committedInputTokens, response.inputTokens());
    committedOutputTokens =
        Math.addExact(committedOutputTokens, response.outputTokens());
    committedCost = committedCost.add(cost);
    committedLatencyMs += response.latencyMs();
    return totals();
  }

  public synchronized void release(String reservationId) {
    if (reservationId == null) {
      return;
    }
    Reservation reservation = reservations.remove(reservationId);
    if (reservation != null) {
      releaseCounters(reservation);
    }
  }

  public synchronized UsageTotals commitAmbiguous(
      String reservationId, BigDecimal possibleCostUsd) {
    Reservation reservation = remove(reservationId);
    releaseCounters(reservation);
    BigDecimal cost =
        Objects.requireNonNull(possibleCostUsd, "possibleCostUsd");
    if (cost.signum() < 0) {
      throw new IllegalArgumentException("possibleCostUsd must not be negative");
    }
    committedCalls++;
    // The remote side may have consumed the full reservation. Keep that exposure until durable
    // provider evidence proves a smaller usage or proves that dispatch never happened.
    committedOutputTokens =
        Math.addExact(committedOutputTokens, reservation.expectedTokens());
    committedCost = committedCost.add(cost);
    return totals();
  }

  public synchronized UsageTotals totals() {
    return new UsageTotals(
        committedCalls,
        committedInputTokens,
        committedOutputTokens,
        committedCost,
        committedLatencyMs);
  }

  /** Restores committed usage into a freshly-created ledger before a durable run resumes. */
  public synchronized void restoreCommittedUsage(UsageTotals persisted) {
    Objects.requireNonNull(persisted, "persisted");
    if (committedCalls != 0L
        || committedInputTokens != 0L
        || committedOutputTokens != 0L
        || committedCost.signum() != 0
        || committedLatencyMs != 0.0d
        || !reservations.isEmpty()) {
      throw new IllegalStateException("committed usage can only be restored into a fresh ledger");
    }
    committedCalls = persisted.calls();
    committedInputTokens = persisted.inputTokens();
    committedOutputTokens = persisted.outputTokens();
    committedCost = persisted.costUsd();
    committedLatencyMs = persisted.latencyMs();
  }

  public synchronized long remainingCalls() {
    return Math.max(0L, maxCalls - committedCalls - reservedCalls);
  }

  public synchronized long remainingTokens() {
    return maxTokens == null
        ? Long.MAX_VALUE
        : Math.max(
            0L,
            maxTokens
                - committedInputTokens
                - committedOutputTokens
                - reservedTokens);
  }

  public synchronized BigDecimal remainingCost() {
    return maxCostUsd == null
        ? new BigDecimal("1E+100")
        : maxCostUsd.subtract(committedCost).subtract(reservedCost).max(BigDecimal.ZERO);
  }

  public synchronized Reconciliation reconcile(UsageTotals persisted) {
    Objects.requireNonNull(persisted, "persisted");
    UsageTotals runtime = totals();
    boolean matches =
        runtime.calls() == persisted.calls()
            && runtime.totalTokens() == persisted.totalTokens()
            && runtime.costUsd().compareTo(persisted.costUsd()) == 0;
    return new Reconciliation(runtime, persisted, matches);
  }

  private Reservation remove(String reservationId) {
    Reservation reservation = reservations.remove(reservationId);
    if (reservation == null) {
      throw new IllegalArgumentException("unknown budget reservation");
    }
    return reservation;
  }

  private void releaseCounters(Reservation reservation) {
    reservedCalls--;
    reservedTokens -= reservation.expectedTokens();
    reservedCost = reservedCost.subtract(reservation.expectedCostUsd());
  }

  public static BigDecimal tokenCost(
      long inputTokens,
      long outputTokens,
      double inputPerMillion,
      double outputPerMillion) {
    BigDecimal million = BigDecimal.valueOf(1_000_000L);
    return BigDecimal.valueOf(inputTokens)
        .multiply(BigDecimal.valueOf(inputPerMillion))
        .divide(million, 12, RoundingMode.HALF_UP)
        .add(
            BigDecimal.valueOf(outputTokens)
                .multiply(BigDecimal.valueOf(outputPerMillion))
                .divide(million, 12, RoundingMode.HALF_UP))
        .stripTrailingZeros();
  }

  private static String requireText(String value, String label) {
    Objects.requireNonNull(value, label);
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }

  public record Reservation(
      String id,
      String stage,
      String bucket,
      long expectedTokens,
      BigDecimal expectedCostUsd) {}

  public record Reconciliation(
      UsageTotals runtime, UsageTotals persisted, boolean matches) {}
}
