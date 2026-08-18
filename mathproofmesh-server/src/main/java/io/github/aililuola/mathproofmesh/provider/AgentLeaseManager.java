package io.github.aililuola.mathproofmesh.provider;

import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseRequest;
import java.util.Objects;
import java.util.Optional;

public final class AgentLeaseManager {
  private final AgentPool pool;

  public AgentLeaseManager(AgentPool pool) {
    this.pool = Objects.requireNonNull(pool, "pool");
  }

  public AgentLease acquire(AgentLeaseRequest request) {
    return pool.acquireLease(request);
  }

  public Optional<AgentLease> tryAcquire(AgentLeaseRequest request) {
    return pool.tryAcquireLease(request);
  }
}
