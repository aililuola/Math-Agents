package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunStateFencingTest {
  @TempDir Path temporaryDirectory;

  @Test
  void staleOptimisticVersionCannotOverwriteAuthority() {
    FileRunStateStore store = new FileRunStateStore(temporaryDirectory);
    RunStateSnapshot state =
        RunStateServerTestSupport.state("fencing-run", RunExecutionStatus.FAILED, true);
    store.compareAndSet("fencing-run", -1, state, "owner", 0);
    assertThatThrownBy(() -> store.compareAndSet("fencing-run", -1, state, "stale", 0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("optimistic version mismatch");
  }
}
