package io.github.aililuola.mathproofmesh.orchestration;

import java.util.Objects;

/** Child reservation transferred atomically from one action envelope to a physical call. */
public record BudgetPhysicalReservation(
    String reservationId,
    BudgetEnvelopeId envelopeId,
    String providerCallId,
    String idempotencyKey,
    String stage,
    int physicalCallOrdinal,
    String pricingHash,
    BudgetResourceVector resources,
    Status status,
    long version) {

  public enum Status {
    RESERVED,
    DISPATCHED,
    SETTLED,
    RELEASED,
    QUARANTINED_UNCERTAIN
  }

  public BudgetPhysicalReservation {
    reservationId = required(reservationId, "reservationId");
    envelopeId = Objects.requireNonNull(envelopeId, "envelopeId");
    providerCallId = required(providerCallId, "providerCallId");
    idempotencyKey = required(idempotencyKey, "idempotencyKey");
    stage = required(stage, "stage");
    pricingHash = required(pricingHash, "pricingHash");
    resources = Objects.requireNonNull(resources, "resources");
    status = Objects.requireNonNull(status, "status");
    if (physicalCallOrdinal < 0 || version < 0) {
      throw new IllegalArgumentException("reservation ordinal and version must not be negative");
    }
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
