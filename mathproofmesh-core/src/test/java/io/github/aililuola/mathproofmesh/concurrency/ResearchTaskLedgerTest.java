package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class ResearchTaskLedgerTest {
  @Test
  void restoreQuarantinesAnUncertainProviderCallButReplansUnstartedWork() {
    FrozenResearchSnapshot snapshot = ConcurrencyTestFixtures.snapshot();
    ResearchWorkItem started = ConcurrencyTestFixtures.item(snapshot, 0, "a", null);
    ResearchWorkItem leased = ConcurrencyTestFixtures.item(snapshot, 1, "b", null);
    ResearchTaskLedger source = new ResearchTaskLedger();
    source.plan(started);
    source.transition(started.workItemId(), ResearchWorkStatus.RUNNING, "agent", "request", null, null);
    source.plan(leased);
    source.transition(leased.workItemId(), ResearchWorkStatus.LEASED, "agent", null, null, null);
    ResearchTaskLedger restored = new ResearchTaskLedger();
    restored.restore(source.snapshot());
    assertThat(restored.require(started.workItemId()).status())
        .isEqualTo(ResearchWorkStatus.QUARANTINED_UNCERTAIN_CALL);
    assertThat(restored.require(leased.workItemId()).status()).isEqualTo(ResearchWorkStatus.PLANNED);
  }
}
