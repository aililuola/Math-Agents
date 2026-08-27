package io.github.aililuola.mathproofmesh.orchestration.teams;

import java.util.List;

/** Independent review result; route-local work is never silently promoted. */
public record RouteTeamResult(
    String routeId,
    boolean skepticPassed,
    boolean toolReplayPassed,
    boolean refereePassed,
    boolean globalShareAllowed,
    List<String> diagnostics) {
  public RouteTeamResult {
    routeId = routeId == null ? "" : routeId.strip();
    diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
  }

  @Override
  public List<String> diagnostics() {
    return List.copyOf(diagnostics);
  }
}
