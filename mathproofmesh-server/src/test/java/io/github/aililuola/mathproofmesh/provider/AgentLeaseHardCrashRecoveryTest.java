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

  @Test
  void reacquiringTheSameWorkIdentityAfterRestoreUsesANewLeaseFence() {
    SystemConfig config = AgentLeaseTestSupport.config();
    AgentLeaseSnapshot checkpoint;
    String firstLeaseId;
    try (AgentPool pool = AgentLeaseTestSupport.pool(config, AgentLeaseTestSupport.responders(config))) {
      try (AgentLease lease =
          pool.acquireLease(
              AgentLeaseTestSupport.request("same-work", AgentLeaseClass.RESEARCH, "explorer"))) {
        firstLeaseId = lease.leaseId();
      }
      checkpoint = pool.leaseSnapshot();
    }

    try (AgentPool restored =
        AgentLeaseTestSupport.pool(config, AgentLeaseTestSupport.responders(config))) {
      restored.restoreLeases(checkpoint, "run");
      String restoredLeaseId;
      try (AgentLease lease =
          restored.acquireLease(
              AgentLeaseTestSupport.request("same-work", AgentLeaseClass.RESEARCH, "explorer"))) {
        restoredLeaseId = lease.leaseId();
      }
      assertThat(restoredLeaseId).isNotEqualTo(firstLeaseId);
      try (AgentLease next =
          restored.acquireLease(
              AgentLeaseTestSupport.request("next-work", AgentLeaseClass.RESEARCH, "explorer"))) {
        assertThat(next.agent()).isNotNull();
      }
    }
  }
}
