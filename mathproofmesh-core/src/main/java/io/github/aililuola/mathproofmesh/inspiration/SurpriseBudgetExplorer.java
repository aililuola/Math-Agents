package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.SurpriseBudgetState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Protects finalization, path caps, reservations, and rejection cooldowns. */
public final class SurpriseBudgetExplorer {
  private final InspirationPolicy.SurpriseRules rules;
  private final Map<String, Reservation> reservations = new LinkedHashMap<>();
  private int totalCalls;
  private int finalizationReserve;
  private int usedCalls;
  private int reservedCalls;
  private int rejectionStreak;
  private Integer cooldownUntilRound;

  public SurpriseBudgetExplorer(
      InspirationPolicy.SurpriseRules rules, int totalCalls, int finalizationReserve) {
    this.rules = java.util.Objects.requireNonNull(rules, "rules");
    if (totalCalls < 0 || finalizationReserve < 0 || finalizationReserve > totalCalls) {
      throw new IllegalArgumentException("invalid surprise budget");
    }
    this.totalCalls = totalCalls;
    this.finalizationReserve = finalizationReserve;
  }

  public synchronized Admission reserve(
      String triggerId,
      String taskId,
      int requestedCalls,
      int roundIndex,
      int currentPathCount,
      int maxPaths) {
    if (requestedCalls < 0 || roundIndex < 0 || currentPathCount < 0 || maxPaths < 0) {
      throw new IllegalArgumentException("reservation inputs must be nonnegative");
    }
    String key = reservationKey(triggerId, taskId, requestedCalls, roundIndex);
    Reservation existing = reservations.get(key);
    if (existing != null) {
      return new Admission(true, key, existing.calls(), "existing reservation");
    }
    if (cooldownUntilRound != null && cooldownUntilRound > roundIndex) {
      return new Admission(false, null, 0, "surprise mechanism is cooling down");
    }
    if (maxPaths > 0 && currentPathCount >= maxPaths) {
      return new Admission(false, null, 0, "global path cap reached");
    }
    int fractional =
        (int) Math.floor(Math.max(0, totalCalls - finalizationReserve) * rules.budgetFraction());
    int surpriseCap =
        Math.min(rules.maximumCalls(), Math.max(rules.minimumCalls(), fractional));
    int remaining =
        Math.max(0, totalCalls - finalizationReserve - usedCalls - reservedCalls);
    if (requestedCalls > remaining || requestedCalls > surpriseCap) {
      return new Admission(false, null, 0, "protected or surprise call budget exhausted");
    }
    reservations.put(key, new Reservation(key, requestedCalls, 0, false));
    reservedCalls += requestedCalls;
    return new Admission(true, key, requestedCalls, "scheduler admission granted");
  }

  public synchronized Reservation charge(String reservationId, int calls) {
    Reservation reservation = requiredReservation(reservationId);
    if (calls < 0 || reservation.consumed() + calls > reservation.calls()) {
      throw new IllegalArgumentException("charge exceeds reservation");
    }
    Reservation updated =
        new Reservation(
            reservation.id(),
            reservation.calls(),
            reservation.consumed() + calls,
            reservation.completed());
    reservations.put(reservationId, updated);
    usedCalls += calls;
    reservedCalls -= calls;
    return updated;
  }

  public synchronized Reservation complete(String reservationId) {
    Reservation reservation = requiredReservation(reservationId);
    int release = reservation.calls() - reservation.consumed();
    reservedCalls -= release;
    Reservation updated =
        new Reservation(reservation.id(), reservation.calls(), reservation.consumed(), true);
    reservations.put(reservationId, updated);
    return updated;
  }

  public synchronized void recordReview(boolean accepted, int roundIndex) {
    if (accepted) {
      rejectionStreak = 0;
      return;
    }
    rejectionStreak++;
    if (rejectionStreak >= rules.maxConsecutiveRejections()) {
      cooldownUntilRound = roundIndex + rules.cooldownRounds();
      rejectionStreak = 0;
    }
  }

  public synchronized SurpriseBudgetState state() {
    return new SurpriseBudgetState(
        cooldownUntilRound,
        finalizationReserve,
        rejectionStreak,
        reservedCalls,
        totalCalls,
        usedCalls);
  }

  private Reservation requiredReservation(String id) {
    Reservation result = reservations.get(id);
    if (result == null) {
      throw new IllegalArgumentException("unknown reservation: " + id);
    }
    return result;
  }

  private static String reservationKey(
      String triggerId, String taskId, int requestedCalls, int roundIndex) {
    return "inspiration_budget_"
        + CanonicalJson.stableHash(List.of(triggerId, taskId, requestedCalls, roundIndex))
            .substring(0, 16);
  }

  public record Admission(
      boolean accepted, String reservationId, int reservedCalls, String reason) {}

  public record Reservation(String id, int calls, int consumed, boolean completed) {}
}
