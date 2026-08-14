package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopPivotDoesNotCloseOldTargetTest {
  @Test
  void retiringStrategyFocusLeavesMathematicalStatusOpen(@TempDir Path directory)
      throws Exception {
    try (DesktopSemanticPivotTestHarness harness =
        DesktopSemanticPivotTestHarness.open(directory, "pivot-old-target-status")) {
      var delta = harness.validDelta(1);
      String old = harness.firstRetiredObligation(delta);
      assertThat(harness.obligationStatus(old)).isEqualTo("open");
      harness.apply(delta);
      assertThat(harness.obligationStatus(old)).isEqualTo("open");
    }
  }
}
