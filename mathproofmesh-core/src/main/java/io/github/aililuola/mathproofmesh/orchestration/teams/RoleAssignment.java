package io.github.aililuola.mathproofmesh.orchestration.teams;

import io.github.aililuola.mathproofmesh.contract.RouteRole;

/** A deterministic route-local role selection. */
public record RoleAssignment(
    String routeId,
    RouteRole role,
    String agentId,
    String selectedVia,
    boolean localOnly,
    String reason) {
  public RoleAssignment {
    routeId = required(routeId, "routeId");
    java.util.Objects.requireNonNull(role, "role");
    agentId = clean(agentId);
    selectedVia = clean(selectedVia);
    reason = clean(reason);
    if (!localOnly && agentId.isEmpty()) {
      throw new IllegalArgumentException("assigned roles require an agent");
    }
  }

  public boolean assigned() {
    return !agentId.isEmpty();
  }

  private static String required(String value, String field) {
    String result = clean(value);
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }

  private static String clean(String value) {
    return value == null ? "" : value.strip();
  }
}
