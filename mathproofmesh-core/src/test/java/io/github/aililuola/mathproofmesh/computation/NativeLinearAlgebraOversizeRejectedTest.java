package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class NativeLinearAlgebraOversizeRejectedTest {
  @Test
  void matrixRowsAreBoundedBeforeProducerOrVerifierRuns() {
    String rows =
        IntStream.range(0, 65)
            .mapToObj(ignored -> "[1]")
            .collect(Collectors.joining(","));
    var spec =
        ComputationFixtures.spec(
            ComputationMethod.EXACT_LINEAR_ALGEBRA,
            "{\"operation\":\"rank\",\"matrix\":[" + rows + "]}");

    assertThatThrownBy(
            () ->
                ComputationResourceGuard.validateRequest(
                    spec, ComputationResourceEnvelope.boundedDefault()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("COMPUTATION_MATRIX_ROW_LIMIT");
  }
}
