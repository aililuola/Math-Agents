package io.github.aililuola.mathproofmesh.provider;

import io.github.aililuola.mathproofmesh.config.AgentConfig;
import io.github.aililuola.mathproofmesh.config.ConcurrencyConfig;
import io.github.aililuola.mathproofmesh.config.RuntimeConfig;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseClass;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseLedger;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseRecord;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseRequest;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseSnapshot;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseStatus;
import io.github.aililuola.mathproofmesh.concurrency.ConcurrencyEventType;
import io.github.aililuola.mathproofmesh.concurrency.ConcurrencyMetrics;
import io.github.aililuola.mathproofmesh.concurrency.ConcurrencyTelemetryLedger;
import io.github.aililuola.mathproofmesh.concurrency.ConcurrencyTelemetrySnapshot;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

public final class AgentPool implements AutoCloseable {
  private final ProviderClientRegistry registry;
  private final ExecutorService virtualExecutor;
  private final Map<String, AgentRuntime> agents;
  private final ConcurrencyConfig concurrency;
  private final int leaseCapacity;
  private final AgentLeaseLedger leaseLedger = new AgentLeaseLedger();
  private final ConcurrencyTelemetryLedger telemetry = new ConcurrencyTelemetryLedger();
  private final Map<String, Map<String, Long>> epochBusyNanos = new LinkedHashMap<>();
  private final Map<String, Map<String, Long>> runLeaseCounts = new LinkedHashMap<>();
  private int leasedCapacity;
  private int leasedResearchCapacity;
  private long selectionCounter;

  public AgentPool(SystemConfig config, ProviderClientRegistry registry) {
    this(
        config,
        registry,
        new InMemoryCircuitStateStore(),
        Thread::sleep,
        Clock.systemUTC());
  }

  public AgentPool(
      SystemConfig config,
      ProviderClientRegistry registry,
      CircuitStateStore circuitStore,
      RetrySleeper retrySleeper,
      Clock clock) {
    Objects.requireNonNull(config, "config");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    this.concurrency = config.concurrency();
    RuntimeConfig runtime = config.runtime();
    Semaphore global = new Semaphore(runtime.maxParallelCalls(), true);
    ProviderCircuitBreaker breaker =
        new ProviderCircuitBreaker(
            runtime.providerCircuitBreakerEnabled(),
            runtime.providerCircuitFailureThreshold(),
            seconds(runtime.providerCircuitWindowSeconds()),
            seconds(runtime.providerCircuitCooldownSeconds()),
            Set.copyOf(runtime.providerTerminalHttpStatuses()),
            Set.copyOf(runtime.providerSharedAuthHttpStatuses()),
            circuitStore,
            clock);
    Map<String, AgentRuntime> created = new LinkedHashMap<>();
    for (AgentConfig agent : config.agents()) {
      if (!agent.enabled()) {
        continue;
      }
      created.put(
          agent.id(),
          new AgentRuntime(
              agent,
              registry.get(agent.id()),
              global,
              breaker,
              runtime.requestRetries(),
              virtualExecutor,
              retrySleeper,
              clock));
    }
    this.agents = Map.copyOf(created);
    this.leaseCapacity =
        Math.min(
            runtime.maxParallelCalls(),
            created.values().stream().mapToInt(agent -> agent.config().maxConcurrency()).sum());
  }

  public List<AgentRuntime> agents() {
    return agents.values().stream()
        .sorted(Comparator.comparing(AgentRuntime::id))
        .toList();
  }

  public AgentRuntime get(String agentId) {
    AgentRuntime agent = agents.get(agentId);
    if (agent == null) {
      throw new IllegalArgumentException("unknown agent: " + agentId);
    }
    return agent;
  }

