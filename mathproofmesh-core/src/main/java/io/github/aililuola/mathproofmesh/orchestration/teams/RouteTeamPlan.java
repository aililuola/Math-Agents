package io.github.aililuola.mathproofmesh.orchestration.teams;

import java.util.List;

/** Immutable plan for one isolated route team. */
public record RouteTeamPlan(
    String routeId,
    RoleAssignment prover,
    RoleAssignment skeptic,
    RoleAssignment toolSpecialist,
    RoleAssignment referee,
    RiskAssessment risk,
    boolean globalShareAllowed,
    List<String> diagnostics) {
  public RouteTeamPlan {
    routeId = routeId == null ? "" : routeId.strip();
    if (routeId.isEmpty()) {
      throw new IllegalArgumentException("routeId is required");
    }
    java.util.Objects.requireNonNull(prover, "prover");
    java.util.Objects.requireNonNull(referee, "referee");
    java.util.Objects.requireNonNull(risk, "risk");
    diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
  }

  @Override
  public List<String> diagnostics() {
    return List.copyOf(diagnostics);
  }
}
