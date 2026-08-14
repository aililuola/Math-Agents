package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotDeterministicAuditor;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphControlMode;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphConvergenceMonitor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSemanticPivotFocusedRecoveryBoundaryTest {
  @Test
  void unrelatedPivotCannotBypassSelectedRecoveryBinding(@TempDir Path directory)
      throws Exception {
    try (DesktopSemanticPivotTestHarness harness =
        DesktopSemanticPivotTestHarness.open(directory, "pivot-focused-boundary")) {
      harness.enterFocusedRecovery();
      ProofGraphConvergenceMonitor monitor = monitor(harness.coordinator());
      assertThat(monitor.controlMode()).isEqualTo(ProofGraphControlMode.FOCUSED_RECOVERY);
      harness.bindRouteToUnrelatedFocus();
      var before = harness.state();
      var record = harness.apply(harness.validDelta(1));
      assertThat(record.deterministicAudit().failureCodes())
          .contains(
              SemanticPivotDeterministicAuditor.FOCUSED_RECOVERY_BINDING_MISMATCH,
              SemanticPivotDeterministicAuditor.CAPACITY_OR_QUOTA_BLOCK);
      var after = harness.state();
      boolean activeStateChanged =
          !after.activeStrategyId().equals(before.activeStrategyId())
              || after.obligations() != before.obligations();
      int focusedBindingBypasses =
          record
                      .deterministicAudit()
                      .failureCodes()
                      .contains(
                          SemanticPivotDeterministicAuditor.FOCUSED_RECOVERY_BINDING_MISMATCH)
                  && activeStateChanged
              ? 1
              : 0;
      int capacityOrQuotaBypasses =
          record
                      .deterministicAudit()
                      .failureCodes()
                      .contains(SemanticPivotDeterministicAuditor.CAPACITY_OR_QUOTA_BLOCK)
                  && activeStateChanged
              ? 1
              : 0;
      System.out.println("FOCUSED_RECOVERY_BINDING_BYPASSES=" + focusedBindingBypasses);
      System.out.println("CAPACITY_OR_QUOTA_BYPASSES=" + capacityOrQuotaBypasses);
      assertThat(focusedBindingBypasses).isZero();
      assertThat(capacityOrQuotaBypasses).isZero();
    }
  }

  private static ProofGraphConvergenceMonitor monitor(DesktopSolveCoordinator coordinator)
      throws Exception {
    Field field = DesktopSolveCoordinator.class.getDeclaredField("proofGraphConvergence");
    field.setAccessible(true);
    return (ProofGraphConvergenceMonitor) field.get(coordinator);
  }
}
