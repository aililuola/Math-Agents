package io.github.aililuola.mathproofmesh.orchestration.teams;

import io.github.aililuola.mathproofmesh.contract.RouteRole;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** Builds Prover, optional Skeptic/Tool Specialist, and independent Referee teams. */
public final class RouteTeamFactory {
  private final RoleRunner runner;

  public RouteTeamFactory(RoleRunner runner) {
    this.runner = java.util.Objects.requireNonNull(runner, "runner");
  }

  public RouteTeamPlan plan(String routeId, String authorAgentId, RiskAssessment risk) {
    String author = required(authorAgentId, "authorAgentId");
    RoleAssignment prover =
        new RoleAssignment(routeId, RouteRole.PROVER, author, "existing_author", false, "");
    Set<String> excluded = new LinkedHashSet<>();
    excluded.add(author);
    RoleAssignment skeptic = null;
    RoleAssignment tool = null;
    if (risk.needsSkeptic()) {
      skeptic = runner.select(routeId, RouteRole.SKEPTIC, excluded);
      if (skeptic.assigned()) {
        excluded.add(skeptic.agentId());
      }
    }
    if (risk.needsTool()) {
      tool = runner.select(routeId, RouteRole.TOOL_SPECIALIST, excluded);
      if (tool.assigned()) {
        excluded.add(tool.agentId());
      }
    }
    RoleAssignment referee = runner.select(routeId, RouteRole.REFEREE, excluded);
    ArrayList<String> diagnostics = new ArrayList<>();
    boolean share = referee.assigned();
    if (!referee.assigned()) {
      diagnostics.add("no independent referee; artifact remains route-local");
    }
    if (skeptic != null && !skeptic.assigned()) {
      share = false;
      diagnostics.add("required skeptic is unavailable");
    }
    if (tool != null && !tool.assigned()) {
      share = false;
      diagnostics.add("required tool specialist is unavailable");
    }
    return new RouteTeamPlan(
        routeId, prover, skeptic, tool, referee, risk, share, diagnostics);
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
