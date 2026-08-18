package io.github.aililuola.mathproofmesh.provider;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.config.AgentConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** One credential-isolated agent with fair concurrency and bounded retries. */
public final class AgentRuntime {
  private final AgentConfig config;
  private final LLMClient client;
  private final Semaphore globalSemaphore;
  private final Semaphore agentSemaphore;
  private final SlidingWindowRateLimiter rateLimiter;
  private final ProviderCircuitBreaker circuitBreaker;
  private final String providerScope;
  private final int requestRetries;
  private final ExecutorService virtualExecutor;
  private final RetrySleeper retrySleeper;
  private final Clock clock;
  private final AtomicInteger activeCalls = new AtomicInteger();
  private final AtomicInteger reservedCalls = new AtomicInteger();
  private final AtomicLong leasedCalls = new AtomicLong();
  private final AtomicLong leaseQueueWaitNanos = new AtomicLong();
  private final AtomicLong leaseBusyNanos = new AtomicLong();
  private final AtomicLong providerBusyNanos = new AtomicLong();
  private final AtomicLong idleWhileEligibleNanos = new AtomicLong();

  private long calls;
  private long failures;
  private long failedAttempts;
  private long inputTokens;
  private long outputTokens;
  private BigDecimal costUsd = BigDecimal.ZERO;
  private double latencyMs;
  private double trust;
  private Instant cooldownUntil = Instant.EPOCH;
  private final Map<String, Long> failureCategories = new LinkedHashMap<>();
  private volatile boolean lastExecutionWasVirtual;
  private volatile int lastCallRetries;

  public AgentRuntime(
      AgentConfig config,
      LLMClient client,
      Semaphore globalSemaphore,
      ProviderCircuitBreaker circuitBreaker,
      int requestRetries,
      ExecutorService virtualExecutor,
      RetrySleeper retrySleeper,
      Clock clock) {
    this.config = Objects.requireNonNull(config, "config");
    this.client = Objects.requireNonNull(client, "client");
    this.globalSemaphore = Objects.requireNonNull(globalSemaphore, "globalSemaphore");
    this.agentSemaphore = new Semaphore(config.maxConcurrency(), true);
    this.rateLimiter =
        new SlidingWindowRateLimiter(
            config.requestsPerMinute(), clock, retrySleeper::sleep);
    this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
    this.providerScope =
        config.provider()
            + ":"
            + (config.baseUrl() == null
                ? config.provider()
                : config.baseUrl().replaceAll("/+$", "").toLowerCase(java.util.Locale.ROOT));
    if (requestRetries < 0) {
      throw new IllegalArgumentException("requestRetries must not be negative");
    }
    this.requestRetries = requestRetries;
    this.virtualExecutor = Objects.requireNonNull(virtualExecutor, "virtualExecutor");
    this.retrySleeper = Objects.requireNonNull(retrySleeper, "retrySleeper");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.trust = config.trustPrior();
  }

  public String id() {
    return config.id();
  }

  public String provider() {
    return config.provider();
  }

  public String model() {
    return config.model();
  }

  public AgentConfig config() {
    return config;
  }

  public boolean supportsRole(String role) {
    return config.roles().contains(role) || config.roles().contains("general");
  }

  public boolean inCooldown() {
    synchronized (this) {
      return cooldownUntil.isAfter(clock.instant());
    }
  }

