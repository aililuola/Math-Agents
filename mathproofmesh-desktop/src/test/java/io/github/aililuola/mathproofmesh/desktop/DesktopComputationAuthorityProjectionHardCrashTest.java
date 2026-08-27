package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationExecutionFailurePoint;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopComputationAuthorityProjectionHardCrashTest {
  @TempDir Path temporaryDirectory;

  @Test
  void everyAuthorityMutationFrontierRestoresToOneCompleteProjection() throws Exception {
    var scenarios =
        List.of(
            new Scenario(
                ComputationExecutionFailurePoint.AFTER_PROJECTION_READY,
                DesktopComputationAuthorityProjectionTestSupport.MutationKind.FACT),
            new Scenario(
                ComputationExecutionFailurePoint
                    .AFTER_COUNTEREXAMPLE_MUTATION_BEFORE_LEDGER_COMMIT,
                DesktopComputationAuthorityProjectionTestSupport.MutationKind.COUNTEREXAMPLE),
            new Scenario(
                ComputationExecutionFailurePoint.AFTER_FACT_MUTATION_BEFORE_LEDGER_COMMIT,
                DesktopComputationAuthorityProjectionTestSupport.MutationKind.FACT),
            new Scenario(
                ComputationExecutionFailurePoint.AFTER_LEDGER_COMMIT_BEFORE_CHECKPOINT,
                DesktopComputationAuthorityProjectionTestSupport.MutationKind.FACT),
            new Scenario(
                ComputationExecutionFailurePoint.AFTER_ATOMIC_CHECKPOINT_MOVE,
                DesktopComputationAuthorityProjectionTestSupport.MutationKind.COUNTEREXAMPLE));
    var total =
        DesktopComputationAuthorityProjectionTestSupport.RecoveryMetrics.zero();
    for (Scenario scenario : scenarios) {
      total =
          total.plus(
              DesktopComputationAuthorityProjectionTestSupport.hardCrash(
                  temporaryDirectory,
                  scenario.failurePoint(),
                  scenario.kind()));
    }

    assertThat(total.hardCrashes()).isEqualTo(scenarios.size());
    assertThat(total.restoreFailures()).isZero();
    assertThat(total.authorityLedgerWithoutMutation()).isZero();
    assertThat(total.mutationWithoutAuthorityLedger()).isZero();
    assertThat(total.duplicateFactProjections()).isZero();
    assertThat(total.duplicateCounterexampleProjections()).isZero();
    assertThat(total.partialAuthorityProjections()).isZero();
    assertThat(total.rootHashChanges()).isZero();
    assertThat(total.secondRestoreChanges()).isZero();

    System.out.println("COMPUTATION AUTHORITY PROJECTION HARD-CRASH DIAGNOSTIC");
    System.out.println("HARD_CRASH_POINTS=" + total.hardCrashes());
    System.out.println("RESTORE_FAILURES=" + total.restoreFailures());
    System.out.println(
        "AUTHORITY_LEDGER_WITHOUT_MUTATION=" + total.authorityLedgerWithoutMutation());
    System.out.println(
        "MUTATION_WITHOUT_AUTHORITY_LEDGER=" + total.mutationWithoutAuthorityLedger());
    System.out.println("DUPLICATE_FACT_PROJECTIONS=" + total.duplicateFactProjections());
    System.out.println(
        "DUPLICATE_COUNTEREXAMPLE_PROJECTIONS="
            + total.duplicateCounterexampleProjections());
    System.out.println("PARTIAL_AUTHORITY_PROJECTIONS=" + total.partialAuthorityProjections());
    System.out.println("ROOT_HASH_CHANGES=" + total.rootHashChanges());
    System.out.println("SECOND_RESTORE_CHANGES=" + total.secondRestoreChanges());
    System.out.println("RESULT=PASS");
  }

  private record Scenario(
      ComputationExecutionFailurePoint failurePoint,
      DesktopComputationAuthorityProjectionTestSupport.MutationKind kind) {}
}
