package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileRunStateTransitionRollbackTest {
  @TempDir Path temporaryDirectory;

  @Test
  void uncommittedLegacyStateProjectionIsRolledBackToEnvelope() throws Exception {
    FileRunStateStore store = new FileRunStateStore(temporaryDirectory);
    RunStateSnapshot committed =
        RunStateServerTestSupport.state("roll-back", RunExecutionStatus.RUNNING, true);
    store.compareAndSet("roll-back", -1L, committed, "worker", 0L);
    RunStateSnapshot uncommitted =
        FileRunStateTransitionRollForwardTest.next(committed, RunExecutionStatus.FAILED);
    Path projection = temporaryDirectory.resolve("roll-back/structured/run_state.json");
    Files.write(
        projection,
        JsonMapper.builder()
            .findAndAddModules()
            .build()
            .writerWithDefaultPrettyPrinter()
            .writeValueAsBytes(uncommitted));

    FileRunStateStore restarted = new FileRunStateStore(temporaryDirectory);
    assertThat(restarted.load("roll-back").orElseThrow().stateHash())
        .isEqualTo(committed.stateHash());
    assertThat(restarted.transitions("roll-back")).hasSize(1);
    assertThat(restarted.transitions("roll-back").getLast().toStateHash())
        .isEqualTo(committed.stateHash());
  }
}
