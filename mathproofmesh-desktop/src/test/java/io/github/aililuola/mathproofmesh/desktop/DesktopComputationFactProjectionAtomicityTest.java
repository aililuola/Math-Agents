package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationArtifactKind;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionFailurePoint;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionStatus;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopComputationFactProjectionAtomicityTest {
  @TempDir Path temporaryDirectory;

  @Test
  void factMutationRollsBackBeforeLedgerCommitAndRetriesExactlyOnce() throws Exception {
    int mutationWithoutLedger = 0;
    int duplicateFacts;
    try (var harness =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory, "fact-projection-atomicity")) {
      harness.initializeRoute();
      var source = DesktopComputationIssue010Support.finiteMap("atomic-fact");
      harness.addObligation("exact-fact-target", source.targetClaim());
      var spec = harness.exactBound(source, "exact-fact-target");
      int factsBefore = harness.typedMemory().facts().size();
      AtomicBoolean injected = new AtomicBoolean();
      harness
          .computation()
          .setExecutionHook(
              (point, executionId) -> {
                if (point
                        == ComputationExecutionFailurePoint
                            .AFTER_FACT_MUTATION_BEFORE_LEDGER_COMMIT
                    && injected.compareAndSet(false, true)) {
                  throw new InjectedMutationFailure(point.name());
                }
              });

      try {
        harness.runComputation(spec);
        throw new AssertionError("fact mutation failure was not injected");
      } catch (InjectedMutationFailure expected) {
        assertThat(expected.getMessage())
            .isEqualTo(
                ComputationExecutionFailurePoint
                    .AFTER_FACT_MUTATION_BEFORE_LEDGER_COMMIT
                    .name());
      }

      assertThat(harness.typedMemory().facts()).hasSize(factsBefore);
      var interrupted = harness.execution("atomic-fact");
      mutationWithoutLedger +=
          interrupted.status() == ComputationExecutionStatus.PROJECTION_READY
                  && interrupted.authorityMutationReceiptRef().isEmpty()
              ? 0
              : 1;

      harness.computation().setExecutionHook(null);
      harness.runComputation(spec);

      var applied = harness.execution("atomic-fact");
      int factDelta = harness.typedMemory().facts().size() - factsBefore;
      duplicateFacts = Math.max(0, factDelta - 1);
      assertThat(applied.status()).isEqualTo(ComputationExecutionStatus.AUTHORITY_APPLIED);
      assertThat(applied.authorityProjections()).isEqualTo(1);
      assertThat(applied.authorityMutationReceiptRef()).isNotBlank();
      assertThat(factDelta).isEqualTo(1);
      assertThat(
              harness.computation().snapshot().artifacts().records().stream()
                  .filter(value -> value.kind() == ComputationArtifactKind.AUTHORITY_MUTATION_RECEIPT))
          .hasSize(1);
    }

    assertThat(mutationWithoutLedger).isZero();
    assertThat(duplicateFacts).isZero();
    System.out.println("FACT MUTATION ATOMICITY DIAGNOSTIC");
    System.out.println("MUTATION_WITHOUT_AUTHORITY_LEDGER=" + mutationWithoutLedger);
    System.out.println("DUPLICATE_FACT_PROJECTIONS=" + duplicateFacts);
    System.out.println("RESULT=PASS");
  }

  @Test
  void ledgerAndCheckpointFailuresWriteACompensatingProjectionReadyCheckpoint()
      throws Exception {
    List<ComputationExecutionFailurePoint> points =
        List.of(
            ComputationExecutionFailurePoint.AFTER_LEDGER_COMMIT_BEFORE_CHECKPOINT,
            ComputationExecutionFailurePoint.AFTER_ATOMIC_CHECKPOINT_MOVE);
    int partialAuthorityProjections = 0;
    for (ComputationExecutionFailurePoint point : points) {
      String suffix = point.name().toLowerCase(java.util.Locale.ROOT);
      try (var harness =
          DesktopComputationIssue010CoordinatorHarness.open(
              temporaryDirectory.resolve(suffix), "fact-compensation-" + suffix)) {
        harness.initializeRoute();
        var source = DesktopComputationIssue010Support.finiteMap("compensated-" + suffix);
        String obligationId = "compensated-target-" + suffix;
        harness.addObligation(obligationId, source.targetClaim());
        var spec = harness.exactBound(source, obligationId);
        int factsBefore = harness.typedMemory().facts().size();
        AtomicBoolean injected = new AtomicBoolean();
        harness
            .computation()
            .setExecutionHook(
                (observed, executionId) -> {
                  if (observed == point && injected.compareAndSet(false, true)) {
                    throw new InjectedMutationFailure(point.name());
                  }
                });

        try {
          harness.runComputation(spec);
          throw new AssertionError("checkpoint failure was not injected at " + point);
        } catch (InjectedMutationFailure expected) {
          assertThat(expected.getMessage()).isEqualTo(point.name());
        }

        var compensated = harness.execution(source.experimentId());
        assertThat(compensated.status()).isEqualTo(ComputationExecutionStatus.PROJECTION_READY);
        assertThat(compensated.authorityMutationReceiptRef()).isEmpty();
        assertThat(harness.typedMemory().facts()).hasSize(factsBefore);
        harness.computation().setExecutionHook(null);
        harness.runComputation(spec);
        int delta = harness.typedMemory().facts().size() - factsBefore;
        var applied = harness.execution(source.experimentId());
        partialAuthorityProjections +=
            applied.status() == ComputationExecutionStatus.AUTHORITY_APPLIED
                    && applied.authorityProjections() == 1
                    && delta == 1
                ? 0
                : 1;
      }
    }

    assertThat(partialAuthorityProjections).isZero();
    System.out.println("COMPENSATING AUTHORITY CHECKPOINT DIAGNOSTIC");
    System.out.println("COMPENSATED_FAILURE_POINTS=" + points.size());
    System.out.println("PARTIAL_AUTHORITY_PROJECTIONS=" + partialAuthorityProjections);
    System.out.println("RESULT=PASS");
  }

  private static final class InjectedMutationFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private InjectedMutationFailure(String message) {
      super(message);
    }
  }
}
