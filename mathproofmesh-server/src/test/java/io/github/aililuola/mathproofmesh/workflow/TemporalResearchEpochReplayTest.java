package io.github.aililuola.mathproofmesh.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochLedger;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TemporalResearchEpochReplayTest {
  @Test
  void replayingTheSameFrozenEpochDoesNotCreateAnotherEpoch() {
    var snapshot = TemporalConcurrencyTestFixtures.snapshot();
    ResearchEpochLedger ledger = new ResearchEpochLedger();
    var first = ledger.plan(snapshot, List.of("work-0"));
    var replay = ledger.plan(snapshot, List.of("work-0"));
    assertThat(replay).isEqualTo(first);
    assertThat(ledger.snapshot().epochs()).hasSize(1);
  }
}
