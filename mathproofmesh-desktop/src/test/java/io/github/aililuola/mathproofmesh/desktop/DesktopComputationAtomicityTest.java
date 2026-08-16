package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationArtifactKind;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionFailurePoint;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionStatus;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopComputationAtomicityTest {
  @TempDir Path temporaryDirectory;

  @Test
  void everyMutationBoundaryCanDeterministicallyRollForwardExactlyOnce() {
    int deterministicRollForwards = 0;
    int partialRecords = 0;
    int duplicateArtifacts = 0;

    for (ComputationExecutionFailurePoint point : ComputationExecutionFailurePoint.values()) {
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
        .isEqualTo(ComputationExecutionFailurePoint.values().length);
    assertThat(partialRecords).isZero();
    assertThat(duplicateArtifacts).isZero();
    System.out.println("COMPUTATION ATOMICITY DIAGNOSTIC");
    System.out.println("FAILURE_POINTS=" + ComputationExecutionFailurePoint.values().length);
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
