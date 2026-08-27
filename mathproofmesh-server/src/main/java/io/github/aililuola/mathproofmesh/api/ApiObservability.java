package io.github.aililuola.mathproofmesh.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public final class ApiObservability {
  private final MeterRegistry registry;
  private final Counter calls;
  private final Counter errors;
  private final Counter tokens;
  private final Counter costMicrounits;
  private final Counter checkpoints;
  private final Counter messages;
  private final Counter leases;
  private final Timer latency;
  private final Map<String, String> lastTraceByOperation = new ConcurrentHashMap<>();

  public ApiObservability(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.calls = registry.counter("mathproofmesh.api.calls");
    this.errors = registry.counter("mathproofmesh.api.errors");
    this.tokens = registry.counter("mathproofmesh.provider.tokens");
    this.costMicrounits = registry.counter("mathproofmesh.provider.cost.microunits");
    this.checkpoints = registry.counter("mathproofmesh.checkpoints");
    this.messages = registry.counter("mathproofmesh.messages");
    this.leases = registry.counter("mathproofmesh.leases");
    this.latency = registry.timer("mathproofmesh.api.latency");
    registry.gauge("mathproofmesh.routes.active", new java.util.concurrent.atomic.AtomicInteger());
    registry.gauge("mathproofmesh.queue.depth", new java.util.concurrent.atomic.AtomicInteger());
    registry.gauge("mathproofmesh.computation.active", new java.util.concurrent.atomic.AtomicInteger());
  }

  public Timer.Sample start(String operation, String traceId) {
    calls.increment();
    lastTraceByOperation.put(
        ActivitySanitizer.identifier(operation, 80), TraceContext.validate(traceId));
    return Timer.start(registry);
  }

  public void complete(Timer.Sample sample) {
    sample.stop(latency);
  }

  public void error() {
    errors.increment();
  }

  public void recordMockRun() {
    tokens.increment(0);
    costMicrounits.increment(0);
    checkpoints.increment();
    messages.increment();
    leases.increment();
  }

  public String lastTrace(String operation) {
    return lastTraceByOperation.get(operation);
  }
}
