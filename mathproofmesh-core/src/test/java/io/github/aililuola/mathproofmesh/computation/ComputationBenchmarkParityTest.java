package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import org.junit.jupiter.api.Test;

class ComputationBenchmarkParityTest {

  @Test
  void test_enumeration_proxy_reduces_reasoning_tokens_without_correctness_loss() {
    HandlerEvidence evidence =
        SequenceFunctions.runBoundedGreedySequence(
            ComputationFixtures.spec(
                ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
                "{\"initial_values\":[0],\"length\":40,"
                    + "\"candidate_min\":0,\"candidate_max\":200,"
                    + "\"rule\":\"avoid_forbidden_differences\","
                    + "\"forbidden_differences\":[1]}"));
    int narrativeTokenProxy = 4_000;

    assertThat(evidence.outcome()).isEqualTo(ExperimentOutcome.NOT_REFUTED);
    assertThat(evidence.casesChecked()).isLessThan(narrativeTokenProxy);
    assertThat(evidence.certificate().path("values")).hasSize(40);
  }
}
