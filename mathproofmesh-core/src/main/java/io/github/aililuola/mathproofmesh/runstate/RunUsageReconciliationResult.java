package io.github.aililuola.mathproofmesh.runstate;

import java.util.List;

public record RunUsageReconciliationResult(
    RunUsageStatus status, RunUsageSnapshot usage, List<RunUsageConflict> conflicts) {
  public RunUsageReconciliationResult {
    status = status == null ? RunUsageStatus.NOT_RECORDED : status;
    usage = usage == null ? RunUsageSnapshot.empty() : usage;
    conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
  }

  @Override
  public List<RunUsageConflict> conflicts() {
    return List.copyOf(conflicts);
  }
}
