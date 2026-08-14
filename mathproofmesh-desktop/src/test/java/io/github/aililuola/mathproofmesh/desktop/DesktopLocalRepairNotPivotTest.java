package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopLocalRepairNotPivotTest {
  @Test
  void localRepairPreservesMechanismAndDoesNotIncrementPivotLedger(@TempDir Path directory)
      throws Exception {
    try (DesktopSemanticPivotTestHarness harness =
        DesktopSemanticPivotTestHarness.open(directory, "local-repair-not-pivot")) {
      String coreIdea = harness.activeStrategy().coreIdea();
      int pivots = harness.state().pivotRecords();

      assertThat(harness.localRepair()).isTrue();

      assertThat(harness.activeStrategy().coreIdea()).isEqualTo(coreIdea);
      assertThat(harness.state().pivotRecords()).isEqualTo(pivots);
      assertThat(harness.activeStrategy().independenceBasis()).contains("local-repair=");
    }
  }
}
