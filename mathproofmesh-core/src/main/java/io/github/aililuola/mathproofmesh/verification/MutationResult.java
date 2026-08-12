package io.github.aililuola.mathproofmesh.verification;

public record MutationResult(
    String mutationId, String agentId, boolean detected, boolean firstErrorCorrect) {

  public MutationResult {
    mutationId = java.util.Objects.requireNonNull(mutationId, "mutationId").trim();
    agentId = java.util.Objects.requireNonNull(agentId, "agentId").trim();
    if (mutationId.isEmpty() || agentId.isEmpty()) {
      throw new IllegalArgumentException("mutation and agent IDs are required");
    }
  }
}
