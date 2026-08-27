package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class AgentLeaseLedgerTest {
  @Test
  void restoreAbandonsNonterminalLeaseFromActiveRun() {
    AgentLeaseRequest request =
        new AgentLeaseRequest(
            "run", "epoch", "work", AgentLeaseClass.RESEARCH, "explorer", Set.of(),
            List.of(), "", "", 1);
    AgentLeaseLedger source = new AgentLeaseLedger();
    AgentLeaseRecord lease = source.acquire("lease", request, "agent", 10L);
    source.transition(lease.leaseId(), AgentLeaseStatus.RUNNING, 11L);
    AgentLeaseLedger restored = new AgentLeaseLedger();
    restored.restore(source.snapshot(), "run");
    assertThat(restored.snapshot().leases().getFirst().status())
        .isEqualTo(AgentLeaseStatus.ABANDONED);
  }
}
