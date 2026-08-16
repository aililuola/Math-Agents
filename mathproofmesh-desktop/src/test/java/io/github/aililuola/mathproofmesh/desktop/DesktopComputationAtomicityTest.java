package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationArtifactKind;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionFailurePoint;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionStatus;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopComputationAtomicityTest {
  private static final List<ComputationExecutionFailurePoint> CORE_FAILURE_POINTS =
      Arrays.stream(ComputationExecutionFailurePoint.values())
          .filter(
              point ->
                  point
                      != ComputationExecutionFailurePoint.AFTER_COUNTEREXAMPLE_MUTATION_BEFORE_LEDGER_COMMIT)
          .filter(
              point ->
                  point
                      != ComputationExecutionFailurePoint.AFTER_FACT_MUTATION_BEFORE_LEDGER_COMMIT)
          .toList();

  @TempDir Path temporaryDirectory;

  @Test
  void everyMutationBoundaryCanDeterministicallyRollForwardExactlyOnce() {
    int deterministicRollForwards = 0;
    int partialRecords = 0;
    int duplicateArtifacts = 0;

    for (ComputationExecutionFailurePoint point : CORE_FAILURE_POINTS) {
      String suffix = point.name().toLowerCase(java.util.Locale.ROOT);
      var broker =
          DesktopComputationIssue010Support.broker(
              "atomic-" + suffix,
              temporaryDirectory.resolve(suffix),
              new InMemoryComputationCache());
      AtomicBoolean injected = new AtomicBoolean();
      broker.setExecutionHook(
          (observed, executionId) -> {
            if (observed == point && injected.compareAndSet(false, true)) {
              throw new InjectedMutationFailure(point.name());
            }
          });
      try {
        DesktopComputationIssue010Support.run(
            broker,
            DesktopComputationIssue010Support.linearAlgebra("atomic-" + suffix, point.ordinal()),
            "route-" + suffix,
            point.ordinal());
        throw new AssertionError("atomicity hook was not reached: " + point);
      } catch (InjectedMutationFailure expected) {
        assertThat(expected.getMessage()).isEqualTo(point.name());
      }
      broker.setExecutionHook(null);
      DesktopComputationIssue010Support.run(
          broker,
          DesktopComputationIssue010Support.linearAlgebra("atomic-" + suffix, point.ordinal()),
          "route-" + suffix,
          point.ordinal());
      deterministicRollForwards++;

      var record = broker.executionService().executions().records().getFirst();
      partialRecords += record.status() == ComputationExecutionStatus.AUTHORITY_APPLIED ? 0 : 1;
      assertThat(record.producerExecutions()).isEqualTo(1);
      assertThat(record.verifierExecutions()).isEqualTo(1);
      assertThat(record.authorityProjections()).isEqualTo(1);
      for (ComputationArtifactKind kind : ComputationArtifactKind.values()) {
        long count =
            broker.executionService().artifacts().snapshot().records().stream()
                .filter(artifact -> artifact.kind() == kind)
                .count();
        duplicateArtifacts += Math.max(0, Math.toIntExact(count - 1));
        assertThat(count).isEqualTo(1L);
      }
    }

    assertThat(deterministicRollForwards)
        .isEqualTo(CORE_FAILURE_POINTS.size());
    assertThat(partialRecords).isZero();
    assertThat(duplicateArtifacts).isZero();
    System.out.println("COMPUTATION ATOMICITY DIAGNOSTIC");
    System.out.println("FAILURE_POINTS=" + CORE_FAILURE_POINTS.size());
    System.out.println("DETERMINISTIC_ROLL_FORWARDS=" + deterministicRollForwards);
    System.out.println("PARTIAL_EXECUTION_RECORDS=" + partialRecords);
    System.out.println("DUPLICATE_ARTIFACTS=" + duplicateArtifacts);
    System.out.println("RESULT=PASS");
  }

  private static final class InjectedMutationFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private InjectedMutationFailure(String message) {
      super(message);
    }
  }
}
