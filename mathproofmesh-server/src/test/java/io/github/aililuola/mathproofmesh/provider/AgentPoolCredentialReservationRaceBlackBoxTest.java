package io.github.aililuola.mathproofmesh.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.config.StrictYamlConfigLoader;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseClass;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

final class AgentPoolCredentialReservationRaceBlackBoxTest {
  @Test
  void concurrentSelectionMustReserveFourDifferentCredentialCapacities() throws Exception {
    SystemConfig config =
        new StrictYamlConfigLoader()
            .read(
                """
                agents:
                  - {id: agent-0, provider: mock, model: mock, roles: [explorer], trust_prior: 1.0}
                  - {id: agent-1, provider: mock, model: mock, roles: [explorer], trust_prior: 0.5}
                  - {id: agent-2, provider: mock, model: mock, roles: [explorer], trust_prior: 0.5}
                  - {id: agent-3, provider: mock, model: mock, roles: [explorer], trust_prior: 0.5}
                  - {id: agent-4, provider: mock, model: mock, roles: [explorer], trust_prior: 0.5}
                runtime: {max_parallel_calls: 5}
                concurrency:
                  research_slots: 4
                  coordination_slots: 1
                  max_in_flight_tasks: 5
                  max_focused_parallel_roles: 4
                  reserve_coordination_capacity: true
                """);
    Map<String, MockResponder> responders =
        config.agents().stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    agent -> agent.id(), agent -> ignored -> response()));
    try (AgentPool pool =
            new AgentPool(
                config,
                new ProviderClientRegistry(
                    config,
                    responders,
                    ignored ->
                        request -> {
                          throw new AssertionError("mock provider must not use HTTP");
                        },
                    false));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch acquired = new CountDownLatch(4);
      CountDownLatch release = new CountDownLatch(1);
      List<Future<String>> futures =
          java.util.stream.IntStream.range(0, 4)
              .mapToObj(
                  index ->
                      executor.submit(
                          () -> {
                            AgentLeaseRequest request =
                                new AgentLeaseRequest(
                                    "run",
                                    "epoch",
                                    "work-" + index,
                                    AgentLeaseClass.RESEARCH,
                                    "explorer",
                                    Set.of(),
                                    List.of(),
                                    "",
                                    "",
                                    1);
                            try (AgentLease lease = pool.acquireLease(request)) {
                              acquired.countDown();
                              release.await();
                              return lease.agent().id();
                            }
                          }))
              .toList();
      acquired.await();
      release.countDown();
      List<String> selected =
          futures.stream()
              .map(
                  future -> {
                    try {
                      return future.get();
                    } catch (Exception exception) {
                      throw new IllegalStateException(exception);
                    }
                  })
              .toList();

      assertThat(selected).doesNotHaveDuplicates();
      assertThat(pool.leaseSnapshot().leases()).hasSize(4).allMatch(record -> record.terminal());
    }
  }

  private static LLMResponse response() {
    return new LLMResponse("{}", "mock", "mock", 0, 0, 1.0d, null, "stop", false, null);
  }
}
