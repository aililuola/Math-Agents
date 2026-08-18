package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunProjectionStaleWriterRejectedTest {
  @TempDir Path temporaryDirectory;

  @Test
  void staleStateHashIsRejectedEvenWhenAuthorityVersionIsUnchanged() {
    FileRunStateStore store = new FileRunStateStore(temporaryDirectory);
    RunStateSnapshot base =
        RunStateServerTestSupport.state("projection-stale", RunExecutionStatus.RUNNING, true);
    store.compareAndSet("projection-stale", -1L, base, "worker", 0L);
    RunStateSnapshot committed = RunProjectionConcurrentCasTest.projection(base, "results/one.json");
    store.compareAndSetProjection(
        "projection-stale", base.stateHash(), 0L, committed, "worker", 0L);
    RunStateSnapshot next = RunProjectionConcurrentCasTest.projection(base, "results/two.json");

    assertThatThrownBy(
            () ->
                store.compareAndSetProjection(
                    "projection-stale", base.stateHash(), 1L, next, "worker", 0L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stale projection");
  }
}
