package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationArtifactKind;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionFailurePoint;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionStatus;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopComputationCounterexampleProjectionAtomicityTest {
  @TempDir Path temporaryDirectory;

  @Test
  void counterexampleMutationRollsBackBeforeLedgerCommitAndRetriesExactlyOnce()
      throws Exception {
    int mutationWithoutLedger = 0;
    int duplicateCounterexamples;
    try (var harness =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory, "counterexample-projection-atomicity")) {
      harness.initializeRoute();
      var source = DesktopComputationIssue010Support.graphCounterexample("atomic-cx", 11);
      harness.addObligation("exact-counterexample-target", source.targetClaim());
      var spec = harness.exactBound(source, "exact-counterexample-target");
      int negativesBefore = harness.typedMemory().negatives().size();
      AtomicBoolean injected = new AtomicBoolean();
      harness
          .computation()
          .setExecutionHook(
              (point, executionId) -> {
                if (point
                        == ComputationExecutionFailurePoint
                            .AFTER_COUNTEREXAMPLE_MUTATION_BEFORE_LEDGER_COMMIT
                    && injected.compareAndSet(false, true)) {
                  throw new InjectedMutationFailure(point.name());
                }
              });

      try {
        harness.runComputation(spec);
        throw new AssertionError("counterexample mutation failure was not injected");
      } catch (InjectedMutationFailure expected) {
        assertThat(expected.getMessage())
            .isEqualTo(
                ComputationExecutionFailurePoint
                    .AFTER_COUNTEREXAMPLE_MUTATION_BEFORE_LEDGER_COMMIT
                    .name());
      }

      assertThat(harness.typedMemory().negatives()).hasSize(negativesBefore);
      assertThat(harness.obligation("exact-counterexample-target").status()).isEqualTo("open");
      var interrupted = harness.execution("atomic-cx");
      mutationWithoutLedger +=
          interrupted.status() == ComputationExecutionStatus.PROJECTION_READY
                  && interrupted.authorityMutationReceiptRef().isEmpty()
              ? 0
              : 1;

      harness.computation().setExecutionHook(null);
      harness.runComputation(spec);

      var applied = harness.execution("atomic-cx");
      int negativeDelta = harness.typedMemory().negatives().size() - negativesBefore;
      duplicateCounterexamples = Math.max(0, negativeDelta - 1);
      assertThat(applied.status()).isEqualTo(ComputationExecutionStatus.AUTHORITY_APPLIED);
      assertThat(applied.authorityProjections()).isEqualTo(1);
      assertThat(applied.authorityMutationReceiptRef()).isNotBlank();
      assertThat(harness.obligation("exact-counterexample-target").status())
          .isEqualTo("refuted");
      assertThat(negativeDelta).isEqualTo(1);
      assertThat(
              harness.computation().snapshot().artifacts().records().stream()
                  .filter(value -> value.kind() == ComputationArtifactKind.AUTHORITY_MUTATION_RECEIPT))
          .hasSize(1);
    }

    assertThat(mutationWithoutLedger).isZero();
    assertThat(duplicateCounterexamples).isZero();
    System.out.println("COUNTEREXAMPLE MUTATION ATOMICITY DIAGNOSTIC");
    System.out.println("MUTATION_WITHOUT_AUTHORITY_LEDGER=" + mutationWithoutLedger);
    System.out.println("DUPLICATE_COUNTEREXAMPLE_PROJECTIONS=" + duplicateCounterexamples);
    System.out.println("RESULT=PASS");
  }

  private static final class InjectedMutationFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private InjectedMutationFailure(String message) {
      super(message);
    }
  }
}