  public synchronized AgentRuntime select(
      String role,
      Set<String> exclude,
      List<String> specialtyHints,
      String preferProviderNot,
      boolean strictExclude) {
    String safeRole = requireText(role, "role");
    Set<String> exclusions =
        exclude == null ? Set.of() : Set.copyOf(exclude);
    List<AgentRuntime> candidates =
        agents().stream()
            .filter(agent -> !exclusions.contains(agent.id()))
            .filter(agent -> agent.supportsRole(safeRole))
            .filter(agent -> !agent.inCooldown())
            .toList();
    if (candidates.isEmpty()) {
      candidates =
          agents().stream()
              .filter(agent -> !exclusions.contains(agent.id()))
              .filter(agent -> agent.supportsRole(safeRole))
              .toList();
    }
    if (candidates.isEmpty() && !strictExclude) {
      candidates =
          agents().stream()
              .filter(agent -> !exclusions.contains(agent.id()))
              .filter(agent -> !agent.inCooldown())
              .toList();
    }
    if (candidates.isEmpty() && !strictExclude) {
      candidates =
          agents().stream()
              .filter(agent -> !exclusions.contains(agent.id()))
              .toList();
    }
    if (candidates.isEmpty()) {
      throw new IllegalStateException(
          strictExclude
              ? "no agent satisfies strict author exclusion for role " + safeRole
              : "agent pool is empty");
    }
    long rotationCounter = ++selectionCounter;
    return candidates.stream()
        .max(
            Comparator.comparingDouble(
                    (AgentRuntime agent) ->
                        score(
                            agent,
                            specialtyHints,
                            preferProviderNot,
                            rotationCounter))
                .thenComparing(
                    AgentRuntime::id, Comparator.reverseOrder()))
        .orElseThrow();
  }

  public AgentRuntime selectReviewer(
      String role, String authorId, List<String> specialtyHints) {
    return select(
        role,
        Set.of(requireText(authorId, "authorId")),
        specialtyHints,
        null,
        true);
  }

  /** Atomically selects and reserves a credential before provider work can begin. */
  public AgentLease acquireLease(AgentLeaseRequest request) {
    Objects.requireNonNull(request, "request");
    long started = System.nanoTime();
    long timeoutNanos = Duration.ofSeconds(concurrency.leaseTimeoutSeconds()).toNanos();
    synchronized (this) {
      while (true) {
        Optional<AgentLease> lease = tryAcquireLeaseLocked(request, started);
        if (lease.isPresent()) {
          return lease.orElseThrow();
        }
        long remaining = timeoutNanos - Math.max(0L, System.nanoTime() - started);
        if (remaining <= 0L) {
          throw new IllegalStateException(
              "timed out acquiring agent lease for work item " + request.workItemId());
        }
        try {
          long millis = Math.max(1L, Math.min(100L, remaining / 1_000_000L));
          wait(millis);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw ProviderException.cancelled();
        }
      }
    }
  }

  public synchronized Optional<AgentLease> tryAcquireLease(AgentLeaseRequest request) {
    Objects.requireNonNull(request, "request");
    return tryAcquireLeaseLocked(request, System.nanoTime());
  }

  public synchronized AgentLeaseSnapshot leaseSnapshot() {
    return leaseLedger.snapshot();
  }

  public synchronized ConcurrencyTelemetrySnapshot concurrencyTelemetrySnapshot() {
    return telemetry.snapshot();
  }

  public synchronized ConcurrencyMetrics concurrencyMetrics() {
    return telemetry.metrics(
        concurrency.researchSlots(), concurrency.researchSlots() + concurrency.coordinationSlots());
  }

  public synchronized void recordConcurrencyEvent(
      ConcurrencyEventType type,
      String epochId,
      String workItemId,
      String agentId,
      int readyWorkCount) {
    telemetry.record(type, epochId, workItemId, agentId, readyWorkCount);
  }

  public synchronized void restoreLeases(AgentLeaseSnapshot snapshot, String activeRunId) {
    leaseLedger.restore(snapshot, requireText(activeRunId, "activeRunId"));
    leasedCapacity = 0;
    leasedResearchCapacity = 0;
    epochBusyNanos.clear();
    runLeaseCounts.clear();
    for (AgentLeaseRecord record : snapshot.leases()) {
      runLeaseCounts
          .computeIfAbsent(record.runId(), ignored -> new LinkedHashMap<>())
          .merge(record.agentId(), 1L, Long::sum);
    }
    for (AgentRuntime agent : agents.values()) {
      while (agent.reservedCalls() > 0) {
        agent.releaseLease(1, 0L);
      }
    }
    notifyAll();
  }

  public synchronized void restoreConcurrencyTelemetry(ConcurrencyTelemetrySnapshot snapshot) {
    telemetry.restore(Objects.requireNonNull(snapshot, "snapshot"));
  }

  synchronized AgentLeaseRecord markLeaseRunning(String leaseId) {
    return leaseLedger.transition(leaseId, AgentLeaseStatus.RUNNING, System.nanoTime());
  }

  synchronized void providerCallStarted(AgentLeaseRecord record, int readyWorkCount) {
    telemetry.record(
        ConcurrencyEventType.PROVIDER_CALL_STARTED,
        record.epochId(),
        record.workItemId(),
        record.agentId(),
        readyWorkCount);
  }

