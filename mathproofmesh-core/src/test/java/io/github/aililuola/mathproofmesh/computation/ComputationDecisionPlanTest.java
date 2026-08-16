package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationDecisionAction;
import org.junit.jupiter.api.Test;

class ComputationDecisionPlanTest {
  @Test
  void finiteCertificateProducesOnlyTheTypedFiniteObligationAction() {
    var outcome =
        ComputationIssue010TestSupport.run(
            ComputationFixtures.broker("decision-plan"),
            ComputationIssue010TestSupport.finiteMapSpec());
    assertThat(outcome.applicationReceipt().action())
        .isEqualTo(ComputationDecisionAction.SATISFY_FINITE_DOMAIN_OBLIGATION);
  }
}
