package io.github.aililuola.mathproofmesh.orchestration;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Atomic action envelopes above the physical CallLedger hard-cap authority. */
@SuppressFBWarnings(
    value = {"USO_UNSAFE_METHOD_SYNCHRONIZATION", "IS2_INCONSISTENT_SYNC"},
    justification =
        "Public operations serialize ledger state; restore writes occur only before publication.")
public final class BudgetEnvelopeLedger {
  private final BudgetResourceVector configuredLimit;
  private final BudgetResourceVector finishReserve;
  private final Map<String, BudgetEnvelope> envelopes = new LinkedHashMap<>();
  private final Map<String, BudgetPhysicalReservation> reservations = new LinkedHashMap<>();
  private final Map<BudgetBucket, BudgetUsageTotals> committedByBucket =
      new EnumMap<>(BudgetBucket.class);
  private BudgetUsageTotals committed = BudgetUsageTotals.zero();
  private boolean overrun;

  public BudgetEnvelopeLedger(
      BudgetResourceVector configuredLimit, BudgetResourceVector finishReserve) {
    this.configuredLimit = Objects.requireNonNull(configuredLimit, "configuredLimit");
    this.finishReserve = Objects.requireNonNull(finishReserve, "finishReserve");
    if (!finishReserve.fitsWithin(configuredLimit)) {
      throw new IllegalArgumentException("finish reserve exceeds configured budget");
    }
  }

  public static BudgetEnvelopeLedger restore(
      BudgetResourceVector configuredLimit,
      BudgetResourceVector finishReserve,
      BudgetEnvelopeSnapshot envelopeSnapshot,
      BudgetReservationSnapshot reservationSnapshot,
      BudgetUsageSnapshot usageSnapshot) {
    BudgetEnvelopeLedger ledger = new BudgetEnvelopeLedger(configuredLimit, finishReserve);
    BudgetEnvelopeSnapshot envelopes =
        envelopeSnapshot == null ? BudgetEnvelopeSnapshot.empty() : envelopeSnapshot;
    BudgetReservationSnapshot reservations =
        reservationSnapshot == null ? BudgetReservationSnapshot.empty() : reservationSnapshot;
    BudgetUsageSnapshot usage =
        usageSnapshot == null ? BudgetUsageSnapshot.empty() : usageSnapshot;
    for (BudgetEnvelope envelope : envelopes.envelopes()) {
      if (ledger.envelopes.putIfAbsent(envelope.envelopeId().value(), envelope) != null) {
        throw new IllegalArgumentException("duplicate budget envelope in snapshot");
      }
      ledger.overrun |= envelope.status() == BudgetEnvelopeStatus.OVERRUN;
    }
    for (BudgetPhysicalReservation reservation : reservations.reservations()) {
      if (!ledger.envelopes.containsKey(reservation.envelopeId().value())) {
        throw new IllegalArgumentException("budget reservation references a missing envelope");
      }
      if (ledger.reservations.putIfAbsent(reservation.reservationId(), reservation) != null) {
        throw new IllegalArgumentException("duplicate physical reservation in snapshot");
      }
    }
    ledger.committed = usage.committed();
    ledger.committedByBucket.putAll(usage.committedByBucket());
    ledger.recoverInterruptedReservations();
    BudgetResourceVector restoredExposure =
        ledger.committed.asResourceVector()
            .plus(ledger.quarantinedUsage())
            .plus(ledger.liveReserved());
    ledger.overrun |= !restoredExposure.fitsWithin(configuredLimit);
    return ledger;
  }

  public synchronized BudgetEnvelope reserve(
      String runId,
      String epochId,
      String workItemId,
      String actionDecisionId,
      BudgetBucket bucket,
      BudgetResourceVector resources) {
    Objects.requireNonNull(bucket, "bucket");
    Objects.requireNonNull(resources, "resources");
    BudgetEnvelopeId id =
        BudgetEnvelopeId.create(runId, epochId, workItemId, actionDecisionId, bucket);
    BudgetEnvelope prior = envelopes.get(id.value());
    if (prior != null) {
      if (!prior.allocated().equals(resources)) {
        throw new IllegalStateException("stable envelope id was reused with different resources");
      }
      return prior;
    }
    if (overrun) {
      throw new IllegalStateException("ACTUAL_USAGE_OVERRUN");
    }
    BudgetResourceVector needed = liveReserved().plus(resources);
    BudgetResourceVector used =
        committed.asResourceVector().plus(quarantinedUsage()).plus(needed);
    if (exploration(bucket)) {
      used = used.plus(finishReserve);
    }
    if (!used.fitsWithin(configuredLimit)) {
      throw new IllegalStateException("ACTION_BUDGET_ENVELOPE_EXHAUSTED");
    }
    BudgetEnvelope envelope =
        new BudgetEnvelope(
            id,
            runId,
            epochId,
            workItemId,
            actionDecisionId,
            bucket,
            resources,
            BudgetResourceVector.zero(),
            BudgetEnvelopeStatus.RESERVED,
            0L,
            null);
    envelopes.put(id.value(), envelope);
    return envelope;
  }

