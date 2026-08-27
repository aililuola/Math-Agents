package io.github.aililuola.mathproofmesh.research;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ResearchFindingDisposition;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingDispositionAction;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingKind;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingUpdateBatch;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResearchCheckpointLedgerTest {
  @Test
  void appendsIdempotentlyAndNeverSilentlyDeletesOmittedFindings() {
    ResearchCheckpointLedger ledger = new ResearchCheckpointLedger();
    var frame =
        ResearchCheckpointTestFixtures.frame(
            0,
            ResearchCheckpointTestFixtures.finding(
                ResearchFindingKind.CANDIDATE_LEMMA, "same support gives one representative"),
            ResearchCheckpointTestFixtures.finding(
                ResearchFindingKind.NEXT_MICRO_OBLIGATION, "prove uniqueness next"));

    ledger.appendEnvelopeFrame("problem", "route", "independent_exploration", "call", frame);
    ledger.appendEnvelopeFrame("problem", "route", "independent_exploration", "call", frame);

    assertThat(ledger.snapshot().checkpoints()).hasSize(1);
    assertThat(ledger.findings()).hasSize(2);
    assertThat(ledger.activeFindings("route")).hasSize(2);
    String findingId = ledger.activeFindings("route").getFirst().findingId();
    ledger.applyUpdates(
        "route",
        new ResearchFindingUpdateBatch(
            List.of(
                new ResearchFindingDisposition(
                    findingId,
                    ResearchFindingDispositionAction.DEFER,
                    "not needed in this segment",
                    null))));
    assertThat(ledger.activeFindings("route")).hasSize(1);
    assertThat(ledger.finding(findingId).status()).isEqualTo(ResearchFindingStatus.DEFERRED);
  }
}
