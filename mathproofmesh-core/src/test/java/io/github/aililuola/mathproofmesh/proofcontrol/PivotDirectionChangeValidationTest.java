package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PivotDirectionChangeValidationTest {
  @Test
  void directionLabelsWithoutDistinctOldAndNewSignaturesAreRejected() {
    assertThatThrownBy(
            () ->
                new PivotDirectionChange(
                    "reverse goal", " reverse   goal ", "label only", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must differ");
  }
}
