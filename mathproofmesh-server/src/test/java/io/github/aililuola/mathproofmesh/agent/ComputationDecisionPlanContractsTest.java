package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ComputationDecisionAction;
import io.github.aililuola.mathproofmesh.contract.ComputationDecisionBranch;
import io.github.aililuola.mathproofmesh.contract.ComputationDecisionPlan;
import io.github.aililuola.mathproofmesh.contract.ContractValidationException;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComputationDecisionPlanContractsTest {
  @Test
  void decisionPlanRequiresAnExactClaimOrObligationTarget() {
    var branch = new ComputationDecisionBranch(
        ExperimentOutcome.NOT_REFUTED, ComputationDecisionAction.RECORD_BOUNDED_OBSERVATION, "s".repeat(64));
    assertThatThrownBy(() -> new ComputationDecisionPlan("plan", null, null, null, null, List.of(branch), null))
        .isInstanceOf(ContractValidationException.class);
    assertThat(new ComputationDecisionPlan("plan", null, null, "obligation-1", null, List.of(branch), null).planHash())
        .hasSize(64);
  }
}
