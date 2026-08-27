package io.github.aililuola.mathproofmesh.concurrency;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record AgentLeaseRequest(
    String runId,
    String epochId,
    String workItemId,
    AgentLeaseClass leaseClass,
    String requiredRole,
    Set<String> excludedAgentIds,
    List<String> specialtyHints,
    String authorAgentId,
    String preferredDifferentProvider,
    int requiredPermits,
    String requiredAgentId) {
  public AgentLeaseRequest {
    runId = text(runId, "runId");
    epochId = text(epochId, "epochId");
    workItemId = text(workItemId, "workItemId");
    leaseClass = Objects.requireNonNull(leaseClass, "leaseClass");
    requiredRole = text(requiredRole, "requiredRole");
    excludedAgentIds = excludedAgentIds == null ? Set.of() : Set.copyOf(excludedAgentIds);
    specialtyHints = specialtyHints == null ? List.of() : List.copyOf(specialtyHints);
    authorAgentId = authorAgentId == null ? "" : authorAgentId.strip();
    preferredDifferentProvider =
        preferredDifferentProvider == null ? "" : preferredDifferentProvider.strip();
    requiredAgentId = requiredAgentId == null ? "" : requiredAgentId.strip();
    if (requiredPermits < 1) {
      throw new IllegalArgumentException("requiredPermits must be positive");
    }
    if (!authorAgentId.isEmpty() && !excludedAgentIds.contains(authorAgentId)) {
      throw new IllegalArgumentException("authorAgentId must be excluded for independent work");
    }
    if (!requiredAgentId.isEmpty() && excludedAgentIds.contains(requiredAgentId)) {
      throw new IllegalArgumentException("requiredAgentId cannot also be excluded");
    }
  }

  /** Backward-compatible request for atomic selection from every eligible credential. */
  public AgentLeaseRequest(
      String runId,
      String epochId,
      String workItemId,
      AgentLeaseClass leaseClass,
      String requiredRole,
      Set<String> excludedAgentIds,
      List<String> specialtyHints,
      String authorAgentId,
      String preferredDifferentProvider,
      int requiredPermits) {
    this(
        runId,
        epochId,
        workItemId,
        leaseClass,
        requiredRole,
        excludedAgentIds,
        specialtyHints,
        authorAgentId,
        preferredDifferentProvider,
        requiredPermits,
        "");
  }

  private static String text(String value, String label) {
    Objects.requireNonNull(value, label);
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }
}
