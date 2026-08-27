package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSemanticPivotRestoreExactlyOnceTest {
  @Test
  void appliedPivotRestoresAndDuplicateApplyIsANoOp(@TempDir Path directory) throws Exception {
    String runId = "semantic-pivot-restore";
    var harness = DesktopSemanticPivotTestHarness.open(directory, runId);
    var delta = harness.validDelta(1);
    harness.apply(delta);
    var before = harness.state();
    DesktopSolveCheckpoint checkpoint = harness.checkpoint();
    harness.close();

    try (DesktopSemanticPivotTestHarness restored =
        DesktopSemanticPivotTestHarness.restore(directory, runId, checkpoint)) {
      var afterRestore = restored.state();
      assertThat(afterRestore.pivotHash()).isEqualTo(before.pivotHash());
      assertThat(afterRestore.activeStrategyId()).isEqualTo(before.activeStrategyId());
      assertThat(afterRestore.obligations()).isEqualTo(before.obligations());
      restored.apply(delta);
      assertThat(restored.state().pivotHash()).isEqualTo(before.pivotHash());
      assertThat(restored.state().obligations()).isEqualTo(before.obligations());
    }
  }
}
