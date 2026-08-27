package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class AgentLeaseLedger {
  private final Map<String, AgentLeaseRecord> leases = new LinkedHashMap<>();
  private long version;

  public synchronized AgentLeaseRecord acquire(
      String leaseId,
      AgentLeaseRequest request,
      String agentId,
      long acquiredNanos) {
    Objects.requireNonNull(request, "request");
    AgentLeaseRecord existing = leases.get(leaseId);
    if (existing != null) {
      if (!existing.workItemId().equals(request.workItemId())
          || !existing.agentId().equals(agentId)) {
        throw new IllegalStateException("lease identity conflict: " + leaseId);
      }
      return existing;
    }
    AgentLeaseRecord record =
        new AgentLeaseRecord(
            leaseId,
            request.runId(),
            request.epochId(),
            request.workItemId(),
            agentId,
            request.leaseClass(),
            AgentLeaseStatus.ACQUIRED,
            acquiredNanos,
            0L,
            1L);
    leases.put(leaseId, record);
    version++;
    return record;
  }

  public synchronized AgentLeaseRecord transition(
      String leaseId, AgentLeaseStatus status, long atNanos) {
    AgentLeaseRecord prior = require(leaseId);
    AgentLeaseRecord next = prior.transition(status, atNanos);
    if (next != prior) {
      leases.put(leaseId, next);
      version++;
    }
    return next;
  }

  public synchronized AgentLeaseSnapshot snapshot() {
    return new AgentLeaseSnapshot(
        leases.values().stream().sorted(Comparator.comparing(AgentLeaseRecord::leaseId)).toList(),
        version);
  }

  public synchronized void restore(AgentLeaseSnapshot snapshot, String activeRunId) {
    Objects.requireNonNull(snapshot, "snapshot");
    leases.clear();
    for (AgentLeaseRecord record : snapshot.leases()) {
      AgentLeaseRecord restored = record;
      if (!record.terminal() && record.runId().equals(activeRunId)) {
        restored = record.transition(AgentLeaseStatus.ABANDONED, record.acquiredNanos());
      }
      leases.put(restored.leaseId(), restored);
    }
    version = snapshot.version();
  }

  private AgentLeaseRecord require(String leaseId) {
    AgentLeaseRecord record = leases.get(leaseId);
    if (record == null) {
      throw new IllegalArgumentException("unknown lease: " + leaseId);
    }
    return record;
  }
}
