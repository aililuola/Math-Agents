package io.github.aililuola.mathproofmesh.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.config.AgentConfig;
import io.github.aililuola.mathproofmesh.config.PricingConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class Phase17ConcurrentMockPerformanceBenchmarkTest {
  private static final int CALLS = 100;
  private static final int CONCURRENCY_LIMIT = 8;

  @Test
  void hundredConcurrentMockCallsRemainBoundedAndLeakFree() throws Exception {
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximum = new AtomicInteger();
    MockProvider provider =
        new MockProvider(
            "mock-model",
            request -> {
              int current = active.incrementAndGet();
              maximum.accumulateAndGet(current, Math::max);
              try {
                Thread.sleep(2);
                return new LLMResponse(
                    "ok",
                    "mock-model",
                    "mock",
                    3,
                    5,
                    0.0d,
                    "phase17-mock",
                    "stop",
                    false,
                    JsonNodeFactory.instance.objectNode());
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw ProviderException.cancelled();
              } finally {
                active.decrementAndGet();
              }
            });

    long elapsed;
    AgentRuntimeMetric metric;
    try (var runtimeExecutor = Executors.newVirtualThreadPerTaskExecutor();
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      AgentRuntime runtime =
          new AgentRuntime(
              agent(),
              provider,
              new Semaphore(CONCURRENCY_LIMIT, true),
              breaker(),
              0,
              runtimeExecutor,
              ignored -> {},
              Clock.systemUTC());
      List<Callable<String>> tasks = new ArrayList<>();
      for (int index = 0; index < CALLS; index++) {
        tasks.add(
            () ->
                runtime
                    .call(
                        ProviderRequest.json(
                            List.of(new ChatMessage("user", "prove p")), 64, false))
                    .text());
      }
      for (int index = 0; index < CONCURRENCY_LIMIT; index++) {
        assertThat(
                runtime
                    .call(
                        ProviderRequest.json(
                            List.of(new ChatMessage("user", "warm up")), 64, false))
                    .text())
            .isEqualTo("ok");
      }
      long started = System.nanoTime();
      var futures = callers.invokeAll(tasks);
      for (var future : futures) {
        assertThat(future.get()).isEqualTo("ok");
      }
      elapsed = System.nanoTime() - started;
      metric = runtime.metric();
    }

    assertThat(metric.calls()).isEqualTo(CALLS + CONCURRENCY_LIMIT);
    assertThat(metric.failedAttempts()).isZero();
    assertThat(maximum.get()).isBetween(2, CONCURRENCY_LIMIT);
    assertThat(active).hasValue(0);

    Path report =
        Path.of(System.getProperty("mathproofmesh.projectRoot"))
            .resolve("target/benchmark-reports/phase17-concurrent-mock.json");
    Files.createDirectories(report.getParent());
    Files.writeString(
        report,
        """
        {
          "scenario":"100-concurrent-mock-agent-calls",
          "calls":%d,
          "concurrency_limit":%d,
          "observed_max_concurrency":%d,
          "elapsed_ns":%d,
          "failed_calls":0,
          "active_after_completion":0,
          "result":"PASS"
        }
        """
            .formatted(CALLS, CONCURRENCY_LIMIT, maximum.get(), elapsed),
        StandardCharsets.UTF_8);
  }

  private static AgentConfig agent() {
    return new AgentConfig(
        "phase17-agent",
        "mock",
        "mock-model",
        null,
        null,
        null,
        List.of("general"),
        List.of("algebra"),
        CONCURRENCY_LIMIT,
        null,
        0.0d,
        1_024,
        1_024,
        30.0d,
        0.6d,
        true,
        new PricingConfig(0.0d, 0.0d),
        Map.of(),
        null,
        false,
        null,
        false,
        null);
  }

  private static ProviderCircuitBreaker breaker() {
    return new ProviderCircuitBreaker(
        true,
        5,
        Duration.ofMinutes(1),
        Duration.ofMinutes(5),
        Set.of(),
        Set.of(401, 403),
        new InMemoryCircuitStateStore(),
        Clock.systemUTC());
  }
}
