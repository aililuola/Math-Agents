package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class NativeRationalBitLengthLimitTest {
  @Test
  void exactIntegerBitLengthIsBoundedBeforeArithmetic() {
    String oversized = BigInteger.ONE.shiftLeft(4_096).toString();
    var spec =
        ComputationFixtures.spec(
            ComputationMethod.EXACT_LINEAR_ALGEBRA,
            "{\"operation\":\"determinant\",\"matrix\":[[\""
                + oversized
                + "\"]]}");

    assertThatThrownBy(
            () ->
                ComputationResourceGuard.validateRequest(
                    spec, ComputationResourceEnvelope.boundedDefault()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("COMPUTATION_RATIONAL_BIT_LENGTH_LIMIT");
  }

  @Test
  void hugeDecimalExponentIsRejectedWithoutMaterializingItsDenominator() {
    var spec =
        ComputationFixtures.spec(
            ComputationMethod.EXACT_LINEAR_ALGEBRA,
            "{\"operation\":\"determinant\",\"matrix\":[[\"1.0e-1000000000\"]]}");

    assertThatThrownBy(
            () ->
                ComputationResourceGuard.validateRequest(
                    spec, ComputationResourceEnvelope.boundedDefault()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("COMPUTATION_RATIONAL_BIT_LENGTH_LIMIT");
  }
}
