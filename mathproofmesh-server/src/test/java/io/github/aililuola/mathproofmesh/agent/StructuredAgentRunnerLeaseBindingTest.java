package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.config.StrictYamlConfigLoader;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseClass;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseRequest;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StructuredAgentRunnerLeaseBindingTest {
  @TempDir Path temporaryDirectory;

  @Test
  void structuredCallUsesTheAlreadyReservedCredential() {
    var config =
        new StrictYamlConfigLoader()
            .read(
                """
                agents:
                  - {id: leased-agent, provider: mock, model: mock, roles: [explorer]}
                runtime: {max_parallel_calls: 1, request_retries: 0}
                concurrency: {research_slots: 1, coordination_slots: 0, max_in_flight_tasks: 1}
                """);
    ProviderClientRegistry providers =
        new ProviderClientRegistry(
            config,
            Map.of(
                "leased-agent",
                ignored ->
                    new LLMResponse(
                        "{\"answer\":\"ok\"}",
                        "mock",
                        "mock",
                        1,
                        1,
                        1.0d,
                        "provider-request",
                        "stop",
                        false,
                        null)),
            ignored -> request -> {
              throw new AssertionError("mock provider must not use HTTP");
            },
            false);
    try (AgentPool pool = new AgentPool(config, providers)) {
      StructuredAgentRunner runner =
          new StructuredAgentRunner(
              pool,
              new ArtifactStore(temporaryDirectory, "leased-run"),
              new InMemoryProviderCallRepository(),
              new CallLedger(1000L, null, null),
              new PromptRedactor(List.of()),
              new BoundedJsonRepairer(4096),
              null,
              0,
              4096);
      AgentLeaseRequest request =
          new AgentLeaseRequest(
              "leased-run",
              "epoch",
              "work",
              AgentLeaseClass.RESEARCH,
              "explorer",
              Set.of(),
              List.of(),
              "",
              "",
              1);
      try (var lease = pool.acquireLease(request)) {
        var result =
            runner.callLeased(
                "leased-run",
                "leased-call",
                new PromptBundle<>(
                    "route_review",
                    "Return strict JSON.",
                    "Review.",
                    Answer.class,
                    0.0d,
                    64,
                    false,
                    null),
                lease,
                "review",
                null,
                null);
        assertThat(result.agentId()).isEqualTo("leased-agent");
        assertThat(result.value().answer()).isEqualTo("ok");
        assertThat(result.attemptedAgents()).containsExactly("leased-agent");
      }
      assertThat(pool.leaseSnapshot().leases()).singleElement()
          .satisfies(record -> assertThat(record.terminal()).isTrue());
    }
  }

  private record Answer(String answer) {}
}
