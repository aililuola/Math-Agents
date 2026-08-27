package io.github.aililuola.mathproofmesh.concurrency;

import java.util.List;

public record AgentLeaseSnapshot(List<AgentLeaseRecord> leases, long version) {
  public AgentLeaseSnapshot {
    leases = leases == null ? List.of() : List.copyOf(leases);
    if (version < 0L) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
  }

  @Override
  public List<AgentLeaseRecord> leases() {
    return List.copyOf(leases);
  }

  public static AgentLeaseSnapshot empty() {
    return new AgentLeaseSnapshot(List.of(), 0L);
  }
}
