package io.github.aililuola.mathproofmesh.provider;

import java.util.List;

@SuppressWarnings("serial")
public final class AgentFailoverExhausted extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String role;
  private final List<String> attemptedAgents;
  private final List<String> errors;

  public AgentFailoverExhausted(
      String role, List<String> attemptedAgents, List<String> errors) {
    super(
        "all failover candidates failed for role "
            + role
            + ": "
            + attemptedAgents);
    this.role = java.util.Objects.requireNonNull(role, "role");
    this.attemptedAgents = List.copyOf(attemptedAgents);
    this.errors = List.copyOf(errors);
  }

  public String role() {
    return role;
  }

  public List<String> attemptedAgents() {
    return attemptedAgents;
  }

  public List<String> errors() {
    return errors;
  }
}