  synchronized void providerCallCompleted(AgentLeaseRecord record, int readyWorkCount) {
    telemetry.record(
        ConcurrencyEventType.PROVIDER_CALL_COMPLETED,
        record.epochId(),
        record.workItemId(),
        record.agentId(),
        readyWorkCount);
  }

  synchronized AgentLeaseRecord releaseLease(
      String leaseId, AgentRuntime agent, int permits, long acquiredNanos) {
    long now = System.nanoTime();
    AgentLeaseRecord prior =
        leaseLedger.snapshot().leases().stream()
            .filter(record -> record.leaseId().equals(leaseId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown lease: " + leaseId));
    AgentLeaseRecord released =
        leaseLedger.transition(leaseId, AgentLeaseStatus.RELEASED, now);
    if (!prior.terminal()) {
      leasedCapacity = Math.max(0, leasedCapacity - permits);
      if (prior.leaseClass() == AgentLeaseClass.RESEARCH) {
        leasedResearchCapacity = Math.max(0, leasedResearchCapacity - permits);
      }
      long busyNanos = Math.max(0L, now - acquiredNanos);
      agent.releaseLease(permits, busyNanos);
      epochBusyNanos
          .computeIfAbsent(prior.epochId(), ignored -> new LinkedHashMap<>())
          .merge(agent.id(), busyNanos, Long::sum);
      telemetry.record(
          ConcurrencyEventType.LEASE_RELEASED,
          prior.epochId(),
          prior.workItemId(),
          prior.agentId(),
          0);
      notifyAll();
    }
    return released;
  }

  public List<AgentRuntime> failoverCandidates(
      String role,
      Set<String> exclude,
      List<String> specialtyHints,
      String preferProviderNot,
      int limit) {
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    Set<String> exclusions =
        exclude == null ? Set.of() : Set.copyOf(exclude);
    return agents().stream()
        .filter(agent -> !exclusions.contains(agent.id()))
        .filter(agent -> agent.supportsRole(role))
        .sorted(
            Comparator.comparingDouble(
                    (AgentRuntime agent) ->
                        score(agent, specialtyHints, preferProviderNot, 0L))
                .reversed()
                .thenComparing(AgentRuntime::id))
        .limit(limit)
        .toList();
  }

  public FailoverResult callWithFailover(
      String role,
      AgentRuntime primary,
      Function<AgentRuntime, ProviderRequest> requestFactory,
      Set<String> exclude,
      List<String> specialtyHints,
      int maximumBackups) {
    Objects.requireNonNull(primary, "primary");
    Objects.requireNonNull(requestFactory, "requestFactory");
    Set<String> exclusions = new LinkedHashSet<>(
        exclude == null ? Set.of() : exclude);
    if (exclusions.contains(primary.id())) {
      throw new IllegalArgumentException("primary agent is excluded");
    }
    List<AgentRuntime> candidates = new ArrayList<>();
    candidates.add(primary);
    exclusions.add(primary.id());
    candidates.addAll(
        failoverCandidates(
            role,
            exclusions,
            specialtyHints,
            primary.provider(),
            maximumBackups));
    List<String> attempted = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (AgentRuntime candidate : candidates) {
      attempted.add(candidate.id());
      try {
        return new FailoverResult(
            candidate.call(requestFactory.apply(candidate)),
            candidate,
            List.copyOf(attempted));
      } catch (ProviderCircuitOpenError error) {
        throw error;
      } catch (AgentCallFailure error) {
        errors.add(
            candidate.id()
                + ":"
                + error.providerFailure().kind());
        if (!error.retryable()
            && error.providerFailure().statusCode() != null
            && error.providerFailure().statusCode() != 401
            && error.providerFailure().statusCode() != 403) {
          break;
        }
      }
    }
    throw new AgentFailoverExhausted(role, attempted, errors);
  }

  public List<AgentRuntimeMetric> metrics() {
    return agents().stream().map(AgentRuntime::metric).toList();
  }

  @Override
  public void close() {
    virtualExecutor.close();
    registry.close();
  }

  private Optional<AgentLease> tryAcquireLeaseLocked(
      AgentLeaseRequest request, long queueStartedNanos) {
    int permits = request.requiredPermits();
    if (!concurrency.enabled()) {
      return Optional.empty();
    }
    if (leasedCapacity + permits > Math.min(leaseCapacity, concurrency.maxInFlightTasks())) {
      return Optional.empty();
    }
    if (request.leaseClass() == AgentLeaseClass.RESEARCH
        && concurrency.reserveCoordinationCapacity()
        && !concurrency.allowCoordinationBorrowing()
        && leasedResearchCapacity + permits > concurrency.researchSlots()) {
      return Optional.empty();
    }
    Map<String, Long> epochBusy =
        epochBusyNanos.computeIfAbsent(request.epochId(), ignored -> new LinkedHashMap<>());
    Map<String, Long> runCounts =
        runLeaseCounts.computeIfAbsent(request.runId(), ignored -> new LinkedHashMap<>());
    Comparator<AgentRuntime> order =
        Comparator.comparingLong((AgentRuntime agent) -> epochBusy.getOrDefault(agent.id(), 0L))
            .thenComparingLong(agent -> runCounts.getOrDefault(agent.id(), 0L))
            .thenComparing(
                (AgentRuntime agent) -> agent.specialtyScore(request.specialtyHints()),
                Comparator.reverseOrder())
            .thenComparing(AgentRuntime::trust, Comparator.reverseOrder())
            .thenComparingInt(
                agent ->
                    request.preferredDifferentProvider().isEmpty()
                            || !request.preferredDifferentProvider().equals(agent.provider())
                        ? 0
                        : 1)
            .thenComparing(AgentRuntime::id);
    Optional<AgentRuntime> selected =
        agents().stream()
            .filter(
                agent ->
                    request.requiredAgentId().isEmpty()
                        || request.requiredAgentId().equals(agent.id()))
            .filter(agent -> !request.excludedAgentIds().contains(agent.id()))
            .filter(agent -> agent.supportsRole(request.requiredRole()))
            .filter(agent -> !agent.inCooldown())
            .filter(agent -> agent.availableLeaseCapacity() >= permits)
            .min(order);
    if (selected.isEmpty()) {
      return Optional.empty();
    }
    AgentRuntime agent = selected.orElseThrow();
    long now = System.nanoTime();
    long queueWait = Math.max(0L, now - queueStartedNanos);
    agent.reserveLease(permits, queueWait);
    leasedCapacity += permits;
    if (request.leaseClass() == AgentLeaseClass.RESEARCH) {
      leasedResearchCapacity += permits;
    }
    runCounts.merge(agent.id(), 1L, Long::sum);
    String leaseId =
        "lease-"
            + CanonicalJson.stableHash(
                    List.of(
                        request.runId(),
                        request.epochId(),
                        request.workItemId(),
                        request.leaseClass().name(),
                        agent.id(),
                        runCounts.get(agent.id())))
                .substring(0, 24);
    AgentLeaseRecord record = leaseLedger.acquire(leaseId, request, agent.id(), now);
    telemetry.record(
        ConcurrencyEventType.LEASE_ACQUIRED,
        request.epochId(),
        request.workItemId(),
        agent.id(),
        0);
    return Optional.of(new AgentLease(this, agent, record, permits, now));
  }

  private static double score(
      AgentRuntime agent,
      List<String> specialtyHints,
      String preferProviderNot,
      long rotationCounter) {
    AgentRuntimeMetric metric = agent.metric();
    double providerDiversity =
        preferProviderNot != null && !preferProviderNot.equals(agent.provider())
            ? 0.12d
            : 0.0d;
    double specialty = 0.18d * agent.specialtyScore(specialtyHints);
    double loadPenalty =
        0.08d * (metric.activeCalls() + metric.reservedCalls())
            + 0.001d * metric.calls();
    long stable = stableId(agent.id());
    double rotation = Math.floorMod(stable + rotationCounter, 17L) / 10_000.0d;
    return agent.trust()
        + providerDiversity
        + specialty
        + rotation
        - loadPenalty;
  }

  private static long stableId(String id) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(id.getBytes(StandardCharsets.UTF_8));
      return Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(digest).getInt());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the JDK", exception);
    }
  }

  private static Duration seconds(double seconds) {
    return Duration.ofMillis(Math.max(1L, Math.round(seconds * 1000.0d)));
  }

  private static String requireText(String value, String label) {
    Objects.requireNonNull(value, label);
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }

  public record FailoverResult(
      LLMResponse response,
      AgentRuntime agent,
      List<String> attemptedAgents) {
    public FailoverResult {
      Objects.requireNonNull(response, "response");
      Objects.requireNonNull(agent, "agent");
      attemptedAgents = List.copyOf(attemptedAgents);
    }
  }
}
