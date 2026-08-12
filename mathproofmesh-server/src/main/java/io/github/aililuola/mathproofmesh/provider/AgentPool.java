package io.github.aililuola.mathproofmesh.provider;

import io.github.aililuola.mathproofmesh.config.AgentConfig;
import io.github.aililuola.mathproofmesh.config.RuntimeConfig;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

public final class AgentPool implements AutoCloseable {
  private final ProviderClientRegistry registry;
  private final ExecutorService virtualExecutor;
  private final Map<String, AgentRuntime> agents;
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
        0.08d * metric.activeCalls() + 0.001d * metric.calls();
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
