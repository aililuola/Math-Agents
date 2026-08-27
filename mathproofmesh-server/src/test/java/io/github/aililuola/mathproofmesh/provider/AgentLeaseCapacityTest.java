package io.github.aililuola.mathproofmesh.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseClass;
import org.junit.jupiter.api.Test;

final class AgentLeaseCapacityTest {
  @Test
  void closingLeaseReturnsCredentialCapacity() {
    SystemConfig config = AgentLeaseTestSupport.config();
    try (AgentPool pool = AgentLeaseTestSupport.pool(config, AgentLeaseTestSupport.responders(config))) {
      AgentLease first = pool.acquireLease(AgentLeaseTestSupport.request("first", AgentLeaseClass.RESEARCH, "explorer"));
      String agent = first.agent().id();
      assertThat(first.agent().reservedCalls()).isOne();
      first.close();
      assertThat(pool.get(agent).reservedCalls()).isZero();
    }
  }
}
