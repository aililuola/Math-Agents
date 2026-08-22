package io.github.aililuola.mathproofmesh.orchestration;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Comparator;
import java.util.List;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor creates an immutable sorted list copy.")
public record BudgetDecisionSnapshot(
    int schemaVersion, List<EvidenceAwareBudgetDecision> decisions) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public BudgetDecisionSnapshot {
    if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported budget decision snapshot schema");
    }
    decisions =
        (decisions == null ? List.<EvidenceAwareBudgetDecision>of() : decisions).stream()
            .sorted(Comparator.comparing(value -> value.identity().stateHash()))
            .toList();
  }

  public static BudgetDecisionSnapshot empty() {
    return new BudgetDecisionSnapshot(CURRENT_SCHEMA_VERSION, List.of());
  }
}