  public synchronized BudgetEnvelope activate(BudgetEnvelopeId id) {
    BudgetEnvelope current = requireEnvelope(id);
    if (current.status() == BudgetEnvelopeStatus.ACTIVE) {
      return current;
    }
    requireStatus(current, BudgetEnvelopeStatus.RESERVED);
    return replace(current, current.consumed(), BudgetEnvelopeStatus.ACTIVE);
  }

  public synchronized BudgetPhysicalReservation reservePhysical(
      BudgetEnvelopeId envelopeId,
      String providerCallId,
      String idempotencyKey,
      String stage,
      int physicalCallOrdinal,
      String pricingHash,
      BudgetResourceVector resources) {
    return reservePhysical(
        envelopeId,
        providerCallId,
        idempotencyKey,
        stage,
        physicalCallOrdinal,
        pricingHash,
        resources,
        BudgetResourceVector.zero());
  }

  /** Reserves one physical call while retaining resources for mandatory work in the action. */
  public synchronized BudgetPhysicalReservation reservePhysical(
      BudgetEnvelopeId envelopeId,
      String providerCallId,
      String idempotencyKey,
      String stage,
      int physicalCallOrdinal,
      String pricingHash,
      BudgetResourceVector resources,
      BudgetResourceVector protectedReserve) {
    Objects.requireNonNull(resources, "resources");
    Objects.requireNonNull(protectedReserve, "protectedReserve");
    BudgetEnvelope envelope = requireEnvelope(envelopeId);
    if (envelope.status() != BudgetEnvelopeStatus.ACTIVE
        && envelope.status() != BudgetEnvelopeStatus.RESERVED) {
      throw new IllegalStateException("budget envelope is not active");
    }
    String reservationId =
        "budget-reservation-"
            + CanonicalJson.stableHash(
                    List.of(
                        envelope.runId(),
                        envelope.epochId(),
                        envelope.workItemId(),
                        envelope.actionDecisionId(),
                        stage,
                        physicalCallOrdinal,
                        providerCallId,
                        idempotencyKey,
                        pricingHash))
                .substring(0, 32);
    BudgetPhysicalReservation prior = reservations.get(reservationId);
    if (prior != null) {
      if (!prior.resources().equals(resources)) {
        throw new IllegalStateException("stable physical reservation changed resources");
      }
      return prior;
    }
    BudgetResourceVector remaining = unassigned(envelope);
    if (!resources.plus(protectedReserve).fitsWithin(remaining)) {
      if (!protectedReserve.isZero()) {
        throw new IllegalStateException("ACTION_ENVELOPE_PROTECTED_RESERVE");
      }
      throw new IllegalStateException(
          "physical reservation exceeds action envelope: requested="
              + resources
              + ", remaining="
              + remaining);
    }
    BudgetPhysicalReservation reservation =
        new BudgetPhysicalReservation(
            reservationId,
            envelopeId,
            providerCallId,
            idempotencyKey,
            stage,
            physicalCallOrdinal,
            pricingHash,
            resources,
            BudgetPhysicalReservation.Status.RESERVED,
            0L);
    reservations.put(reservationId, reservation);
    return reservation;
  }

  public synchronized BudgetPhysicalReservation markDispatched(String reservationId) {
    BudgetPhysicalReservation current = requireReservation(reservationId);
    if (current.status() == BudgetPhysicalReservation.Status.DISPATCHED) {
      return current;
    }
    if (current.status() != BudgetPhysicalReservation.Status.RESERVED) {
      throw new IllegalStateException("physical reservation cannot be dispatched");
    }
    return replace(current, current.resources(), BudgetPhysicalReservation.Status.DISPATCHED);
  }

