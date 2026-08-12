package io.github.aililuola.mathproofmesh.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.config.AgentConfig;
import io.github.aililuola.mathproofmesh.config.PricingConfig;
import io.github.aililuola.mathproofmesh.config.StrictYamlConfigLoader;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AgentRuntimePolicyTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);

  @Test
  void authenticationFailureIsNeverRetriedOnTheSameCredential() {
    AtomicInteger calls = new AtomicInteger();
    LLMClient client =
        new StubClient(
            request -> {
              calls.incrementAndGet();
              throw ProviderException.http(401, null);
            });
    List<Duration> sleeps = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      AgentRuntime runtime =
          runtime("agent-a", 3, client, executor, sleeps::add, breaker());

      assertThatThrownBy(() -> runtime.call(request()))
          .isInstanceOf(AgentCallFailure.class)
          .satisfies(
              failure -> {
                AgentCallFailure callFailure = (AgentCallFailure) failure;
                assertThat(callFailure.retries()).isZero();
                assertThat(callFailure.providerFailure().statusCode())
                    .isEqualTo(401);
              });
      assertThat(calls).hasValue(1);
      assertThat(sleeps).isEmpty();
    }
  }

  @Test
  void retryableStatusReconnectsAndHonorsLongerRetryAfter() {
    AtomicInteger calls = new AtomicInteger();
    LLMClient client =
        new StubClient(
            request -> {
              if (calls.incrementAndGet() == 1) {
                throw ProviderException.http(429, Duration.ofSeconds(2));
              }
              return response("reconnected");
            });
    List<Duration> sleeps = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      AgentRuntime runtime =
          runtime("agent-a", 2, client, executor, sleeps::add, breaker());

      assertThat(runtime.call(request()).text()).isEqualTo("reconnected");
      assertThat(calls).hasValue(2);
      assertThat(runtime.lastCallRetries()).isEqualTo(1);
      assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
      assertThat(runtime.metric().lastExecutionWasVirtual()).isTrue();
    }
  }

  @Test
  void perCredentialSemaphoreIsFairAndBoundsConcurrentCalls() throws Exception {
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximum = new AtomicInteger();
    LLMClient client =
        new StubClient(
            request -> {
              int now = active.incrementAndGet();
              maximum.accumulateAndGet(now, Math::max);
              try {
                Thread.sleep(40);
                return response("ok");
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw ProviderException.cancelled();
              } finally {
                active.decrementAndGet();
              }
            });
    try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor();
        ExecutorService callers = Executors.newFixedThreadPool(4)) {
      AgentRuntime runtime =
          runtime("agent-a", 0, client, virtual, ignored -> {}, breaker());
      List<Callable<String>> tasks =
          List.of(
              () -> runtime.call(request()).text(),
              () -> runtime.call(request()).text(),
              () -> runtime.call(request()).text(),
              () -> runtime.call(request()).text());

      assertThat(callers.invokeAll(tasks))
          .allSatisfy(
              future -> assertThat(future.get()).isEqualTo("ok"));
      assertThat(maximum).hasValue(1);
      assertThat(runtime.metric().calls()).isEqualTo(4);
    }
  }

  @Test
  void slidingWindowRateLimiterReopensExactlyAtWindowBoundary() {
    SlidingWindowRateLimiter limiter =
        new SlidingWindowRateLimiter(2, CLOCK, ignored -> {});
    Instant now = CLOCK.instant();

    assertThat(limiter.tryAcquire(now)).isTrue();
    assertThat(limiter.tryAcquire(now.plusSeconds(1))).isTrue();
    assertThat(limiter.tryAcquire(now.plusSeconds(59))).isFalse();
    assertThat(limiter.tryAcquire(now.plusSeconds(60))).isTrue();
    assertThat(limiter.inWindow()).isEqualTo(2);
  }

  @Test
  void providerCircuitStateSurvivesBreakerRecreationAndNeedsDistinctKeys() {
    InMemoryCircuitStateStore store = new InMemoryCircuitStateStore();
    ProviderCircuitBreaker first = breaker(store);
    ProviderCircuitBreaker restarted = breaker(store);

    first.recordFailure(
        "provider:scope",
        "key-a",
        ProviderException.http(500, null));
    assertThat(first.snapshot("provider:scope").openUntil()).isNull();

    assertThatThrownBy(
            () ->
                restarted.recordFailure(
                    "provider:scope",
                    "key-b",
                    ProviderException.http(500, null)))
        .isInstanceOf(ProviderCircuitOpenError.class);
    assertThatThrownBy(
            () -> breaker(store).assertAvailable("provider:scope"))
        .isInstanceOf(ProviderCircuitOpenError.class);
  }

  @Test
  void providerCircuitDoesNotTreatEstablishedStreamDisconnectAsSharedOutage() {
    InMemoryCircuitStateStore store = new InMemoryCircuitStateStore();
    ProviderCircuitBreaker circuit = breaker(store);

    circuit.recordFailure(
        "provider:scope",
        "key-a",
        ProviderException.network(new IOException("stream reset"), "", 2_465));
    circuit.recordFailure(
        "provider:scope",
        "key-b",
        ProviderException.network(new IOException("stream reset"), "partial answer", 41_446));

    assertThat(circuit.snapshot("provider:scope").failures()).isEmpty();
    circuit.assertAvailable("provider:scope");
  }

  @Test
  void providerResponseLimitCoversConfiguredLongReasoningStream() {
    SystemConfig config =
        new StrictYamlConfigLoader()
            .read(
                """
                agents:
                  - id: planner
                    provider: mock
                    model: mock-model
                    roles: [planner]
                    max_output_tokens: 384000
                    provider_max_output_tokens: 384000
                """);

    assertThat(ProviderClientRegistry.responseByteLimit(config))
        .isEqualTo(1_048_576L + 384_000L * 512L)
        .isGreaterThan(16L * 1024 * 1024);
  }

  @Test
  void poolFailsOverAfterAuthFailureButNeverSelectsAuthorAsReviewer() {
    SystemConfig config =
        new StrictYamlConfigLoader()
            .read(
                """
                agents:
                  - id: author
                    provider: mock
                    model: mock-author
                    roles: [general, detailed_verifier]
                  - id: reviewer
                    provider: mock
                    model: mock-reviewer
                    roles: [general, detailed_verifier]
                runtime:
                  request_retries: 3
                """);
    Map<String, MockResponder> responders =
        Map.of(
            "author",
            request -> {
              throw ProviderException.http(401, null);
            },
            "reviewer",
            request -> response("reviewed"));
    ProviderClientRegistry registry =
        new ProviderClientRegistry(
            config, responders, ignored -> unreachableTransport(), false);
    try (AgentPool pool =
        new AgentPool(
            config,
            registry,
            new InMemoryCircuitStateStore(),
            ignored -> {},
            CLOCK)) {
      assertThat(
              pool.selectReviewer(
                      "detailed_verifier", "author", List.of())
                  .id())
          .isEqualTo("reviewer");

      AgentPool.FailoverResult result =
          pool.callWithFailover(
              "general",
              pool.get("author"),
              ignored -> request(),
              Set.of(),
              List.of(),
              1);
      assertThat(result.response().text()).isEqualTo("reviewed");
      assertThat(result.agent().id()).isEqualTo("reviewer");
      assertThat(result.attemptedAgents())
          .containsExactly("author", "reviewer");
      assertThat(pool.get("author").metric().failedAttempts()).isEqualTo(1);
    }
  }

  private static AgentRuntime runtime(
      String id,
      int retries,
      LLMClient client,
      ExecutorService executor,
      RetrySleeper sleeper,
      ProviderCircuitBreaker breaker) {
    return new AgentRuntime(
        agent(id),
        client,
        new Semaphore(8, true),
        breaker,
        retries,
        executor,
        sleeper,
        CLOCK);
  }

  private static AgentConfig agent(String id) {
    return new AgentConfig(
        id,
        "mock",
        "mock-model",
        null,
        null,
        null,
        List.of("general"),
        List.of("algebra"),
        1,
        null,
        0.0d,
        1024,
        1024,
        30.0d,
        0.6d,
        true,
        new PricingConfig(1.0d, 2.0d),
        Map.of(),
        null,
        false,
        null,
        false,
        null);
  }

  private static ProviderCircuitBreaker breaker() {
    return breaker(new InMemoryCircuitStateStore());
  }

  private static ProviderCircuitBreaker breaker(CircuitStateStore store) {
    return new ProviderCircuitBreaker(
        true,
        2,
        Duration.ofMinutes(1),
        Duration.ofMinutes(5),
        Set.of(),
        Set.of(401, 403),
        store,
        CLOCK);
  }

  private static ProviderRequest request() {
    return ProviderRequest.json(
        List.of(new ChatMessage("user", "prove p")), 256, false);
  }

  private static LLMResponse response(String text) {
    return new LLMResponse(
        text,
        "mock-model",
        "mock",
        3,
        5,
        7.0d,
        "fixture-request",
        "stop",
        false,
        JsonNodeFactory.instance.objectNode());
  }

  private static HttpTransport unreachableTransport() {
    return request -> {
      throw new AssertionError("mock providers must not use HTTP");
    };
  }

  private static final class StubClient implements LLMClient {
    private final Function<ProviderRequest, LLMResponse> responder;

    private StubClient(Function<ProviderRequest, LLMResponse> responder) {
      this.responder = responder;
    }

    @Override
    public String providerId() {
      return "mock";
    }

    @Override
    public LLMResponse complete(ProviderRequest request) {
      return responder.apply(request);
    }
  }
}
