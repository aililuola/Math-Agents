package io.github.aililuola.mathproofmesh.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseClass;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class PerAgentParallelLimitTest {
  @Test
  void singleCapacityCredentialsCannotReceiveCollidingLeases() {
    var config = AgentLeaseTestSupport.config();
    try (AgentPool pool =
        AgentLeaseTestSupport.pool(config, AgentLeaseTestSupport.responders(config))) {
      var leases = new ArrayList<AgentLease>();
      for (int index = 0; index < 4; index++) {
        leases.add(
            pool.acquireLease(
                AgentLeaseTestSupport.request(
                    "work-" + index, AgentLeaseClass.RESEARCH, "explorer")));
      }
      assertThat(leases).extracting(lease -> lease.agent().id()).doesNotHaveDuplicates();
      assertThat(leases).allSatisfy(lease -> assertThat(lease.agent().reservedCalls()).isOne());
      leases.forEach(AgentLease::close);
    }
  }
}
