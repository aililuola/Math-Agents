package io.github.aililuola.mathproofmesh.research;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingKind;
import org.junit.jupiter.api.Test;

class ResearchCheckpointSnapshotTest {
  @Test
  void jsonRoundTripRestorePreservesHashAndExactlyOnceProjection() {
    ResearchCheckpointLedger ledger = new ResearchCheckpointLedger();
    ledger.appendEnvelopeFrame(
        "problem",
        "route",
        "independent_exploration",
        "call",
        ResearchCheckpointTestFixtures.frame(
            0,
            ResearchCheckpointTestFixtures.finding(
                ResearchFindingKind.SHARP_OBSTRUCTION, "a sharp obstruction")));
    String before = ledger.ledgerHash();
    ResearchCheckpointSnapshot decoded =
        ContractObjectMapper.read(
            ContractObjectMapper.write(ledger.snapshot()), ResearchCheckpointSnapshot.class);
    ResearchCheckpointLedger restored = ResearchCheckpointLedger.restore(decoded);

    assertThat(restored.ledgerHash()).isEqualTo(before);
    assertThat(restored.findings()).hasSize(1);
    assertThat(ResearchCheckpointLedger.restore(null).snapshot())
        .isEqualTo(ResearchCheckpointSnapshot.empty());
  }
}
