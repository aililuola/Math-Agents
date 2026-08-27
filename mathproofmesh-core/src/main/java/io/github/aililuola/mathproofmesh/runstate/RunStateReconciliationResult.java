package io.github.aililuola.mathproofmesh.runstate;

import java.util.List;

public record RunStateReconciliationResult(
    RunStateSnapshot state, List<RunStateConflict> conflicts) {
  public RunStateReconciliationResult {
    if (state == null) {
      throw new NullPointerException("state");
    }
    conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
  }

  @Override
  public List<RunStateConflict> conflicts() {
    return List.copyOf(conflicts);
  }
}
