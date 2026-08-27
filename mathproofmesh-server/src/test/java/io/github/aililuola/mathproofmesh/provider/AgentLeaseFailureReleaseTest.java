package io.github.aililuola.mathproofmesh.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseClass;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class AgentLeaseFailureReleaseTest {
  @Test
  void providerFailureCannotLeakLease() {
    SystemConfig config = AgentLeaseTestSupport.config();
    Map<String, MockResponder> responders = new java.util.LinkedHashMap<>(AgentLeaseTestSupport.responders(config));
    responders.put(
        "agent-0",
        ignored -> {
          throw ProviderException.timeout("provider-call", new IllegalStateException("timeout"));
        });
    try (AgentPool pool = AgentLeaseTestSupport.pool(config, responders)) {
      try (AgentLease lease = pool.acquireLease(AgentLeaseTestSupport.request("work", AgentLeaseClass.RESEARCH, "explorer"))) {
        assertThatThrownBy(
                () ->
                    lease.call(
                        ProviderRequest.json(
                            java.util.List.of(new ChatMessage("user", "test")), 10, false)))
            .isInstanceOf(AgentCallFailure.class);
      }
      assertThat(pool.metrics()).allMatch(metric -> metric.reservedCalls() == 0);
    }
  }
}
