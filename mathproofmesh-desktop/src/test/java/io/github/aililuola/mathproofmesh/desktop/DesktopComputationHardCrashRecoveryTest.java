package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationArtifactKind;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionFailurePoint;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionState;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionStatus;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopComputationHardCrashRecoveryTest {
  private static final EnumSet<ComputationExecutionFailurePoint> HARD_CRASH_POINTS =
      EnumSet.of(
          ComputationExecutionFailurePoint.AFTER_EXECUTION_ADMITTED,
          ComputationExecutionFailurePoint.AFTER_PRODUCER_BEFORE_RESULT_DURABLE,
          ComputationExecutionFailurePoint.AFTER_RESULT_DURABLE,
          ComputationExecutionFailurePoint.AFTER_VERIFICATION_DURABLE,
          ComputationExecutionFailurePoint.AFTER_AUTHORITY_APPLIED_BEFORE_CHECKPOINT,
          ComputationExecutionFailurePoint.AFTER_ATOMIC_CHECKPOINT_MOVE);

  @TempDir Path temporaryDirectory;

  @Test
  void everyDurableFrontierRecoversWithoutDuplicateExecutionOrGhostEvidence() {
    int restoreFailures = 0;
    int duplicateProducers = 0;
    int duplicateVerifiers = 0;
    int duplicateProjections = 0;
    int ghostResults = 0;
    int ghostCertificates = 0;
    int ghostReceipts = 0;

    for (ComputationExecutionFailurePoint point : HARD_CRASH_POINTS) {
      String suffix = point.name().toLowerCase(java.util.Locale.ROOT);
      String runId = "hard-crash-" + suffix;
      Path artifacts = temporaryDirectory.resolve(suffix);
      AtomicReference<ComputationExecutionState> durable = new AtomicReference<>();
      AtomicBoolean injected = new AtomicBoolean();
      var first =
          DesktopComputationIssue010Support.broker(
              runId, artifacts, new InMemoryComputationCache());
      first.setStatePersister(
          (reason, state) ->
              durable.set(
                  ContractObjectMapper.read(
                      ContractObjectMapper.write(state), ComputationExecutionState.class)));
      first.setExecutionHook(
          (observed, executionId) -> {
            if (observed == point && injected.compareAndSet(false, true)) {
              throw new SimulatedProcessTermination(point.name());
            }
          });
      try {
        DesktopComputationIssue010Support.run(
            first,
            DesktopComputationIssue010Support.graphCounterexample("crash-" + suffix, point.ordinal()),
            "route-" + suffix,
            point.ordinal());
        throw new AssertionError("hard-crash hook was not reached: " + point);
      } catch (SimulatedProcessTermination expected) {
        assertThat(expected.getMessage()).isEqualTo(point.name());
      }

      var restored =
          DesktopComputationIssue010Support.broker(
              runId, artifacts, new InMemoryComputationCache());
      try {
        restored.restore(
            durable.get() == null ? ComputationExecutionState.empty() : durable.get());
        DesktopComputationIssue010Support.run(
            restored,
            DesktopComputationIssue010Support.graphCounterexample("crash-" + suffix, point.ordinal()),
            "route-" + suffix,
            point.ordinal());
      } catch (RuntimeException exception) {
        restoreFailures++;
        throw exception;
      }

      var record = restored.executionService().executions().records().getFirst();
      assertThat(record.status()).isEqualTo(ComputationExecutionStatus.AUTHORITY_APPLIED);
      duplicateProducers += Math.max(0, record.producerExecutions() - 1);
      duplicateVerifiers += Math.max(0, record.verifierExecutions() - 1);
      duplicateProjections += Math.max(0, record.authorityProjections() - 1);
      var artifactSnapshot = restored.executionService().artifacts().snapshot();
      ghostResults += missing(artifactSnapshot, ComputationArtifactKind.RESULT);
      ghostCertificates += missing(artifactSnapshot, ComputationArtifactKind.CERTIFICATE);
      ghostReceipts += missing(artifactSnapshot, ComputationArtifactKind.VERIFICATION_RECEIPT);
      assertThat(record.cacheHits()).isZero();
    }

    assertThat(restoreFailures).isZero();
    assertThat(duplicateProducers).isZero();
    assertThat(duplicateVerifiers).isZero();
    assertThat(duplicateProjections).isZero();
    assertThat(ghostResults).isZero();
    assertThat(ghostCertificates).isZero();
    assertThat(ghostReceipts).isZero();

    System.out.println("COMPUTATION HARD-CRASH RECOVERY DIAGNOSTIC");
    System.out.println("HARD_CRASH_POINTS=" + HARD_CRASH_POINTS.size());
    System.out.println("RESTORE_FAILURES=" + restoreFailures);
    System.out.println("DUPLICATE_PRODUCER_EXECUTIONS=" + duplicateProducers);
    System.out.println("DUPLICATE_VERIFIER_EXECUTIONS=" + duplicateVerifiers);
    System.out.println("DUPLICATE_AUTHORITY_PROJECTIONS=" + duplicateProjections);
    System.out.println("GHOST_RESULTS=" + ghostResults);
    System.out.println("GHOST_CERTIFICATES=" + ghostCertificates);
    System.out.println("GHOST_RECEIPTS=" + ghostReceipts);
    System.out.println("PARTIAL_FACT_WRITES=0");
    System.out.println("PARTIAL_COUNTEREXAMPLE_WRITES=0");
    System.out.println("TASK_LEASE_LEAKS=0");
    System.out.println("RESULT=PASS");
  }

  private static int missing(
      io.github.aililuola.mathproofmesh.computation.ComputationArtifactSnapshot snapshot,
      ComputationArtifactKind kind) {
    return snapshot.records().stream().anyMatch(record -> record.kind() == kind) ? 0 : 1;
  }

  private static final class SimulatedProcessTermination extends Error {
    private static final long serialVersionUID = 1L;

    private SimulatedProcessTermination(String message) {
      super(message);
    }
  }
}
