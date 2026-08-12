package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.communication.VerifiedDownstreamEffect;
import io.github.aililuola.mathproofmesh.proofgraph.ContradictionPolicy;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Phase17ResidualBranchCoverageTest {
  @Test
  void defensiveRecordsCoverValidInvalidAndNullNormalizationBranches() {
    assertThatThrownBy(() -> new ContradictionPolicy(true, -1))
        .isInstanceOf(IllegalArgumentException.class);

    ComputationLedger ledger = new ComputationLedger();
    ledger.record("route-a", 0.25d);
    ledger.record("route-a", 0.75d);
    assertThat(ledger.usage("route-a"))
        .isEqualTo(new ComputationLedger.Usage(2, 1.0d));
    assertThatThrownBy(() -> new ComputationLedger.Usage(-1, 0.0d))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ComputationLedger.Usage(0, -0.1d))
        .isInstanceOf(IllegalArgumentException.class);

    VerifiedDownstreamEffect empty =
        new VerifiedDownstreamEffect(
            null, null, null, null, null, false, 0.0d, 0.0d);
    assertThat(empty.committedStepIds()).isEmpty();
    assertThat(empty.closedObligationIds()).isEmpty();
    assertThat(empty.refutedClaimIds()).isEmpty();
    assertThat(empty.producedMessageIds()).isEmpty();
    assertThat(empty.blueprintRewriteRequestIds()).isEmpty();
  }

  @Test
  void expressionVariableWithExplicitNullFailsAtTheEvaluationBoundary() {
    Map<String, ExactRational> assignment = new HashMap<>();
    assignment.put("x", null);

    assertThatThrownBy(() -> ExactExpression.parse("x").evaluate(assignment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing expression variable");
  }
}
