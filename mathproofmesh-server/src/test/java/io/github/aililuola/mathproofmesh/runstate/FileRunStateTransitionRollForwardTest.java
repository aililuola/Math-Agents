package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileRunStateTransitionRollForwardTest {
  @TempDir Path temporaryDirectory;

  @Test
  void committedEnvelopeRollsLegacyProjectionsForwardAfterHardCrash() {
    FileRunStateStore initial = new FileRunStateStore(temporaryDirectory);
    RunStateSnapshot first =
        RunStateServerTestSupport.state("roll-forward", RunExecutionStatus.RUNNING, true);
    initial.compareAndSet("roll-forward", -1L, first, "worker", 0L);
    RunStateSnapshot second = next(first, RunExecutionStatus.FAILED);
    FileRunStateStore crashing =
        new FileRunStateStore(
            temporaryDirectory,
            JsonMapper.builder().findAndAddModules().build(),
            point -> {
              if (point == FileRunStateStore.FailurePoint.AFTER_STATE_PROJECTION) {
                throw new SimulatedTermination();
              }
            });

    assertThatThrownBy(
            () -> crashing.compareAndSet("roll-forward", 0L, second, "worker", 0L))
        .isInstanceOf(SimulatedTermination.class);

    FileRunStateStore restarted = new FileRunStateStore(temporaryDirectory);
    assertThat(restarted.load("roll-forward").orElseThrow().stateHash())
        .isEqualTo(second.stateHash());
    assertThat(restarted.transitions("roll-forward")).hasSize(2);
    assertThat(restarted.transitions("roll-forward").getLast().toStateHash())
        .isEqualTo(second.stateHash());
  }

  static RunStateSnapshot next(RunStateSnapshot previous, RunExecutionStatus status) {
    return new RunStateReconciler()
        .reconcile(
            new RunStateEvidenceBundle(
                previous.authority().runId(),
                previous.authority().problemHash(),
                "attempt-two",
                status,
                RunTerminalReason.NONE,
                "proof",
                true,
                false,
                previous.authority().latestSemanticCheckpointRef(),
                "4".repeat(64),
                previous.authority().proofGraphHash(),
                previous.authority().mathematicalProgress(),
                java.util.List.of(),
                previous,
                previous.projection(),
                java.time.Instant.parse("2026-08-17T00:00:01Z")))
        .state();
  }

  private static final class SimulatedTermination extends Error {
    private static final long serialVersionUID = 1L;
  }
}