  public synchronized BudgetPhysicalReservation settle(
      String reservationId, BudgetResourceVector actual) {
    Objects.requireNonNull(actual, "actual");
    BudgetPhysicalReservation current = requireReservation(reservationId);
    if (current.status() == BudgetPhysicalReservation.Status.SETTLED) {
      if (!current.resources().equals(actual)) {
        throw new IllegalStateException("physical call was already settled with different usage");
      }
      return current;
    }
    if (current.status() != BudgetPhysicalReservation.Status.DISPATCHED) {
      throw new IllegalStateException("only a dispatched physical call can settle");
    }
    BudgetEnvelope envelope = requireEnvelope(current.envelopeId());
    BudgetResourceVector nextConsumed = envelope.consumed().plus(actual);
    BudgetEnvelopeStatus nextStatus =
        !nextConsumed.fitsWithin(envelope.allocated())
            ? BudgetEnvelopeStatus.OVERRUN
            : hasQuarantinedChild(envelope.envelopeId())
                ? BudgetEnvelopeStatus.QUARANTINED_UNCERTAIN
                : BudgetEnvelopeStatus.ACTIVE;
    if (nextStatus == BudgetEnvelopeStatus.OVERRUN) {
      overrun = true;
    }
    replace(envelope, nextConsumed, nextStatus);
    BudgetUsageTotals actualUsage = BudgetUsageTotals.reserved(actual);
    committed = committed.plus(actualUsage);
    committedByBucket.merge(envelope.bucket(), actualUsage, BudgetUsageTotals::plus);
    return replace(current, actual, BudgetPhysicalReservation.Status.SETTLED);
  }

  public synchronized BudgetPhysicalReservation quarantineUncertain(String reservationId) {
    BudgetPhysicalReservation current = requireReservation(reservationId);
    if (current.status() == BudgetPhysicalReservation.Status.QUARANTINED_UNCERTAIN) {
      return current;
    }
    if (current.status() != BudgetPhysicalReservation.Status.DISPATCHED) {
      throw new IllegalStateException("only dispatched usage can be quarantined");
    }
    BudgetEnvelope envelope = requireEnvelope(current.envelopeId());
    replace(
        envelope,
        envelope.consumed().plus(current.resources()),
        BudgetEnvelopeStatus.QUARANTINED_UNCERTAIN);
    return replace(
        current,
        current.resources(),
        BudgetPhysicalReservation.Status.QUARANTINED_UNCERTAIN);
  }

  /** Reconciles an uncertain dispatch only after durable provider evidence supplies actual usage. */
  public synchronized BudgetPhysicalReservation settleQuarantined(
      String reservationId, BudgetResourceVector actual) {
    Objects.requireNonNull(actual, "actual");
    BudgetPhysicalReservation current = requireReservation(reservationId);
    if (current.status() != BudgetPhysicalReservation.Status.QUARANTINED_UNCERTAIN) {
      throw new IllegalStateException("only quarantined usage can be reconciled");
    }
    BudgetEnvelope envelope = requireEnvelope(current.envelopeId());
    BudgetResourceVector nextConsumed =
        envelope.consumed().minus(current.resources()).plus(actual);
    BudgetEnvelopeStatus nextStatus =
        !nextConsumed.fitsWithin(envelope.allocated())
            ? BudgetEnvelopeStatus.OVERRUN
            : hasOtherQuarantinedChild(envelope.envelopeId(), reservationId)
                ? BudgetEnvelopeStatus.QUARANTINED_UNCERTAIN
                : BudgetEnvelopeStatus.ACTIVE;
    if (nextStatus == BudgetEnvelopeStatus.OVERRUN) {
      overrun = true;
    }
    replace(envelope, nextConsumed, nextStatus);
    BudgetUsageTotals actualUsage = BudgetUsageTotals.reserved(actual);
    committed = committed.plus(actualUsage);
    committedByBucket.merge(envelope.bucket(), actualUsage, BudgetUsageTotals::plus);
    return replace(current, actual, BudgetPhysicalReservation.Status.SETTLED);
  }

  /** Releases an uncertain dispatch only after durable evidence proves it never left the client. */
  public synchronized BudgetPhysicalReservation releaseQuarantinedBeforeDispatch(
      String reservationId) {
    BudgetPhysicalReservation current = requireReservation(reservationId);
    if (current.status() != BudgetPhysicalReservation.Status.QUARANTINED_UNCERTAIN) {
      throw new IllegalStateException("only quarantined usage can be reconciled");
    }
    BudgetEnvelope envelope = requireEnvelope(current.envelopeId());
    BudgetResourceVector nextConsumed = envelope.consumed().minus(current.resources());
    BudgetEnvelopeStatus nextStatus =
        hasOtherQuarantinedChild(envelope.envelopeId(), reservationId)
            ? BudgetEnvelopeStatus.QUARANTINED_UNCERTAIN
            : BudgetEnvelopeStatus.ACTIVE;
    replace(envelope, nextConsumed, nextStatus);
    return replace(current, current.resources(), BudgetPhysicalReservation.Status.RELEASED);
  }

