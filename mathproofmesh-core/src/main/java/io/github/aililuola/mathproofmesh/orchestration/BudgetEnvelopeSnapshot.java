package io.github.aililuola.mathproofmesh.orchestration;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Comparator;
import java.util.List;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor creates an immutable sorted list copy.")
public record BudgetEnvelopeSnapshot(int schemaVersion, List<BudgetEnvelope> envelopes) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public BudgetEnvelopeSnapshot {
    if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported budget envelope snapshot schema");
    }
    envelopes =
        (envelopes == null ? List.<BudgetEnvelope>of() : envelopes).stream()
            .sorted(Comparator.comparing(value -> value.envelopeId().value()))
            .toList();
  }

  public static BudgetEnvelopeSnapshot empty() {
    return new BudgetEnvelopeSnapshot(CURRENT_SCHEMA_VERSION, List.of());
  }
}
