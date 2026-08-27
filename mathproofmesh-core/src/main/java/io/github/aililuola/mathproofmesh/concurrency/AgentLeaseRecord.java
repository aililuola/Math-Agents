package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Objects;

public record AgentLeaseRecord(
    String leaseId,
    String runId,
    String epochId,
    String workItemId,
    String agentId,
    AgentLeaseClass leaseClass,
    AgentLeaseStatus status,
    long acquiredNanos,
    long releasedNanos,
    long version) {
  public AgentLeaseRecord {
    leaseId = text(leaseId, "leaseId");
    runId = text(runId, "runId");
    epochId = text(epochId, "epochId");
    workItemId = text(workItemId, "workItemId");
    agentId = text(agentId, "agentId");
    leaseClass = Objects.requireNonNull(leaseClass, "leaseClass");
    status = Objects.requireNonNull(status, "status");
    if (acquiredNanos < 0L || releasedNanos < 0L || version < 1L) {
      throw new IllegalArgumentException("lease counters must be nonnegative and version positive");
    }
  }

  public AgentLeaseRecord transition(AgentLeaseStatus next, long atNanos) {
    Objects.requireNonNull(next, "next");
    if (terminal()) {
      return this;
    }
    long released =
        next == AgentLeaseStatus.RELEASED
                || next == AgentLeaseStatus.EXPIRED
                || next == AgentLeaseStatus.ABANDONED
            ? Math.max(acquiredNanos, atNanos)
            : releasedNanos;
    return new AgentLeaseRecord(
        leaseId,
        runId,
        epochId,
        workItemId,
        agentId,
        leaseClass,
        next,
        acquiredNanos,
        released,
        version + 1L);
  }

  public boolean terminal() {
    return status == AgentLeaseStatus.RELEASED
        || status == AgentLeaseStatus.EXPIRED
        || status == AgentLeaseStatus.ABANDONED;
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