  public synchronized BudgetPhysicalReservation releaseBeforeDispatch(String reservationId) {
    BudgetPhysicalReservation current = requireReservation(reservationId);
    if (current.status() == BudgetPhysicalReservation.Status.RELEASED) {
      return current;
    }
    if (current.status() != BudgetPhysicalReservation.Status.RESERVED) {
      throw new IllegalStateException("a dispatched reservation cannot be released as unused");
    }
    return replace(current, current.resources(), BudgetPhysicalReservation.Status.RELEASED);
  }

  public synchronized BudgetEnvelope finish(BudgetEnvelopeId id) {
    BudgetEnvelope envelope = requireEnvelope(id);
    boolean openChild =
        reservations.values().stream()
            .filter(value -> value.envelopeId().equals(id))
            .anyMatch(
                value ->
                    value.status() == BudgetPhysicalReservation.Status.RESERVED
                        || value.status() == BudgetPhysicalReservation.Status.DISPATCHED);
    if (openChild) {
      throw new IllegalStateException("cannot finish an envelope with an active physical call");
    }
    if (envelope.status() == BudgetEnvelopeStatus.QUARANTINED_UNCERTAIN
        || envelope.status() == BudgetEnvelopeStatus.OVERRUN) {
      return envelope;
    }
    return replace(
        envelope,
        envelope.consumed(),
        envelope.consumed().isZero()
            ? BudgetEnvelopeStatus.RELEASED
            : BudgetEnvelopeStatus.SETTLED);
  }

  public synchronized BudgetEnvelope cancelBeforeDispatch(BudgetEnvelopeId id) {
    BudgetEnvelope envelope = requireEnvelope(id);
    if (envelope.status() != BudgetEnvelopeStatus.RESERVED || !envelope.consumed().isZero()) {
      throw new IllegalStateException("only an unused reserved envelope can be cancelled");
    }
    return replace(envelope, envelope.consumed(), BudgetEnvelopeStatus.CANCELLED_BEFORE_DISPATCH);
  }

  public synchronized BudgetResourceVector available() {
    BudgetResourceVector used =
        committed.asResourceVector().plus(quarantinedUsage()).plus(liveReserved());
    return used.fitsWithin(configuredLimit)
        ? configuredLimit.minus(used)
        : BudgetResourceVector.zero();
  }

  /**
   * Returns action resources not yet represented by physical committed usage.
   *
   * <p>Settled and quarantined child usage is already retained by {@code CallLedger}; counting the
   * entire parent allocation here would charge it twice in the canonical budget snapshot.
   */
  public synchronized BudgetResourceVector reservedResources() {
    return liveReserved();
  }

  public synchronized BudgetResourceVector remaining(BudgetEnvelopeId envelopeId) {
    return unassigned(requireEnvelope(envelopeId));
  }

  public synchronized BudgetEnvelopeSnapshot envelopeSnapshot() {
    return new BudgetEnvelopeSnapshot(
        BudgetEnvelopeSnapshot.CURRENT_SCHEMA_VERSION,
        envelopes.values().stream()
            .sorted(Comparator.comparing(value -> value.envelopeId().value()))
            .toList());
  }

  public synchronized BudgetReservationSnapshot reservationSnapshot() {
    return new BudgetReservationSnapshot(
        BudgetReservationSnapshot.CURRENT_SCHEMA_VERSION,
        reservations.values().stream()
            .sorted(Comparator.comparing(BudgetPhysicalReservation::reservationId))
            .toList());
  }

  public synchronized BudgetUsageSnapshot usageSnapshot() {
    return new BudgetUsageSnapshot(
        BudgetUsageSnapshot.CURRENT_SCHEMA_VERSION, committed, committedByBucket);
  }

  public synchronized boolean overrun() {
    return overrun;
  }

