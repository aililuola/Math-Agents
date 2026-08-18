package io.github.aililuola.mathproofmesh.provider;

import io.github.aililuola.mathproofmesh.config.StrictYamlConfigLoader;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseClass;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AgentLeaseTestSupport {
  private AgentLeaseTestSupport() {}

  static SystemConfig config() {
    return new StrictYamlConfigLoader()
        .read(
            """
            agents:
              - {id: agent-0, provider: mock, model: mock, roles: [explorer, route_referee], trust_prior: 1.0}
              - {id: agent-1, provider: mock, model: mock, roles: [explorer, route_referee], trust_prior: 0.5}
              - {id: agent-2, provider: mock, model: mock, roles: [explorer, route_referee], trust_prior: 0.5}
              - {id: agent-3, provider: mock, model: mock, roles: [explorer, route_referee], trust_prior: 0.5}
              - {id: agent-4, provider: mock, model: mock, roles: [explorer, route_referee], trust_prior: 0.5}
            runtime: {max_parallel_calls: 5, request_retries: 0}
            concurrency:
              research_slots: 4
              coordination_slots: 1
              max_in_flight_tasks: 5
              max_focused_parallel_roles: 4
              reserve_coordination_capacity: true
              lease_timeout_seconds: 1
            """);
  }

  static AgentPool pool(SystemConfig config, Map<String, MockResponder> responders) {
    return new AgentPool(
        config,
        new ProviderClientRegistry(
            config,
            responders,
            ignored -> request -> { throw new AssertionError("mock provider must not use HTTP"); },
            false));
  }

  static Map<String, MockResponder> responders(SystemConfig config) {
    return config.agents().stream()
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                agent -> agent.id(), agent -> ignored -> response()));
  }

  static AgentLeaseRequest request(String work, AgentLeaseClass leaseClass, String role) {
    return new AgentLeaseRequest(
        "run", "epoch", work, leaseClass, role, Set.of(), List.of(), "", "", 1);
  }

  static LLMResponse response() {
    return new LLMResponse("{}", "mock", "mock", 0, 0, 1.0d, null, "stop", false, null);
  }
}
