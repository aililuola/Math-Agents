package io.github.aililuola.mathproofmesh.workflow;

import java.util.List;

/** Query-safe run state containing IDs and summaries, never prompts or secrets. */
public record SolveState(
    String runId,
    String status,
    String currentStage,
    int budget,
    int generation,
    List<String> completedRouteIds,
    String checkpointId) {
  public SolveState {
    runId = clean(runId);
    status = clean(status);
    currentStage = clean(currentStage);
    completedRouteIds =
        completedRouteIds == null ? List.of() : List.copyOf(completedRouteIds);
    checkpointId = clean(checkpointId);
  }

  @Override
  public List<String> completedRouteIds() {
    return List.copyOf(completedRouteIds);
  }

  private static String clean(String value) {
    return value == null ? "" : value.strip();
  }
}
