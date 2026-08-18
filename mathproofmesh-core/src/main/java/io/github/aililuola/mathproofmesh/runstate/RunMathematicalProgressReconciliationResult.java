package io.github.aililuola.mathproofmesh.runstate;

import java.util.List;
import java.util.Objects;

public record RunMathematicalProgressReconciliationResult(
    RunMathematicalProgressSnapshot progress, List<RunStateConflict> conflicts) {
  public RunMathematicalProgressReconciliationResult {
    progress = Objects.requireNonNull(progress, "progress");
    conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
  }
}
