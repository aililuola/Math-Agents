package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationDecisionAction;
import org.junit.jupiter.api.Test;

class ComputationOutcomeProjectorBoundaryTest {
  @Test
  void notRefutedRemainsABoundedObservationRatherThanAFactPromotion() {
    var outcome =
        ComputationIssue010TestSupport.run(
            ComputationFixtures.broker("bounded-projector"),
            ComputationIssue010TestSupport.boundedObservationSpec());
    assertThat(outcome.applicationReceipt().action())
        .isEqualTo(ComputationDecisionAction.RECORD_BOUNDED_OBSERVATION);
    assertThat(outcome.authority()).isEqualTo(ComputationEvidenceGate.EvidenceAuthority.NOT_REFUTED);
  }
}
