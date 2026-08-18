package io.github.aililuola.mathproofmesh.provider;

import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseRecord;
import java.util.Objects;

public record LeasedAgent(AgentRuntime agent, AgentLeaseRecord leaseRecord) {
  public LeasedAgent {
    agent = Objects.requireNonNull(agent, "agent");
    leaseRecord = Objects.requireNonNull(leaseRecord, "leaseRecord");
    if (!agent.id().equals(leaseRecord.agentId())) {
      throw new IllegalArgumentException("lease is not bound to the selected agent");
    }
  }
}
