package io.github.aililuola.mathproofmesh.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchResultLedger;
import org.junit.jupiter.api.Test;

final class TemporalResearchTaskExactlyOnceTest {
  @Test
  void replayingOneTaskResultKeepsOneContentAddressedArtifact() {
    var snapshot = TemporalConcurrencyTestFixtures.snapshot();
    var item = TemporalConcurrencyTestFixtures.item(snapshot, 0);
    var result = TemporalConcurrencyTestFixtures.result(item, "agent-0");
    ResearchResultLedger ledger = new ResearchResultLedger();
    var first = ledger.store(result);
    var replay = ledger.store(result);
    assertThat(replay).isEqualTo(first);
    assertThat(ledger.snapshot().artifacts()).hasSize(1);
  }
}
