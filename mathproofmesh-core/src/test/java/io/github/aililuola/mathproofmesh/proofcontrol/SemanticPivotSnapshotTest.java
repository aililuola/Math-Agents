package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import org.junit.jupiter.api.Test;

class SemanticPivotSnapshotTest {
  @Test
  void snapshotRoundTripsWithoutChangingItsStableHash() {
    SemanticPivotController controller = new SemanticPivotController();
    PivotDelta delta = SemanticPivotTestFixtures.validDelta();
    controller.prepare(
        delta,
        SemanticPivotTestFixtures.sourceSignature(),
        SemanticPivotTestFixtures.proposedSignature(),
        SemanticPivotTestFixtures.authority(),
        "proposer",
        SemanticPivotTestFixtures.acceptedReview(delta),
        0.9d);
    String before = controller.ledger().stableHash();
    SemanticPivotSnapshot decoded =
        ContractObjectMapper.read(
            ContractObjectMapper.write(controller.ledger().snapshot()), SemanticPivotSnapshot.class);
    SemanticPivotLedger restored = new SemanticPivotLedger();
    restored.restore(decoded);
    assertThat(restored.stableHash()).isEqualTo(before);
  }
}
