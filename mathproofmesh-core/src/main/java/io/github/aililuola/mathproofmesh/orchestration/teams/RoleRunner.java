package io.github.aililuola.mathproofmesh.orchestration.teams;

import io.github.aililuola.mathproofmesh.contract.RouteRole;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selects role-capable agents without the author/referee reuse fallback. */
public final class RoleRunner {
  private final Map<RouteRole, List<String>> agents;
  private final Map<String, Map<RouteRole, String>> assignments = new LinkedHashMap<>();

  public RoleRunner(Map<RouteRole, List<String>> agents) {
    EnumMap<RouteRole, List<String>> copy = new EnumMap<>(RouteRole.class);
    if (agents != null) {
      agents.forEach(
          (role, values) ->
              copy.put(
                  java.util.Objects.requireNonNull(role, "role"),
                  values == null
                      ? List.of()
                      : values.stream().map(String::strip).filter(s -> !s.isEmpty()).toList()));
    }
    this.agents = Map.copyOf(copy);
  }

  public synchronized RoleAssignment select(
      String routeId, RouteRole role, Set<String> exclusions) {
    String route = required(routeId, "routeId");
    Set<String> blocked =
        exclusions == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(exclusions));
    Map<RouteRole, String> routeAssignments =
        assignments.computeIfAbsent(route, ignored -> new EnumMap<>(RouteRole.class));
    for (String candidate : agents.getOrDefault(role, List.of())) {
      if (!blocked.contains(candidate) && !routeAssignments.containsValue(candidate)) {
        routeAssignments.put(role, candidate);
        return new RoleAssignment(route, role, candidate, role.value(), false, "");
      }
    }
    return new RoleAssignment(
        route,
        role,
        "",
        "",
        true,
        "no independent agent is available for this role");
  }

  public synchronized Map<RouteRole, String> assignments(String routeId) {
    return Map.copyOf(assignments.getOrDefault(routeId, Map.of()));
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
