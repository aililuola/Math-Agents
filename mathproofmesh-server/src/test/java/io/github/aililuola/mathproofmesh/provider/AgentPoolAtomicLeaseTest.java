package io.github.aililuola.mathproofmesh.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseClass;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AgentPoolAtomicLeaseTest {
  @Test
  void researchCannotConsumeReservedCoordinationCredential() {
    SystemConfig config = AgentLeaseTestSupport.config();
    try (AgentPool pool = AgentLeaseTestSupport.pool(config, AgentLeaseTestSupport.responders(config))) {
      List<AgentLease> research = new ArrayList<>();
      for (int index = 0; index < 4; index++) {
        research.add(pool.acquireLease(AgentLeaseTestSupport.request("r-" + index, AgentLeaseClass.RESEARCH, "explorer")));
      }
      assertThat(pool.tryAcquireLease(AgentLeaseTestSupport.request("r-5", AgentLeaseClass.RESEARCH, "explorer")))
          .isEmpty();
      try (AgentLease coordination =
          pool.acquireLease(AgentLeaseTestSupport.request("review", AgentLeaseClass.COORDINATION, "route_referee"))) {
        assertThat(coordination.agent()).isNotNull();
      }
      research.forEach(AgentLease::close);
    }
  }
}
