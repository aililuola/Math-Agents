package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunStateTransitionRepositoryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void fileCommitWritesExactlyOneDurableTransition() {
    FileRunStateStore store = new FileRunStateStore(temporaryDirectory);
    RunStateSnapshot state =
        RunStateServerTestSupport.state("transition-run", RunExecutionStatus.FAILED, true);
    store.compareAndSet("transition-run", -1, state, "test", 0);
    assertThat(store.transitions("transition-run")).hasSize(1);
    assertThat(store.transitions("transition-run").getFirst().toStateHash()).isEqualTo(state.stateHash());
  }
}
