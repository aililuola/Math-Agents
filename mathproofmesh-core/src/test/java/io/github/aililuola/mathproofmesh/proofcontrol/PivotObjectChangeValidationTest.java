package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PivotObjectChangeValidationTest {
  @Test
  void replacementRequiresBothObjectsAndABridge() {
    assertThat(SemanticPivotTestFixtures.objectReplacement().disposition())
        .isEqualTo(PivotObjectDisposition.REPLACE);
    assertThatThrownBy(
            () ->
                new MathematicalObjectChange(
                    "old", "old object", PivotObjectDisposition.REPLACE,
                    "new", "new object", null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MathematicalObjectChange(
                    "old", "old object", PivotObjectDisposition.ADD,
                    "new", "new object", null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
