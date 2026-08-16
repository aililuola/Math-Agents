package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import org.junit.jupiter.api.Test;

class ComputationResourceEnvelopeEnforcementTest {
  @Test
  void typedFiniteSetLimitIsEnforcedBeforeExecution() {
    var envelope =
        new ComputationResourceEnvelope(
            100,
            1.0d,
            1_000_000L,
            100_000,
            8,
            8,
            256,
            2,
            8,
            1_000,
            100_000);
    var spec =
        ComputationFixtures.spec(
            ComputationMethod.FINITE_SET_MAP_CHECK,
            "{\"operation\":\"injective\",\"domain\":[\"a\",\"b\",\"c\"],"
                + "\"codomain\":[\"x\",\"y\",\"z\"],"
                + "\"mapping\":{\"a\":\"x\",\"b\":\"y\",\"c\":\"z\"}}");

    assertThatThrownBy(() -> ComputationResourceGuard.validateRequest(spec, envelope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("COMPUTATION_FINITE_SET_LIMIT");
  }

  @Test
  void everyExtendedResourceLimitMustBePositive() {
    assertThatThrownBy(
            () ->
                new ComputationResourceEnvelope(
                    100, 1.0d, 1_000_000L, 100_000, -1, 8, 256, 2, 8, 1_000, 100_000))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid computation resource envelope");
  }
}
