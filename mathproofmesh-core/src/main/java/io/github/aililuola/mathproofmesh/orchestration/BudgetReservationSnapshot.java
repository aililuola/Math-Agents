package io.github.aililuola.mathproofmesh.orchestration;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Comparator;
import java.util.List;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor creates an immutable sorted list copy.")
public record BudgetReservationSnapshot(
    int schemaVersion, List<BudgetPhysicalReservation> reservations) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public BudgetReservationSnapshot {
    if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported budget reservation snapshot schema");
    }
    reservations =
        (reservations == null ? List.<BudgetPhysicalReservation>of() : reservations).stream()
            .sorted(Comparator.comparing(BudgetPhysicalReservation::reservationId))
            .toList();
  }

  public static BudgetReservationSnapshot empty() {
    return new BudgetReservationSnapshot(CURRENT_SCHEMA_VERSION, List.of());
  }
}
