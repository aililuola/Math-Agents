package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ResearchEpochLedgerTest {
  @Test
  void recordsAllSettledAndCommittedFrontier() {
    FrozenResearchSnapshot snapshot = ConcurrencyTestFixtures.snapshot();
    ResearchEpochLedger ledger = new ResearchEpochLedger();
    ledger.plan(snapshot, List.of("work"));
    ledger.transition(snapshot.epochId(), ResearchEpochStatus.DISPATCHING, null, null);
    ledger.transition(snapshot.epochId(), ResearchEpochStatus.ALL_SETTLED, List.of("result"), null);
    ledger.transition(snapshot.epochId(), ResearchEpochStatus.MERGE_PREPARED, null, "merge");
    assertThat(ledger.transition(snapshot.epochId(), ResearchEpochStatus.COMMITTED, null, null).status())
        .isEqualTo(ResearchEpochStatus.COMMITTED);
  }
}
