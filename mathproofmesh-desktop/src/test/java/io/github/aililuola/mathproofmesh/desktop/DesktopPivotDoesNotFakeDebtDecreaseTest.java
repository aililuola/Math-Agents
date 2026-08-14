package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopPivotDoesNotFakeDebtDecreaseTest {
  @Test
  void schedulingRetirementCannotReduceGlobalCanonicalDebt(@TempDir Path directory)
      throws Exception {
    try (DesktopSemanticPivotTestHarness harness =
        DesktopSemanticPivotTestHarness.open(directory, "pivot-global-debt")) {
      double before = harness.state().globalDebt();
      harness.apply(harness.validDelta(1));
      assertThat(harness.state().globalDebt()).isGreaterThanOrEqualTo(before);
    }
  }
}