  private void recoverInterruptedReservations() {
    for (BudgetPhysicalReservation reservation : List.copyOf(reservations.values())) {
      if (reservation.status() == BudgetPhysicalReservation.Status.RESERVED) {
        replace(
            reservation,
            reservation.resources(),
            BudgetPhysicalReservation.Status.RELEASED);
      } else if (reservation.status() == BudgetPhysicalReservation.Status.DISPATCHED) {
        quarantineUncertain(reservation.reservationId());
      }
    }
  }

  private BudgetResourceVector liveReserved() {
    BudgetResourceVector result = BudgetResourceVector.zero();
    for (BudgetEnvelope envelope : envelopes.values()) {
      if (envelope.status() == BudgetEnvelopeStatus.RESERVED
          || envelope.status() == BudgetEnvelopeStatus.ACTIVE) {
        result = result.plus(envelope.remaining());
      }
    }
    return result;
  }

  private BudgetResourceVector quarantinedUsage() {
    BudgetResourceVector result = BudgetResourceVector.zero();
    for (BudgetPhysicalReservation reservation : reservations.values()) {
      if (reservation.status() == BudgetPhysicalReservation.Status.QUARANTINED_UNCERTAIN) {
        result = result.plus(reservation.resources());
      }
    }
    return result;
  }

  private BudgetResourceVector unassigned(BudgetEnvelope envelope) {
    BudgetResourceVector assigned = envelope.consumed();
    for (BudgetPhysicalReservation value : reservations.values()) {
      if (value.envelopeId().equals(envelope.envelopeId())
          && (value.status() == BudgetPhysicalReservation.Status.RESERVED
              || value.status() == BudgetPhysicalReservation.Status.DISPATCHED)) {
        assigned = assigned.plus(value.resources());
      }
    }
    return assigned.fitsWithin(envelope.allocated())
        ? envelope.allocated().minus(assigned)
        : BudgetResourceVector.zero();
  }

  private boolean hasQuarantinedChild(BudgetEnvelopeId envelopeId) {
    return hasOtherQuarantinedChild(envelopeId, "");
  }

  private boolean hasOtherQuarantinedChild(
      BudgetEnvelopeId envelopeId, String excludedReservationId) {
    return reservations.values().stream()
        .anyMatch(
            value ->
                value.envelopeId().equals(envelopeId)
                    && !value.reservationId().equals(excludedReservationId)
                    && value.status()
                        == BudgetPhysicalReservation.Status.QUARANTINED_UNCERTAIN);
  }

  private BudgetEnvelope requireEnvelope(BudgetEnvelopeId id) {
    Objects.requireNonNull(id, "id");
    BudgetEnvelope result = envelopes.get(id.value());
    if (result == null) {
      throw new IllegalArgumentException("unknown budget envelope");
    }
    return result;
  }

  private BudgetPhysicalReservation requireReservation(String id) {
    BudgetPhysicalReservation result = reservations.get(id);
    if (result == null) {
      throw new IllegalArgumentException("unknown physical budget reservation");
    }
    return result;
  }

  private BudgetEnvelope replace(
      BudgetEnvelope current,
      BudgetResourceVector consumed,
      BudgetEnvelopeStatus status) {
    BudgetEnvelope next =
        new BudgetEnvelope(
            current.envelopeId(),
            current.runId(),
            current.epochId(),
            current.workItemId(),
            current.actionDecisionId(),
            current.bucket(),
            current.allocated(),
            consumed,
            status,
            Math.addExact(current.version(), 1L),
            null);
    envelopes.put(next.envelopeId().value(), next);
    return next;
  }

  private BudgetPhysicalReservation replace(
      BudgetPhysicalReservation current,
      BudgetResourceVector resources,
      BudgetPhysicalReservation.Status status) {
    BudgetPhysicalReservation next =
        new BudgetPhysicalReservation(
            current.reservationId(),
            current.envelopeId(),
            current.providerCallId(),
            current.idempotencyKey(),
            current.stage(),
            current.physicalCallOrdinal(),
            current.pricingHash(),
            resources,
            status,
            Math.addExact(current.version(), 1L));
    reservations.put(next.reservationId(), next);
    return next;
  }

  private static void requireStatus(BudgetEnvelope current, BudgetEnvelopeStatus expected) {
    if (current.status() != expected) {
      throw new IllegalStateException("unexpected budget envelope status: " + current.status());
    }
  }

  private static boolean exploration(BudgetBucket bucket) {
    return bucket == BudgetBucket.BREADTH
        || bucket == BudgetBucket.DEPTH
        || bucket == BudgetBucket.REVISION;
  }
}
