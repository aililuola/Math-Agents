package io.github.aililuola.mathproofmesh.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseClass;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class GlobalParallelLimitTest {
  @Test
  void leasesNeverExceedConfiguredGlobalProviderCapacity() {
    var config = AgentLeaseTestSupport.config();
    CountDownLatch allStarted = new CountDownLatch(5);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximum = new AtomicInteger();
    Map<String, MockResponder> responders =
        config.agents().stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    agent -> agent.id(),
                    agent ->
                        request -> {
                          int current = active.incrementAndGet();
                          maximum.accumulateAndGet(current, Math::max);
                          allStarted.countDown();
                          try {
                            if (!release.await(5L, TimeUnit.SECONDS)) {
                              throw new AssertionError("provider overlap barrier timed out");
                            }
                          } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("provider overlap barrier interrupted", exception);
                          } finally {
                            active.decrementAndGet();
                          }
                          return AgentLeaseTestSupport.response();
                        }));
    try (AgentPool pool =
            AgentLeaseTestSupport.pool(config, responders);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var leases = new ArrayList<AgentLease>();
      for (int index = 0; index < 4; index++) {
        leases.add(
            pool.acquireLease(
                AgentLeaseTestSupport.request(
                    "research-" + index, AgentLeaseClass.RESEARCH, "explorer")));
      }
      leases.add(
          pool.acquireLease(
              AgentLeaseTestSupport.request(
                  "coordination", AgentLeaseClass.COORDINATION, "route_referee")));
      assertThat(pool.tryAcquireLease(
              AgentLeaseTestSupport.request(
                  "overflow", AgentLeaseClass.COORDINATION, "route_referee")))
          .isEmpty();
      assertThat(leases).hasSize(5);
      List<Future<LLMResponse>> calls =
          leases.stream()
              .map(
                  lease ->
                      executor.submit(
                          () ->
                              lease.call(
                                  ProviderRequest.json(
                                      List.of(new ChatMessage("user", lease.leaseId())),
                                      32,
                                      false))))
              .toList();
      try {
        assertThat(allStarted.await(5L, TimeUnit.SECONDS)).isTrue();
        assertThat(active.get()).isEqualTo(5);
        assertThat(maximum.get()).isEqualTo(5);
        assertThat(pool.concurrencyMetrics().maxActiveProviderCalls()).isEqualTo(5);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("test interrupted", exception);
      } finally {
        release.countDown();
      }
      calls.forEach(GlobalParallelLimitTest::join);
      leases.forEach(AgentLease::close);
    }
  }

  private static void join(Future<LLMResponse> future) {
    try {
      future.get(5L, TimeUnit.SECONDS);
    } catch (Exception exception) {
      throw new AssertionError("provider call did not settle", exception);
    }
  }
}
