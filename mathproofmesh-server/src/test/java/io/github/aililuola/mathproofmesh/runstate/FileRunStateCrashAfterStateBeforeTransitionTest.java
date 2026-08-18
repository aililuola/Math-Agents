package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileRunStateCrashAfterStateBeforeTransitionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void recoveryNeverExposesAuthorityWithoutItsTransition() throws Exception {
    FileRunStateStore store = new FileRunStateStore(temporaryDirectory);
    RunStateSnapshot first =
        RunStateServerTestSupport.state("crash-window", RunExecutionStatus.RUNNING, true);
    store.compareAndSet("crash-window", -1, first, "worker", 0);
    RunStateSnapshot second =
        new RunStateReconciler()
            .reconcile(
                new RunStateEvidenceBundle(
                    "crash-window",
                    first.authority().problemHash(),
                    "attempt-two",
                    RunExecutionStatus.FAILED,
                    RunTerminalReason.EXECUTION_FAILED,
                    "proof",
                    true,
                    false,
                    first.authority().latestSemanticCheckpointRef(),
                    first.authority().latestSemanticCheckpointHash(),
                    first.authority().proofGraphHash(),
                    first.authority().mathematicalProgress(),
                    java.util.List.of(),
                    first,
                    first.projection(),
                    java.time.Instant.parse("2026-08-17T00:00:01Z")))
            .state();
    Path statePath =
        temporaryDirectory.resolve("crash-window/structured/run_state.json");
    Files.writeString(
        statePath,
        JsonMapper.builder().findAndAddModules().build().writeValueAsString(second),
        StandardCharsets.UTF_8);

    FileRunStateStore restarted = new FileRunStateStore(temporaryDirectory);
    RunStateSnapshot recovered = restarted.load("crash-window").orElseThrow();

    assertThat(restarted.transitions("crash-window").getLast().toStateHash())
        .isEqualTo(recovered.stateHash());
    assertThat(recovered.stateHash()).isEqualTo(first.stateHash());
  }
}
