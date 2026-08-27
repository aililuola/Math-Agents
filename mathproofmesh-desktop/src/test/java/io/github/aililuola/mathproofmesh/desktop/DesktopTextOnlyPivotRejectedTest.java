package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofcontrol.PivotDeltaStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotDeterministicAuditor;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopTextOnlyPivotRejectedTest {
  @Test
  void titleAndCoreIdeaOnlyChangesCannotMutateProductionState(@TempDir Path directory)
      throws Exception {
    try (DesktopSemanticPivotTestHarness harness =
        DesktopSemanticPivotTestHarness.open(directory, "semantic-pivot-text-only")) {
      DesktopSemanticPivotTestHarness.State before = harness.state();
      var record = harness.apply(harness.textOnlyDelta(1));
      DesktopSemanticPivotTestHarness.State after = harness.state();

      assertThat(record.status()).isEqualTo(PivotDeltaStatus.DETERMINISTICALLY_REJECTED);
      assertThat(record.deterministicAudit().failureCodes())
          .contains(
              SemanticPivotDeterministicAuditor.EMPTY_SEMANTIC_DELTA,
              SemanticPivotDeterministicAuditor.TEXT_ONLY_REVISION);
      assertThat(after.activeStrategyId()).isEqualTo(before.activeStrategyId());
      assertThat(after.obligations()).isEqualTo(before.obligations());
      assertThat(after.pendingTasks()).isEqualTo(before.pendingTasks());
    }
  }
}
