package io.github.aililuola.mathproofmesh.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseClass;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseSnapshot;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseStatus;
import org.junit.jupiter.api.Test;

final class AgentLeaseHardCrashRecoveryTest {
  @Test
  void restoreFencesLeasesOwnedByDeadProcess() {
    SystemConfig config = AgentLeaseTestSupport.config();
    AgentLeaseSnapshot crashed;
    try (AgentPool pool = AgentLeaseTestSupport.pool(config, AgentLeaseTestSupport.responders(config))) {
      pool.acquireLease(AgentLeaseTestSupport.request("work", AgentLeaseClass.RESEARCH, "explorer"));
      crashed = pool.leaseSnapshot();
    }
    try (AgentPool restored = AgentLeaseTestSupport.pool(config, AgentLeaseTestSupport.responders(config))) {
      restored.restoreLeases(crashed, "run");
      assertThat(restored.leaseSnapshot().leases())
          .allMatch(record -> record.status() == AgentLeaseStatus.ABANDONED);
      try (AgentLease retry = restored.acquireLease(
          AgentLeaseTestSupport.request("retry", AgentLeaseClass.RESEARCH, "explorer"))) {
        assertThat(retry.agent()).isNotNull();
      }
    }
  }
}
