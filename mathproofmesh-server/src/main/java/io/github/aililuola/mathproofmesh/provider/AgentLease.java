package io.github.aililuola.mathproofmesh.provider;

import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseRecord;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AgentLease implements AutoCloseable {
  private final AgentPool pool;
  private final AgentRuntime agent;
  private final int permits;
  private final long acquiredNanos;
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile AgentLeaseRecord record;

  AgentLease(
      AgentPool pool,
      AgentRuntime agent,
      AgentLeaseRecord record,
      int permits,
      long acquiredNanos) {
    this.pool = Objects.requireNonNull(pool, "pool");
    this.agent = Objects.requireNonNull(agent, "agent");
    this.record = Objects.requireNonNull(record, "record");
    this.permits = permits;
    this.acquiredNanos = acquiredNanos;
  }

  public String leaseId() {
    return record.leaseId();
  }

  public AgentRuntime agent() {
    return agent;
  }

  public AgentLeaseRecord record() {
    return record;
  }

  public LeasedAgent leasedAgent() {
    return new LeasedAgent(agent, record);
  }

  public LLMResponse call(ProviderRequest request) {
    if (closed.get()) {
      throw new IllegalStateException("agent lease is closed");
    }
    record = pool.markLeaseRunning(record.leaseId());
    pool.providerCallStarted(record, 0);
    try {
      return agent.call(request);
    } finally {
      pool.providerCallCompleted(record, 0);
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      record = pool.releaseLease(record.leaseId(), agent, permits, acquiredNanos);
    }
  }
}
