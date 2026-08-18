package io.github.aililuola.mathproofmesh.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseClass;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class AgentLeaseCooldownIsolationTest {
  @Test
  void failedCredentialDoesNotPreventAnotherCredentialFromBeingLeased() {
    SystemConfig config = AgentLeaseTestSupport.config();
    Map<String, MockResponder> responders = new LinkedHashMap<>(AgentLeaseTestSupport.responders(config));
    responders.put("agent-0", ignored -> { throw ProviderException.http(500, null); });
    try (AgentPool pool = AgentLeaseTestSupport.pool(config, responders)) {
      try (AgentLease lease = pool.acquireLease(
          AgentLeaseTestSupport.request("bad", AgentLeaseClass.RESEARCH, "explorer"))) {
        assertThat(lease.agent().id()).isEqualTo("agent-0");
        assertThatThrownBy(
                () -> lease.call(ProviderRequest.json(List.of(new ChatMessage("user", "test")), 10, false)))
            .isInstanceOf(AgentCallFailure.class);
      }
      try (AgentLease next = pool.acquireLease(
          AgentLeaseTestSupport.request("good", AgentLeaseClass.RESEARCH, "explorer"))) {
        assertThat(next.agent().id()).isNotEqualTo("agent-0");
      }
    }
  }
}
