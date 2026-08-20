package io.github.aililuola.mathproofmesh.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseClass;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseLedger;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseRequest;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseStatus;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TemporalAgentLeaseFencingTest {
  @Test
  void restoreAbandonsAStaleRunningLeaseBeforeNewWorkCanCommit() {
    AgentLeaseRequest request =
        new AgentLeaseRequest(
            "run", "epoch", "work", AgentLeaseClass.RESEARCH, "explorer", Set.of(), List.of(), "", "", 1);
    AgentLeaseLedger first = new AgentLeaseLedger();
    first.acquire("lease-old", request, "agent-0", 1L);
    first.transition("lease-old", AgentLeaseStatus.RUNNING, 2L);

    AgentLeaseLedger restored = new AgentLeaseLedger();
    restored.restore(first.snapshot(), "run");
    assertThat(restored.snapshot().leases()).singleElement()
        .extracting(lease -> lease.status())
        .isEqualTo(AgentLeaseStatus.ABANDONED);
  }
}
