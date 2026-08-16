package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationExecutionFailurePoint;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopComputationAuthorityRestoreReconciliationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void projectionReadyAndMutationDurableFrontiersReconcileIdempotently()
      throws Exception {
    var projectionReady =
        DesktopComputationAuthorityProjectionTestSupport.hardCrash(
            temporaryDirectory.resolve("projection-ready"),
            ComputationExecutionFailurePoint.AFTER_PROJECTION_READY,
            DesktopComputationAuthorityProjectionTestSupport.MutationKind.FACT);
    var mutationNotCheckpointed =
        DesktopComputationAuthorityProjectionTestSupport.hardCrash(
            temporaryDirectory.resolve("ledger-frontier"),
            ComputationExecutionFailurePoint.AFTER_LEDGER_COMMIT_BEFORE_CHECKPOINT,
            DesktopComputationAuthorityProjectionTestSupport.MutationKind.COUNTEREXAMPLE);
    var mutationDurable =
        DesktopComputationAuthorityProjectionTestSupport.hardCrash(
            temporaryDirectory.resolve("mutation-durable"),
            ComputationExecutionFailurePoint.AFTER_ATOMIC_CHECKPOINT_MOVE,
            DesktopComputationAuthorityProjectionTestSupport.MutationKind.FACT);
    var total = projectionReady.plus(mutationNotCheckpointed).plus(mutationDurable);

    assertThat(total.restoreFailures()).isZero();
    assertThat(total.authorityLedgerWithoutMutation()).isZero();
    assertThat(total.mutationWithoutAuthorityLedger()).isZero();
    assertThat(total.partialAuthorityProjections()).isZero();
    assertThat(total.secondRestoreChanges()).isZero();

    System.out.println("COMPUTATION AUTHORITY RESTORE RECONCILIATION DIAGNOSTIC");
    System.out.println("FRONTIERS_RECONCILED=" + total.hardCrashes());
    System.out.println("AUTHORITY_LEDGER_WITHOUT_MUTATION=" + total.authorityLedgerWithoutMutation());
    System.out.println("MUTATION_WITHOUT_AUTHORITY_LEDGER=" + total.mutationWithoutAuthorityLedger());
    System.out.println("PARTIAL_AUTHORITY_PROJECTIONS=" + total.partialAuthorityProjections());
    System.out.println("SECOND_RESTORE_CHANGES=" + total.secondRestoreChanges());
    System.out.println("RESULT=PASS");
  }
}
