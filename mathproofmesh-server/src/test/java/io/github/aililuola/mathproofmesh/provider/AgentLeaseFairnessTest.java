package io.github.aililuola.mathproofmesh.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseClass;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class AgentLeaseFairnessTest {
  @Test
  void runLeaseCountsPreventTrustFromStarvingOtherKeys() {
    SystemConfig config = AgentLeaseTestSupport.config();
    try (AgentPool pool = AgentLeaseTestSupport.pool(config, AgentLeaseTestSupport.responders(config))) {
      Set<String> selected = new LinkedHashSet<>();
      for (int index = 0; index < 5; index++) {
        try (AgentLease lease = pool.acquireLease(
            AgentLeaseTestSupport.request("work-" + index, AgentLeaseClass.RESEARCH, "explorer"))) {
          selected.add(lease.agent().id());
        }
      }
      assertThat(selected).hasSize(5);
    }
  }
}
