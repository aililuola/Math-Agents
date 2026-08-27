package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import org.junit.jupiter.api.Test;

class DirectedComputationSmokeParityTest {

  @Test
  void test_directed_computation_smoke_creates_and_completes_one_node() {
    ComputationBroker broker = ComputationFixtures.broker("directed-smoke");
    ExperimentSpec spec =
        ComputationFixtures.spec(
            ComputationMethod.MODULAR_EXHAUSTIVE,
            "{\"lhs\":\"x^5\",\"rhs\":\"x\",\"modulus\":5,"
                + "\"finite_reduction\":true,"
                + "\"reduction_justification\":\"Depends only on residues.\"}",
            "{\"x\":{\"min\":0,\"max\":4}}");
    ExperimentResult result = ComputationFixtures.run(broker, spec);

    assertThat(result.outcome()).isEqualTo(ExperimentOutcome.CERTIFIED);
    assertThat(broker.ledger().usage(spec.pathId()).experiments()).isEqualTo(1);
  }
}
