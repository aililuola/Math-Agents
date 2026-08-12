package io.github.aililuola.mathproofmesh.orchestration;

import io.github.aililuola.mathproofmesh.contract.BudgetDecision;
import java.util.List;
import java.util.Map;

/** Scheduler result for inspiration tasks before any provider call. */
public record InspirationAdmission(
    List<String> admittedTaskIds, Map<String, String> rejected, BudgetDecision decision) {
  public InspirationAdmission {
    admittedTaskIds = admittedTaskIds == null ? List.of() : List.copyOf(admittedTaskIds);
    rejected = rejected == null ? Map.of() : Map.copyOf(rejected);
    java.util.Objects.requireNonNull(decision, "decision");
  }

  @Override
  public List<String> admittedTaskIds() {
    return List.copyOf(admittedTaskIds);
  }

  @Override
  public Map<String, String> rejected() {
    return Map.copyOf(rejected);
  }
}