  public double specialtyScore(Iterable<String> hints) {
    if (hints == null) {
      return 0.0d;
    }
    Set<String> specialties =
        config.specialties().stream()
            .map(value -> value.toLowerCase(java.util.Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    int total = 0;
    int matched = 0;
    for (String hint : hints) {
      total++;
      if (specialties.contains(hint.toLowerCase(java.util.Locale.ROOT))) {
        matched++;
      }
    }
    return total == 0 ? 0.0d : (double) matched / total;
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "Virtual-thread execution deliberately preserves the original typed runtime "
              + "failure so retry and failover policy can classify it.")
  public LLMResponse call(ProviderRequest request) {
    Future<LLMResponse> future =
        virtualExecutor.submit(
            () -> {
              lastExecutionWasVirtual = Thread.currentThread().isVirtual();
              return callOnVirtualThread(applyDefaults(request));
            });
    try {
      return future.get();
    } catch (InterruptedException exception) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw ProviderException.cancelled();
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException("virtual agent task failed", cause);
    }
  }

  private LLMResponse callOnVirtualThread(ProviderRequest request) {
    boolean globalAcquired = false;
    boolean agentAcquired = false;
    boolean active = false;
    long providerStarted = 0L;
    try {
      rateLimiter.acquire();
      globalSemaphore.acquire();
      globalAcquired = true;
      agentSemaphore.acquire();
      agentAcquired = true;
      activeCalls.incrementAndGet();
      active = true;
      providerStarted = System.nanoTime();
      ProviderException lastFailure = null;
      int attemptedRetries = 0;
      ProviderRequest attemptRequest = request;
      for (int attempt = 0; attempt <= requestRetries; attempt++) {
        circuitBreaker.assertAvailable(providerScope);
        try {
          LLMResponse response = client.complete(attemptRequest);
          lastCallRetries = attemptedRetries;
          recordSuccess(response);
          circuitBreaker.recordSuccess(providerScope);
          return response;
        } catch (ProviderException failure) {
          lastFailure = failure;
          recordFailedAttempt(failure);
          circuitBreaker.recordFailure(providerScope, id(), failure);
          if (!failure.retryable()
              || failure.statusCode() != null
                  && (failure.statusCode() == 401 || failure.statusCode() == 403)
              || attempt >= requestRetries) {
            break;
          }
          attemptRequest = retryRequest(attemptRequest, failure);
          Duration backoff =
              Duration.ofMillis(Math.min(8000L, 500L << Math.min(attempt, 4)));
          Duration retryAfter = failure.retryAfter();
          if (retryAfter != null && retryAfter.compareTo(backoff) > 0) {
            backoff = retryAfter;
          }
          attemptedRetries++;
          retrySleeper.sleep(backoff);
        }
      }
      recordFailure();
      lastCallRetries = attemptedRetries;
      throw new AgentCallFailure(
          id(),
          Objects.requireNonNull(lastFailure, "lastFailure"),
          attemptedRetries);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw ProviderException.cancelled();
    } finally {
      if (active) {
        providerBusyNanos.addAndGet(Math.max(0L, System.nanoTime() - providerStarted));
        activeCalls.decrementAndGet();
      }
      if (agentAcquired) {
        agentSemaphore.release();
      }
      if (globalAcquired) {
        globalSemaphore.release();
      }
    }
  }

  private ProviderRequest applyDefaults(ProviderRequest request) {
    Objects.requireNonNull(request, "request");
    Boolean thinkingEnabled =
        request.thinkingEnabled() == null
            ? config.thinkingEnabled()
            : request.thinkingEnabled();
    String reasoningEffort =
        Boolean.FALSE.equals(thinkingEnabled)
            ? null
            : request.reasoningEffort() == null
                ? config.reasoningEffort()
                : request.reasoningEffort();
    return new ProviderRequest(
        request.messages(),
        request.temperature(),
        Math.min(
            request.maxOutputTokens(),
            Math.min(config.maxOutputTokens(), config.providerMaxOutputTokens())),
        request.jsonMode(),
        request.schemaName(),
        request.schema(),
        thinkingEnabled,
        reasoningEffort,
        request.streaming() || config.streaming(),
        request.userId() == null ? config.userId() : request.userId(),
        request.cancelled());
  }

  static ProviderRequest retryRequest(
      ProviderRequest request, ProviderException failure) {
    if (failure.partialPublicContent().isEmpty()) {
      return request;
    }
    java.util.ArrayList<ChatMessage> messages =
        new java.util.ArrayList<>(request.messages());
    messages.add(
        new ChatMessage(
            "user",
            ("The previous stream disconnected. Continue from the exact public prefix "
                    + "below without repeating it.\n"
                    + "PUBLIC_OUTPUT_PREFIX_SHA256: "
                    + failure.partialPublicContentSha256()
                    + "\nPUBLIC_OUTPUT_PREFIX:\n"
                    + failure.partialPublicContent()
                    + "\nDo not reconstruct hidden chain-of-thought.")
                .strip()));
    return new ProviderRequest(
        messages,
        request.temperature(),
        request.maxOutputTokens(),
        request.jsonMode(),
        request.schemaName(),
        request.schema(),
        request.thinkingEnabled(),
        request.reasoningEffort(),
        request.streaming(),
        request.userId(),
        request.cancelled());
  }

  private synchronized void recordSuccess(LLMResponse response) {
    calls++;
    inputTokens = Math.addExact(inputTokens, response.inputTokens());
    outputTokens = Math.addExact(outputTokens, response.outputTokens());
    latencyMs += response.latencyMs();
    costUsd =
        costUsd.add(
            tokenCost(
                response.inputTokens(),
                response.outputTokens(),
                config.pricing().inputPerMillion(),
                config.pricing().outputPerMillion()));
    cooldownUntil = Instant.EPOCH;
  }

  private synchronized void recordFailedAttempt(ProviderException failure) {
    failedAttempts++;
    failureCategories.merge(failure.kind().name(), 1L, Long::sum);
  }

  private synchronized void recordFailure() {
    failures++;
    long exponent = Math.min(4L, Math.max(0L, failures - 1L));
    cooldownUntil =
        clock.instant().plusSeconds(Math.min(120L, 5L << exponent));
  }

  public synchronized void updateTrust(double delta) {
    trust = Math.max(0.0d, Math.min(1.0d, trust + delta));
  }

  public synchronized double trust() {
    return trust;
  }

  public int activeCalls() {
    return activeCalls.get();
  }

  public int reservedCalls() {
    return reservedCalls.get();
  }

  int availableLeaseCapacity() {
    return Math.max(0, config.maxConcurrency() - activeCalls.get() - reservedCalls.get());
  }

  void reserveLease(int permits, long queueWaitNanos) {
    int reserved = reservedCalls.addAndGet(permits);
    if (activeCalls.get() + reserved > config.maxConcurrency()) {
      reservedCalls.addAndGet(-permits);
      throw new IllegalStateException("agent lease capacity exceeded: " + id());
    }
    leasedCalls.incrementAndGet();
    leaseQueueWaitNanos.addAndGet(Math.max(0L, queueWaitNanos));
  }

  void releaseLease(int permits, long busyNanos) {
    int remaining = reservedCalls.addAndGet(-permits);
    if (remaining < 0) {
      reservedCalls.addAndGet(permits);
      throw new IllegalStateException("agent lease released more than once: " + id());
    }
    leaseBusyNanos.addAndGet(Math.max(0L, busyNanos));
  }

  void recordIdleWhileEligible(long idleNanos) {
    idleWhileEligibleNanos.addAndGet(Math.max(0L, idleNanos));
  }

  public int lastCallRetries() {
    return lastCallRetries;
  }

  public synchronized AgentRuntimeMetric metric() {
    return new AgentRuntimeMetric(
        id(),
        calls,
        inputTokens,
        outputTokens,
        costUsd,
        latencyMs,
        trust,
        failures,
        failedAttempts,
        Map.copyOf(failureCategories),
        activeCalls(),
        reservedCalls(),
        leasedCalls.get(),
        leaseQueueWaitNanos.get() / 1_000_000.0d,
        leaseBusyNanos.get() / 1_000_000.0d,
        providerBusyNanos.get() / 1_000_000.0d,
        idleWhileEligibleNanos.get() / 1_000_000.0d,
        lastExecutionWasVirtual);
  }

  private static BigDecimal tokenCost(
      long input,
      long output,
      double inputRate,
      double outputRate) {
    BigDecimal million = BigDecimal.valueOf(1_000_000L);
    return BigDecimal.valueOf(input)
        .multiply(BigDecimal.valueOf(inputRate))
        .divide(million, 12, RoundingMode.HALF_UP)
        .add(
            BigDecimal.valueOf(output)
                .multiply(BigDecimal.valueOf(outputRate))
                .divide(million, 12, RoundingMode.HALF_UP))
        .stripTrailingZeros();
  }
}
